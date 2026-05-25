package com.cropcenter.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Pin the literal values of DialogStrings constants. DialogStrings is the chokepoint that consolidated
 * the Cancel / OK / Apply button labels duplicated across 9 call sites — without these pins, a typo or
 * mojibake (smart-quoted "Apply", curly apostrophe, full-width spaces) slipping into one of the constants would
 * silently change every dialog button text across the app without compile error or test failure. Mirrors the
 * pattern used by ThemeColors / JpegMarker / TiffTag, all of which have value-pinning tests.
 *
 * The reflection-based field count test catches a new constant being added (e.g., REPLACE, KEEP_BOTH)
 * without a corresponding value pin — forcing the test author to add a value pin in the same commit
 * keeps the chokepoint defensively covered.
 */
public final class DialogStringsTest
{
	@Test
	public void allPublicStaticFinalStringsArePinnedByThisTest()
	{
		// Reflection sanity: if someone adds a 5th constant (REPLACE, KEEP_BOTH, …) without a
		// corresponding assertEquals below, fail until the new constant is explicitly pinned.
		// Update the expected count + add a pin when a constant is legitimately added.
		int pinnedCount = 4; // OK, CANCEL, APPLY, INVALID_FILENAME
		int actualCount = 0;
		for (Field field : DialogStrings.class.getDeclaredFields())
		{
			int mods = field.getModifiers();
			if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods)
				&& field.getType() == String.class)
			{
				actualCount++;
			}
		}
		assertEquals("Pinned count out of sync — add a value pin for the new constant",
			pinnedCount, actualCount);
	}

	@Test
	public void noConstantIsAccidentallyBlank()
	{
		// Defensive: an empty button label would render as a tap target with no text — visually
		// broken but easy to ship if a constant was edited to "" by mistake. Pin non-empty.
		assertNotEquals("", DialogStrings.APPLY);
		assertNotEquals("", DialogStrings.CANCEL);
		assertNotEquals("", DialogStrings.INVALID_FILENAME);
		assertNotEquals("", DialogStrings.OK);
	}

	@Test
	public void okCancelApplyInvalidFilenameValuesArePinned()
	{
		assertEquals("OK", DialogStrings.OK);
		assertEquals("Cancel", DialogStrings.CANCEL);
		assertEquals("Apply", DialogStrings.APPLY);
		assertEquals("Invalid filename", DialogStrings.INVALID_FILENAME);
	}
}
