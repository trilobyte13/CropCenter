package com.cropcenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

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

	@Test
	public void nextAvailableNumberedNameHandlesExtensionlessOriginal() throws IOException
	{
		// No extension in the original — suggestion appends "(N)" with no extension.
		File dir = Files.createTempDirectory("cc-test").toFile();
		try
		{
			new File(dir, "README").createNewFile();
			assertEquals("README (1)", SaveController.nextAvailableNumberedName(dir, "README"));
		}
		finally
		{
			deleteRecursively(dir);
		}
	}

	@Test
	public void nextAvailableNumberedNameReturnsCandidateWhenNothingExists() throws IOException
	{
		// Empty folder — suggestion is (1) even though the "original" doesn't actually collide. The
		// caller (showInAppRenameDialog) only invokes this AFTER detecting a collision, so the "no
		// collision actually exists" case shouldn't fire in production — but the method's contract is
		// "first available (N)", which is (1) in an empty dir.
		File dir = Files.createTempDirectory("cc-test").toFile();
		try
		{
			String result = SaveController.nextAvailableNumberedName(dir, "foo.jpg");
			assertNotNull(result);
			assertEquals("foo (1).jpg", result);
		}
		finally
		{
			deleteRecursively(dir);
		}
	}

	@Test
	public void nextAvailableNumberedNameSkipsExistingSuffixedFiles() throws IOException
	{
		// foo.jpg AND foo (1).jpg both taken — first available is (2).
		File dir = Files.createTempDirectory("cc-test").toFile();
		try
		{
			new File(dir, "foo.jpg").createNewFile();
			new File(dir, "foo (1).jpg").createNewFile();
			assertEquals("foo (2).jpg", SaveController.nextAvailableNumberedName(dir, "foo.jpg"));
		}
		finally
		{
			deleteRecursively(dir);
		}
	}

	@Test
	public void nextAvailableNumberedNameStripsExistingSuffixBeforeProbing() throws IOException
	{
		// Renaming "foo (1).jpg" must suggest "foo (2).jpg" NOT "foo (1) (1).jpg" — the existing "(1)"
		// is stripped by autoRenameBaseName so the loop probes against "foo.jpg" as the stem.
		File dir = Files.createTempDirectory("cc-test").toFile();
		try
		{
			new File(dir, "foo.jpg").createNewFile();
			new File(dir, "foo (1).jpg").createNewFile();
			assertEquals("foo (2).jpg", SaveController.nextAvailableNumberedName(dir, "foo (1).jpg"));
		}
		finally
		{
			deleteRecursively(dir);
		}
	}

	@Test
	public void nextAvailableNumberedNameSuggestsFirstSuffixWhenStemIsFree() throws IOException
	{
		// Base case: "foo.jpg" exists, "(1)..(N)" don't — suggestion is "foo (1).jpg".
		File dir = Files.createTempDirectory("cc-test").toFile();
		try
		{
			new File(dir, "foo.jpg").createNewFile();
			assertEquals("foo (1).jpg", SaveController.nextAvailableNumberedName(dir, "foo.jpg"));
		}
		finally
		{
			deleteRecursively(dir);
		}
	}

	/**
	 * Recursively delete a temp directory. JUnit 4 doesn't have @TempDir; explicit cleanup keeps the
	 * test side-effect-free.
	 *
	 * @param dir directory to remove; safe to call on a non-existent path
	 */
	private static void deleteRecursively(File dir)
	{
		if (dir == null || !dir.exists())
		{
			return;
		}
		File[] kids = dir.listFiles();
		if (kids != null)
		{
			for (File f : kids)
			{
				if (f.isDirectory())
				{
					deleteRecursively(f);
				}
				else
				{
					assertTrue("failed to delete " + f, f.delete() || !f.exists());
				}
			}
		}
		assertTrue("failed to delete " + dir, dir.delete() || !dir.exists());
	}
}
