package com.cropcenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Tests for SaveController's auto-rename pattern detection. The user-facing flow this gates: SAF returned a different
 * name than the user typed (because the framework auto-renamed on collision); the controller infers the base name and
 * then offers Replace / Keep / Cancel against the colliding original. A false-positive would put a Replace dialog on a
 * file the user really meant to type as "foo (1).jpg" — and a false-negative skips the dialog and silently saves at the
 * suffixed name. Pin both directions of the pattern match.
 */
public final class SaveControllerTest
{
	@Test
	public void autoRenameBaseNameDetectsClassicalCollision()
	{
		// Most common case: SAF auto-renamed by appending " (1)" before the extension.
		assertEquals("crop.jpg", SaveController.autoRenameBaseName("crop (1).jpg"));
	}

	@Test
	public void autoRenameBaseNameDetectsMultiDigitSuffix()
	{
		// SAF can suffix arbitrary digit counts when prior collisions exist.
		assertEquals("photo.png", SaveController.autoRenameBaseName("photo (123).png"));
	}

	@Test
	public void autoRenameBaseNameDetectsSuffixWithoutSpace()
	{
		// Some legacy providers omit the space before the open paren.
		assertEquals("crop.jpg", SaveController.autoRenameBaseName("crop(1).jpg"));
	}

	@Test
	public void autoRenameBaseNameDetectsUserEditedThenCollided()
	{
		// User typed "foo.jpg" in the picker; SAF returned "foo (1).jpg" because foo.jpg existed in the chosen
		// directory. The controller MUST infer "foo.jpg" (the actual collision) — using the original
		// pendingSaveName would point Replace at the wrong file.
		assertEquals("foo.jpg", SaveController.autoRenameBaseName("foo (1).jpg"));
	}

	@Test
	public void autoRenameBaseNameHandlesStemWithDotsAndSpaces()
	{
		// Multi-dot stem like "image.v2.final.jpg" → "image.v2.final (1).jpg" suffixed → infer the same with
		// "(1)" stripped. lastIndexOf('.') is the canonical extension splitter — pin behaviour against a stem
		// that itself contains dots.
		assertEquals("image.v2.final.jpg", SaveController.autoRenameBaseName("image.v2.final (1).jpg"));
	}

	@Test
	public void autoRenameBaseNamePreservesBaseExtension()
	{
		// Base extension is whatever the suffixed name carries, regardless of case.
		assertEquals("snapshot.PNG", SaveController.autoRenameBaseName("snapshot (5).PNG"));
	}

	@Test
	public void autoRenameBaseNameReturnsNullForCloseWithoutOpen()
	{
		// Reversed bracket — must not crash, must not match.
		assertNull(SaveController.autoRenameBaseName("crop 1).jpg"));
	}

	@Test
	public void autoRenameBaseNameReturnsNullForEmptyParens()
	{
		// "()" has no digits — bare parens, not an auto-rename suffix.
		assertNull(SaveController.autoRenameBaseName("crop ().jpg"));
	}

	@Test
	public void autoRenameBaseNameReturnsNullForExtensionOnly()
	{
		// Leading-dot file (no stem) — SAF wouldn't generate this from a typed filename.
		assertNull(SaveController.autoRenameBaseName(".jpg"));
	}

	@Test
	public void autoRenameBaseNameReturnsNullForNoExtension()
	{
		// No dot at all — can't be the SAF auto-rename pattern.
		assertNull(SaveController.autoRenameBaseName("crop (1)"));
	}

	@Test
	public void autoRenameBaseNameReturnsNullForNoSuffixPattern()
	{
		// Plain filename, no parenthesised suffix.
		assertNull(SaveController.autoRenameBaseName("crop.jpg"));
	}

	@Test
	public void autoRenameBaseNameReturnsNullForNonNumericSuffix()
	{
		// "(copy)" is not the SAF auto-rename pattern; user typed it as part of the filename.
		assertNull(SaveController.autoRenameBaseName("crop (copy).jpg"));
	}

	@Test
	public void autoRenameBaseNameReturnsNullForNullInput()
	{
		assertNull(SaveController.autoRenameBaseName(null));
	}

	@Test
	public void autoRenameBaseNameReturnsNullForOpenParenWithoutClose()
	{
		// Malformed bracket — must not crash, must not match.
		assertNull(SaveController.autoRenameBaseName("crop (1.jpg"));
	}

	@Test
	public void autoRenameBaseNameReturnsNullForSuffixAlone()
	{
		// "(1)" with no leading stem — empty base after stripping the suffix.
		assertNull(SaveController.autoRenameBaseName("(1).jpg"));
	}

	@Test
	public void autoRenameBaseNameStripsTrailingWhitespaceFromBase()
	{
		// Multiple spaces between stem and "(N)" — exercises stripTrailing distinctly from the canonical
		// single-space test (autoRenameBaseNameDetectsClassicalCollision). A regression that switched
		// stripTrailing for a single-char trim would still pass the canonical test but fail this one.
		assertEquals("crop.jpg", SaveController.autoRenameBaseName("crop   (1).jpg"));
	}
}
