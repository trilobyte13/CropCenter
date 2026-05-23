package com.cropcenter.util;

/**
 * 2D rotation math shared by the viewport, crop engine, editor renderer, and exporters. Every path that rotates a point
 * around the image center — whether to forward-rotate an image coord for display or reverse-rotate a screen coord to
 * find the corresponding image pixel — goes through here so the sign of sin / cos stays consistent across the codebase.
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
	 * Un-rotate (x, y) around (pivotX, pivotY) by `degrees`. Equivalent to rotate(..., −degrees). Writes the result
	 * into `out[0]` / `out[1]` (caller-allocated length-2 array). Returns `out` for chaining.
	 *
	 * @param x       point x-coordinate to un-rotate
	 * @param y       point y-coordinate to un-rotate
	 * @param pivotX  pivot x-coordinate
	 * @param pivotY  pivot y-coordinate
	 * @param degrees rotation angle in degrees; un-rotation negates this internally
	 * @param out     caller-allocated length-2 array; mutated with [unrotatedX, unrotatedY]
	 * @return the same `out` reference for chaining
	 */
	public static float[] inverse(float x, float y, float pivotX, float pivotY, float degrees, float[] out)
	{
		return rotate(x, y, pivotX, pivotY, -degrees, out);
	}

	/**
	 * Forward-rotate (x, y) around (pivotX, pivotY) by `degrees` — matching Canvas.rotate's clockwise sign
	 * convention under Android's Y-down screen coordinates. Writes the result into `out[0]` / `out[1]`
	 * (caller-allocated length-2 array). Returns `out` for chaining.
	 *
	 * Identity fast path when `|degrees| < BitmapUtils.ROTATION_EPSILON`: the render pipeline already treats
	 * sub-epsilon rotation as zero, so skipping the trig here keeps this helper consistent with that convention and
	 * spares callers from having to pre-filter small residuals.
	 *
	 * @param x       point x-coordinate to rotate
	 * @param y       point y-coordinate to rotate
	 * @param pivotX  pivot x-coordinate
	 * @param pivotY  pivot y-coordinate
	 * @param degrees clockwise rotation angle in degrees under Y-down screen coordinates; sub-epsilon values
	 *                short-circuit to the identity transform
	 * @param out     caller-allocated length-2 array; mutated with [rotatedX, rotatedY]
	 * @return the same `out` reference for chaining
	 */
	public static float[] rotate(float x, float y, float pivotX, float pivotY, float degrees, float[] out)
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

	/**
	 * Compute the dimensions of the axis-aligned bounding box (AABB) of an imgW × imgH rectangle rotated by
	 * `rotationDegrees` around its center. The standard rotated-AABB formula:
	 *   rotatedW = imgW · |cos θ| + imgH · |sin θ|
	 *   rotatedH = imgW · |sin θ| + imgH · |cos θ|
	 *
	 * Used by viewport fit-to-view to size the rotated image into the view, and by the crop engine to size
	 * the screen-aligned center bounds correctly when the rotated bitmap extends past the un-rotated
	 * [0, imgW] × [0, imgH] rectangle. Sub-epsilon rotations collapse to the input dims unchanged so callers
	 * don't have to pre-filter — the |cos θ| → 1, |sin θ| → 0 limits match the unrotated formula exactly.
	 *
	 * @param imgW            rectangle width in pixels (must be ≥ 0)
	 * @param imgH            rectangle height in pixels (must be ≥ 0)
	 * @param rotationDegrees rotation angle in degrees; sign is irrelevant because the AABB is invariant
	 *                        under sign flip (cos / sin's absolute values are taken)
	 * @param out             caller-allocated length-2 array; mutated and returned
	 * @return the same `out` reference with out[0] = rotatedW, out[1] = rotatedH
	 */
	public static float[] rotatedAabbDimensions(int imgW, int imgH, float rotationDegrees, float[] out)
	{
		if (Math.abs(rotationDegrees) < BitmapUtils.ROTATION_EPSILON)
		{
			out[0] = imgW;
			out[1] = imgH;
			return out;
		}
		double rad = Math.toRadians(rotationDegrees);
		double absCos = Math.abs(Math.cos(rad));
		double absSin = Math.abs(Math.sin(rad));
		out[0] = (float) (imgW * absCos + imgH * absSin);
		out[1] = (float) (imgW * absSin + imgH * absCos);
		return out;
	}

	/**
	 * Round `degrees` to the nearest 0.01° — the precision of the rotation ruler's tick spacing. Used by the
	 * horizon-detector and auto-rotate paths so the announced angle lands exactly on a ruler tick rather than
	 * between two of them, which would otherwise show as visible jitter when the user nudges the dial.
	 *
	 * @param degrees raw rotation in degrees
	 * @return degrees rounded to the nearest 0.01°
	 */
	public static float snapToHundredth(float degrees)
	{
		return Math.round(degrees * 100f) / 100f;
	}
}
