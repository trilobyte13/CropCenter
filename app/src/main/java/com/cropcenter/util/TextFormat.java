package com.cropcenter.util;

import java.util.Locale;

/**
 * Small formatting helpers shared by views and activity UI code.
 */
public final class TextFormat
{
	private TextFormat() {}

	/**
	 * Format a rotation value in degrees with variable precision: integers as "5°", one-decimal as "5.1°", finer as
	 * "5.12°". Used by both the rotation-ruler tick labels and the rotation readout (which must stay visually
	 * consistent). Locale.ROOT because the same string flows into log lines.
	 *
	 * @param deg rotation in degrees, any sign / magnitude
	 * @return formatted degree string (e.g. "5°", "5.1°", "5.12°") with precision adapted to the value's decimal
	 *         residue
	 */
	public static String degrees(float deg)
	{
		if (deg == (int) deg)
		{
			return (int) deg + "\u00B0";
		}
		if (Math.abs(deg * 10 - Math.round(deg * 10)) < 0.001f)
		{
			return String.format(Locale.ROOT, "%.1f\u00B0", deg);
		}
		return String.format(Locale.ROOT, "%.2f\u00B0", deg);
	}
}
