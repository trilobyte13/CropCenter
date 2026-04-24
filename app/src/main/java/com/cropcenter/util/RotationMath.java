package com.cropcenter.util;

/**
 * 2D rotation math shared by the viewport, crop engine, editor renderer, and exporters.
 * Every path that rotates a point around the image center — whether to forward-rotate an
 * image coord for display or reverse-rotate a screen coord to find the corresponding
 * image pixel — goes through here so the sign of sin / cos stays consistent across the
 * codebase.
 *
 * Convention. Android's Canvas.rotate(positiveDegrees) rotates the canvas clockwise in
 * screen space (Y-down). The matching math formula for a "clockwise rotation by θ" in a
 * Y-down system is:
 *   newX = dx * cos(θ) − dy * sin(θ) + pivotX
 *   newY = dx * sin(θ) + dy * cos(θ) + pivotY
 * where (dx, dy) is the offset from the pivot. So the FORWARD rotation here uses
 * positive degrees to match Canvas.rotate, and the inverse just negates the angle.
 */
public final class RotationMath
{
	private RotationMath() {}

	/**
	 * Un-rotate (x, y) around (pivotX, pivotY) by `degrees`. Equivalent to rotate(...,
	 * −degrees). Writes the result into `out[0]` / `out[1]` (caller-allocated length-2
	 * array). Returns `out` for chaining.
	 */
	public static float[] inverse(float x, float y, float pivotX, float pivotY,
		float degrees, float[] out)
	{
		return rotate(x, y, pivotX, pivotY, -degrees, out);
	}

	/**
	 * Forward-rotate (x, y) around (pivotX, pivotY) by `degrees` — matching
	 * Canvas.rotate's clockwise sign convention under Android's Y-down screen
	 * coordinates. Writes the result into `out[0]` / `out[1]` (caller-allocated
	 * length-2 array). Returns `out` for chaining.
	 *
	 * Identity fast path when `|degrees| < BitmapUtils.ROTATION_EPSILON`: the render
	 * pipeline already treats sub-epsilon rotation as zero, so skipping the trig here
	 * keeps this helper consistent with that convention and spares callers from having
	 * to pre-filter small residuals.
	 */
	public static float[] rotate(float x, float y, float pivotX, float pivotY,
		float degrees, float[] out)
	{
		if (Math.abs(degrees) < BitmapUtils.ROTATION_EPSILON)
		{
			out[0] = x;
			out[1] = y;
			return out;
		}
		double rad = Math.toRadians(degrees);
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		double dx = x - pivotX;
		double dy = y - pivotY;
		out[0] = (float) (dx * cos - dy * sin + pivotX);
		out[1] = (float) (dx * sin + dy * cos + pivotY);
		return out;
	}
}
