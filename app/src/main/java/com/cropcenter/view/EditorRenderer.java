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
	private final Paint cropBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint dimPaint = new Paint();
	private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
	private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint pixelGridPaint = new Paint();
	private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint polygonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
		if (state == null || state.getSourceImage() == null)
		{
			drawEmptyHint(canvas);
			return;
		}

		canvas.drawColor(ThemeColors.APP_BG);

		Bitmap bmp = state.getSourceImage();
		float scale = viewport.getBaseScale() * viewport.getZoom();

		// Crisp pixels when zoomed past 4x
		imagePaint.setFilterBitmap(scale < 4f);

		float left = viewport.imageToScreenX(0);
		float top = viewport.imageToScreenY(0);
		float rotation = state.getRotationDegrees();
		Matrix matrix = new Matrix();
		matrix.setScale(scale, scale);
		matrix.postTranslate(left, top);
		// Treat sub-UI-resolution residues as exactly zero — skipping postRotate keeps
		// the identity transform path (crisper preview) when the user has returned the
		// ruler to "0" but a tiny float residue remains.
		if (Math.abs(rotation) >= BitmapUtils.ROTATION_EPSILON)
		{
			float imgCx = left + bmp.getWidth() * scale / 2f;
			float imgCy = top + bmp.getHeight() * scale / 2f;
			matrix.postRotate(rotation, imgCx, imgCy);
		}
		canvas.drawBitmap(bmp, matrix, imagePaint);

		drawPixelGridIfZoomed(canvas, state, bmp, scale);

		float gridImgX;
		float gridImgY;
		int gridW;
		int gridH;
		if (state.hasCenter())
		{
			int cw = state.getCropW();
			int ch = state.getCropH();
			// Integer crop origin by the pixel-alignment invariant (getCropImgX/Y validates it).
			gridImgX = state.getCropImgX();
			gridImgY = state.getCropImgY();
			gridW = cw;
			gridH = ch;
			drawCropOverlay(canvas, state, gridImgX, gridImgY, cw, ch);
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
		float gridImgX, float gridImgY, int cw, int ch)
	{
		float cropL = viewport.imageToScreenX(gridImgX);
		float cropT = viewport.imageToScreenY(gridImgY);
		float cropR = viewport.imageToScreenX(gridImgX + cw);
		float cropB = viewport.imageToScreenY(gridImgY + ch);

		// Dim outside crop — cover full canvas, not just image bounds
		int vw = view.getWidth();
		int vh = view.getHeight();
		canvas.drawRect(0, 0, vw, cropT, dimPaint);       // top
		canvas.drawRect(0, cropB, vw, vh, dimPaint);       // bottom
		canvas.drawRect(0, cropT, cropL, cropB, dimPaint); // left
		canvas.drawRect(cropR, cropT, vw, cropB, dimPaint); // right

		canvas.drawRect(cropL, cropT, cropR, cropB, cropBorderPaint);

		float scx = viewport.imageToScreenX(state.getCenterX());
		float scy = viewport.imageToScreenY(state.getCenterY());
		float armLen = 15;
		canvas.drawLine(scx - armLen, scy, scx + armLen, scy, crosshairPaint);
		canvas.drawLine(scx, scy - armLen, scx, scy + armLen, crosshairPaint);

		infoPaint.setTextAlign(Paint.Align.LEFT);
		infoPaint.setTextSize(11f * density);
		infoPaint.setColor(withAlpha(ThemeColors.SUBTEXT0, 0xAA));
		canvas.drawText(cw + " x " + ch, cropL + 4, cropT - 6, infoPaint);
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

		float visLeft = viewport.screenToImageX(0);
		float visTop = viewport.screenToImageY(0);
		float visRight = viewport.screenToImageX(view.getWidth());
		float visBottom = viewport.screenToImageY(view.getHeight());

		int startX = Math.max(0, (int) Math.floor(visLeft));
		int startY = Math.max(0, (int) Math.floor(visTop));
		int endX = Math.min(bmp.getWidth(), (int) Math.ceil(visRight));
		int endY = Math.min(bmp.getHeight(), (int) Math.ceil(visBottom));

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
	}

	private void drawSelectionPoints(Canvas canvas, CropState state, float scale)
	{
		List<SelectionPoint> points = state.getSelectionPoints();

		// Use the shared selection color (with its exact alpha) for points and polygon.
		int selColor = state.getGridConfig().selectionColor();
		pointPaint.setColor(selColor);
		polygonPaint.setColor(selColor);

		if (points.size() >= 3)
		{
			Path path = new Path();
			boolean first = true;
			for (SelectionPoint point : points)
			{
				float sx = viewport.imageToScreenX(point.x());
				float sy = viewport.imageToScreenY(point.y());
				if (first)
				{
					path.moveTo(sx, sy);
					first = false;
				}
				else
				{
					path.lineTo(sx, sy);
				}
			}
			path.close();
			canvas.drawPath(path, polygonPaint);
		}

		// Draw points — fill pixel square when zoomed in, circle when zoomed out
		int idx = 0;
		float pixelSize = scale; // one image pixel in screen pixels
		for (SelectionPoint point : points)
		{
			idx++;
			if (pixelSize >= 6f)
			{
				int pixelX = (int) Math.floor(point.x());
				int pixelY = (int) Math.floor(point.y());
				float pixelLeft = viewport.imageToScreenX(pixelX);
				float pixelTop = viewport.imageToScreenY(pixelY);
				float pixelRight = viewport.imageToScreenX(pixelX + 1);
				float pixelBottom = viewport.imageToScreenY(pixelY + 1);
				canvas.drawRect(pixelLeft, pixelTop, pixelRight, pixelBottom, pointPaint);
				infoPaint.setTextAlign(Paint.Align.CENTER);
				infoPaint.setTextSize(Math.min(pixelSize * 0.6f, 14f * density));
				infoPaint.setColor(POINT_LABEL_COLOR);
				float textX = (pixelLeft + pixelRight) / 2;
				float textY = (pixelTop + pixelBottom) / 2 + infoPaint.getTextSize() * 0.35f;
				canvas.drawText(String.valueOf(idx), textX, textY, infoPaint);
			}
			else
			{
				float screenX = viewport.imageToScreenX(point.x());
				float screenY = viewport.imageToScreenY(point.y());
				canvas.drawCircle(screenX, screenY, 10, pointPaint);
				infoPaint.setTextAlign(Paint.Align.CENTER);
				infoPaint.setTextSize(9f * density);
				infoPaint.setColor(POINT_LABEL_COLOR);
				canvas.drawText(String.valueOf(idx), screenX, screenY + 4, infoPaint);
			}
		}
	}

	private static int withAlpha(int color, int alpha)
	{
		return (color & 0x00FFFFFF) | (alpha << 24);
	}
}
