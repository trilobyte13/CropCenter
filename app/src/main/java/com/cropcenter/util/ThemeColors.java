package com.cropcenter.util;

/**
 * Catppuccin Mocha palette — UI colors shared across views and dialogs. Mirrors
 * res/values/colors.xml for code paths that don't have an android.content.Context handy (static
 * helpers, Paint setup, custom View construction, etc.).
 *
 * When a Context is available, prefer getResources().getColor(R.color.X, null) — the XML
 * definition is the single source of truth. This class exists to avoid forcing a Context
 * parameter through code that's otherwise context-free.
 */
public final class ThemeColors
{
	// Catppuccin Mocha — surfaces & text
	public static final int BASE       = 0xFF1E1E2E;
	public static final int CRUST      = 0xFF11111B;
	public static final int OVERLAY0   = 0xFF6C7086;
	public static final int SUBTEXT0   = 0xFFA6ADC8;
	public static final int SURFACE0   = 0xFF313244;
	public static final int SURFACE1   = 0xFF45475A;
	public static final int SURFACE2   = 0xFF585B70;
	public static final int TEXT       = 0xFFCDD6F4;

	// Catppuccin Mocha — accents
	public static final int MAUVE      = 0xFFCBA6F7;
	public static final int RED        = 0xFFF38BA8;

	// App-specific — not part of the Catppuccin spec
	public static final int APP_BG     = 0xFF111318; // image editor background (darker than base)

	private ThemeColors() {}
}
