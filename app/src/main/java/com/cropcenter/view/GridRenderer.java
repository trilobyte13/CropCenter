package com.cropcenter.view;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.cropcenter.model.GridConfig;

// Draws grid overlay lines within the crop rectangle. Grid lines are snapped to image pixel
// boundaries so they sit ON pixels, not between them. This is computed in image space then
// converted to screen space.
public class GridRenderer
{
	// Functional interface for coordinate mapping.
	public interface CoordMapper
	{
		float map(float imageCoord);
	}

	private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	/**
	 * Draw grid lines snapped to pixel boundaries.
	 *
	 * @param canvas              the canvas to draw on (screen coordinates)
	 * @param cropImgX            crop left in image pixels
	 * @param cropImgY            crop top in image pixels
	 * @param cropImgW            crop width in image pixels
	 * @param cropImgH            crop height in image pixels
	 * @param config              grid configuration
	 * @param pixelsPerImagePixel screen pixels per image pixel (baseScale * zoom)
	 * @param imgToScreenX        converts image X to screen X
	 * @param imgToScreenY        converts image Y to screen Y
	 */
	public void draw(Canvas canvas, float cropImgX, float cropImgY,
		int cropImgW, int cropImgH, GridConfig config, float pixelsPerImagePixel,
		CoordMapper imgToScreenX, CoordMapper imgToScreenY)
	{
		if (!config.enabled || config.columns <= 0 || config.rows <= 0)
		{
			return;
		}

		gridPaint.setColor(config.color);
		// Scale line width by image-to-screen ratio so preview matches export
		gridPaint.setStrokeWidth(Math.max(1, config.lineWidth * pixelsPerImagePixel));
		gridPaint.setStyle(Paint.Style.STROKE);

		float screenTop = imgToScreenY.map(cropImgY);
		float screenBottom = imgToScreenY.map(cropImgY + cropImgH);
		float screenLeft = imgToScreenX.map(cropImgX);
		float screenRight = imgToScreenX.map(cropImgX + cropImgW);

		// Vertical lines — snap to integer pixel positions in image space
		for (int i = 1; i < config.columns; i++)
		{
			int imgX = Math.round(cropImgX + cropImgW * i / (float) config.columns);
			float sx = imgToScreenX.map(imgX);
			canvas.drawLine(sx, screenTop, sx, screenBottom, gridPaint);
		}

		// Horizontal lines
		for (int i = 1; i < config.rows; i++)
		{
			int imgY = Math.round(cropImgY + cropImgH * i / (float) config.rows);
			float sy = imgToScreenY.map(imgY);
			canvas.drawLine(screenLeft, sy, screenRight, sy, gridPaint);
		}
	}
}
