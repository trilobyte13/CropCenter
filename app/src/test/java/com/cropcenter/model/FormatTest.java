package com.cropcenter.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for Format's accessors and the fromExtension reverse-lookup. SaveController routes filenames through
 * fromExtension and the SAF picker round-trips the extension/MIME pair, so the contract strings (".jpg", ".png",
 * "image/jpeg", "image/png") are pinned here against the external SAF / ContentResolver wire format.
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
	public void fromExtensionReturnsEmptyForNullInput()
	{
		// Null guard — caller relies on this to mean "no format change" rather than NPE.
		assertTrue(Format.fromExtension(null).isEmpty());
	}

	@Test
	public void fromExtensionRecognisesDotJpg()
	{
		assertEquals(Format.JPEG, Format.fromExtension("photo.jpg").orElseThrow());
	}

	@Test
	public void fromExtensionRecognisesDotJpegAlias()
	{
		// Common alternate spelling — pin that we accept it; SaveController's MIME-mismatch check depends on
		// .jpeg → JPEG (not empty) so a user typing "foo.jpeg" in the SAF picker doesn't get rejected.
		assertEquals(Format.JPEG, Format.fromExtension("photo.jpeg").orElseThrow());
	}

	@Test
	public void fromExtensionRecognisesDotPng()
	{
		assertEquals(Format.PNG, Format.fromExtension("photo.png").orElseThrow());
	}

	@Test
	public void fromExtensionIsCaseInsensitive()
	{
		// SAF-returned filenames can come back in any case; user-typed names too. The toLowerCase(Locale.ROOT)
		// in the impl handles this; pin all four canonical case variants.
		assertEquals(Format.JPEG, Format.fromExtension("PHOTO.JPG").orElseThrow());
		assertEquals(Format.JPEG, Format.fromExtension("Photo.Jpeg").orElseThrow());
		assertEquals(Format.PNG, Format.fromExtension("PHOTO.PNG").orElseThrow());
		assertEquals(Format.PNG, Format.fromExtension("Photo.PNG").orElseThrow());
	}

	@Test
	public void fromExtensionReturnsEmptyForUnknownExtension()
	{
		// Unknown extensions leave the format untouched (caller's "leave-format-unchanged" semantics).
		assertTrue(Format.fromExtension("photo.heic").isEmpty());
		assertTrue(Format.fromExtension("photo.webp").isEmpty());
		assertTrue(Format.fromExtension("photo.gif").isEmpty());
	}

	@Test
	public void fromExtensionReturnsEmptyForFilenameWithNoExtension()
	{
		assertTrue(Format.fromExtension("photo").isEmpty());
		assertTrue(Format.fromExtension("").isEmpty());
	}

	@Test
	public void fromExtensionMatchesEvenWithCompoundSuffix()
	{
		// `endsWith` semantics: "foo.jpg.bak" ends with ".bak", not ".jpg". Recognises a match only on the real
		// trailing extension — pin to prevent a regression that uses substring contains.
		assertTrue("backup file with .bak extension is not JPEG",
			Format.fromExtension("photo.jpg.bak").isEmpty());
		// But "screenshot_2025.png" DOES end with ".png" — recognised.
		assertEquals(Format.PNG, Format.fromExtension("screenshot_2025.png").orElseThrow());
	}

	@Test
	public void fromExtensionAcceptsMixedCaseAlias()
	{
		// Internal mixed-case checks supplement fromExtensionIsCaseInsensitive: ".JpEg" exercises the
		// toLowerCase(Locale.ROOT) path on every character (alternating case rather than block-cased like "JPG"
		// / "Jpeg"). A regression that did Locale.getDefault().toLowerCase would mangle the dotless 'i' on
		// tr_TR locales and miss this case; Locale.ROOT keeps it deterministic.
		assertEquals(Format.JPEG, Format.fromExtension("photo.JpEg").orElseThrow());
		assertEquals(Format.JPEG, Format.fromExtension("photo.jPg").orElseThrow());
		assertEquals(Format.PNG, Format.fromExtension("photo.PnG").orElseThrow());
	}

	@Test
	public void fromExtensionRejectsTrailingDotAfterExtension()
	{
		// "photo.jpg." — the trailing dot breaks the endsWith(".jpg") match. Some Android file pickers strip
		// trailing dots automatically, but a hand-typed filename in the SAF dialog can arrive with one. Pin
		// that we return empty (no format inference) rather than guessing JPEG, because the user's typed
		// extension as-written is NOT a valid filename a file manager would round-trip.
		assertTrue(Format.fromExtension("photo.jpg.").isEmpty());
		assertTrue(Format.fromExtension("photo.png.").isEmpty());
	}

	@Test
	public void fromExtensionAcceptsBareExtensionAsFullName()
	{
		// ".jpg" / ".png" as the entire filename — degenerate but endsWith(".jpg") still matches, so the
		// function returns JPEG / PNG. This is consistent with the function's name (it parses extensions, not
		// stems) but worth pinning: a future regression that required a non-empty stem (e.g. via
		// lastIndexOf('.') > 0 + substring) would change the answer here from JPEG to empty.
		assertEquals(Format.JPEG, Format.fromExtension(".jpg").orElseThrow());
		assertEquals(Format.JPEG, Format.fromExtension(".jpeg").orElseThrow());
		assertEquals(Format.PNG, Format.fromExtension(".png").orElseThrow());
	}

	@Test
	public void fromExtensionRejectsBareDotSequences()
	{
		// "." / ".." — degenerate path-component names that look like extensions but have no recognisable
		// format. Pin that we return empty rather than match-by-coincidence.
		assertTrue(Format.fromExtension(".").isEmpty());
		assertTrue(Format.fromExtension("..").isEmpty());
		assertTrue(Format.fromExtension("...").isEmpty());
	}

	@Test
	public void fromExtensionRecognisesPathBasedNames()
	{
		// SAF display names sometimes include path-like prefixes. The endsWith check still works.
		assertEquals(Format.JPEG, Format.fromExtension("/storage/emulated/0/DCIM/foo.jpg").orElseThrow());
		assertEquals(Format.PNG, Format.fromExtension("primary:Pictures/screenshot.png").orElseThrow());
	}

	// ── stripExtension ──

	@Test
	public void stripExtensionLeadingDotKeepsName()
	{
		// Leading-dot names (.gitignore) have NO separable extension by convention; lastIndexOf > 0 rejects the
		// index-0 dot. Pin so a refactor to `>= 0` doesn't accidentally produce an empty-stem name.
		assertEquals(".gitignore", Format.stripExtension(".gitignore"));
		assertEquals(".env", Format.stripExtension(".env"));
	}

	@Test
	public void stripExtensionMultiDotKeepsAllButLast()
	{
		// "image.v2.final.heic" — the meaningful name structure ("image.v2.final") survives; only the trailing
		// extension is stripped. Mirrors normaliseExtension's swap behavior.
		assertEquals("image.v2.final", Format.stripExtension("image.v2.final.heic"));
		assertEquals("archive.tar", Format.stripExtension("archive.tar.gz"));
	}

	@Test
	public void stripExtensionNoDotReturnsInputUnchanged()
	{
		// No dot anywhere — return verbatim. Caller's normaliseExtension appends the format extension; for
		// "photo" + JPEG, the result is "photo.jpg".
		assertEquals("photo", Format.stripExtension("photo"));
		assertEquals("noext", Format.stripExtension("noext"));
	}

	@Test
	public void stripExtensionRemovesTrailingExtension()
	{
		// Canonical case: any trailing extension is removed regardless of what it is. Caller
		// (normaliseExtension) appends the format-aligned extension after.
		assertEquals("photo", Format.stripExtension("photo.jpg"));
		assertEquals("photo", Format.stripExtension("photo.heic"));
		assertEquals("photo", Format.stripExtension("photo.PNG"));
	}

	// ── values cardinality ──

	@Test
	public void valuesContainsExactlyJpegAndPng()
	{
		// Exhaustiveness pin: Format is JPEG and PNG — adding a third value (e.g. WEBP) without updating
		// CropExporter's switch dispatch reintroduces the silent-default-branch foot-gun the enum was created
		// to prevent. This test fails on the cardinality OR on the membership, forcing a deliberate update
		// everywhere a Format consumer dispatches.
		Format[] vals = Format.values();
		assertEquals("Adding a new Format requires updating CropExporter's dispatch and this test",
			2, vals.length);
		assertEquals(Format.JPEG, vals[0]);
		assertEquals(Format.PNG, vals[1]);
	}
}
