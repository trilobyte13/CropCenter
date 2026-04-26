package com.cropcenter.view;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.cropcenter.model.GridConfig;

/**
 * Draws grid overlay lines within the crop rectangle at the same integer image-pixel
 * positions the exporter's CropExporter.drawGridPixels bakes into the output. Snapping
 * to integer image pixels means a zoomed-in preview shows the grid lines at exactly the
 * columns/rows the export will write — without the snap, Canvas anti-aliasing renders
 * lines at sub-pixel screen positions that diverge from the exporter's rounded pixel
 * positions by up to half an image pixel (visible as several screen pixels of offset at
 * 10×+ zoom).
 *
 * The rounding formula mirrors CropExporter.gridLinePixel exactly: first-half lines
 * round `cropExtent * i / count`; second-half lines mirror through `cropExtent -
 * round(cropExtent * (count - i) / count)` (relative to cropExtent, matching the
 * export's mirror-through-dim). The middle line for count == 2 or 4 stays on cropCenter
 * so single-point selection markers still sit at the grid intersection; the 0.5-pixel
 * export divergence for odd cropExtent + count ∈ {2, 4} is accepted as the only
 * remaining mismatch, and rule-of-thirds (count == 3, the common case) has no middle.
 *
 * Side effect: during a rotation sweep, as CropEngine.recomputeCrop changes cropW by
 * integer amounts, the rounded line positions can jump by one pixel at cropW parity
 * boundaries. The rotation ruler moves in discrete ticks so this happens at tick rate,
 * not per-frame — acceptable trade for preview-matches-export fidelity.
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
	 * Draw the grid's lines inside the crop rectangle. Line positions are snapped to
	 * integer image pixels (see class Javadoc) so the preview matches what the
	 * exporter's drawGridPixels writes.
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
	 * Position line i of a count-N grid along one axis, snapped to an integer image
	 * pixel so the preview matches CropExporter.gridLinePixel's rounding.
	 *
	 * Middle line (i * 2 == count) preserves cropCenter rather than snapping — this
	 * keeps single-point selection markers at the grid intersection. Count ∈ {2, 4}
	 * with odd cropExtent is the only case where this line disagrees with the export
	 * by 0.5 px; rule of thirds (count == 3) has no middle line.
	 *
	 * Second-half lines mirror through cropExtent (not cropCenter) so the rounding
	 * matches the exporter's `dim - round(dim * (count - i) / count)` exactly.
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
			return cropOrigin + Math.round((float) cropExtent * i / count);
		}
		int mirrorOut = Math.round((float) cropExtent * (count - i) / count);
		return cropOrigin + (cropExtent - mirrorOut);
	}
}
