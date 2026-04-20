package com.cropcenter.view;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.cropcenter.model.GridConfig;

// Draws grid overlay lines within the crop rectangle. Positioning is keyed on the crop
// DIMENSION'S parity, so the grid's visual behavior flips as the cropped size crosses
// even ↔ odd:
//   • Even cropW/cropH — lines snap to integer image coords (pixel boundaries). A stroke-1
//     line at an integer coord straddles a pixel boundary ("between pixels").
//   • Odd cropW/cropH — lines snap to pixel centers (N + 0.5). A stroke-1 line at a pixel
//     center sits fully on one pixel ("covers pixels").
// The middle line (if one exists, i = N/2 for even count) uses cropCenter directly —
// because selection-point taps are snapped to pixel centers upstream, cropCenter is
// already at N + 0.5 for a single-point selection (cropW = 2·(N+0.5) = odd integer),
// so the middle line covers the selection point's pixel.
// Second-half lines mirror the first half around cropCenter for pair symmetry.
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

		// Vertical lines.
		boolean widthEven = (cropImgW & 1) == 0;
		float cropCenterX = cropImgX + cropImgW / 2f;
		for (int i = 1; i < config.columns; i++)
		{
			float sx = imgToScreenX.map(
				snap(i, config.columns, cropImgX, cropImgW, cropCenterX, widthEven));
			canvas.drawLine(sx, screenTop, sx, screenBottom, gridPaint);
		}

		// Horizontal lines.
		boolean heightEven = (cropImgH & 1) == 0;
		float cropCenterY = cropImgY + cropImgH / 2f;
		for (int i = 1; i < config.rows; i++)
		{
			float sy = imgToScreenY.map(
				snap(i, config.rows, cropImgY, cropImgH, cropCenterY, heightEven));
			canvas.drawLine(screenLeft, sy, screenRight, sy, gridPaint);
		}
	}

	// Position line i of a count-N grid along one axis. Middle line uses cropCenter
	// directly so it passes through the selection point (tap coordinates are snapped to
	// pixel centers upstream). Non-middle lines snap by crop dimension parity so the whole
	// grid visibly transitions between "between pixels" (even dim) and "covers pixels"
	// (odd dim) as cropW/cropH flips. Second-half lines mirror the first half for
	// symmetry around cropCenter.
	private static float snap(int i, int count, float cropOrigin, int cropExtent,
		float cropCenter, boolean dimEven)
	{
		if (i * 2 == count)
		{
			return cropCenter;
		}
		if (i * 2 < count)
		{
			float raw = cropOrigin + cropExtent * i / (float) count;
			return alignByDim(raw, dimEven);
		}
		int mirrorI = count - i;
		float mirrorRaw = cropOrigin + cropExtent * mirrorI / (float) count;
		return 2 * cropCenter - alignByDim(mirrorRaw, dimEven);
	}

	// Snap by crop-dimension parity: even dim → integer coord (line between pixels),
	// odd dim → pixel center (line covers a pixel).
	private static float alignByDim(float value, boolean dimEven)
	{
		if (dimEven)
		{
			return Math.round(value);
		}
		return (float) Math.floor(value) + 0.5f;
	}
}
