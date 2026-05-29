package com.cropcenter.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cropcenter.model.Format;

import java.io.File;
import java.util.List;

/**
 * Tests for the pure-function helpers backing the merged save dialog. Extension-normalisation +
 * filename-validation live on FolderPickerDialog (save-flow-specific); breadcrumb-chain +
 * image-extension-recognition live on FolderBrowser (shared between FolderPickerDialog and
 * OpenPickerDialog since the refactor). Both pickers route through the same FolderBrowser helpers,
 * so the breadcrumb / image-filter tests here pin the contract for both dialogs simultaneously.
 *
 * The merged save dialog itself is dominated by AlertDialog / Context / View dependencies that are
 * difficult to exercise from JUnit; these pure-string / pure-File tests are the JVM-side coverage
 * we can offer. A regression in any of them would silently mis-handle the user's pick — wrong
 * extension on save, traversal filename accepted, breadcrumb missing a segment, image file
 * invisible in the grid.
 */
public final class FolderPickerDialogTest
{
	@Test
	public void breadcrumbChainAtRootReturnsSingleSegment()
	{
		// When current == root, the chain contains only the root segment. The breadcrumb renderer
		// uses this to show "Internal storage" alone with no chevron-separated descendants.
		File root = new File("/storage/emulated/0");
		File current = new File("/storage/emulated/0");
		List<File> chain = FolderBrowser.breadcrumbChain(root, current);
		assertEquals(1, chain.size());
		assertEquals(root.getAbsolutePath(), chain.get(0).getAbsolutePath());
	}

	@Test
	public void breadcrumbChainDeepPathBuildsAllSegments()
	{
		// Multi-level descent: chain should contain root, each intermediate, and the leaf. The
		// breadcrumb renderer walks this list adding chevron separators between segments. Using
		// File.getAbsolutePath() rather than hardcoded "/"-paths so the test works on Windows JVM
		// (where the JVM rewrites separators to "\") and Android both.
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
		// Regression: prior absolute-path implementation called currentPath.substring(rootPath.length() + 1)
		// without verifying currentPath was actually nested under rootPath — when isInsideRoot accepted a
		// symlinked startDir (e.g. /sdcard/Pictures pointing into /storage/emulated/0/Pictures) the
		// canonicalized current didn't share rootPath's absolute prefix, and the substring call threw
		// StringIndexOutOfBoundsException on the UI thread. The canonical-path rewrite returns [root]
		// rather than throwing when current is not actually a descendant. Use a path that exists on neither
		// JVM nor Android so getCanonicalPath returns the input unchanged, isolating the boundary check
		// from filesystem-resolution behaviour.
		File root = new File("/storage/emulated/0");
		File outsider = new File("/tmp/somewhere-else");
		List<File> chain = FolderBrowser.breadcrumbChain(root, outsider);
		assertEquals(1, chain.size());
		assertEquals(root.getAbsolutePath(), chain.get(0).getAbsolutePath());
	}

	@Test
	public void breadcrumbChainOneLevelDownReturnsTwoSegments()
	{
		// Single descent: [root, child]. Most common case for the breadcrumb after the user enters
		// a top-level folder.
		File root = new File("/storage/emulated/0");
		File dcim = new File(root, "DCIM");
		List<File> chain = FolderBrowser.breadcrumbChain(root, dcim);
		assertEquals(2, chain.size());
		assertEquals(root.getAbsolutePath(), chain.get(0).getAbsolutePath());
		assertEquals(dcim.getAbsolutePath(), chain.get(1).getAbsolutePath());
	}

	@Test
	public void isImageFileAcceptsAllVisibleExtensions()
	{
		// Production filter for the file-list pass in refresh(): JPEG, PNG, WebP, HEIC, HEIF —
		// the visible set. This is intentionally broader than the loadable set (isSupportedSourceFormat
		// — JPEG/PNG only); WebP / HEIC / HEIF render as dimmed non-tappable cells so the user sees
		// they exist but can't pick them. Locking the accepted set so a regression doesn't silently
		// drop one of the formats from the picker.
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
		// Filenames from Windows / macOS USB transfers often have uppercase extensions. Lowercase
		// pass in production via toLowerCase(Locale.ROOT) inside isImageFile — pin the contract
		// so a regression doesn't make those files invisible.
		assertTrue(FolderBrowser.isImageFile(new File("PHOTO.JPG")));
		assertTrue(FolderBrowser.isImageFile(new File("Photo.PnG")));
		assertTrue(FolderBrowser.isImageFile(new File("vacation.HEIC")));
	}

	@Test
	public void isImageFileRejectsNonImageExtensions()
	{
		// GIF / BMP / RAW / TIFF intentionally not visible (no thumbnail decode path); the picker
		// hides them. Also non-image files (.txt, .pdf) and bare names without an extension.
		assertFalse(FolderBrowser.isImageFile(new File("photo.gif")));
		assertFalse(FolderBrowser.isImageFile(new File("photo.bmp")));
		assertFalse(FolderBrowser.isImageFile(new File("photo.raw")));
		assertFalse(FolderBrowser.isImageFile(new File("photo.tiff")));
		assertFalse(FolderBrowser.isImageFile(new File("readme.txt")));
		assertFalse(FolderBrowser.isImageFile(new File("noext")));
	}

	@Test
	public void isSupportedSourceFormatAcceptsJpegAndPngOnly()
	{
		// The narrower predicate that gates which visible cells are TAPPABLE (load-pipeline
		// accepts only JPEG/PNG by magic-byte check; HEIC / WebP / HEIF show in the picker but
		// render at 0.4 alpha with no click handler). Visibility (isImageFile, broader) and
		// loadability (isSupportedSourceFormat, narrower) must stay separate — if a future
		// refactor collapses them, the user either loses the visual cue for HEIC / WebP / HEIF
		// or starts tapping them and hitting "Unsupported image format" toasts after-the-fact.
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
		// Typical filenames the user would actually type: plain stem, stem with extension, stem with
		// spaces (allowed — POSIX and Android filesystems accept them), Unicode characters, and the
		// auto-generated "crop_<timestamp>" pattern from the loader's pre-fill.
		assertTrue(FolderPickerDialog.isValidFilename("photo"));
		assertTrue(FolderPickerDialog.isValidFilename("photo.jpg"));
		assertTrue(FolderPickerDialog.isValidFilename("My Photo.jpg"));
		assertTrue(FolderPickerDialog.isValidFilename("crop_20260523_143012.png"));
		assertTrue(FolderPickerDialog.isValidFilename("café.jpg"));
	}

	@Test
	public void isValidFilenameAcceptsUtf8AtByteLimitBoundary()
	{
		// 250 ASCII chars = 250 UTF-8 bytes — exactly at the limit, should be accepted. Pin the
		// inclusive `<= MAX_FILENAME_UTF8_BYTES` semantics so a future off-by-one regression that
		// flipped to `<` would surface here.
		StringBuilder ascii = new StringBuilder();
		for (int i = 0; i < 250; i++)
		{
			ascii.append('a');
		}
		assertTrue("250 ASCII chars = 250 UTF-8 bytes is exactly at the limit",
			FolderPickerDialog.isValidFilename(ascii.toString()));
	}

	@Test
	public void isValidFilenameRejectsBackslash()
	{
		// Windows path separator. Same rule as forward-slash — even if the underlying filesystem
		// accepts backslash as a literal character, treating it as a path component is misleading
		// and the Replace flow downstream might misinterpret it.
		assertFalse(FolderPickerDialog.isValidFilename("path\\to\\file.jpg"));
		assertFalse(FolderPickerDialog.isValidFilename("file\\.jpg"));
	}

	@Test
	public void isValidFilenameRejectsEmptyAndDots()
	{
		// Empty string passes isEmpty(); ".", ".." are traversal segments that the kernel resolves
		// to the current / parent directory, which would silently mis-route the save target.
		assertFalse(FolderPickerDialog.isValidFilename(""));
		assertFalse(FolderPickerDialog.isValidFilename("."));
		assertFalse(FolderPickerDialog.isValidFilename(".."));
	}

	@Test
	public void isValidFilenameRejectsForwardSlash()
	{
		// Any "/" injects a path component — a typed "evil/../etc" would let the user walk OUT of
		// the picker's chosen folder. Reject categorically, not just leading "/".
		assertFalse(FolderPickerDialog.isValidFilename("subdir/file.jpg"));
		assertFalse(FolderPickerDialog.isValidFilename("/abs/path.jpg"));
		assertFalse(FolderPickerDialog.isValidFilename("a/b"));
	}

	@Test
	public void isValidFilenameRejectsUtf8OneByteOverLimit()
	{
		// 251 ASCII chars = 251 UTF-8 bytes — one byte over. Pin the boundary alongside the
		// at-limit accept above so the off-by-one is double-bracketed.
		StringBuilder ascii = new StringBuilder();
		for (int i = 0; i < 251; i++)
		{
			ascii.append('a');
		}
		assertFalse("251 ASCII chars = 251 UTF-8 bytes is one byte over the 250-byte cap",
			FolderPickerDialog.isValidFilename(ascii.toString()));
	}

	@Test
	public void isValidFilenameRejectsUtf8OverlongMultibyteName()
	{
		// 4-byte UTF-8 emoji codepoints (each 2 UTF-16 chars) — 100 emoji = 200 UTF-16 chars
		// (within the LengthFilter limit) but 400 UTF-8 bytes (over the 250-byte filesystem cap).
		// Pre-fix this passed validation and would fail at the FS write with ENAMETOOLONG; now it
		// rejects cleanly at the dialog. "🎨" (palette emoji) encodes as 4 UTF-8 bytes and 2
		// UTF-16 chars — exactly the LengthFilter-vs-byte-length-diverging case the cap addresses.
		StringBuilder emoji = new StringBuilder();
		for (int i = 0; i < 100; i++)
		{
			emoji.append("🎨");
		}
		assertFalse("100 4-byte emojis = 400 UTF-8 bytes — over 250-byte FS cap",
			FolderPickerDialog.isValidFilename(emoji.toString()));
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
