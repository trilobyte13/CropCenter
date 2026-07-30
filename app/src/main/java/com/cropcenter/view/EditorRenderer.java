package com.cropcenter.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.cropcenter.crop.CropEngine;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.GridConfig;
import com.cropcenter.model.SelectionPoint;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.DpToPx;
import com.cropcenter.util.ThemeColors;

import java.util.List;

/**
 * onDraw body for CropEditorView, extracted so the host view can focus on lifecycle and gesture routing. Owns every
 * Paint used during rendering plus the GridRenderer; reads current transforms from a ViewportMath, current state from
 * CropState, and delegates the auto-rotate overlay to a HorizonPaintOverlay.
 *
 * No mutation of CropState happens here — this is a pure read-and-draw pass.
 */
final class EditorRenderer
{
	// Pixel-peep threshold in DP (density-independent units): once each source pixel renders at MIN_PIXEL_PEEP_DP
	// or more dp on screen, the pixel grid + per-pixel selection markers become visible. Specified in dp (not raw
	// screen pixels) so visibility is consistent across screen densities (3dp ≈ 0.75–1mm on phones). Multiplied by
	// display density at use site.
	private static final float MIN_PIXEL_PEEP_DP = 3f;
	// Fraction of the visible region added as margin around a re-cut source tile so small pans at high zoom stay
	// inside the cached tile instead of re-extracting every frame.
	private static final float TILE_MARGIN_FRACTION = 0.25f;

	private static final int DIM_OVERLAY = 0xAA000000;    // 66% black — dims area outside crop
	private static final int POINT_LABEL_COLOR = ThemeColors.CRUST;

	private final GridRenderer gridRenderer = new GridRenderer();
	private final Matrix bitmapMatrix = new Matrix();
	private final Paint cropBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint dimPaint = new Paint();
	private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
	private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint pixelGridPaint = new Paint();
	private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint polygonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path selectionPolygonPath = new Path();
	private final View view;
	private final ViewportMath viewport;
	// Shared 2-float scratch for the visible-bounds AABB walk (4 sequential corner reads for min/max) and the
	// selection-label imageToScreenRotatedInto calls in drawSelectionLabels. Both run on the UI thread and don't
	// overlap within a single draw call.
	private final float[] coordScratch = new float[2];
	// Per-draw scratch — reset at the top of each use so onDraw does no allocation.
	private final int[] aabbScratch = new int[4];

	// Region-tile cache for over-budget sources; see drawSourceTile. tileSourceRef identity-checks the source for
	// invalidation; the rect (tileX / tileY / tileWidth / tileHeight) gates cache reuse via tileCovers.
	private Bitmap tileBitmap;
	private Bitmap tileSourceRef;
	private float density = 1f;
	private int tileHeight;
	private int tileWidth;
	private int tileX;
	private int tileY;

	EditorRenderer(Context context, View view, ViewportMath viewport)
	{
		this.view = view;
		this.viewport = viewport;
		density = context.getResources().getDisplayMetrics().density;

		dimPaint.setColor(DIM_OVERLAY);
		cropBorderPaint.setColor(ThemeColors.MAUVE);
		cropBorderPaint.setStrokeWidth(2f);
		cropBorderPaint.setStyle(Paint.Style.STROKE);
		crosshairPaint.setStrokeWidth(1f);
		// crosshair / point / polygon / horizon colors are set per-draw from GridConfig — crosshair tracks
		// grid.color() so the centerpoint marker stays visually consistent with the grid lines.
		pointPaint.setStyle(Paint.Style.FILL);
		polygonPaint.setStyle(Paint.Style.FILL);
		infoPaint.setColor(ThemeColors.TEXT);
		infoPaint.setTextSize(24f);
	}

	/**
	 * Decide whether the renderer uploads the WHOLE source bitmap for crisp 1:1 pixels at the current zoom. True
	 * only at the pixel-peep transition (scale ≥ 4) AND when the whole source fits the GPU texture budget
	 * (sourceFitsBudget). An over-budget source instead takes the region-tile path (shouldRenderSourceTile); below
	 * 4× both are false and the renderer draws the display proxy.
	 *
	 * Package-private static so EditorRendererTest can pin the cap boundaries without a Canvas; the draw pass calls
	 * it with the live source bitmap's dimensions.
	 *
	 * @param srcW  source bitmap width in pixels
	 * @param srcH  source bitmap height in pixels
	 * @param scale current on-screen scale (baseScale × zoom); each source pixel covers `scale` screen pixels
	 * @return true to upload and render the whole source, false to use the tile path or the proxy
	 */
	static boolean shouldRenderSource(int srcW, int srcH, float scale)
	{
		return scale >= 4f && sourceFitsBudget(srcW, srcH);
	}

	/**
	 * Decide whether the renderer draws the visible source REGION as a viewport-bounded tile — the crisp path for
	 * an over-budget source (one that fails sourceFitsBudget, e.g. a 200 MP capture whose ~800 MB ARGB texture
	 * can't be uploaded whole). True at the pixel-peep transition (scale ≥ 4) for exactly the sources
	 * shouldRenderSource rejects, so at scale ≥ 4 every source reaches true 1:1 pixels by one path or the other.
	 *
	 * Package-private static so EditorRendererTest can pin the boundary opposite shouldRenderSource.
	 *
	 * @param srcW  source bitmap width in pixels
	 * @param srcH  source bitmap height in pixels
	 * @param scale current on-screen scale (baseScale × zoom); each source pixel covers `scale` screen pixels
	 * @return true to draw the region tile, false when the whole-source upload or the proxy applies
	 */
	static boolean shouldRenderSourceTile(int srcW, int srcH, float scale)
	{
		return scale >= 4f && !sourceFitsBudget(srcW, srcH);
	}

	/**
	 * Decide whether the cached region tile still covers the visible AABB, i.e. whether drawSourceTile may reuse it
	 * instead of re-cutting. Pure containment with all four boundaries inclusive. Rotation is deliberately NOT an
	 * input: the tile holds raw axis-aligned source pixels (rotation is applied by the draw matrix), and the
	 * visible AABB is already computed under the current rotation — so a ruler drag at high zoom reuses the tile
	 * every frame instead of re-cutting a multi-MB bitmap.
	 *
	 * Package-private static so EditorRendererTest can pin the containment boundaries — and rotation's absence from
	 * the signature — without a Canvas.
	 *
	 * @param tileX      source X of the cached tile's left edge
	 * @param tileY      source Y of the cached tile's top edge
	 * @param tileWidth  cached tile width in source pixels
	 * @param tileHeight cached tile height in source pixels
	 * @param visStartX  visible-AABB left edge in source pixels
	 * @param visStartY  visible-AABB top edge in source pixels
	 * @param visEndX    visible-AABB right edge in source pixels (exclusive end, matching the tile rect)
	 * @param visEndY    visible-AABB bottom edge in source pixels (exclusive end, matching the tile rect)
	 * @return true when the visible AABB lies entirely inside the cached tile rect
	 */
	static boolean tileCovers(int tileX, int tileY, int tileWidth, int tileHeight,
		int visStartX, int visStartY, int visEndX, int visEndY)
	{
		return visStartX >= tileX && visStartY >= tileY
			&& visEndX <= tileX + tileWidth && visEndY <= tileY + tileHeight;
	}

	/**
	 * Top-level frame draw. Renders (in order): background fill, source bitmap with rotation, optional pixel grid,
	 * optional crop overlay (dim outside + grid lines + crosshair), selection points/polygon/labels, horizon paint
	 * overlay. Snapshots `state.getSourceImage()` AND `state.getDisplayImage()` once at entry to avoid races with
	 * the bg-thread loadImage path nulling either field mid-frame.
	 *
	 * @param canvas  destination canvas (the View's onDraw canvas)
	 * @param state   editor state — source image, display proxy, crop rect, rotation, selection, grid config
	 * @param horizon overlay that draws the painted horizon-region polygon
	 */
	void draw(Canvas canvas, CropState state, HorizonPaintOverlay horizon)
	{
		// Snapshot once: sourceImage / displayImage can be nulled on the background executor during loadImage's
		// reset() while this draw is mid-flight. A null check followed by a second read can race — the check
		// passes on the previous bitmap and the second read returns null, NPE'ing the subsequent getWidth().
		// One local read each is consistent for the whole frame regardless of concurrent writes.
		Bitmap source = state == null ? null : state.getSourceImage();
		Bitmap display = state == null ? null : state.getDisplayImage();
		// Invalidate the region-tile cache when the source reference changes (new image load) or the image is
		// unloaded — the cached tile was cut from the previous source's pixel buffer and must not outlive it.
		if (tileBitmap != null && source != tileSourceRef)
		{
			tileBitmap.recycle();
			tileBitmap = null;
			tileSourceRef = null;
		}
		if (source == null || display == null)
		{
			drawEmptyHint(canvas);
			return;
		}
		// Zero-dimension guard. A pathological source / proxy with width or height 0 would (a) divide-by-zero
		// in the proxyToSource ratio below, producing Infinity that propagates through bitmapMatrix and renders
		// nothing visible AND (b) trip downstream pixel-grid culling. Real Bitmaps from BitmapFactory always
		// have positive dimensions, but the guard catches a recycled-during-snapshot race or a future test
		// fixture that constructs a 0-dim bitmap.
		if (source.getWidth() <= 0 || source.getHeight() <= 0
			|| display.getWidth() <= 0 || display.getHeight() <= 0)
		{
			drawEmptyHint(canvas);
			return;
		}
		GridConfig grid = state.getGridConfig();

		canvas.drawColor(ThemeColors.BACKGROUND);
		float scale = viewport.getBaseScale() * viewport.getZoom();
		float left = viewport.imageToScreenX(0);
		float top = viewport.imageToScreenY(0);
		float rotation = state.getRotationDegrees();
		// Treat sub-UI-resolution residues as exactly zero — skipping postRotate keeps the identity-transform
		// path (crisper preview) when the user has returned the ruler to "0" but a tiny float residue remains.
		boolean rotated = Math.abs(rotation) >= BitmapUtils.ROTATION_EPSILON;
		// Rotation pivot uses source dims × scale so it stays at the geometric image center regardless of which
		// bitmap (proxy / whole source / region tile) is drawn — all three render at the same on-screen size.
		float imageScreenCenterX = left + source.getWidth() * scale / 2f;
		float imageScreenCenterY = top + source.getHeight() * scale / 2f;

		// Three-way render select at zoom ≥ 4. (1) In-budget source (≤ MAX_SOURCE_RENDER_PIXELS 64 MP AND ≤
		// MAX_SOURCE_RENDER_AXIS 16384/axis): upload it whole for crisp 1:1 pixels. (2) Over-budget source
		// (e.g. a 200 MP capture, ~800 MB as one ARGB texture): draw only its visible region as a
		// viewport-bounded tile cut from the software-ARGB source, so the GPU gets no over-budget texture and
		// pixels stay 1:1 crisp. Below 4×: (3) the filtered display proxy (≤ MAX_DISPLAY_PIXELS = 16 MP,
		// GPU-resident). The grid gates on showingSourcePixels (1, 2) so its source-coord lines land only on
		// true source pixels, never the bilinear proxy; it still reads source dims for line spacing.
		boolean useWholeSource = shouldRenderSource(source.getWidth(), source.getHeight(), scale);
		boolean useTile = shouldRenderSourceTile(source.getWidth(), source.getHeight(), scale);
		boolean showingSourcePixels = useWholeSource || useTile;
		if (useTile)
		{
			drawSourceTile(canvas, state, source, scale, left, top, rotation, rotated,
				imageScreenCenterX, imageScreenCenterY);
		}
		else
		{
			// proxyToSource ≥ 1 always (display ≤ source pixels); 1 when aliased (display == source).
			// Nearest-neighbor for the whole source (crisp pixel-peeping); bilinear for the proxy so a
			// magnified proxy texel isn't a hard rectangle.
			Bitmap bmp = useWholeSource ? source : display;
			imagePaint.setFilterBitmap(!useWholeSource);
			float proxyToSource = (float) source.getWidth() / bmp.getWidth();
			float drawScale = scale * proxyToSource;
			// setScale initialises the matrix to a pure scale (overwrites prior state), so bitmapMatrix is
			// reusable across frames without an explicit reset().
			bitmapMatrix.setScale(drawScale, drawScale);
			bitmapMatrix.postTranslate(left, top);
			if (rotated)
			{
				bitmapMatrix.postRotate(rotation, imageScreenCenterX, imageScreenCenterY);
			}
			canvas.drawBitmap(bmp, bitmapMatrix, imagePaint);
		}

		drawPixelGridIfZoomed(canvas, state, grid, source, scale, showingSourcePixels);

		float gridImgX;
		float gridImgY;
		int gridW;
		int gridH;
		if (state.hasCenter())
		{
			int cropW = state.getCropW();
			int cropH = state.getCropH();
			// Crop-center derivation: in SELECT mode with selection points, re-derive the center from the
			// rotated selection midpoint at draw time rather than reading state.centerX / centerY.
			// CropEngine.recomputeCrop normally keeps state.centerX / Y in lockstep with the rotation, but
			// during ruler-fling momentum (where setRotationDegrees fires once per frame and the renderer
			// reads state on the same UI thread) we've seen intermittent one-frame flickers where the
			// bitmap renders at the new rotation while the crop overlay sits at a center derived from a
			// previous rotation — the crop visibly jumps to a different position then snaps back the next
			// frame. Deriving the center from the rotated selection midpoint at draw time guarantees the
			// crop tracks the selection every frame, regardless of any timing window between rotation
			// update and the recompute pass that refreshes state.centerX / Y. PER-AXIS, though: the
			// midpoint only equals the EXPORTED center on LOCKED axes. On a FREE axis (H mode frees Y, V
			// mode frees X) recomputeCrop's clampFreeAxes shifts the export center to the image midpoint,
			// so drawing the overlay at the raw selection midpoint there would position the crop box / grid
			// / crosshair where the save does NOT land — the overlay would lie about what gets exported. So
			// use the midpoint only on locked axes (where it matches export AND tracks rotation, keeping
			// the default BOTH mode flicker-free) and read state.centerX / Y on free axes (the
			// authoritative exported value). Outside SELECT mode (Move mode, or SELECT with no points) both
			// axes fall back to state.centerX / Y, which the user-pannable MOVE mode depends on (it's not
			// derivable from selection).
			float drawCenterX;
			float drawCenterY;
			List<SelectionPoint> activeSelection = state.getSelectionPoints();
			if (state.getEditorMode() == EditorMode.SELECT_FEATURE && !activeSelection.isEmpty())
			{
				CenterMode mode = state.getCenterMode();
				boolean lockedX = mode == CenterMode.BOTH || mode == CenterMode.HORIZONTAL;
				boolean lockedY = mode == CenterMode.BOTH || mode == CenterMode.VERTICAL;
				float[] midpoint = CropEngine.rotatedSelectionMidpoint(
					activeSelection, source.getWidth(), source.getHeight(), rotation);
				drawCenterX = lockedX ? midpoint[0] : state.getCenterX();
				drawCenterY = lockedY ? midpoint[1] : state.getCenterY();
			}
			else
			{
				drawCenterX = state.getCenterX();
				drawCenterY = state.getCenterY();
			}
			// Use the continuous-float crop origin for rendering so smooth rotation produces smooth crop
			// motion. CropExporter samples the same float origin via BitmapUtils.drawCropped, which falls
			// back to bilinear sampling on non-integer offsets — so the rendered overlay and the exported
			// pixels stay in lockstep at sub-pixel precision. Computed from drawCenterX / Y instead of
			// state.getCropImageXFloat() so the crop rectangle, grid lines, and crosshair all use the same
			// draw-time-derived center.
			gridImgX = drawCenterX - cropW / 2f;
			gridImgY = drawCenterY - cropH / 2f;
			gridW = cropW;
			gridH = cropH;
			drawCropOverlay(canvas, grid, drawCenterX, drawCenterY, gridImgX, gridImgY, cropW, cropH);
		}
		else
		{
			gridImgX = 0;
			gridImgY = 0;
			// Read from source (NOT bmp) — bmp switches between source and proxy on the 4× zoom threshold,
			// so reading bmp.getWidth() would make the no-crop overlay grid visibly jump in extent every
			// time the user crosses zoom=4 on a downsampled image. The grid renders in image-pixel space
			// via the viewport's imageToScreen mapping, so the dimensions must always be in source coords.
			gridW = source.getWidth();
			gridH = source.getHeight();
		}

		gridRenderer.draw(canvas, gridImgX, gridImgY, gridW, gridH,
			grid, viewport.getBaseScale() * viewport.getZoom(),
			viewport::imageToScreenX, viewport::imageToScreenY);

		// Snapshot the selection list once. The list is volatile-swapped on image-load reset, so reading it
		// twice (once for isEmpty, once for the iteration inside drawSelectionPoints) could see two different
		// lists if a bg-thread reset lands between the calls. Pass the local snapshot down to keep the whole
		// frame consistent.
		List<SelectionPoint> selectionPoints = state.getSelectionPoints();
		if (!selectionPoints.isEmpty())
		{
			drawSelectionPoints(canvas, selectionPoints, state, grid, scale);
		}

		if (horizon.isActive() || horizon.isDrawing())
		{
			horizon.draw(canvas, view.getWidth(), view.getHeight(),
				grid.selectionColor(), infoPaint, density);
		}
	}

	/**
	 * Whether the whole source bitmap fits the GPU texture budget: total pixels ≤ MAX_SOURCE_RENDER_PIXELS (64 MP)
	 * AND each axis ≤ MAX_SOURCE_RENDER_AXIS (16384 px). The pixel product uses long arithmetic so a 200 MP source
	 * can't overflow int and spuriously pass. A 200 MP capture fails the pixel cap; a 32767×1000 panorama passes
	 * the pixel cap (32 MP) but fails the axis cap.
	 *
	 * @param srcW source bitmap width in pixels
	 * @param srcH source bitmap height in pixels
	 * @return true when the whole source can be uploaded as one GPU texture
	 */
	private static boolean sourceFitsBudget(int srcW, int srcH)
	{
		long sourcePixels = (long) srcW * srcH;
		return sourcePixels <= BitmapUtils.MAX_SOURCE_RENDER_PIXELS
			&& srcW <= BitmapUtils.MAX_SOURCE_RENDER_AXIS && srcH <= BitmapUtils.MAX_SOURCE_RENDER_AXIS;
	}

	private static int withAlpha(int color, int alpha)
	{
		return (color & 0x00FFFFFF) | (alpha << 24);
	}

	/**
	 * Draw the crop-rectangle overlay: 4 dim rectangles outside the crop, grid lines inside, and the center-of-crop
	 * crosshair. Crop coords arrive in image-space, converted to screen-space via the viewport. The crosshair uses
	 * grid.color() (0xCC alpha) to match the grid lines. The crop center is a parameter (not read from CropState)
	 * so the caller chooses state.centerX/Y or a draw-time override — see the call site for the rotation-flicker
	 * rationale.
	 *
	 * @param canvas   target Canvas; drawn into in place
	 * @param grid     GridConfig supplying crop / grid colors and the crosshair color
	 * @param centerX  crop center X in image-space (gridImgX is centerX − cropW / 2f)
	 * @param centerY  crop center Y in image-space (source of gridImgY)
	 * @param gridImgX crop origin X in image-space
	 * @param gridImgY crop origin Y in image-space
	 * @param cropW    crop width in image pixels
	 * @param cropH    crop height in image pixels
	 */
	private void drawCropOverlay(Canvas canvas, GridConfig grid, float centerX, float centerY,
		float gridImgX, float gridImgY, int cropW, int cropH)
	{
		float cropLeft = viewport.imageToScreenX(gridImgX);
		float cropTop = viewport.imageToScreenY(gridImgY);
		float cropRight = viewport.imageToScreenX(gridImgX + cropW);
		float cropBottom = viewport.imageToScreenY(gridImgY + cropH);

		// Dim outside crop — cover full canvas, not just image bounds
		int viewWidth = view.getWidth();
		int viewHeight = view.getHeight();
		canvas.drawRect(0, 0, viewWidth, cropTop, dimPaint);                    // top
		canvas.drawRect(0, cropBottom, viewWidth, viewHeight, dimPaint);        // bottom
		canvas.drawRect(0, cropTop, cropLeft, cropBottom, dimPaint);            // left
		canvas.drawRect(cropRight, cropTop, viewWidth, cropBottom, dimPaint);   // right

		canvas.drawRect(cropLeft, cropTop, cropRight, cropBottom, cropBorderPaint);

		float screenCenterX = viewport.imageToScreenX(centerX);
		float screenCenterY = viewport.imageToScreenY(centerY);
		crosshairPaint.setColor(withAlpha(grid.color(), 0xCC));
		float crosshairArmLength = DpToPx.toPx(8, density);
		canvas.drawLine(screenCenterX - crosshairArmLength, screenCenterY,
			screenCenterX + crosshairArmLength, screenCenterY, crosshairPaint);
		canvas.drawLine(screenCenterX, screenCenterY - crosshairArmLength,
			screenCenterX, screenCenterY + crosshairArmLength, crosshairPaint);

		infoPaint.setTextAlign(Paint.Align.LEFT);
		infoPaint.setTextSize(11f * density);
		infoPaint.setColor(withAlpha(ThemeColors.SUBTEXT0, 0xAA));
		canvas.drawText(cropW + " x " + cropH,
			cropLeft + DpToPx.toPx(2, density), cropTop - DpToPx.toPx(3, density), infoPaint);
	}

	private void drawEmptyHint(Canvas canvas)
	{
		canvas.drawColor(ThemeColors.BACKGROUND);
		infoPaint.setTextAlign(Paint.Align.CENTER);
		infoPaint.setTextSize(16f);
		infoPaint.setColor(ThemeColors.SURFACE2);
		canvas.drawText("Tap the gallery icon to open an image",
			view.getWidth() / 2f, view.getHeight() / 2f, infoPaint);
	}

	/**
	 * Draw a 1-pixel-per-image-pixel grid when the GridConfig flag is on AND each source pixel renders at
	 * MIN_PIXEL_PEEP_DP or more dp on screen. Below the threshold the grid lines would be sub-pixel and unreadable.
	 * The threshold is dp-normalised so visibility is consistent across screen densities (a raw-pixel threshold
	 * would render at ~2dp on a 3x phone, barely visible). Computes a rotation-aware axis-aligned bounding box of
	 * the visible image area and only draws lines inside that AABB to avoid the O(W * H) full-image walk.
	 *
	 * @param canvas target Canvas; drawn into in place
	 * @param state  CropState supplying rotation + image dimensions
	 * @param grid   GridConfig; pixel-grid color and the toggle that gates this method
	 * @param source full-resolution source bitmap; only width/height are read. Must NOT be the display proxy
	 *               — at the pixel-peep threshold the renderer's bmp variable already holds the source,
	 *               but passing the proxy here would scale the pixel grid to proxy resolution and the
	 *               per-pixel lines would no longer land on source-coord pixel boundaries
	 * @param scale     current screen-pixels-per-image-pixel (baseScale * zoom); below
	 *                  MIN_PIXEL_PEEP_DP * density short-circuits to no-op
	 * @param showingSourcePixels whether the frame is showing true 1:1 source pixels — the whole-source upload
	 *                  (shouldRenderSource) or the region tile (shouldRenderSourceTile) — rather than the display
	 *                  proxy; the grid only draws when true so its source-coordinate lines land on pixels shown
	 */
	private void drawPixelGridIfZoomed(Canvas canvas, CropState state, GridConfig grid, Bitmap source,
		float scale, boolean showingSourcePixels)
	{
		// Gate on the same decision the bitmap render used (showingSourcePixels = the whole-source upload OR
		// the region-tile path), not scale alone. On mdpi (density 1) the dp threshold (3) sits below the
		// source-switch scale (4), so without this the grid would paint source-coordinate lines over the
		// bilinear display proxy in the 3 <= scale < 4 band — lines that wouldn't land on the pixels shown.
		if (!grid.showPixelGrid() || !showingSourcePixels || scale < MIN_PIXEL_PEEP_DP * density)
		{
			return;
		}
		pixelGridPaint.setColor(grid.pixelGridColor());
		pixelGridPaint.setStrokeWidth(1f);

		// The grid must follow the rotated bitmap. Draw in the rotated canvas so the pixel lines stay aligned
		// with the actual pixel boundaries the user sees.
		float rotation = state.getRotationDegrees();
		boolean rotated = Math.abs(rotation) >= BitmapUtils.ROTATION_EPSILON;
		if (rotated)
		{
			float imageScreenCenterX = viewport.imageToScreenX(state.getImageWidth() / 2f);
			float imageScreenCenterY = viewport.imageToScreenY(state.getImageHeight() / 2f);
			canvas.save();
			canvas.rotate(rotation, imageScreenCenterX, imageScreenCenterY);
		}

		// Cull to the rotated viewport's AABB in image space. Un-rotating the four screen corners gives the
		// image coords that could possibly be visible under the current rotation + viewport; any pixel line
		// outside that AABB is guaranteed off-screen. For a 10000×10000 bitmap zoomed to ~6× on a 1080p view we
		// go from ~20 000 lines drawn to a few hundred — the difference shows up in onDraw time on lower-end
		// devices. Add a one-pixel margin so the border lines of the visible region always draw.
		int imgW = source.getWidth();
		int imgH = source.getHeight();
		int[] bounds = visibleImageBoundsAabb(state, imgW, imgH);
		int startX = bounds[0];
		int startY = bounds[1];
		int endX = bounds[2];
		int endY = bounds[3];

		// Hoist endpoint screen coordinates outside the loops — they're constant per draw. At full zoom on a
		// 10000×10000 source the vertical loop alone would otherwise make ~10000 redundant imageToScreenY calls
		// per frame (2 per iteration × 4096 iterations on a 4096-wide AABB).
		float screenTop = viewport.imageToScreenY(startY);
		float screenBottom = viewport.imageToScreenY(endY);
		float screenLeft = viewport.imageToScreenX(startX);
		float screenRight = viewport.imageToScreenX(endX);
		for (int x = startX; x <= endX; x++)
		{
			float screenX = viewport.imageToScreenX(x);
			canvas.drawLine(screenX, screenTop, screenX, screenBottom, pixelGridPaint);
		}
		for (int y = startY; y <= endY; y++)
		{
			float screenY = viewport.imageToScreenY(y);
			canvas.drawLine(screenLeft, screenY, screenRight, screenY, pixelGridPaint);
		}

		if (rotated)
		{
			canvas.restore();
		}
	}

	/**
	 * Draw the numeric index label next to each selection point. Labels are drawn axis-aligned (upright) at the
	 * rotated screen position of each point so the digits stay legible under rotation. This runs in the un-rotated
	 * canvas — caller must have already restored out of the rotated canvas before calling.
	 *
	 * @param canvas target Canvas; drawn into in place. Caller must have already restored out of any rotated save
	 *               layer.
	 * @param state  CropState supplying rotation + image dimensions for the rotated screen-position mapping
	 * @param points selection points in image-space coordinates; read-only
	 * @param scale  current screen-pixels-per-image-pixel (baseScale * zoom); selects between the zoomed-in
	 *               pixel-center label placement and the zoomed-out fallback
	 */
	private void drawSelectionLabels(Canvas canvas, CropState state, List<SelectionPoint> points, float scale)
	{
		float pixelSize = scale;
		int labelIndex = 0;
		for (SelectionPoint point : points)
		{
			labelIndex++;
			if (pixelSize >= 6f)
			{
				int pixelX = (int) Math.floor(point.x());
				int pixelY = (int) Math.floor(point.y());
				viewport.imageToScreenRotatedInto(pixelX + 0.5f, pixelY + 0.5f, state, coordScratch);
				infoPaint.setTextAlign(Paint.Align.CENTER);
				infoPaint.setTextSize(Math.min(pixelSize * 0.6f, 14f * density));
				infoPaint.setColor(POINT_LABEL_COLOR);
				float labelOffset = infoPaint.getTextSize() * 0.35f;
				canvas.drawText(String.valueOf(labelIndex),
					coordScratch[0], coordScratch[1] + labelOffset, infoPaint);
			}
			else
			{
				viewport.imageToScreenRotatedInto(point.x(), point.y(), state, coordScratch);
				infoPaint.setTextAlign(Paint.Align.CENTER);
				infoPaint.setTextSize(9f * density);
				infoPaint.setColor(POINT_LABEL_COLOR);
				canvas.drawText(String.valueOf(labelIndex),
					coordScratch[0], coordScratch[1] + 4, infoPaint);
			}
		}
	}

	/**
	 * Draw the per-selection-point marker. Filled image-pixel square when each source pixel renders at
	 * MIN_PIXEL_PEEP_DP or more dp on screen (marker visibly follows the rotated pixel grid, becoming a rotated
	 * quadrilateral at non-cardinal angles); a 10-px circle when zoomed out (single pixel is too small to see).
	 * Keyed off the same MIN_PIXEL_PEEP_DP dp threshold as the pixel grid; the grid additionally requires the
	 * zoom-≥-4 source switch, so on mdpi the grid can trail the marker style by the 3..4 scale band.
	 *
	 * @param canvas target Canvas; runs inside the caller's rotated save layer when the image is rotated
	 * @param points selection points in image-space coordinates; read-only
	 * @param scale  current screen-pixels-per-image-pixel (baseScale * zoom); selects between the pixel-square
	 *               and circle marker styles via MIN_PIXEL_PEEP_DP * density
	 */
	private void drawSelectionMarkers(Canvas canvas, List<SelectionPoint> points, float scale)
	{
		float pixelPeepThreshold = MIN_PIXEL_PEEP_DP * density;
		for (SelectionPoint point : points)
		{
			if (scale >= pixelPeepThreshold)
			{
				int pixelX = (int) Math.floor(point.x());
				int pixelY = (int) Math.floor(point.y());
				float pixelLeft = viewport.imageToScreenX(pixelX);
				float pixelTop = viewport.imageToScreenY(pixelY);
				float pixelRight = viewport.imageToScreenX(pixelX + 1);
				float pixelBottom = viewport.imageToScreenY(pixelY + 1);
				canvas.drawRect(pixelLeft, pixelTop, pixelRight, pixelBottom, pointPaint);
			}
			else
			{
				float screenX = viewport.imageToScreenX(point.x());
				float screenY = viewport.imageToScreenY(point.y());
				canvas.drawCircle(screenX, screenY, 10, pointPaint);
			}
		}
	}

	/**
	 * Draw selection markers + connecting polygon + labels for state.getSelectionPoints(). Wraps the marker draws
	 * in a canvas save/rotate/restore when the editor is rotated, so markers stay axis-aligned in image-space
	 * (visually rotating with the image) rather than appearing to slide as the image rotates underneath.
	 *
	 * @param canvas target Canvas; drawn into in place (a save/rotate/restore pair brackets the marker + polygon
	 *               draws when the image is rotated)
	 * @param points selection points in image-space coordinates; read-only
	 * @param state  CropState supplying rotation + image dimensions
	 * @param grid   GridConfig supplying the selection color shared by points and the connecting polygon
	 * @param scale  current screen-pixels-per-image-pixel (baseScale * zoom); forwarded to the marker / label
	 *               helpers
	 */
	private void drawSelectionPoints(Canvas canvas, List<SelectionPoint> points, CropState state,
		GridConfig grid, float scale)
	{
		// Shared selection color (with its exact alpha) drives points and polygon.
		int selColor = grid.selectionColor();
		pointPaint.setColor(selColor);
		polygonPaint.setColor(selColor);

		// Markers + polygon track image content under rotation by drawing inside a canvas rotated the same way
		// the bitmap was — a point placed on the sun stays on the sun after rotation. Text labels are drawn
		// afterwards (axis-aligned) at the rotated position so the digits stay upright.
		float rotation = state.getRotationDegrees();
		boolean rotated = Math.abs(rotation) >= BitmapUtils.ROTATION_EPSILON;
		if (rotated)
		{
			float imageScreenCenterX = viewport.imageToScreenX(state.getImageWidth() / 2f);
			float imageScreenCenterY = viewport.imageToScreenY(state.getImageHeight() / 2f);
			canvas.save();
			canvas.rotate(rotation, imageScreenCenterX, imageScreenCenterY);
		}

		drawSelectionPolygon(canvas, points);
		drawSelectionMarkers(canvas, points, scale);

		if (rotated)
		{
			canvas.restore();
		}

		drawSelectionLabels(canvas, state, points, scale);
	}

	/**
	 * Draw the translucent polygon connecting selection points. Only rendered when 3+ points are placed (fewer
	 * don't form a closed region). Runs inside the caller's rotated canvas so the polygon follows the image under
	 * rotation.
	 *
	 * @param canvas target Canvas; runs inside the caller's rotated save layer when the image is rotated
	 * @param points selection points in image-space coordinates; no-op when fewer than 3 are supplied
	 */
	private void drawSelectionPolygon(Canvas canvas, List<SelectionPoint> points)
	{
		if (points.size() < 3)
		{
			return;
		}
		selectionPolygonPath.rewind();
		boolean first = true;
		for (SelectionPoint point : points)
		{
			float sx = viewport.imageToScreenX(point.x());
			float sy = viewport.imageToScreenY(point.y());
			if (first)
			{
				selectionPolygonPath.moveTo(sx, sy);
				first = false;
			}
			else
			{
				selectionPolygonPath.lineTo(sx, sy);
			}
		}
		selectionPolygonPath.close();
		canvas.drawPath(selectionPolygonPath, polygonPaint);
	}

	/**
	 * Draw the visible source region as a viewport-bounded tile for an over-budget source — the crisp high-zoom
	 * path for a source that exceeds the GPU texture budget (sourceFitsBudget false) and so can't be uploaded
	 * whole. Cuts the visible source-pixel AABB (expanded by TILE_MARGIN_FRACTION) into a small software bitmap and
	 * draws it 1:1 with nearest-neighbor filtering, so a 200 MP capture pixel-peeks crisply without ever handing
	 * the GPU an over-budget texture. The tile holds raw axis-aligned source pixels — rotation is applied by the
	 * draw matrix each frame — so it is cached and re-cut only when the visible AABB (computed under the current
	 * rotation) leaves the cached rect or the source reference changes; steady-state panning and rotation at high
	 * zoom allocate nothing. Safe because the source is software ARGB_8888 (ImageLoadController decodes with no
	 * inPreferredConfig), so createBitmap is a bounded CPU sub-region copy, not a whole-texture upload.
	 *
	 * @param canvas             target Canvas; drawn into in place
	 * @param state              CropState supplying rotation for the visible-bounds un-rotation
	 * @param source             full-resolution software source bitmap; the tile is cut from this
	 * @param scale              screen-pixels-per-source-pixel (baseScale * zoom); the tile draws 1:1 so this is
	 *                           also its draw scale
	 * @param left               screen X of source pixel (0, 0) before rotation
	 * @param top                screen Y of source pixel (0, 0) before rotation
	 * @param rotation           current rotation in degrees (applied about the image center)
	 * @param rotated            whether rotation exceeds ROTATION_EPSILON; skips postRotate when false
	 * @param imageScreenCenterX screen X of the image center (rotation pivot)
	 * @param imageScreenCenterY screen Y of the image center (rotation pivot)
	 */
	private void drawSourceTile(Canvas canvas, CropState state, Bitmap source, float scale,
		float left, float top, float rotation, boolean rotated,
		float imageScreenCenterX, float imageScreenCenterY)
	{
		int imgW = source.getWidth();
		int imgH = source.getHeight();
		int[] bounds = visibleImageBoundsAabb(state, imgW, imgH);
		int visStartX = bounds[0];
		int visStartY = bounds[1];
		int visEndX = bounds[2];
		int visEndY = bounds[3];
		if (visEndX <= visStartX || visEndY <= visStartY)
		{
			// Viewport clamp should keep ≥ 1 column/row visible; guard a 0-area createBitmap throw.
			return;
		}

		// Reuse the cached tile while it was cut from this source and still covers the visible AABB (tile
		// pixels are rotation-independent — see tileCovers). Otherwise re-cut a tile expanded by
		// TILE_MARGIN_FRACTION so small pans stay inside it (no per-frame allocation), clamped to the image
		// bounds.
		boolean cacheValid = tileBitmap != null && tileSourceRef == source
			&& tileCovers(tileX, tileY, tileWidth, tileHeight, visStartX, visStartY, visEndX, visEndY);
		if (!cacheValid)
		{
			int marginX = (int) ((visEndX - visStartX) * TILE_MARGIN_FRACTION);
			int marginY = (int) ((visEndY - visStartY) * TILE_MARGIN_FRACTION);
			int cutX = Math.max(0, visStartX - marginX);
			int cutY = Math.max(0, visStartY - marginY);
			int cutEndX = Math.min(imgW, visEndX + marginX);
			int cutEndY = Math.min(imgH, visEndY + marginY);
			Bitmap newTile = Bitmap.createBitmap(source, cutX, cutY, cutEndX - cutX, cutEndY - cutY);
			newTile.prepareToDraw();
			if (tileBitmap != null)
			{
				tileBitmap.recycle();
			}
			tileBitmap = newTile;
			tileSourceRef = source;
			tileX = cutX;
			tileY = cutY;
			tileWidth = cutEndX - cutX;
			tileHeight = cutEndY - cutY;
		}

		// Draw the tile 1:1 (drawScale == scale) at its source origin, then the same image-center rotation as
		// the whole-source path. Nearest-neighbor (filter off) for crisp pixel-peeping.
		imagePaint.setFilterBitmap(false);
		bitmapMatrix.setScale(scale, scale);
		bitmapMatrix.postTranslate(left + tileX * scale, top + tileY * scale);
		if (rotated)
		{
			bitmapMatrix.postRotate(rotation, imageScreenCenterX, imageScreenCenterY);
		}
		canvas.drawBitmap(tileBitmap, bitmapMatrix, imagePaint);
	}

	/**
	 * Return [startX, startY, endX, endY] — the integer AABB of image coords visible under the current viewport +
	 * rotation, clamped to the bitmap's bounds. Computed by un-rotating each of the four screen-viewport corners
	 * into image space and taking the axis-aligned bbox of those points.
	 *
	 * @param state CropState supplying rotation; forwarded to viewport.screenToImagePixelInto
	 * @param imgW  bitmap width in pixels; used to clamp the AABB to the image's right edge
	 * @param imgH  bitmap height in pixels; used to clamp the AABB to the image's bottom edge
	 * @return the shared per-renderer aabbScratch array filled as [startX, startY, endX, endY] — integer AABB of
	 *         visible image coords clamped to [0, imgW] × [0, imgH]; caller must read it immediately and must NOT
	 *         retain it (the next call overwrites the same buffer — reused to avoid per-frame allocation)
	 */
	private int[] visibleImageBoundsAabb(CropState state, int imgW, int imgH)
	{
		int viewWidth = view.getWidth();
		int viewHeight = view.getHeight();
		// Reuse coordScratch across the four corner reads. The min/max are accumulated as floats so we never
		// need to keep two corners alive simultaneously — the alternative (4 fresh float[2]s) was the dominant
		// per-frame allocation source under profiling.
		viewport.screenToImagePixelInto(0f, 0f, state, coordScratch);
		float minX = coordScratch[0];
		float maxX = coordScratch[0];
		float minY = coordScratch[1];
		float maxY = coordScratch[1];
		viewport.screenToImagePixelInto(viewWidth, 0f, state, coordScratch);
		minX = Math.min(minX, coordScratch[0]);
		maxX = Math.max(maxX, coordScratch[0]);
		minY = Math.min(minY, coordScratch[1]);
		maxY = Math.max(maxY, coordScratch[1]);
		viewport.screenToImagePixelInto(0f, viewHeight, state, coordScratch);
		minX = Math.min(minX, coordScratch[0]);
		maxX = Math.max(maxX, coordScratch[0]);
		minY = Math.min(minY, coordScratch[1]);
		maxY = Math.max(maxY, coordScratch[1]);
		viewport.screenToImagePixelInto(viewWidth, viewHeight, state, coordScratch);
		minX = Math.min(minX, coordScratch[0]);
		maxX = Math.max(maxX, coordScratch[0]);
		minY = Math.min(minY, coordScratch[1]);
		maxY = Math.max(maxY, coordScratch[1]);

		aabbScratch[0] = Math.max(0, (int) Math.floor(minX) - 1);
		aabbScratch[1] = Math.max(0, (int) Math.floor(minY) - 1);
		aabbScratch[2] = Math.min(imgW, (int) Math.ceil(maxX) + 1);
		aabbScratch[3] = Math.min(imgH, (int) Math.ceil(maxY) + 1);
		return aabbScratch;
	}
}
