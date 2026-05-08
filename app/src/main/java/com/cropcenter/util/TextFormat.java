package com.cropcenter.util;

import java.util.Locale;

/**
 * Small formatting helpers shared by views and activity UI code.
 */
public final class TextFormat
{
	private TextFormat() {}

	/**
	 * Format a rotation value in degrees with variable precision:
	 *   integer values render as "5°"
	 *   one-decimal values render as "5.1°"
	 *   finer values render as "5.12°"
	 *
	 * Used by both the rotation-ruler tick labels and the rotation readout, which must stay visually consistent.
	 * Locale.ROOT — the same string flows into log lines and is part of the toolbar UI; mixing locale-specific
	 * decimal separators between the two would surface as inconsistent log/UI in non-en-US locales (CLAUDE.md
	 * "String formatting" rule mandates Locale.ROOT for internal-use formatting).
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
