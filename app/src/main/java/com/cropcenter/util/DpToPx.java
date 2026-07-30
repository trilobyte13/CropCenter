package com.cropcenter.util;

/**
 * Density-independent-pixel to pixel conversion helper. Single chokepoint for every dp→px conversion in the codebase.
 *
 * Why Math.round, not int truncation: non-integer densities shrink small dp values when truncated. At density 1.5,
 * truncation turns 3-dp into 4-pixels while Math.round would produce 5; the difference shows up as inconsistent
 * paddings and stroke widths. At density 0.75 the bug is louder: truncating 1-dp produces 0 pixels, collapsing every
 * 1dp value to zero — thin dividers and 1-px tick marks become invisible on low-density screens.
 */
public final class DpToPx
{
	private DpToPx() {}

	/**
	 * Convert a dp value to pixels at the given display density, rounding to the nearest integer pixel.
	 *
	 * @param dp      density-independent pixel value
	 * @param density display density (typically from DisplayMetrics.density)
	 * @return rounded pixel value
	 */
	public static int toPx(int dp, float density)
	{
		return Math.round(dp * density);
	}
}
