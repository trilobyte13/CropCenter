package com.cropcenter.util;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

/**
 * Tests for the rotation-degree formatter that drives both the rotation-ruler tick labels and the toolbar
 * readout. The variable-precision behavior — integer / one-decimal / two-decimal — is the chokepoint; a
 * regression in any branch would surface as inconsistent UI between ticks and readout.
 *
 * The Locale.ROOT test deliberately forces a non-en-US JVM default for the duration of the test method (and
 * restores it in @After) so the regression — passing `Locale.getDefault()` instead of `Locale.ROOT` to
 * String.format — would actually surface as a comma-decimal output. Without the locale switch the test would
 * pass vacuously on any en-US JVM.
 */
public final class TextFormatTest
{
	private static final String DEG = "°";

	private Locale savedDefault;

	@Test
	public void degreesRendersIntegerWithoutDecimals()
	{
		assertEquals("5" + DEG, TextFormat.degrees(5f));
		assertEquals("0" + DEG, TextFormat.degrees(0f));
		assertEquals("180" + DEG, TextFormat.degrees(180f));
		assertEquals("-180" + DEG, TextFormat.degrees(-180f));
		assertEquals("-5" + DEG, TextFormat.degrees(-5f));
	}

	@Test
	public void degreesRendersOneDecimalWhenTenthsAreInteger()
	{
		// Within the 0.001 epsilon, deg * 10 rounds exactly to an int — render with %.1f.
		assertEquals("5.1" + DEG, TextFormat.degrees(5.1f));
		assertEquals("0.5" + DEG, TextFormat.degrees(0.5f));
		assertEquals("-0.5" + DEG, TextFormat.degrees(-0.5f));
	}

	@Test
	public void degreesRendersTwoDecimalsForFinerValues()
	{
		// Hundredths-precision values pass both early returns and fall through to %.2f.
		assertEquals("5.12" + DEG, TextFormat.degrees(5.12f));
		assertEquals("0.01" + DEG, TextFormat.degrees(0.01f));
		assertEquals("-0.05" + DEG, TextFormat.degrees(-0.05f));
		assertEquals("-0.99" + DEG, TextFormat.degrees(-0.99f));
	}

	@Test
	public void degreesUsesLocaleRootDecimalSeparator()
	{
		// Force the JVM default to a comma-decimal locale (German) for the duration of this test. A
		// regression that passes Locale.getDefault() instead of Locale.ROOT to String.format would surface
		// as "5,1°" here. Without forcing the locale, the test would pass vacuously on any en-US runner.
		Locale.setDefault(Locale.GERMANY);
		assertEquals("period decimal separator must survive a non-en-US JVM default",
			"5.1" + DEG, TextFormat.degrees(5.1f));
		assertEquals("two-decimal branch must also survive",
			"0.05" + DEG, TextFormat.degrees(0.05f));
	}

	@After
	public void restoreLocale()
	{
		Locale.setDefault(savedDefault);
	}

	@Before
	public void saveLocale()
	{
		savedDefault = Locale.getDefault();
	}
}
