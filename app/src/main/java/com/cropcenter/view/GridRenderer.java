package com.cropcenter.view;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.cropcenter.model.GridConfig;

/**
 * Draws grid overlay lines within the crop rectangle at continuous float positions
 * (cropOrigin + cropExtent * i / count), mirrored around cropCenter for pair symmetry.
 * Positions are NOT snapped to pixel boundaries — a smoothly moving crop (e.g. during
 * rotation fling) produces smoothly moving grid lines. At rest the lines are
 * anti-aliased instead of pixel-aligned, a small cosmetic trade for no flicker.
 */
public class GridRenderer
{
	/**
	 * Functional interface for coordinate mapping.
	 */
	public interface CoordMapper
	{
		float map(float imageCoord);
	}

	private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	/**
	 * Draw the grid's lines at continuous-float positions inside the crop rectangle (see
	 * the class Javadoc). Line positions are NOT snapped to pixel boundaries.
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
		if (!config.enabled() || config.columns() <= 0 || config.rows() <= 0)
		{
			return;
		}

		gridPaint.setColor(config.color());
		// Scale line width by image-to-screen ratio so preview matches export
		gridPaint.setStrokeWidth(Math.max(1, config.lineWidth() * pixelsPerImagePixel));
		gridPaint.setStyle(Paint.Style.STROKE);

		float screenTop = imgToScreenY.map(cropImgY);
		float screenBottom = imgToScreenY.map(cropImgY + cropImgH);
		float screenLeft = imgToScreenX.map(cropImgX);
		float screenRight = imgToScreenX.map(cropImgX + cropImgW);

		// Vertical lines.
		float cropCenterX = cropImgX + cropImgW / 2f;
		for (int i = 1; i < config.columns(); i++)
		{
			float sx = imgToScreenX.map(
				linePos(i, config.columns(), cropImgX, cropImgW, cropCenterX));
			canvas.drawLine(sx, screenTop, sx, screenBottom, gridPaint);
		}

		// Horizontal lines.
		float cropCenterY = cropImgY + cropImgH / 2f;
		for (int i = 1; i < config.rows(); i++)
		{
			float sy = imgToScreenY.map(
				linePos(i, config.rows(), cropImgY, cropImgH, cropCenterY));
			canvas.drawLine(screenLeft, sy, screenRight, sy, gridPaint);
		}
	}

	/**
	 * Position line i of a count-N grid along one axis. Middle line (i * 2 == count) sits
	 * exactly on cropCenter — important for single-point selections where the selection
	 * marker sits at cropCenter. Second-half lines mirror the first half around cropCenter
	 * so the grid is visually symmetric even when cropExtent / count isn't integer.
	 */
	private static float linePos(int i, int count, float cropOrigin, int cropExtent,
		float cropCenter)
	{
		if (i * 2 == count)
		{
			return cropCenter;
		}
		if (i * 2 < count)
		{
			return cropOrigin + cropExtent * i / (float) count;
		}
		int mirrorI = count - i;
		float mirrorRaw = cropOrigin + cropExtent * mirrorI / (float) count;
		return 2 * cropCenter - mirrorRaw;
	}
}
