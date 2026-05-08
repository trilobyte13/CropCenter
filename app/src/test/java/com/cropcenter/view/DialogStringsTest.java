package com.cropcenter.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pin the literal values of DialogStrings constants. DialogStrings is the round-13 chokepoint that consolidated
 * the Cancel / OK / Apply button labels duplicated across 9 call sites — without these pins, a typo or
 * mojibake (smart-quoted "Apply", curly apostrophe, full-width spaces) slipping into one of the constants would
 * silently change every dialog button text across the app without compile error or test failure. Mirrors the
 * pattern used by ThemeColors / JpegMarker / TiffTag, all of which have value-pinning tests.
 */
public final class DialogStringsTest
{
	@Test
	public void okCancelApplyValuesArePinned()
	{
		assertEquals("OK", DialogStrings.OK);
		assertEquals("Cancel", DialogStrings.CANCEL);
		assertEquals("Apply", DialogStrings.APPLY);
	}
}
