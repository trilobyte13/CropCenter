package com.cropcenter.util;

/**
 * Density-independent-pixel to pixel conversion helper. Hoisted from per-file static methods (in ToolbarBinder,
 * ColorPickerDialog) and inline `(int) (N * density)` casts (in SaveDialog, SettingsDialog, DialogCards) so every
 * caller uses the same Math.round-based conversion.
 *
 * Why Math.round, not (int) truncation: non-integer densities shrink small dp values when truncated. Density 1.5 with
 * `(int) (1 * density) == 1` is fine, but `(int) (3 * density) == 4` and `Math.round(3 * 1.5) == 5` differ — and at
 * density 0.75, `(int) density == 0` collapses every 1dp value to zero, making thin dividers and 1-px tick marks
 * invisible on low-density screens. Math.round is the documented fix; centralising here removes the four call-sites
 * that were still using truncation (SaveDialog, SettingsDialog, DialogCards inline casts) so the bug can't recur.
 */
public final class DpToPx
{
	private DpToPx() {}

	/**
	 * Convert a dp value to pixels at the given display density, rounding to the nearest integer pixel.
	 *
	 * @param dp      density-independent pixel value
	 * @param density display density (typically from {@code DisplayMetrics.density})
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
