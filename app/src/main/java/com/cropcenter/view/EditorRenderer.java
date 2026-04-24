package com.cropcenter.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.cropcenter.model.CropState;
import com.cropcenter.model.SelectionPoint;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.ThemeColors;

import java.util.List;

/**
 * onDraw body for CropEditorView, extracted so the host view can focus on lifecycle and
 * gesture routing. Owns every Paint used during rendering plus the GridRenderer; reads
 * current transforms from a ViewportMath, current state from CropState, and delegates the
 * auto-rotate overlay to a HorizonPaintOverlay.
 *
 * No mutation of CropState happens here — this is a pure read-and-draw pass.
 */
final class EditorRenderer
{
	private static final int DIM_OVERLAY = 0xAA000000;    // 66% black — dims area outside crop
	private static final int POINT_LABEL_COLOR = ThemeColors.CRUST;

	private final GridRenderer gridRenderer = new GridRenderer();
	// Cached per-draw scratch — reset at the top of each use so onDraw does no allocation.
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
		crosshairPaint.setColor(withAlpha(ThemeColors.MAUVE, 0xCC));
		crosshairPaint.setStrokeWidth(1f);
		// Point / polygon / horizon colors are set per-draw from GridConfig.selectionColor.
		pointPaint.setStyle(Paint.Style.FILL);
		polygonPaint.setStyle(Paint.Style.FILL);
		infoPaint.setColor(ThemeColors.TEXT);
		infoPaint.setTextSize(24f);
	}

	void draw(Canvas canvas, CropState state, HorizonPaintOverlay horizon)
	{
		// Snapshot once: sourceImage can be nulled on the background executor during
		// loadImage's reset() while this draw is mid-flight. A null check followed by a
		// second read can race — the check passes on the previous bitmap and the second
		// read returns null, NPE'ing the subsequent bmp.getWidth(). One local read is
		// consistent for the whole frame regardless of concurrent writes.
		Bitmap bmp = state == null ? null : state.getSourceImage();
		if (bmp == null)
		{
			drawEmptyHint(canvas);
			return;
		}

		canvas.drawColor(ThemeColors.APP_BG);
		float scale = viewport.getBaseScale() * viewport.getZoom();

		// Crisp pixels when zoomed past 4x
		imagePaint.setFilterBitmap(scale < 4f);

		float left = viewport.imageToScreenX(0);
		float top = viewport.imageToScreenY(0);
		float rotation = state.getRotationDegrees();
		// setScale initialises the matrix to a pure scale — overwrites any prior state, so
		// we can reuse bitmapMatrix across frames without an explicit reset().
		bitmapMatrix.setScale(scale, scale);
		bitmapMatrix.postTranslate(left, top);
		// Treat sub-UI-resolution residues as exactly zero — skipping postRotate keeps
		// the identity transform path (crisper preview) when the user has returned the
		// ruler to "0" but a tiny float residue remains.
		if (Math.abs(rotation) >= BitmapUtils.ROTATION_EPSILON)
		{
			float imageScreenCenterX = left + bmp.getWidth() * scale / 2f;
			float imageScreenCenterY = top + bmp.getHeight() * scale / 2f;
			bitmapMatrix.postRotate(rotation, imageScreenCenterX, imageScreenCenterY);
		}
		canvas.drawBitmap(bmp, bitmapMatrix, imagePaint);

		drawPixelGridIfZoomed(canvas, state, bmp, scale);

		float gridImgX;
		float gridImgY;
		int gridW;
		int gridH;
		if (state.hasCenter())
		{
			int cropW = state.getCropW();
			int cropH = state.getCropH();
			// Use the continuous-float crop origin for rendering so smooth rotation produces
			// smooth crop motion. The exporter's integer getCropImgX absorbs the sub-pixel.
			gridImgX = state.getCropImgXFloat();
			gridImgY = state.getCropImgYFloat();
			gridW = cropW;
			gridH = cropH;
			drawCropOverlay(canvas, state, gridImgX, gridImgY, cropW, cropH);
		}
		else
		{
			gridImgX = 0;
			gridImgY = 0;
			gridW = bmp.getWidth();
			gridH = bmp.getHeight();
		}

		gridRenderer.draw(canvas, gridImgX, gridImgY, gridW, gridH,
			state.getGridConfig(), viewport.getBaseScale() * viewport.getZoom(),
			viewport::imageToScreenX, viewport::imageToScreenY);

		if (!state.getSelectionPoints().isEmpty())
		{
			drawSelectionPoints(canvas, state, scale);
		}

		if (horizon.isActive() || horizon.isDrawing())
		{
			int selColor = state.getGridConfig().selectionColor();
			horizon.draw(canvas, view.getWidth(), view.getHeight(), selColor, infoPaint, density);
		}
	}

	private void drawCropOverlay(Canvas canvas, CropState state,
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
		float crosshairArmLength = 15;
		canvas.drawLine(screenCenterX - crosshairArmLength, screenCenterY,
			screenCenterX + crosshairArmLength, screenCenterY, crosshairPaint);
		canvas.drawLine(screenCenterX, screenCenterY - crosshairArmLength,
			screenCenterX, screenCenterY + crosshairArmLength, crosshairPaint);

		infoPaint.setTextAlign(Paint.Align.LEFT);
		infoPaint.setTextSize(11f * density);
		infoPaint.setColor(withAlpha(ThemeColors.SUBTEXT0, 0xAA));
		canvas.drawText(cropW + " x " + cropH, cropLeft + 4, cropTop - 6, infoPaint);
	}

	private void drawEmptyHint(Canvas canvas)
	{
		canvas.drawColor(ThemeColors.APP_BG);
		infoPaint.setTextAlign(Paint.Align.CENTER);
		infoPaint.setTextSize(16f);
		infoPaint.setColor(ThemeColors.SURFACE2);
		canvas.drawText("Tap the gallery icon to open an image",
			view.getWidth() / 2f, view.getHeight() / 2f, infoPaint);
	}

	private void drawPixelGridIfZoomed(Canvas canvas, CropState state, Bitmap bmp, float scale)
	{
		if (!state.getGridConfig().showPixelGrid() || scale < 6f)
		{
			return;
		}
		pixelGridPaint.setColor(state.getGridConfig().pixelGridColor());
		pixelGridPaint.setStrokeWidth(1f);

		// The grid must follow the rotated bitmap. Draw in the rotated canvas so the
		// pixel lines stay aligned with the actual pixel boundaries the user sees.
		float rotation = state.getRotationDegrees();
		boolean rotated = Math.abs(rotation) >= BitmapUtils.ROTATION_EPSILON;
		if (rotated)
		{
			float imageScreenCenterX = viewport.imageToScreenX(state.getImageWidth() / 2f);
			float imageScreenCenterY = viewport.imageToScreenY(state.getImageHeight() / 2f);
			canvas.save();
			canvas.rotate(rotation, imageScreenCenterX, imageScreenCenterY);
		}

		// Cull to the rotated viewport's AABB in image space. Un-rotating the four screen
		// corners gives the image coords that could possibly be visible under the current
		// rotation + viewport; any pixel line outside that AABB is guaranteed off-screen.
		// For a 10000×10000 bitmap zoomed to ~6× on a 1080p view we go from ~20 000 lines
		// drawn to a few hundred — the difference shows up in onDraw time on lower-end
		// devices. Add a one-pixel margin so the border lines of the visible region always
		// draw.
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
	 * Return [startX, startY, endX, endY] — the integer AABB of image coords visible
	 * under the current viewport + rotation, clamped to the bitmap's bounds. Computed
	 * by un-rotating each of the four screen-viewport corners into image space and
	 * taking the axis-aligned bbox of those points.
	 */
	private int[] visibleImageBoundsAabb(CropState state, int imgW, int imgH)
	{
		int viewWidth = view.getWidth();
		int viewHeight = view.getHeight();
		float[] cornerTopLeft = viewport.screenToImagePixel(0f, 0f, state);
		float[] cornerTopRight = viewport.screenToImagePixel(viewWidth, 0f, state);
		float[] cornerBottomLeft = viewport.screenToImagePixel(0f, viewHeight, state);
		float[] cornerBottomRight = viewport.screenToImagePixel(viewWidth, viewHeight, state);

		float minX = Math.min(Math.min(cornerTopLeft[0], cornerTopRight[0]),
			Math.min(cornerBottomLeft[0], cornerBottomRight[0]));
		float maxX = Math.max(Math.max(cornerTopLeft[0], cornerTopRight[0]),
			Math.max(cornerBottomLeft[0], cornerBottomRight[0]));
		float minY = Math.min(Math.min(cornerTopLeft[1], cornerTopRight[1]),
			Math.min(cornerBottomLeft[1], cornerBottomRight[1]));
		float maxY = Math.max(Math.max(cornerTopLeft[1], cornerTopRight[1]),
			Math.max(cornerBottomLeft[1], cornerBottomRight[1]));

		int startX = Math.max(0, (int) Math.floor(minX) - 1);
		int startY = Math.max(0, (int) Math.floor(minY) - 1);
		int endX = Math.min(imgW, (int) Math.ceil(maxX) + 1);
		int endY = Math.min(imgH, (int) Math.ceil(maxY) + 1);
		return new int[] { startX, startY, endX, endY };
	}

	private void drawSelectionPoints(Canvas canvas, CropState state, float scale)
	{
		List<SelectionPoint> points = state.getSelectionPoints();

		// Shared selection color (with its exact alpha) drives points and polygon.
		int selColor = state.getGridConfig().selectionColor();
		pointPaint.setColor(selColor);
		polygonPaint.setColor(selColor);

		// Markers + polygon track image content under rotation by drawing inside a canvas
		// rotated the same way the bitmap was — a point placed on the sun stays on the sun
		// after rotation. Text labels are drawn afterwards (axis-aligned) at the rotated
		// position so the digits stay upright.
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
	 * Draw the translucent polygon connecting selection points. Only rendered when 3+
	 * points are placed (fewer don't form a closed region). Runs inside the caller's
	 * rotated canvas so the polygon follows the image under rotation.
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
	 * Draw the per-selection-point marker. Filled image-pixel square when zoomed past
	 * 6× screen-pixel per image-pixel (marker visibly follows the rotated pixel grid,
	 * becoming a rotated quadrilateral at non-cardinal angles); a 10-px circle when
	 * zoomed out (single pixel is too small to see).
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
	 * Draw the numeric index label next to each selection point. Labels are drawn
	 * axis-aligned (upright) at the rotated screen position of each point so the digits
	 * stay legible under rotation. This runs in the un-rotated canvas — caller must
	 * have already restored out of the rotated canvas before calling.
	 */
	private void drawSelectionLabels(Canvas canvas, CropState state,
		List<SelectionPoint> points, float scale)
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
				float[] center = viewport.imageToScreenRotated(
					pixelX + 0.5f, pixelY + 0.5f, state);
				infoPaint.setTextAlign(Paint.Align.CENTER);
				infoPaint.setTextSize(Math.min(pixelSize * 0.6f, 14f * density));
				infoPaint.setColor(POINT_LABEL_COLOR);
				float labelOffset = infoPaint.getTextSize() * 0.35f;
				canvas.drawText(String.valueOf(labelIndex),
					center[0], center[1] + labelOffset, infoPaint);
			}
			else
			{
				float[] center = viewport.imageToScreenRotated(point.x(), point.y(), state);
				infoPaint.setTextAlign(Paint.Align.CENTER);
				infoPaint.setTextSize(9f * density);
				infoPaint.setColor(POINT_LABEL_COLOR);
				canvas.drawText(String.valueOf(labelIndex),
					center[0], center[1] + 4, infoPaint);
			}
		}
	}

	private static int withAlpha(int color, int alpha)
	{
		return (color & 0x00FFFFFF) | (alpha << 24);
	}
}
