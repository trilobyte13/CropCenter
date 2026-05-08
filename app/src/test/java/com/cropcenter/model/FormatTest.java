package com.cropcenter.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Tests for Format's accessors and the fromExtension reverse-lookup. Format replaced the previous String constants
 * (FORMAT_JPEG / FORMAT_PNG); SaveController routes filenames through fromExtension and the SAF picker round-trips the
 * extension/MIME pair, so the contract strings (".jpg", ".png", "image/jpeg", "image/png") are pinned here against the
 * external SAF / ContentResolver wire format.
 */
public final class FormatTest
{
	// ── extension / mimeType ──

	@Test
	public void jpegExtensionIsLowerCaseDotJpg()
	{
		// Pinned against the SAF picker filename builder convention (.jpg, not .JPG / .jpeg).
		assertEquals(".jpg", Format.JPEG.extension());
	}

	@Test
	public void pngExtensionIsLowerCaseDotPng()
	{
		assertEquals(".png", Format.PNG.extension());
	}

	@Test
	public void jpegMimeTypeIsImageSlashJpeg()
	{
		// Pinned against the ContentResolver / SAF picker MIME-routing convention.
		assertEquals("image/jpeg", Format.JPEG.mimeType());
	}

	@Test
	public void pngMimeTypeIsImageSlashPng()
	{
		assertEquals("image/png", Format.PNG.mimeType());
	}

	// ── fromExtension ──

	@Test
	public void fromExtensionReturnsNullForNullInput()
	{
		// Null guard — caller relies on this to mean "no format change" rather than NPE.
		assertNull(Format.fromExtension(null));
	}

	@Test
	public void fromExtensionRecognisesDotJpg()
	{
		assertEquals(Format.JPEG, Format.fromExtension("photo.jpg"));
	}

	@Test
	public void fromExtensionRecognisesDotJpegAlias()
	{
		// Common alternate spelling — pin that we accept it; SaveController's MIME-mismatch check depends on
		// .jpeg → JPEG (not null) so a user typing "foo.jpeg" in the SAF picker doesn't get rejected.
		assertEquals(Format.JPEG, Format.fromExtension("photo.jpeg"));
	}

	@Test
	public void fromExtensionRecognisesDotPng()
	{
		assertEquals(Format.PNG, Format.fromExtension("photo.png"));
	}

	@Test
	public void fromExtensionIsCaseInsensitive()
	{
		// SAF-returned filenames can come back in any case; user-typed names too. The toLowerCase(Locale.ROOT)
		// in the impl handles this; pin all four canonical case variants.
		assertEquals(Format.JPEG, Format.fromExtension("PHOTO.JPG"));
		assertEquals(Format.JPEG, Format.fromExtension("Photo.Jpeg"));
		assertEquals(Format.PNG, Format.fromExtension("PHOTO.PNG"));
		assertEquals(Format.PNG, Format.fromExtension("Photo.PNG"));
	}

	@Test
	public void fromExtensionReturnsNullForUnknownExtension()
	{
		// Unknown extensions leave the format untouched (caller's "leave-format-unchanged" semantics).
		assertNull(Format.fromExtension("photo.heic"));
		assertNull(Format.fromExtension("photo.webp"));
		assertNull(Format.fromExtension("photo.gif"));
	}

	@Test
	public void fromExtensionReturnsNullForFilenameWithNoExtension()
	{
		assertNull(Format.fromExtension("photo"));
		assertNull(Format.fromExtension(""));
	}

	@Test
	public void fromExtensionMatchesEvenWithCompoundSuffix()
	{
		// `endsWith` semantics: "foo.jpg.bak" ends with ".bak", not ".jpg". Recognises a match only on the real
		// trailing extension — pin to prevent a regression that uses substring contains.
		assertNull("backup file with .bak extension is not JPEG", Format.fromExtension("photo.jpg.bak"));
		// But "screenshot_2025.png" DOES end with ".png" — recognised.
		assertEquals(Format.PNG, Format.fromExtension("screenshot_2025.png"));
	}

	@Test
	public void fromExtensionRecognisesPathBasedNames()
	{
		// SAF display names sometimes include path-like prefixes. The endsWith check still works.
		assertEquals(Format.JPEG, Format.fromExtension("/storage/emulated/0/DCIM/foo.jpg"));
		assertEquals(Format.PNG, Format.fromExtension("primary:Pictures/screenshot.png"));
	}

	@Test
	public void valuesContainsExactlyJpegAndPng()
	{
		// Exhaustiveness pin: Format is JPEG and PNG — adding a third value (e.g. WEBP) without updating
		// CropExporter's switch dispatch reintroduces the silent-default-branch foot-gun the enum was
		// created to prevent. This test fails on the cardinality OR on the membership, forcing a
		// deliberate update everywhere a Format consumer dispatches.
		Format[] vals = Format.values();
		assertEquals("Adding a new Format requires updating CropExporter's dispatch and this test",
			2, vals.length);
		assertEquals(Format.JPEG, vals[0]);
		assertEquals(Format.PNG, vals[1]);
	}
}
