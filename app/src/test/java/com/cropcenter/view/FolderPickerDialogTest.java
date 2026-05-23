package com.cropcenter.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.cropcenter.model.Format;

/**
 * Tests for FolderPickerDialog's package-private static helpers. The merged save dialog is dominated by
 * AlertDialog / Context / View dependencies that are difficult to exercise from JUnit, but the
 * extension-normalisation rule that gates the in-app save path is pure-string and worth pinning. A
 * regression here would let "photo.heic" with format=PNG save PNG bytes under the .heic name —
 * misclassifying the file for gallery / file-manager type inference.
 */
public final class FolderPickerDialogTest
{
	@Test
	public void normaliseExtensionAppendsWhenMissing()
	{
		// User typed a bare stem; expect the format extension appended verbatim.
		assertEquals("photo.jpg", FolderPickerDialog.normaliseExtension("photo", Format.JPEG));
		assertEquals("photo.png", FolderPickerDialog.normaliseExtension("photo", Format.PNG));
	}

	@Test
	public void normaliseExtensionLeadingDotStemKeepsName()
	{
		// "lastIndexOf('.') > 0" — a name whose only dot is at index 0 (.gitignore-style) is treated
		// as a bare stem; the format extension is APPENDED, not swapped onto the leading-dot segment.
		// Production callers don't hit this path (the picker pre-fills "<stem>.<ext>" so the user
		// would have to type a leading-dot name explicitly), but the helper's contract should be
		// stable under it rather than crash or produce a misleading "..jpg".
		assertEquals(".gitignore.jpg",
			FolderPickerDialog.normaliseExtension(".gitignore", Format.JPEG));
	}

	@Test
	public void normaliseExtensionPreservesStemWithDots()
	{
		// Multi-dot stem like "image.v2.final.heic" — only the LAST dot's extension is swapped, so
		// the meaningful filename structure ("image.v2.final") survives intact.
		assertEquals("image.v2.final.png",
			FolderPickerDialog.normaliseExtension("image.v2.final.heic", Format.PNG));
	}

	@Test
	public void normaliseExtensionSwapsMismatchedExtension()
	{
		// Core case the Codex finding was about: user typed a mismatched extension (heic, jpg under
		// a PNG format toggle, etc.). The save path must align the on-disk extension with the
		// encoded format so gallery type inference doesn't lie.
		assertEquals("photo.png", FolderPickerDialog.normaliseExtension("photo.heic", Format.PNG));
		assertEquals("photo.jpg", FolderPickerDialog.normaliseExtension("photo.png", Format.JPEG));
	}

	@Test
	public void normaliseExtensionSwapsTrailingExtension()
	{
		// Canonical case: jpg ↔ png swap with a single trailing extension. Pin both directions so
		// the format chip's effect on the filename remains symmetric.
		assertEquals("photo.png", FolderPickerDialog.normaliseExtension("photo.jpg", Format.PNG));
		assertEquals("photo.jpg", FolderPickerDialog.normaliseExtension("photo.png", Format.JPEG));
	}
}
