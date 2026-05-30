package com.cropcenter.model;

/**
 * Immutable aspect-ratio specification for the crop box. Dimensions are arbitrary unit — only their ratio is used;
 * `(16, 9)` and `(160, 90)` mean the same thing. The FREE constant (dimensions ≤ 0) signals "no constraint" and lets
 * CropEngine use the maximum available extent on each axis.
 *
 * Hosts the MIN_CROP_DIMENSION_PX constant (moved from CropEngine) so model/ doesn't have to import crop/ —
 * AspectRatio.snap needs the floor to keep tiny ARs from collapsing, and CropEngine's per-axis clamps need it too;
 * keeping it on AspectRatio puts the constraint at the data-type that defines what a "valid AR shape" can be.
 */
public record AspectRatio(float width, float height)
{
	public static final AspectRatio FREE  = new AspectRatio(0, 0);
	// R16_9 sorts before R1_1 in case-sensitive ASCII because '6' (0x36) < '_' (0x5F).
	public static final AspectRatio R16_9 = new AspectRatio(16, 9);
	public static final AspectRatio R1_1  = new AspectRatio(1, 1);
	public static final AspectRatio R2_3  = new AspectRatio(2, 3);
	public static final AspectRatio R3_2  = new AspectRatio(3, 2);
	public static final AspectRatio R3_4  = new AspectRatio(3, 4);
	public static final AspectRatio R4_3  = new AspectRatio(4, 3);
	public static final AspectRatio R4_5  = new AspectRatio(4, 5);
	public static final AspectRatio R5_4  = new AspectRatio(5, 4);
	public static final AspectRatio R9_16 = new AspectRatio(9, 16);

	// 4 px gives the smallest meaningful "this is still a crop" shape — under that, the crop is visible noise
	// rather than a usable selection. Consumed by both AspectRatio.snap (floor on k post-snap) and CropEngine's
	// per-axis clamps (Math.max(MIN_CROP_DIMENSION_PX, ...)); a future "raise the floor" change lands here.
	public static final int MIN_CROP_DIMENSION_PX = 4;

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
	 * Compute the bound aspect ratio. Callers must check isFree() before dividing by the return value —
	 * a free ratio returns the sentinel 0 rather than width / height (which would be NaN for free).
	 *
	 * @return width / height when bound; 0 when free (isFree() returns true)
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
	 * Snap (cropW, cropH) to (Wr·k, Hr·k) where (Wr, Hr) is this AR reduced to lowest terms, picking the
	 * integer k minimising Euclidean distance from (cropW, cropH) — the exact integer-pixel realisation of the
	 * locked AR nearest the requested crop, eliminating the ~0.5 px/axis drift that independent per-axis
	 * Math.round produces.
	 *
	 * Returns input unchanged when this AR is FREE (nothing to snap to) or either dimension is non-integer
	 * (fractional custom AR — exact-integer snap isn't achievable). Capped at (maxW, maxH) so the result never
	 * exceeds bounds (caller passes imgW/imgH, or the input cropW/cropH in a never-grow context), and k is
	 * floored so both axes stay ≥ CropEngine.MIN_CROP_DIMENSION_PX (a 1:1 lock yields 4×4, not 1×1).
	 *
	 * @param cropW pre-snap width in image pixels (output of CropEngine's per-axis Math.round)
	 * @param cropH pre-snap height in image pixels (same)
	 * @param maxW  upper bound on snapped width (typically imgW; pass cropW to forbid growth)
	 * @param maxH  upper bound on snapped height (typically imgH; pass cropH to forbid growth)
	 * @return {snappedW, snappedH}; equals Wr / Hr exactly when both dims are integer, or (cropW, cropH) when
	 *         this AR is FREE / fractional
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
		// smallest k that makes wInt·k ≥ floor; same for hInt. The floor constant lives on this type
		// so model/ doesn't have to depend on crop/; CropEngine reads it back from here.
		int floor = MIN_CROP_DIMENSION_PX;
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
