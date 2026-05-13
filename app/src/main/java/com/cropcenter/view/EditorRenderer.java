package com.cropcenter.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.cropcenter.model.CropState;
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
	// Cached per-draw scratch — reset / overwritten at the top of each use so onDraw does no allocation.
	// Sorted into the lowercase-primitive sub-block per CLAUDE.md's uppercase-before-lowercase rule.
	private final int[] aabbScratch = new int[4];
	// Single shared 2-float scratch used by the visible-bounds AABB walk (4 corner reads, sequentially used
	// for min/max — single buffer is safe because we don't need to keep all 4 corners alive at once) and the
	// selection-label imageToScreenRotatedInto calls in drawSelectionLabels (single label position consumed
	// immediately by drawText). Both paths run on the UI thread and don't overlap with each other within a
	// single draw call.
	private final float[] coordScratch = new float[2];

	private float density = 1f;

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
		// grid.color() so the centerpoint marker stays visually consistent with the grid lines around it
		// (user reported the mismatch as a bug when the grid was recoloured but the crosshair stayed mauve).
		pointPaint.setStyle(Paint.Style.FILL);
		polygonPaint.setStyle(Paint.Style.FILL);
		infoPaint.setColor(ThemeColors.TEXT);
		infoPaint.setTextSize(24f);
	}

	/**
	 * Top-level frame draw. Renders (in order): background fill, source bitmap with rotation, optional pixel grid,
	 * optional crop overlay (dim outside + grid lines + crosshair), selection points/polygon/labels, horizon paint
	 * overlay. Snapshots `state.getSourceImage()` once at entry to avoid races with the bg-thread loadImage path
	 * nulling the field mid-frame.
	 *
	 * @param canvas  destination canvas (the View's onDraw canvas)
	 * @param state   editor state — source image, crop rect, rotation, selection, grid config
	 * @param horizon overlay that draws the painted horizon-region polygon
	 */
	void draw(Canvas canvas, CropState state, HorizonPaintOverlay horizon)
	{
		// Snapshot once: sourceImage can be nulled on the background executor during loadImage's reset() while
		// this draw is mid-flight. A null check followed by a second read can race — the check passes on the
		// previous bitmap and the second read returns null, NPE'ing the subsequent bmp.getWidth(). One local
		// read is consistent for the whole frame regardless of concurrent writes.
		Bitmap bmp = state == null ? null : state.getSourceImage();
		if (bmp == null)
		{
			drawEmptyHint(canvas);
			return;
		}
		// Same snapshot reasoning for gridConfig: CropState.reset() replaces the gridConfig reference on the
		// bg executor, and a multi-stage draw that re-reads `state.getGridConfig()` per stage could see the
		// old config in early stages and the new config in later stages — selection markers and the horizon
		// overlay would briefly paint in different colors during a load. One read = one consistent frame.
		GridConfig grid = state.getGridConfig();

		canvas.drawColor(ThemeColors.BACKGROUND);
		float scale = viewport.getBaseScale() * viewport.getZoom();

		// Crisp pixels when zoomed past 4x
		imagePaint.setFilterBitmap(scale < 4f);

		float left = viewport.imageToScreenX(0);
		float top = viewport.imageToScreenY(0);
		float rotation = state.getRotationDegrees();
		// setScale initialises the matrix to a pure scale — overwrites any prior state, so we can reuse
		// bitmapMatrix across frames without an explicit reset().
		bitmapMatrix.setScale(scale, scale);
		bitmapMatrix.postTranslate(left, top);
		// Treat sub-UI-resolution residues as exactly zero — skipping postRotate keeps the identity transform
		// path (crisper preview) when the user has returned the ruler to "0" but a tiny float residue remains.
		if (Math.abs(rotation) >= BitmapUtils.ROTATION_EPSILON)
		{
			float imageScreenCenterX = left + bmp.getWidth() * scale / 2f;
			float imageScreenCenterY = top + bmp.getHeight() * scale / 2f;
			bitmapMatrix.postRotate(rotation, imageScreenCenterX, imageScreenCenterY);
		}
		canvas.drawBitmap(bmp, bitmapMatrix, imagePaint);

		drawPixelGridIfZoomed(canvas, state, grid, bmp, scale);

		float gridImgX;
		float gridImgY;
		int gridW;
		int gridH;
		if (state.hasCenter())
		{
			int cropW = state.getCropW();
			int cropH = state.getCropH();
			// Use the continuous-float crop origin for rendering so smooth rotation produces smooth crop
			// motion. CropExporter samples the same float origin via BitmapUtils.drawCropped, which falls
			// back to bilinear sampling on non-integer offsets — so the rendered overlay and the exported
			// pixels stay in lockstep at sub-pixel precision.
			gridImgX = state.getCropImageXFloat();
			gridImgY = state.getCropImageYFloat();
			gridW = cropW;
			gridH = cropH;
			drawCropOverlay(canvas, state, grid, gridImgX, gridImgY, cropW, cropH);
		}
		else
		{
			gridImgX = 0;
			gridImgY = 0;
			gridW = bmp.getWidth();
			gridH = bmp.getHeight();
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

	private static int withAlpha(int color, int alpha)
	{
		return (color & 0x00FFFFFF) | (alpha << 24);
	}

	/**
	 * Draw the crop-rectangle overlay: 4 dim rectangles outside the crop, the grid lines inside, and the
	 * center-of-crop crosshair. Crop coordinates arrive in image-space; converted to screen-space via the viewport
	 * before drawing. The crosshair colour is taken from `grid.color()` (with 0xCC alpha) so the centerpoint
	 * marker visually matches the grid lines the user picked — earlier the crosshair was a hard-coded mauve
	 * and stayed mauve even after the user recoloured the grid (user-reported bug).
	 */
	private void drawCropOverlay(Canvas canvas, CropState state, GridConfig grid,
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

		float screenCenterX = viewport.imageToScreenX(state.getCenterX());
		float screenCenterY = viewport.imageToScreenY(state.getCenterY());
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
	 * Draw a 1-pixel-per-image-pixel grid when the GridConfig flag is on AND the viewport is zoomed past 6×. Below
	 * the threshold the grid lines would be sub-pixel and unreadable. Computes a rotation-aware axis-aligned
	 * bounding box of the visible image area and only draws lines inside that AABB to avoid the O(W * H) full-image
	 * walk.
	 */
	private void drawPixelGridIfZoomed(Canvas canvas, CropState state, GridConfig grid, Bitmap bmp, float scale)
	{
		if (!grid.showPixelGrid() || scale < 6f)
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
		int imgW = bmp.getWidth();
		int imgH = bmp.getHeight();
		int[] bounds = visibleImageBoundsAabb(state, imgW, imgH);
		int startX = bounds[0];
		int startY = bounds[1];
		int endX = bounds[2];
		int endY = bounds[3];

		// Vertical lines
		for (int x = startX; x <= endX; x++)
		{
			float sx = viewport.imageToScreenX(x);
			canvas.drawLine(sx, viewport.imageToScreenY(startY),
				sx, viewport.imageToScreenY(endY), pixelGridPaint);
		}
		// Horizontal lines
		for (int y = startY; y <= endY; y++)
		{
			float sy = viewport.imageToScreenY(y);
			canvas.drawLine(viewport.imageToScreenX(startX), sy,
				viewport.imageToScreenX(endX), sy, pixelGridPaint);
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
	 * Draw the per-selection-point marker. Filled image-pixel square when zoomed past 6× screen-pixel per
	 * image-pixel (marker visibly follows the rotated pixel grid, becoming a rotated quadrilateral at non-cardinal
	 * angles); a 10-px circle when zoomed out (single pixel is too small to see).
	 */
	private void drawSelectionMarkers(Canvas canvas, List<SelectionPoint> points, float scale)
	{
		float pixelSize = scale; // one image pixel in screen pixels
		for (SelectionPoint point : points)
		{
			if (pixelSize >= 6f)
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
	 * Return [startX, startY, endX, endY] — the integer AABB of image coords visible under the current viewport +
	 * rotation, clamped to the bitmap's bounds. Computed by un-rotating each of the four screen-viewport corners
	 * into image space and taking the axis-aligned bbox of those points.
	 */
	private int[] visibleImageBoundsAabb(CropState state, int imgW, int imgH)
	{
		int viewWidth = view.getWidth();
		int viewHeight = view.getHeight();
		// Reuse coordScratch across the four corner reads. The min/max are accumulated as floats so we never
		// need to keep two corners alive simultaneously — the alternative (4 fresh float[2]s) was the
		// dominant per-frame allocation source flagged by the round-10 audit.
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
