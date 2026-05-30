package com.cropcenter.view;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.cropcenter.model.GridConfig;
import com.cropcenter.util.GridGeometry;

/**
 * Draws grid overlay lines within the crop rectangle at positions that match the exporter's
 * CropExporter.drawGridPixels. Each line is at `cropOrigin + Math.round(cropExtent * i / count)` — the rounded
 * relative-offset is an integer image-pixel step, so the line lands on an integer image pixel when the crop origin is
 * integer (the export's crop-output-space coordinate system) and on an equivalent fractional source-image position
 * when the preview's crop origin is fractional (centerX − cropW/2f for odd cropW with even centerX, etc.). Either way
 * the relative spacing inside the crop matches the export's baked positions byte-for-byte, so a zoomed-in preview
 * shows the grid lines at exactly the columns / rows the export will write.
 *
 * The rounding formula mirrors CropExporter.gridLinePixel exactly: first-half lines round `cropExtent * i / count`;
 * second-half lines mirror through `cropExtent - round(cropExtent * (count - i) / count)` (relative to cropExtent,
 * matching the export's mirror-through-dim). The middle line for count == 2 or 4 stays on cropCenter so single-point
 * selection markers still sit at the grid intersection; the 0.5-pixel export divergence for odd cropExtent + count ∈
 * {2, 4} is accepted as the only remaining mismatch, and rule-of-thirds (count == 3, the common case) has no middle.
 *
 * Side effect: during a rotation sweep, as CropEngine.recomputeCrop changes cropW by integer amounts, the rounded
 * relative offsets can jump by one pixel at cropW parity boundaries. The rotation ruler moves in discrete ticks so
 * this happens at tick rate, not per-frame — acceptable trade for preview-matches-export fidelity.
 */
public final class GridRenderer
{
	/**
	 * Single-axis image → screen coordinate mapper. Callers supply one instance for the X axis and one
	 * for the Y axis when invoking draw(); the mapper applies the viewport's pan + zoom so an image
	 * pixel maps to a canvas pixel. Pure function — no per-call allocation, no internal state.
	 */
	public interface CoordMapper
	{
		/**
		 * Project an image-pixel coordinate onto its corresponding screen coordinate along one axis.
		 *
		 * @param imageCoord coordinate in image-pixel space (one axis only)
		 * @return the corresponding canvas-pixel coordinate
		 */
		float map(float imageCoord);
	}

	private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	/**
	 * Draw the grid's lines inside the crop rectangle. Line positions are snapped to integer image pixels (see
	 * class Javadoc) so the preview matches what the exporter's drawGridPixels writes.
	 *
	 * @param canvas              the canvas to draw on (screen coordinates)
	 * @param cropImageX            crop left in image pixels
	 * @param cropImageY            crop top in image pixels
	 * @param cropImageW            crop width in image pixels
	 * @param cropImageH            crop height in image pixels
	 * @param config              grid configuration
	 * @param pixelsPerImagePixel screen pixels per image pixel (baseScale * zoom)
	 * @param imgToScreenX        converts image X to screen X
	 * @param imgToScreenY        converts image Y to screen Y
	 */
	public void draw(Canvas canvas, float cropImageX, float cropImageY,
		int cropImageW, int cropImageH, GridConfig config, float pixelsPerImagePixel,
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

		float screenTop = imgToScreenY.map(cropImageY);
		float screenBottom = imgToScreenY.map(cropImageY + cropImageH);
		float screenLeft = imgToScreenX.map(cropImageX);
		float screenRight = imgToScreenX.map(cropImageX + cropImageW);

		float cropCenterX = cropImageX + cropImageW / 2f;
		for (int i = 1; i < config.columns(); i++)
		{
			float sx = imgToScreenX.map(linePos(i, config.columns(), cropImageX, cropImageW, cropCenterX));
			canvas.drawLine(sx, screenTop, sx, screenBottom, gridPaint);
		}

		float cropCenterY = cropImageY + cropImageH / 2f;
		for (int i = 1; i < config.rows(); i++)
		{
			float sy = imgToScreenY.map(linePos(i, config.rows(), cropImageY, cropImageH, cropCenterY));
			canvas.drawLine(screenLeft, sy, screenRight, sy, gridPaint);
		}
	}

	/**
	 * Position line i of a count-N grid along one axis. Returns cropOrigin + Math.round(...) so the relative
	 * offset matches CropExporter.gridLinePixel's rounding byte-for-byte, keeping preview and export aligned
	 * whether cropOrigin is integer or fractional.
	 *
	 * Middle line (i*2 == count) preserves cropCenter rather than snapping, so single-point selection markers
	 * stay on the grid intersection. (Count ∈ {2, 4} with odd cropExtent is the only case this disagrees with
	 * export, by 0.5 px; rule of thirds has no middle line.) Second-half lines mirror through cropExtent (not
	 * cropCenter) so rounding matches the exporter's dim - round(dim * (count - i) / count) exactly.
	 *
	 * Package-private so GridRendererTest can pin the preview-matches-export contract without a Canvas.
	 *
	 * @param i          grid line index in [1, count - 1] (inner lines; edges are the crop boundary)
	 * @param count      total grid intervals (draws count - 1 inner lines on this axis)
	 * @param cropOrigin axis origin of the crop in image-pixel space; may be fractional at a non-integer pan
	 * @param cropExtent total crop dimension in pixels along this axis
	 * @param cropCenter crop-center coord; consulted only on the middle line (single-point marker alignment)
	 * @return image-space coordinate of line i; the (i, count - i) pair satisfies
	 *         linePos(i) + linePos(count - i) - 2 * cropOrigin == cropExtent up to half-pixel rounding
	 */
	static float linePos(int i, int count, float cropOrigin, int cropExtent, float cropCenter)
	{
		// Middle-line special case: anchor to cropCenter (the fractional float pan position) so a
		// single-point selection marker sits exactly on the grid intersection. Delegating to
		// GridGeometry.mirroredLinePos would lose this — that helper returns an integer offset.
		if (i * 2 == count)
		{
			return cropCenter;
		}
		// Off-centre lines: delegate to the shared chokepoint so the on-screen preview and the
		// export-baked grid use byte-identical rounding (preventing the "ghost grid" mismatch
		// that would otherwise appear at certain dims when comparing a save against the editor).
		return cropOrigin + GridGeometry.mirroredLinePos(i, count, cropExtent);
	}
}
