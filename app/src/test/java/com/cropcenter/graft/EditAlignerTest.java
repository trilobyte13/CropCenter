package com.cropcenter.graft;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Tests for EditAligner's pure-function pieces: the Result record's factory methods and the displayDims /
 * inverseOrientation helpers that drive the alignment decision.
 *
 * The full align() entry point exercises BitmapFactory.decodeByteArray and Bitmap.compress, both of which return
 * default values in the unit-test harness (returnDefaultValues=true). What's reachable from here is the no-decode
 * failure path; everything else needs Robolectric or a real device. The displayDims + inverseOrientation helpers
 * together cover the core "what stored layout should the edit end up in" logic that motivates the realignment in the
 * first place.
 */
public final class EditAlignerTest
{
	@Test
	public void alignChecksSourceDecodeBeforeEditDecode()
	{
		// Pin the source-then-edit probe ordering. Both bytes pass the SOI gate; both would fail
		// decodeStoredDims under unitTests.returnDefaultValues=true (BitmapFactory.decodeByteArray returns null
		// for any 4-byte SOI fixture on the JVM). The error attribution is what we observe: the "Source image
		// is corrupt" message must fire first, BEFORE the "Couldn't decode the edit" message that would
		// otherwise come from a swap of the two probes. Distinct fixtures aren't strictly needed for the
		// ordering check (since both probe results are null-equal under the stub), but using different arrays
		// documents the intent at the call site that an edit-side decode would be the only path that produces
		// the OTHER error.
		byte[] originalSoi = { (byte) 0xFF, (byte) 0xD8, 0x11, 0x22 };
		byte[] editSoi = { (byte) 0xFF, (byte) 0xD8, 0x33, 0x44 };
		EditAligner.Result result = EditAligner.align(originalSoi, editSoi);
		assertEquals("Source image is corrupt — reload it", result.errorMessage());
	}

	@Test
	public void alignRejectsNonJpegByteStreamAtSoiCheck()
	{
		// A HEIC / WebP / PNG byte stream can bypass the picker's image/jpeg MIME filter, and
		// decodeStoredDims alone would accept it (BitmapFactory decodes those formats), deferring the
		// failure to GraftWriter's generic "Edit is not a JPEG" IOException. The explicit SOI gate at the
		// top of align() must catch it first with an actionable "Selected file is not a JPEG" message —
		// telling the user what kind of file they need rather than a corrupt-file blame.
		byte[] heicMagic = { 0x00, 0x00, 0x00, 0x20, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c' };
		byte[] originalSoi = { (byte) 0xFF, (byte) 0xD8, 0x00, 0x00 };
		EditAligner.Result result = EditAligner.align(originalSoi, heicMagic);
		assertEquals("Selected file is not a JPEG", result.errorMessage());
		assertNull(result.alignedBytes());
	}

	@Test
	public void alignRejectsNullEditAtSoiCheck()
	{
		byte[] originalSoi = { (byte) 0xFF, (byte) 0xD8, 0x00, 0x00 };
		EditAligner.Result result = EditAligner.align(originalSoi, null);
		assertEquals("Selected file is not a JPEG", result.errorMessage());
	}

	@Test
	public void alignRejectsTooShortEditAtSoiCheck()
	{
		// 0-3 byte input can't carry SOI — the gate handles it without an AIOOBE. Same SOI-mismatch error path
		// as the non-JPEG byte stream case.
		byte[] tooShort = { (byte) 0xFF, (byte) 0xD8, 0x00 };
		byte[] originalSoi = { (byte) 0xFF, (byte) 0xD8, 0x00, 0x00 };
		EditAligner.Result result = EditAligner.align(originalSoi, tooShort);
		assertEquals("Selected file is not a JPEG", result.errorMessage());
	}

	@Test
	public void alignReturnsErrorWhenOriginalDecodeFails()
	{
		// Under unitTests.returnDefaultValues=true, BitmapFactory.decodeByteArray returns null and never sets
		// outWidth/outHeight, so decodeStoredDims sees 0×0 and returns null on the source. The error message
		// must point at the source ("reload") not the edit, since the source was loaded successfully earlier
		// and only became un-decodable later (memory pressure, race).
		byte[] notRealJpeg = { (byte) 0xFF, (byte) 0xD8, 0x00, 0x00 };
		EditAligner.Result result = EditAligner.align(notRealJpeg, notRealJpeg);
		assertNotNull(result.errorMessage());
		assertEquals("Source image is corrupt — reload it", result.errorMessage());
		assertNull(result.alignedBytes());
	}

	@Test
	public void displayDimsLeavesAxesUnchangedForFlipOrientations()
	{
		// Orientations 2/3/4 are mirror / rotate-180 / mirror — no axis swap.
		int[] stored = { 100, 50 };
		assertArrayEquals(new int[]{ 100, 50 }, EditAligner.displayDims(stored, 2));
		assertArrayEquals(new int[]{ 100, 50 }, EditAligner.displayDims(stored, 3));
		assertArrayEquals(new int[]{ 100, 50 }, EditAligner.displayDims(stored, 4));
	}

	@Test
	public void displayDimsReturnsFreshArrayNotAliasOfInput()
	{
		// Caller mutability invariant: the helper docs promise a fresh int[2] so callers can mutate without
		// aliasing. Pin it so a future refactor that returns the input directly (e.g. as a no-op for orient=1)
		// doesn't break any caller that stashes the array and assumes ownership.
		int[] stored = { 100, 50 };
		int[] display = EditAligner.displayDims(stored, 1);
		display[0] = 999;
		assertEquals("input array must not alias output", 100, stored[0]);
	}

	@Test
	public void displayDimsSwapsAxesForAllNinetyDegreeOrientations()
	{
		// Orientations 5/6/7/8 all involve a ±90° rotation and so all swap axes.
		int[] stored = { 100, 50 };
		assertArrayEquals(new int[]{ 50, 100 }, EditAligner.displayDims(stored, 5));
		assertArrayEquals(new int[]{ 50, 100 }, EditAligner.displayDims(stored, 6));
		assertArrayEquals(new int[]{ 50, 100 }, EditAligner.displayDims(stored, 7));
		assertArrayEquals(new int[]{ 50, 100 }, EditAligner.displayDims(stored, 8));
	}

	@Test
	public void inverseOrientationIsIdentityForFlipsAndIdentity()
	{
		// Orientations 1 / 2 / 3 / 4 / 5 / 7 are involutions — applying twice equals the identity transform, so
		// each is its own inverse.
		assertEquals(1, EditAligner.inverseOrientation(1));
		assertEquals(2, EditAligner.inverseOrientation(2));
		assertEquals(3, EditAligner.inverseOrientation(3));
		assertEquals(4, EditAligner.inverseOrientation(4));
		assertEquals(5, EditAligner.inverseOrientation(5));
		assertEquals(7, EditAligner.inverseOrientation(7));
	}

	@Test
	public void inverseOrientationSwapsSixAndEight()
	{
		// 6 ↔ 8 are the only true inverse pair: 6 is "rotate 90° CW", 8 is "rotate 90° CCW". Composition gives
		// identity.
		assertEquals(8, EditAligner.inverseOrientation(6));
		assertEquals(6, EditAligner.inverseOrientation(8));
	}

	@Test
	public void resultErrorBuildsFailureResult()
	{
		EditAligner.Result result = EditAligner.Result.error("dimensions mismatch");
		assertNull(result.alignedBytes());
		assertEquals("dimensions mismatch", result.errorMessage());
	}

	@Test
	public void resultOkBuildsSuccessResult()
	{
		byte[] bytes = { 0x01, 0x02, 0x03 };
		EditAligner.Result result = EditAligner.Result.ok(bytes);
		assertArrayEquals(bytes, result.alignedBytes());
		assertNull(result.errorMessage());
	}
}
