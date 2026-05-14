package com.cropcenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for GraftController's package-private pure-static helpers — `isOversizedEdit` and `suggestedFilename`.
 * Both are small static gates with no Android dependencies, but each is load-bearing in its own way:
 *
 *   - `isOversizedEdit` decides whether to surface a confirm-before-graft dialog when the AI mask covers more
 *     than 10% of the image. A false-negative (skip the confirm at the boundary) lets a user accidentally
 *     graft the wrong file's pixels under the loaded image's metadata; a false-positive (always confirm)
 *     adds friction to every legitimate Generative Remove pass. The strict-greater boundary at exactly 10%
 *     is the spec contract; flipping to greater-or-equal would silently shift the threshold.
 *
 *   - `suggestedFilename` is the only place the "-graft.jpg" naming convention is generated. A regression
 *     that lost the dot-split logic would either bury the suffix inside the extension ("foo.jpg-graft.jpg")
 *     or strip extensions entirely ("foo-graft" with no extension would mis-route through MIME inference).
 */
public final class GraftControllerStaticTest
{
	@Test
	public void isOversizedEditAtBoundaryUsesStrictGreater()
	{
		// LARGE_EDIT_FRACTION is 0.10f (10%). count = 10 of 100 → exactly 10% → must NOT trip (strict >, not
		// >=). count = 11 of 100 → 11% → must trip. Pin the boundary so a regression that loosens to >=
		// silently shifts the threshold and changes which edits prompt the confirm dialog.
		assertFalse("exactly 10% must not trip the strict-greater gate",
			GraftController.isOversizedEdit(10, 100));
		assertTrue("11 / 100 = 11% must trip the > 10% gate",
			GraftController.isOversizedEdit(11, 100));
	}

	@Test
	public void isOversizedEditRejectsNegativeCount()
	{
		// Defensive guard: AiRegionDetector returns -1 / null-equivalent when the mask wasn't computed.
		// isOversizedEdit must treat any negative count as "no mask" (false), not crash and not flag.
		assertFalse(GraftController.isOversizedEdit(-1, 100));
		assertFalse(GraftController.isOversizedEdit(Integer.MIN_VALUE, 100));
	}

	@Test
	public void isOversizedEditRejectsZeroOrNegativeTotal()
	{
		// Zero / negative total would either zero-out the threshold (false-positive every call) or invert it.
		// Guard returns false unconditionally so a malformed total never escalates into a confirm dialog.
		assertFalse(GraftController.isOversizedEdit(5, 0));
		assertFalse(GraftController.isOversizedEdit(5, -100));
	}

	@Test
	public void isOversizedEditTripsForLargeFraction()
	{
		// 50% — clearly oversized. Defensive sanity that the predicate fires at a clearly-over-threshold input.
		assertTrue(GraftController.isOversizedEdit(50, 100));
		// 100% — every pixel masked. The strict-greater gate compares count (100) > total * 0.1 (10), so true.
		assertTrue(GraftController.isOversizedEdit(100, 100));
	}

	@Test
	public void suggestedFilenameAppendsGraftSuffixBeforeExtension()
	{
		// Canonical case: "foo.jpg" → "foo-graft.jpg". The suffix lands BEFORE the extension so MIME-by-
		// extension inference + the user's at-a-glance "this is a graft" recognition both work.
		assertEquals("foo-graft.jpg", GraftController.suggestedFilename("foo.jpg"));
		// Stem with dots in it — only the LAST dot is the extension boundary.
		assertEquals("photo.2024.01-graft.jpg", GraftController.suggestedFilename("photo.2024.01.jpg"));
	}

	@Test
	public void suggestedFilenameFallsBackForNullOrEmpty()
	{
		// Content URIs without OpenableColumns return null for getDisplayName. Defensive fallback is
		// "graft.jpg" — a recognisable, save-able name rather than crashing or producing "-graft.jpg".
		assertEquals("graft.jpg", GraftController.suggestedFilename(null));
		assertEquals("graft.jpg", GraftController.suggestedFilename(""));
	}

	@Test
	public void suggestedFilenameHandlesLeadingDot()
	{
		// ".hidden" (dotfile, no real extension before the dot) — the dot is at index 0, so dot > 0 is
		// false → use whole name as stem → ".hidden-graft.jpg". This is consistent (preserves the leading
		// dot rather than treating it as a missing-stem extension).
		assertEquals(".hidden-graft.jpg", GraftController.suggestedFilename(".hidden"));
	}

	@Test
	public void suggestedFilenameHandlesNoExtension()
	{
		// "img" (no dot) → "img-graft.jpg". The fallback adds the .jpg extension explicitly so the saved
		// file has a recognisable MIME hint even when the source did not.
		assertEquals("img-graft.jpg", GraftController.suggestedFilename("img"));
	}
}
