package com.cropcenter.util;

/**
 * Density-independent-pixel to pixel conversion helper. Hoisted from per-file static methods (in ToolbarBinder,
 * ColorPickerDialog) and inline truncation casts (in SaveDialog, SettingsDialog, DialogCards) so every caller uses
 * the same Math.round-based conversion.
 *
 * Why Math.round, not int truncation: non-integer densities shrink small dp values when truncated. At density 1.5,
 * the truncation form turns 3-dp into 4-pixels while Math.round would produce 5; the difference shows up as
 * inconsistent paddings and stroke widths. At density 0.75 the bug is louder: truncating 1-dp produces 0 pixels,
 * collapsing every 1dp value to zero — thin dividers and 1-px tick marks become invisible on low-density screens.
 * Math.round is the documented fix; centralising here removes the four call-sites that were still using truncation
 * (SaveDialog, SettingsDialog, DialogCards inline casts) so the bug can't recur.
 *
 * The pre-fix banned pattern is documented in the CLAUDE.md self-audit grep, which this Javadoc deliberately
 * avoids reproducing literally — describing the bug class without including a sample that the audit grep would
 * itself flag.
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

	/**
	 * Float-input variant for sources where the dp value is itself a fractional measurement (e.g. text-baseline
	 * offsets). Same rounding convention.
	 *
	 * @param dp      density-independent pixel value
	 * @param density display density
	 * @return rounded pixel value
	 */
	public static int toPx(float dp, float density)
	{
		return Math.round(dp * density);
	}
}
