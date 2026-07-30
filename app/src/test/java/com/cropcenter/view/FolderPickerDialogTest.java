package com.cropcenter.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.cropcenter.model.Format;

import org.junit.Test;

import java.io.File;
import java.util.List;

/**
 * Tests for the pure-function helpers backing the merged save dialog. Extension-normalisation + filename-validation +
 * the filename field's byte-cap filter seam (editExceedsByteCap — the InputFilter's pure core, shared with the
 * tap-to-populate pre-check) live on FolderPickerDialog (save-flow-specific); breadcrumb-chain +
 * image-extension-recognition live on FolderBrowser
 * (shared between FolderPickerDialog and OpenPickerDialog since the refactor). Both pickers route through the same
 * FolderBrowser helpers, so the breadcrumb / image-filter tests here pin the contract for both dialogs simultaneously.
 *
 * The merged save dialog itself is dominated by AlertDialog / Context / View dependencies that are difficult to
 * exercise from JUnit; these pure-string / pure-File tests are the JVM-side coverage we can offer. A regression in any
 * of them would silently mis-handle the user's pick — wrong extension on save, traversal filename accepted, breadcrumb
 * missing a segment, image file invisible in the grid.
 */
public final class FolderPickerDialogTest
{
	@Test
	public void breadcrumbChainAtRootReturnsSingleSegment()
	{
		// When current == root, the chain contains only the root segment. The breadcrumb renderer uses this to
		// show "Internal storage" alone with no chevron-separated descendants.
		File root = new File("/storage/emulated/0");
		File current = new File("/storage/emulated/0");
		List<File> chain = FolderBrowser.breadcrumbChain(root, current);
		assertEquals(1, chain.size());
		assertEquals(root.getAbsolutePath(), chain.get(0).getAbsolutePath());
	}

	@Test
	public void breadcrumbChainDeepPathBuildsAllSegments()
	{
		// Multi-level descent: chain should contain root, each intermediate, and the leaf. The breadcrumb
		// renderer walks this list adding chevron separators between segments. Using File.getAbsolutePath()
		// rather than hardcoded "/"-paths so the test works on Windows JVM (where the JVM rewrites separators
		// to "\") and Android both.
		File root = new File("/storage/emulated/0");
		File dcim = new File(root, "DCIM");
		File camera = new File(dcim, "Camera");
		File year = new File(camera, "2026");
		File vacation = new File(year, "Vacation");
		List<File> chain = FolderBrowser.breadcrumbChain(root, vacation);
		assertEquals(5, chain.size());
		assertEquals(root.getAbsolutePath(), chain.get(0).getAbsolutePath());
		assertEquals(dcim.getAbsolutePath(), chain.get(1).getAbsolutePath());
		assertEquals(camera.getAbsolutePath(), chain.get(2).getAbsolutePath());
		assertEquals(year.getAbsolutePath(), chain.get(3).getAbsolutePath());
		assertEquals(vacation.getAbsolutePath(), chain.get(4).getAbsolutePath());
	}

	@Test
	public void breadcrumbChainNonDescendantDegradesToRootOnly()
	{
		// Regression guard: a prefix computation like currentPath.substring(rootPath.length() + 1) without
		// verifying currentPath is actually nested under rootPath throws StringIndexOutOfBoundsException on
		// the UI thread whenever isInsideRoot accepts a symlinked startDir (e.g. /sdcard/Pictures pointing
		// into /storage/emulated/0/Pictures) whose canonicalized current doesn't share rootPath's absolute
		// prefix. The canonical-path chain must return [root] rather than throw when current is not actually
		// a descendant. Use a path that exists on neither JVM nor Android so getCanonicalPath returns the
		// input unchanged, isolating the boundary check from filesystem-resolution behaviour.
		File root = new File("/storage/emulated/0");
		File outsider = new File("/tmp/somewhere-else");
		List<File> chain = FolderBrowser.breadcrumbChain(root, outsider);
		assertEquals(1, chain.size());
		assertEquals(root.getAbsolutePath(), chain.get(0).getAbsolutePath());
	}

	@Test
	public void breadcrumbChainOneLevelDownReturnsTwoSegments()
	{
		// Single descent: [root, child]. Most common case for the breadcrumb after the user enters a top-level
		// folder.
		File root = new File("/storage/emulated/0");
		File dcim = new File(root, "DCIM");
		List<File> chain = FolderBrowser.breadcrumbChain(root, dcim);
		assertEquals(2, chain.size());
		assertEquals(root.getAbsolutePath(), chain.get(0).getAbsolutePath());
		assertEquals(dcim.getAbsolutePath(), chain.get(1).getAbsolutePath());
	}

	@Test
	public void editExceedsByteCapAccountsForReplacedDestRange()
	{
		// Mid-edit shapes: the seam measures dest-minus-replaced-range plus the insertion, so an edit that
		// REPLACES existing text can fit where a pure insertion would not. dest sits exactly at the 210-byte
		// cap.
		String dest = "a".repeat(206) + ".jpg";
		assertFalse("replacing 10 chars with 2 shrinks the text — accepted",
			FolderPickerDialog.editExceedsByteCap("xy", 0, 2, dest, 0, 10));
		assertTrue("inserting 1 char into an at-cap name must reject",
			FolderPickerDialog.editExceedsByteCap("x", 0, 1, dest, 0, 0));
		assertFalse("deleting from an at-cap name is always allowed",
			FolderPickerDialog.editExceedsByteCap("", 0, 0, dest, 0, 10));
	}

	@Test
	public void editExceedsByteCapBoundaryMatchesValidator()
	{
		// The filter and isValidFilename share MAX_FILENAME_UTF8_BYTES: 210 ASCII bytes populate whole
		// (setText shape — empty dest), 211 reject. Pinned alongside the validator's 210/211 pins so the
		// field can never hold a length Save here's validation would bounce, nor bounce one it would accept.
		String atCap = "a".repeat(210);
		String overCap = "a".repeat(211);
		assertFalse("210 UTF-8 bytes is exactly at the shared cap",
			FolderPickerDialog.editExceedsByteCap(atCap, 0, atCap.length(), "", 0, 0));
		assertTrue("211 UTF-8 bytes is one byte over the shared cap",
			FolderPickerDialog.editExceedsByteCap(overCap, 0, overCap.length(), "", 0, 0));
	}

	@Test
	public void editExceedsByteCapCountsUtf8BytesNotUtf16Chars()
	{
		// The divergence the byte-aware filter exists for: a UTF-16 char-count cap reads 100 palette emoji
		// as 200 chars and lets 400 UTF-8 bytes through to an ENAMETOOLONG write failure, while flagging a
		// legal 210-byte ASCII name (see the boundary pin above) as over-length. The seam measures the same
		// getBytes(UTF_8) length isValidFilename does: 100 emoji (400 bytes) reject, 52 emoji (208 bytes) fit.
		String fourHundredBytes = "🎨".repeat(100);
		String twoHundredEightBytes = "🎨".repeat(52);
		assertTrue("400 UTF-8 bytes must reject regardless of the 200-char UTF-16 count",
			FolderPickerDialog.editExceedsByteCap(fourHundredBytes, 0, fourHundredBytes.length(),
				"", 0, 0));
		assertFalse("208 UTF-8 bytes of emoji fit under the byte cap",
			FolderPickerDialog.editExceedsByteCap(twoHundredEightBytes, 0,
				twoHundredEightBytes.length(), "", 0, 0));
	}

	@Test
	public void editExceedsByteCapRejectsOverLimitTapWholesale()
	{
		// The no-silent-mutation contract for tap-to-populate: a legal 250-byte ext4 filename tapped in the
		// browser must NOT be truncated into a different valid name — onFileTappedForSave asks this exact
		// setText-shaped question (empty dest), and a true return leaves the field unchanged and surfaces
		// the "Name is too long to reuse" toast. A fitting tapped name populates WHOLE, so Save here
		// targets exactly the file the user tapped.
		String overLimitTap = "a".repeat(246) + ".jpg";
		String fittingTap = "a".repeat(206) + ".jpg";
		assertTrue("an over-cap tapped name must be rejected whole — never truncated",
			FolderPickerDialog.editExceedsByteCap(overLimitTap, 0, overLimitTap.length(), "", 0, 0));
		assertFalse("a 210-byte tapped name populates the field whole",
			FolderPickerDialog.editExceedsByteCap(fittingTap, 0, fittingTap.length(), "", 0, 0));
	}

	@Test
	public void filterByteCapEditAcceptsFittingEditAsNull()
	{
		// Per the InputFilter contract, null means "accept source verbatim" — the framework applies the
		// edit unmodified. A fitting insertion and a fitting replacement must both return null so ordinary
		// typing is untouched by the byte-cap filter.
		String dest = "vacation-photo.jpg";
		assertNull("a fitting insertion must return null (accept verbatim)",
			FolderPickerDialog.filterByteCapEdit("x", 0, 1, dest, 0, 0));
		assertNull("a fitting replacement must return null (accept verbatim)",
			FolderPickerDialog.filterByteCapEdit("holiday", 0, 7, dest, 0, 8));
	}

	@Test
	public void filterByteCapEditOverCapReplacementIsTrueNoOp()
	{
		// The silent-selection-deletion regression: the filter's returned CharSequence REPLACES
		// dest[dstart, dend) whether the edit is accepted or rejected, so rejecting with "" deletes the
		// selected range — selecting ".jpg" in an at-cap name and pasting an over-cap emoji string would
		// leave "aaa…a", a different valid name that Save here happily writes. Rejection must return the
		// untouched dest range (the keep-original idiom) so applying the framework contract reproduces the
		// original text byte-for-byte.
		String dest = "a".repeat(206) + ".jpg"; // exactly at the 210-byte cap
		String paste = "🎨".repeat(60); // 240 UTF-8 bytes — over cap in place of the 4-byte ".jpg"
		CharSequence kept = FolderPickerDialog.filterByteCapEdit(paste, 0, paste.length(), dest, 206, 210);
		assertEquals("rejection must return the untouched dest range, not \"\"", ".jpg", kept.toString());
		// Apply the InputFilter contract — dest[0, dstart) + returned + dest[dend, length) — and require
		// a true no-op: the field's text is unchanged by the rejected edit.
		String applied = dest.substring(0, 206) + kept + dest.substring(210);
		assertEquals("a rejected replacement edit must leave the field text unchanged", dest, applied);
	}

	@Test
	public void filterByteCapEditOverCapSelectAllPasteKeepsWholeText()
	{
		// Select-all + over-cap paste: the replaced dest range is the entire field, so the keep-original
		// result IS the whole text — the field must survive unchanged instead of being emptied ("" would
		// 'land' the edit as a wholesale deletion).
		String dest = "vacation-photo.jpg";
		String paste = "🎨".repeat(60); // 240 UTF-8 bytes
		CharSequence kept = FolderPickerDialog.filterByteCapEdit(paste, 0, paste.length(), dest, 0,
			dest.length());
		assertEquals("select-all + over-cap paste must keep the whole existing text", dest, kept.toString());
	}

	@Test
	public void filterByteCapEditOverCapSetTextShapeClearsEmptyDest()
	{
		// Programmatic setText runs the filter against an EMPTY dest, where the keep-original result
		// collapses to dest[0, 0) = "" — the field clears rather than holding an over-cap value. Pinned
		// because both setText callers (onFileTappedForSave, syncFilenameExtension) rely on pre-checking
		// editExceedsByteCap precisely to avoid this clearing shape.
		String overCap = "a".repeat(211);
		CharSequence kept = FolderPickerDialog.filterByteCapEdit(overCap, 0, overCap.length(), "", 0, 0);
		assertEquals("over-cap setText shape must reject to the empty dest range", "", kept.toString());
	}

	@Test
	public void isHiddenNameAllowsOrdinaryNames()
	{
		// Dot-less names and names whose dots are internal (stem.ext, multi-dot stems) are the entire visible
		// population — the hidden rule keys on the LEADING dot only.
		assertFalse(FolderBrowser.isHiddenName("IMG_0042.jpg"));
		assertFalse(FolderBrowser.isHiddenName("photo.v2.final.png"));
		assertFalse(FolderBrowser.isHiddenName("noext"));
		assertFalse(FolderBrowser.isHiddenName("Camera"));
	}

	@Test
	public void isHiddenNameHidesDotPrefixedTrashPendingAndSaveTemps()
	{
		// MediaStore trash and pending shapes: the OS materialises these as real dot-prefixed files that raw
		// listFiles sees under MANAGE_EXTERNAL_STORAGE. They must never render as tappable photos — trash is
		// purged by the OS within ~30 days.
		assertTrue(FolderBrowser.isHiddenName(".trashed-1748601600-IMG_0042.jpg"));
		assertTrue(FolderBrowser.isHiddenName(".pending-1748601600-IMG_0042.jpg"));
		// Crash-safe save temp: SaveTempFiles.tempName builds ".cropcenter-tmp-<nanos>-<name>"
		// (SaveTempFilesTest pins the leading-dot marker on every built name); this pins that the picker's
		// hidden rule covers the shape, so an in-flight or orphaned temp never shows up as an openable photo.
		assertTrue(FolderBrowser.isHiddenName(".cropcenter-tmp-123456789-crop.jpg"));
		// Bare ".jpg" — a dotfile whose entire name is the extension — is hidden too.
		assertTrue(FolderBrowser.isHiddenName(".jpg"));
		// Dot-prefixed folder names route through the same rule.
		assertTrue(FolderBrowser.isHiddenName(".thumbnails"));
		// The extension filter alone would ADMIT MediaStore trash (its suffix is a real image extension) —
		// pinned so a future refactor can't merge the two predicates and silently drop the hidden gate.
		assertTrue(FolderBrowser.isImageFile(new File(".trashed-1748601600-IMG_0042.jpg")));
	}

	@Test
	public void isImageFileAcceptsAllVisibleExtensions()
	{
		// Production filter for the file-list pass in refresh(): JPEG, PNG, WebP, HEIC, HEIF — the visible set.
		// This is intentionally broader than the loadable set (isSupportedSourceFormat — JPEG/PNG only); WebP /
		// HEIC / HEIF render as dimmed non-tappable cells so the user sees they exist but can't pick them.
		// Locking the accepted set so a regression doesn't silently drop one of the formats from the picker.
		assertTrue(FolderBrowser.isImageFile(new File("photo.jpg")));
		assertTrue(FolderBrowser.isImageFile(new File("photo.jpeg")));
		assertTrue(FolderBrowser.isImageFile(new File("photo.png")));
		assertTrue(FolderBrowser.isImageFile(new File("photo.webp")));
		assertTrue(FolderBrowser.isImageFile(new File("photo.heic")));
		assertTrue(FolderBrowser.isImageFile(new File("photo.heif")));
	}

	@Test
	public void isImageFileIsCaseInsensitive()
	{
		// Filenames from Windows / macOS USB transfers often have uppercase extensions. Lowercase pass in
		// production via toLowerCase(Locale.ROOT) inside isImageFile — pin the contract so a regression doesn't
		// make those files invisible.
		assertTrue(FolderBrowser.isImageFile(new File("PHOTO.JPG")));
		assertTrue(FolderBrowser.isImageFile(new File("Photo.PnG")));
		assertTrue(FolderBrowser.isImageFile(new File("vacation.HEIC")));
	}

	@Test
	public void isImageFileRejectsNonImageExtensions()
	{
		// GIF / BMP / RAW / TIFF intentionally not visible (no thumbnail decode path); the picker hides them.
		// Also non-image files (.txt, .pdf) and bare names without an extension.
		assertFalse(FolderBrowser.isImageFile(new File("photo.gif")));
		assertFalse(FolderBrowser.isImageFile(new File("photo.bmp")));
		assertFalse(FolderBrowser.isImageFile(new File("photo.raw")));
		assertFalse(FolderBrowser.isImageFile(new File("photo.tiff")));
		assertFalse(FolderBrowser.isImageFile(new File("readme.txt")));
		assertFalse(FolderBrowser.isImageFile(new File("noext")));
	}

	@Test
	public void isJpegSourceFormatAcceptsJpegOnlyForGraftGate()
	{
		// The graft-mode (jpegOnly) tappability gate — strictly narrower than isSupportedSourceFormat. The
		// obvious simplification (collapse the two: both delegate to Format.fromExtension, differing only by
		// `== Format.JPEG` vs `isPresent()`) would make PNGs tappable in the Apply External Edit picker and
		// feed a
		// PNG into the JPEG-byte-splicing graft flow. Pin the JPEG-only accept set and the PNG reject.
		assertTrue(FolderBrowser.isJpegSourceFormat(new File("photo.jpg")));
		assertTrue(FolderBrowser.isJpegSourceFormat(new File("photo.jpeg")));
		assertTrue(FolderBrowser.isJpegSourceFormat(new File("PHOTO.JPG")));

		assertFalse("PNG is loadable in the open flow but must NOT be graft-tappable",
			FolderBrowser.isJpegSourceFormat(new File("photo.png")));
		assertFalse(FolderBrowser.isJpegSourceFormat(new File("photo.webp")));
		assertFalse(FolderBrowser.isJpegSourceFormat(new File("photo.heic")));
		assertFalse(FolderBrowser.isJpegSourceFormat(new File("noext")));
	}

	@Test
	public void isSupportedSourceFormatAcceptsJpegAndPngOnly()
	{
		// The narrower predicate that gates which visible cells are TAPPABLE (load-pipeline accepts only
		// JPEG/PNG by magic-byte check; HEIC / WebP / HEIF show in the picker but render at 0.4 alpha with no
		// click handler). Visibility (isImageFile, broader) and loadability (isSupportedSourceFormat, narrower)
		// must stay separate — if a future refactor collapses them, the user either loses the visual cue for
		// HEIC / WebP / HEIF or starts tapping them and hitting "Unsupported image format" toasts
		// after-the-fact.
		assertTrue(FolderBrowser.isSupportedSourceFormat(new File("photo.jpg")));
		assertTrue(FolderBrowser.isSupportedSourceFormat(new File("photo.jpeg")));
		assertTrue(FolderBrowser.isSupportedSourceFormat(new File("photo.png")));
		assertTrue(FolderBrowser.isSupportedSourceFormat(new File("PHOTO.JPG")));
		assertTrue(FolderBrowser.isSupportedSourceFormat(new File("Photo.PnG")));

		assertFalse(FolderBrowser.isSupportedSourceFormat(new File("photo.webp")));
		assertFalse(FolderBrowser.isSupportedSourceFormat(new File("photo.heic")));
		assertFalse(FolderBrowser.isSupportedSourceFormat(new File("photo.heif")));
		assertFalse(FolderBrowser.isSupportedSourceFormat(new File("vacation.HEIC")));
		assertFalse(FolderBrowser.isSupportedSourceFormat(new File("photo.gif")));
		assertFalse(FolderBrowser.isSupportedSourceFormat(new File("noext")));
	}

	@Test
	public void isValidFilenameAcceptsNormalNames()
	{
		// Typical filenames the user would actually type: plain stem, stem with extension, stem with spaces
		// (allowed — POSIX and Android filesystems accept them), Unicode characters, and the auto-generated
		// "crop_<timestamp>" pattern from the loader's pre-fill.
		assertTrue(FolderPickerDialog.isValidFilename("photo"));
		assertTrue(FolderPickerDialog.isValidFilename("photo.jpg"));
		assertTrue(FolderPickerDialog.isValidFilename("My Photo.jpg"));
		assertTrue(FolderPickerDialog.isValidFilename("crop_20260523_143012.png"));
		assertTrue(FolderPickerDialog.isValidFilename("café.jpg"));
	}

	@Test
	public void isValidFilenameAcceptsUtf8AtByteLimitBoundary()
	{
		// 210 ASCII chars = 210 UTF-8 bytes — exactly at the limit, should be accepted. Pin the inclusive `<=
		// MAX_FILENAME_UTF8_BYTES` semantics so a future off-by-one regression that flipped to `<` would
		// surface here. The cap is 210 (not the filesystem's 255) because the crash-safe pipeline prepends
		// SaveTempFiles.tempName's up-to-36-byte hidden prefix to every validated name — a longer validated
		// name hard-fails placeholder / write-temp creation with ENAMETOOLONG after validation passed.
		StringBuilder ascii = new StringBuilder();
		for (int i = 0; i < 210; i++)
		{
			ascii.append('a');
		}
		assertTrue("210 ASCII chars = 210 UTF-8 bytes is exactly at the limit",
			FolderPickerDialog.isValidFilename(ascii.toString()));
	}

	@Test
	public void isValidFilenameRejectsBackslash()
	{
		// Windows path separator. Same rule as forward-slash — even if the underlying filesystem accepts
		// backslash as a literal character, treating it as a path component is misleading and the Replace flow
		// downstream might misinterpret it.
		assertFalse(FolderPickerDialog.isValidFilename("path\\to\\file.jpg"));
		assertFalse(FolderPickerDialog.isValidFilename("file\\.jpg"));
	}

	@Test
	public void isValidFilenameRejectsEmptyAndDots()
	{
		// Empty string passes isEmpty(); ".", ".." are traversal segments that the kernel resolves to the
		// current / parent directory, which would silently mis-route the save target.
		assertFalse(FolderPickerDialog.isValidFilename(""));
		assertFalse(FolderPickerDialog.isValidFilename("."));
		assertFalse(FolderPickerDialog.isValidFilename(".."));
	}

	@Test
	public void isValidFilenameRejectsForwardSlash()
	{
		// Any "/" injects a path component — a typed "evil/../etc" would let the user walk OUT of the picker's
		// chosen folder. Reject categorically, not just leading "/".
		assertFalse(FolderPickerDialog.isValidFilename("subdir/file.jpg"));
		assertFalse(FolderPickerDialog.isValidFilename("/abs/path.jpg"));
		assertFalse(FolderPickerDialog.isValidFilename("a/b"));
	}

	@Test
	public void isValidFilenameRejectsReservedTempNamespace()
	{
		// The crash-safe temp namespace (SaveTempFiles marker shapes) is reserved from user filenames. A user
		// file matching the sweep's deletable shapes would be silently deleted an hour after creation, and
		// anything one nonce short of matching still collides with the pipeline's own hidden siblings — so
		// the whole marker substring rejects. Both save entry points (the merged dialog's Save here and the
		// in-app Rename OK, SaveController.onRenameOkClicked) route through this predicate.
		// Exact current sweep shape — the sweep would delete this user file once stale.
		assertFalse(FolderPickerDialog.isValidFilename(".cropcenter-tmp-123456789-crop.jpg"));
		// Exact legacy sweep shape — same deletion exposure via the legacy matcher arm.
		assertFalse(FolderPickerDialog.isValidFilename(".crop.jpg.cropcenter-tmp-123456789"));
		// Raw current-marker prefix, one nonce short of the current shape.
		assertFalse(FolderPickerDialog.isValidFilename(".cropcenter-tmp-"));
		assertFalse(FolderPickerDialog.isValidFilename(".cropcenter-tmp-crop.jpg"));
		assertFalse(FolderPickerDialog.isValidFilename(".cropcenter-tmp-123456-"));
		// Legacy marker-substring shape, one nonce short of the legacy tail.
		assertFalse(FolderPickerDialog.isValidFilename(".notes.cropcenter-tmp-reference.jpg"));
		// Marker mid-name without the leading dot still sits in the reserved substring namespace.
		assertFalse(FolderPickerDialog.isValidFilename("notes.cropcenter-tmp-1.jpg"));
	}

	@Test
	public void isValidFilenameRejectsUtf8OneByteOverLimit()
	{
		// 211 ASCII chars = 211 UTF-8 bytes — one byte over. Pin the boundary alongside the at-limit accept
		// above so the off-by-one is double-bracketed. The 210-byte cap reserves headroom for the ~36-byte
		// SaveTempFiles temp prefix: a 211-250 byte name would validate here yet deterministically hard-fail
		// the save when the prefix pushes the placeholder and write-temp names past the 255-byte filesystem
		// component limit (ENAMETOOLONG), surfacing only a generic error.
		StringBuilder ascii = new StringBuilder();
		for (int i = 0; i < 211; i++)
		{
			ascii.append('a');
		}
		assertFalse("211 ASCII chars = 211 UTF-8 bytes is one byte over the 210-byte cap",
			FolderPickerDialog.isValidFilename(ascii.toString()));
	}

	@Test
	public void isValidFilenameRejectsUtf8OverlongMultibyteName()
	{
		// 4-byte UTF-8 emoji codepoints (each 2 UTF-16 chars) — 100 emoji = 200 UTF-16 chars (within the
		// LengthFilter limit) but 400 UTF-8 bytes (over the 210-byte cap). Validation must reject
		// this cleanly at the dialog — passing it through would fail at the FS write with ENAMETOOLONG. "🎨"
		// (palette emoji) encodes as 4 UTF-8 bytes and 2 UTF-16 chars — exactly the
		// LengthFilter-vs-byte-length-diverging case the cap addresses.
		String emoji = "🎨".repeat(100);
		assertFalse("100 4-byte emojis = 400 UTF-8 bytes — over the 210-byte cap",
			FolderPickerDialog.isValidFilename(emoji));
	}

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
		// "lastIndexOf('.') > 0" — a name whose only dot is at index 0 (.gitignore-style) is treated as a bare
		// stem; the format extension is APPENDED, not swapped onto the leading-dot segment. Production callers
		// don't hit this path (the picker pre-fills "<stem>.<ext>" so the user would have to type a leading-dot
		// name explicitly), but the helper's contract should be stable under it rather than crash or produce a
		// misleading "..jpg".
		assertEquals(".gitignore.jpg", FolderPickerDialog.normaliseExtension(".gitignore", Format.JPEG));
	}

	@Test
	public void normaliseExtensionPreservesStemWithDots()
	{
		// Multi-dot stem like "image.v2.final.heic" — only the LAST dot's extension is swapped, so the
		// meaningful filename structure ("image.v2.final") survives intact.
		assertEquals("image.v2.final.png",
			FolderPickerDialog.normaliseExtension("image.v2.final.heic", Format.PNG));
	}

	@Test
	public void normaliseExtensionSwapsMismatchedExtension()
	{
		// Core case the Codex finding was about: user typed a mismatched extension (heic, jpg under a PNG
		// format toggle, etc.). The save path must align the on-disk extension with the encoded format so
		// gallery type inference doesn't lie.
		assertEquals("photo.png", FolderPickerDialog.normaliseExtension("photo.heic", Format.PNG));
		assertEquals("photo.jpg", FolderPickerDialog.normaliseExtension("photo.png", Format.JPEG));
	}

	@Test
	public void normaliseExtensionSwapsTrailingExtension()
	{
		// Canonical case: jpg ↔ png swap with a single trailing extension. Pin both directions so the format
		// chip's effect on the filename remains symmetric.
		assertEquals("photo.png", FolderPickerDialog.normaliseExtension("photo.jpg", Format.PNG));
		assertEquals("photo.jpg", FolderPickerDialog.normaliseExtension("photo.png", Format.JPEG));
	}

	@Test
	public void tapCaretIndexClampsToShorterFieldText()
	{
		// Defense-in-depth clamp: the tap path populates the field only with names the byte-cap filter
		// accepts whole (over-cap taps leave the field unchanged and toast — see the wholesale-rejection
		// pin), but the caret math must stay safe against ANY field text shorter than the tapped name. The
		// name's last dot sits at index 246; against a field holding 200 chars, an unclamped
		// setSelection(246) would throw IndexOutOfBoundsException — the clamp caps the caret at the actual
		// text length.
		StringBuilder stem = new StringBuilder();
		for (int i = 0; i < 246; i++)
		{
			stem.append('a');
		}
		String name = stem + ".jpg";
		assertEquals(200, FolderPickerDialog.tapCaretIndex(name, 200));
	}

	@Test
	public void tapCaretIndexParksAtStemEnd()
	{
		// Normal tap-to-populate case: caret lands on the LAST dot so the user can type a stem suffix
		// ("-edited") without skipping past the extension.
		assertEquals(8, FolderPickerDialog.tapCaretIndex("IMG_0042.jpg", 12));
		assertEquals(14, FolderPickerDialog.tapCaretIndex("image.v2.final.png", 18));
	}

	@Test
	public void tapCaretIndexTreatsNoExtensionAndLeadingDotAsEndOfText()
	{
		// No dot → caret at end of text. Leading-dot-only names (".gitignore") also land at end of text so
		// typing can't corrupt the dotfile prefix.
		assertEquals(5, FolderPickerDialog.tapCaretIndex("noext", 5));
		assertEquals(10, FolderPickerDialog.tapCaretIndex(".gitignore", 10));
	}
}
