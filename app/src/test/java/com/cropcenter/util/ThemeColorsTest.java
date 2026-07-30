package com.cropcenter.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pin the integer values of ThemeColors constants against the canonical XML hex literals declared in
 * res/values/colors.xml. The Java side exists for context-free callers (Paint setup, custom Views without a Context);
 * the XML side drives styles, layouts, and getResources().getColor lookups. A drift between the two would manifest as a
 * 1-frame color flash on first draw — EditorRenderer's onDraw clearColor would disagree with the activity's window
 * background — without any test failure or compile error.
 */
public final class ThemeColorsTest
{
	@Test
	public void backgroundConstantMatchesXmlValue()
	{
		// BACKGROUND mirrors @color/background: the Javadoc on the constant commits to 0xFF111318 matching
		// res/values/colors.xml's `<color name="background">#FF111318`. Pin the value so a future tweak to one
		// side without the other gets caught here.
		assertEquals(0xFF111318, ThemeColors.BACKGROUND);
	}

	@Test
	public void crustConstantMatchesXmlValue()
	{
		// Pin crust — the darkest Catppuccin surface, used as button-active text color.
		assertEquals(0xFF11111B, ThemeColors.CRUST);
	}

	@Test
	public void mauveConstantMatchesXmlValue()
	{
		// Pin Catppuccin mauve — used heavily by EditorRenderer / dialog accents.
		assertEquals(0xFFCBA6F7, ThemeColors.MAUVE);
	}

	@Test
	public void overlayAndSubtextConstantMatchXmlValues()
	{
		// Pin overlay0 (RotationRulerView axis labels) and subtext0 (RotationRulerView detent labels,
		// EditorRenderer labels, AutoRotateBinder reset color). Both drive the same XML-vs-Java drift class the
		// rest of the constants in this file guard against — a single-side hex tweak would flash the wrong
		// color across multiple surfaces with no compile error.
		assertEquals(0xFF6C7086, ThemeColors.OVERLAY0);
		assertEquals(0xFFA6ADC8, ThemeColors.SUBTEXT0);
	}

	@Test
	public void redConstantMatchesXmlValue()
	{
		// Pin red — used by the Cancel button / paint-mode signal.
		assertEquals(0xFFF38BA8, ThemeColors.RED);
	}

	@Test
	public void surfaceConstantsMatchXmlValues()
	{
		// Pin the three Catppuccin surface tiers — load-bearing across DialogCards backgrounds (SURFACE0),
		// ColorPickerDialog borders / SaveDialog format buttons / SettingsDialog backgrounds (SURFACE1), and
		// EditorRenderer info bar / RotationRulerView major ticks (SURFACE2). Same drift hazard as the accent
		// and text constants above.
		assertEquals(0xFF313244, ThemeColors.SURFACE0);
		assertEquals(0xFF45475A, ThemeColors.SURFACE1);
		assertEquals(0xFF585B70, ThemeColors.SURFACE2);
	}

	@Test
	public void textConstantMatchesXmlValue()
	{
		// Pin text fg — appears in dialog body text and info-bar labels.
		assertEquals(0xFFCDD6F4, ThemeColors.TEXT);
	}
}
