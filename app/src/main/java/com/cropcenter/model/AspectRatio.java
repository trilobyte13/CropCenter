package com.cropcenter.model;

import com.cropcenter.crop.CropEngine;

/**
 * Immutable aspect-ratio specification for the crop box. Dimensions are arbitrary unit — only their ratio is used;
 * `(16, 9)` and `(160, 90)` mean the same thing. The FREE constant (dimensions ≤ 0) signals "no constraint" and lets
 * CropEngine use the maximum available extent on each axis.
 */
public record AspectRatio(float width, float height)
{
	public static final AspectRatio FREE  = new AspectRatio(0, 0);
	public static final AspectRatio R1_1  = new AspectRatio(1, 1);
	public static final AspectRatio R16_9 = new AspectRatio(16, 9);
	public static final AspectRatio R2_3  = new AspectRatio(2, 3);
	public static final AspectRatio R3_2  = new AspectRatio(3, 2);
	public static final AspectRatio R3_4  = new AspectRatio(3, 4);
	public static final AspectRatio R4_3  = new AspectRatio(4, 3);
	public static final AspectRatio R4_5  = new AspectRatio(4, 5);
	public static final AspectRatio R5_4  = new AspectRatio(5, 4);
	public static final AspectRatio R9_16 = new AspectRatio(9, 16);

	/**
	 * Any non-positive or non-finite dimension means "no constraint" — catches both the canonical FREE (0, 0)
	 * and malformed external constructions like (4, 0) or (-1, -1) that would otherwise produce ratio() == 0
	 * and poison CropEngine's Math.round(cropW / ratio) with Integer.MAX_VALUE. The non-finite check (NaN /
	 * Infinity) prevents ratio() returning NaN or Infinity when the user types "NaN" / "Infinity" into the
	 * Custom AR dialog and ToolbarBinder.parseIntOr falls back to a poisoned value.
	 */
	public boolean isFree()
	{
		return width <= 0 || height <= 0 || !Float.isFinite(width) || !Float.isFinite(height);
	}

	/**
	 * width / height, or 0 when free. Callers must check isFree() before dividing by the return value.
	 */
	public float ratio()
	{
		if (isFree())
		{
			return 0;
		}
		return width / height;
	}

	/**
	 * Snap (cropW, cropH) to (Wr·k, Hr·k) where (Wr, Hr) is THIS aspect ratio reduced to lowest terms,
	 * picking the integer k that minimises Euclidean distance from (cropW, cropH). The result is the
	 * exact integer-pixel realisation of the locked aspect ratio nearest the requested crop, eliminating
	 * the ~0.5-pixel-per-axis drift that independent Math.round on each dimension produces.
	 *
	 * Returns input unchanged when:
	 *   - this AR is FREE (no constraint, nothing to snap to), or
	 *   - either dimension is non-integer (custom AR with fractional inputs — exact-integer snap isn't
	 *     achievable; the caller's per-axis round is the best available match)
	 *
	 * The snap is capped at (maxW, maxH) so the result never exceeds the image's actual bounds — caller
	 * passes imgW / imgH (or, in a never-grow context, the input cropW / cropH) to bound the search.
	 * k is also floored at the smallest value that keeps both axes ≥ `CropEngine.MIN_CROP_DIMENSION_PX`
	 * (so a 1:1 lock at the bottom end yields 4×4, not 1×1).
	 *
	 * @param cropW pre-snap width in image pixels (output of CropEngine's per-axis Math.round)
	 * @param cropH pre-snap height in image pixels (same)
	 * @param maxW  upper bound on the snapped width (typically imgW; pass cropW to forbid growth)
	 * @param maxH  upper bound on the snapped height (typically imgH; pass cropH to forbid growth)
	 * @return two-element int array {snappedW, snappedH}; snappedW / snappedH equals Wr / Hr exactly
	 *         when both dimensions are integer-valued, or the original (cropW, cropH) when this AR is
	 *         FREE / fractional
	 */
	public int[] snap(int cropW, int cropH, int maxW, int maxH)
	{
		if (isFree())
		{
			return new int[] { cropW, cropH };
		}
		if (width != Math.round(width) || height != Math.round(height))
		{
			return new int[] { cropW, cropH };
		}
		int wInt = (int) width;
		int hInt = (int) height;
		int divisor = gcd(wInt, hInt);
		wInt /= divisor;
		hInt /= divisor;
		// k that minimises (wInt·k - cropW)² + (hInt·k - cropH)². Setting the derivative to 0 gives
		// k = (wInt·cropW + hInt·cropH) / (wInt² + hInt²). Use long arithmetic so a 16:9 AR on a
		// 50000×50000 image (50000·16 + 50000·9 ≈ 1.25M) doesn't overflow.
		long num = (long) wInt * cropW + (long) hInt * cropH;
		long den = (long) wInt * wInt + (long) hInt * hInt;
		int k = Math.round((float) num / den);
		// Floor k so both axes land at ≥ MIN_CROP_DIMENSION_PX pixels — keeps 1:1 / 2:1 / similar-tiny
		// ARs from collapsing to 1×1 at the lower edge of the optimisation. ceil(floor/wInt) is the
		// smallest k that makes wInt·k ≥ floor; same for hInt. The floor constant is shared with
		// CropEngine via cross-file reference so a future "raise the floor" change lands once.
		int floor = CropEngine.MIN_CROP_DIMENSION_PX;
		int kMin = Math.max(1, Math.max((floor + wInt - 1) / wInt, (floor + hInt - 1) / hInt));
		// Cap k at the supplied bounds — never snap larger than the image (or no-grow caller) allows.
		int kMax = Math.min(maxW / wInt, maxH / hInt);
		if (kMax < kMin)
		{
			// The AR can't fit inside (maxW, maxH) at the 4-pixel floor — caller's pre-snap dims are
			// already the best-available match (e.g. 16:9 lock with maxW=8 has no valid k ≥ 1, so the
			// caller's per-axis round of (8, 4.5) → (8, 5) stands).
			return new int[] { cropW, cropH };
		}
		k = Math.clamp(k, kMin, kMax);
		return new int[] { wInt * k, hInt * k };
	}

	/**
	 * Greatest common divisor via Euclid. Pure helper for snap's reduce-to-lowest-terms step. Operates
	 * on positive integers only (snap pre-checks that both dimensions are positive integer-valued).
	 *
	 * @param a positive integer
	 * @param b positive integer
	 * @return greatest common divisor of a and b
	 */
	private static int gcd(int a, int b)
	{
		while (b != 0)
		{
			int prevB = b;
			b = a % b;
			a = prevB;
		}
		return a;
	}
}
