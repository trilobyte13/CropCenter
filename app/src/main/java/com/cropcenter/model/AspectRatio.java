package com.cropcenter.model;

/**
 * Immutable aspect-ratio specification for the crop box. Dimensions are arbitrary unit —
 * only their ratio is used; `(16, 9)` and `(160, 90)` mean the same thing. The FREE
 * constant (dimensions ≤ 0) signals "no constraint" and lets CropEngine use the maximum
 * available extent on each axis.
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
	 * Any non-positive dimension means "no constraint" — catches both the canonical FREE
	 * (0, 0) and malformed external constructions like (4, 0) or (-1, -1) that would
	 * otherwise produce ratio() == 0 and poison CropEngine's Math.round(cropW / ratio)
	 * with Integer.MAX_VALUE.
	 */
	public boolean isFree()
	{
		return width <= 0 || height <= 0;
	}

	/**
	 * width / height, or 0 when free. Callers must check isFree() before dividing by the
	 * return value.
	 */
	public float ratio()
	{
		if (isFree())
		{
			return 0;
		}
		return width / height;
	}
}
