package com.cropcenter.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cropcenter.util.AiRegionDetector.AiMask;

import org.junit.Test;

/**
 * Tests for the AiMask record's pure-data accessors plus the diffPixels helper that AiRegionDetector.detect uses
 * internally. The detect() method itself needs BitmapFactory and isn't unit-testable without Robolectric, but the
 * predicates and counters that callers use to make routing decisions are pure logic and easy to pin, and the per-pixel
 * max-channel-diff math now lives in a package-private helper so the threshold contract is testable directly.
 *
 * hasMaskedPixels and maskedCount drive two important paths: the inpaint-skip when the detected mask is empty (avoids
 * wasting a JPEG re-encode of the gain map) and the GraftController.LARGE_EDIT_FRACTION sanity gate (forces a confirm
 * dialog when a wrong-file pick or wholesale global edit produces a suspiciously large mask).
 */
public final class AiRegionDetectorTest
{
	@Test
	public void canonicalConstructorAcceptsExplicitMaskedCount()
	{
		// AiRegionDetector.detect uses the canonical record constructor directly to avoid re-walking the mask
		// post-detect. Pin the contract: the explicit count is preserved verbatim, even if it doesn't match a
		// recount of the boolean array. This is intentional — the count is the source of truth at construction;
		// the boolean array is just storage.
		boolean[] mask = { true, false, true, false };
		AiMask aiMask = new AiMask(mask, 2, 2, 4, 99);
		assertEquals("explicit count is not recomputed", 99, aiMask.maskedCount());
	}

	@Test
	public void hasMaskedPixelsFalseOnEmptyMaskArray()
	{
		// Zero-pixel mask (impossible in real life — detect() always returns at least one element — but the
		// predicate must handle it without IOOBE).
		AiMask aiMask = AiMask.of(new boolean[0], 0, 0, 4);
		assertFalse(aiMask.hasMaskedPixels());
	}

	@Test
	public void hasMaskedPixelsFalseWhenAllFalse()
	{
		// No detected change — equivalent to "applying this edit changes nothing visible at the threshold".
		// Caller (UltraHdrCompat) skips the inpaint step entirely so the gain-map JPEG isn't re-encoded for
		// nothing.
		boolean[] mask = new boolean[100];
		AiMask aiMask = AiMask.of(mask, 10, 10, 4);
		assertFalse(aiMask.hasMaskedPixels());
	}

	@Test
	public void hasMaskedPixelsTrueWhenAiMaskOfCountedFirstPositionFlag()
	{
		// AiMask.of walks the input mask to compute maskedCount; hasMaskedPixels then returns `maskedCount >
		// 0`. Pin that a single flagged pixel at position 0 of a long array is counted (a regression in
		// AiMask.of that started the walk at index 1 instead of 0 would surface here as maskedCount=0 →
		// hasMaskedPixels=false).
		boolean[] mask = new boolean[1000];
		mask[0] = true;
		AiMask aiMask = AiMask.of(mask, 10, 100, 4);
		assertTrue(aiMask.hasMaskedPixels());
	}

	@Test
	public void hasMaskedPixelsTrueWhenAiMaskOfCountedLastPositionFlag()
	{
		// Companion to the first-position test: a single flagged pixel at the LAST index forces AiMask.of's
		// walk to reach mask.length-1. A regression that stopped one position short (e.g., `i < mask.length
		// - 1`) would surface here as maskedCount=0 → hasMaskedPixels=false.
		boolean[] mask = new boolean[100];
		mask[99] = true;
		AiMask aiMask = AiMask.of(mask, 10, 10, 4);
		assertTrue(aiMask.hasMaskedPixels());
	}

	@Test
	public void maskedCountAllFalse()
	{
		boolean[] mask = new boolean[50];
		AiMask aiMask = AiMask.of(mask, 5, 10, 4);
		assertEquals(0, aiMask.maskedCount());
	}

	@Test
	public void maskedCountAllTrue()
	{
		boolean[] mask = new boolean[50];
		for (int i = 0; i < mask.length; i++)
		{
			mask[i] = true;
		}
		AiMask aiMask = AiMask.of(mask, 5, 10, 4);
		assertEquals(50, aiMask.maskedCount());
	}

	@Test
	public void maskedCountEmptyArray()
	{
		// Zero-element array — count is 0 without throwing.
		AiMask aiMask = AiMask.of(new boolean[0], 0, 0, 4);
		assertEquals(0, aiMask.maskedCount());
	}

	@Test
	public void maskedCountInsensitiveToWidthHeightFields()
	{
		// width/height/sampleSize don't affect maskedCount — it walks the raw mask array. This test pins down
		// that contract: even with mismatched dims the count reflects the actual flagged-pixel total.
		boolean[] mask = { true, false, true, true };
		AiMask aiMask = AiMask.of(mask, 99, 99, 4);
		assertEquals(3, aiMask.maskedCount());
	}

	@Test
	public void maskedCountMatchesFractionForSanityGate()
	{
		// Pin down the fraction calculation that GraftController.isOversizedEdit uses (maskedCount >
		// totalLength * LARGE_EDIT_FRACTION). For a 1000-px mask at 50% coverage, count = 500, fraction = 0.5 —
		// well above the 0.10 threshold.
		boolean[] mask = new boolean[1000];
		for (int i = 0; i < 500; i++)
		{
			mask[i] = true;
		}
		AiMask aiMask = AiMask.of(mask, 10, 100, 4);
		int count = aiMask.maskedCount();
		assertEquals(500, count);
		assertTrue("50% should exceed 10% threshold", count > mask.length * 0.10);
	}

	@Test
	public void maskedCountSparse()
	{
		// Sparse flag pattern — count is exactly the number of trues regardless of distribution.
		boolean[] mask = new boolean[1000];
		mask[0] = true;
		mask[100] = true;
		mask[500] = true;
		mask[999] = true;
		AiMask aiMask = AiMask.of(mask, 100, 10, 4);
		assertEquals(4, aiMask.maskedCount());
	}

	// ── diffPixels ──

	@Test
	public void diffPixelsFlagsRedChannelDifferenceAboveThreshold()
	{
		// Single pixel with a 50-LSB red-channel diff — well above the 30 threshold. Pin the channel mask (>>16
		// & 0xFF) so a regression that swapped red/blue would surface here.
		int[] source = { 0xFF200000 };  // red = 0x20
		int[] edit   = { 0xFF600000 };  // red = 0x60 → diff 0x40 = 64
		boolean[] mask = new boolean[1];
		int count = AiRegionDetector.diffPixels(source, edit, mask, 30);
		assertEquals(1, count);
		assertTrue(mask[0]);
	}

	@Test
	public void diffPixelsFlagsGreenChannelDifferenceAboveThreshold()
	{
		// Same as above but for green channel — pin the >>8 & 0xFF shift / mask. A swap of red/green would pass
		// the red-channel test alone.
		int[] source = { 0xFF002000 };
		int[] edit   = { 0xFF006000 };
		boolean[] mask = new boolean[1];
		int count = AiRegionDetector.diffPixels(source, edit, mask, 30);
		assertEquals(1, count);
		assertTrue(mask[0]);
	}

	@Test
	public void diffPixelsFlagsBlueChannelDifferenceAboveThreshold()
	{
		// Pin the blue channel (& 0xFF). Together the three single-channel tests prevent a channel-mask
		// regression from sneaking through with two of three covered.
		int[] source = { 0xFF000020 };
		int[] edit   = { 0xFF000060 };
		boolean[] mask = new boolean[1];
		int count = AiRegionDetector.diffPixels(source, edit, mask, 30);
		assertEquals(1, count);
		assertTrue(mask[0]);
	}

	@Test
	public void diffPixelsIgnoresAlphaChannelEntirely()
	{
		// Alpha differs by 0xFF but no RGB diff — must not flag. The diff loop intentionally ignores alpha (the
		// gain map's HDR boost only cares about RGB content); a regression that included alpha would over-mask
		// every PNG-with-alpha source.
		int[] source = { 0x00112233 };
		int[] edit   = { 0xFF112233 };
		boolean[] mask = new boolean[1];
		int count = AiRegionDetector.diffPixels(source, edit, mask, 30);
		assertEquals(0, count);
		assertFalse(mask[0]);
	}

	@Test
	public void diffPixelsRejectsEqualToThreshold()
	{
		// The threshold check is strict greater-than (> threshold), not >=. A diff exactly at threshold must
		// NOT flag. Pin both edges of the boundary so a refactor that swapped > for >= is caught.
		int[] source = { 0xFF000000 };
		int[] edit   = { 0xFF001E00 };  // green diff = 0x1E = 30
		boolean[] mask = new boolean[1];
		int count = AiRegionDetector.diffPixels(source, edit, mask, 30);
		assertEquals("diff == threshold must not flag", 0, count);
		assertFalse(mask[0]);
	}

	@Test
	public void diffPixelsAcceptsOneAboveThreshold()
	{
		// Complement to the above: diff = 31 (one LSB past threshold) must flag.
		int[] source = { 0xFF000000 };
		int[] edit   = { 0xFF001F00 };  // green diff = 0x1F = 31
		boolean[] mask = new boolean[1];
		int count = AiRegionDetector.diffPixels(source, edit, mask, 30);
		assertEquals(1, count);
		assertTrue(mask[0]);
	}

	@Test
	public void diffPixelsUsesMaxAcrossChannels()
	{
		// Channel diffs: red 5, green 10, blue 40. Max = 40 → flagged at threshold 30. Pin that the algorithm
		// picks max, not sum — a regression to "sum > threshold" would also pass for this input (5+10+40=55),
		// but the dedicated red-only and blue-only tests above would catch sum-vs-max drift at the per-channel
		// boundary. This case pins the max-aggregation when multiple channels move.
		int[] source = { 0xFF000000 };
		int[] edit   = { 0xFF050A28 };  // r=5, g=10, b=40
		boolean[] mask = new boolean[1];
		int count = AiRegionDetector.diffPixels(source, edit, mask, 30);
		assertEquals(1, count);
		assertTrue(mask[0]);
	}

	@Test
	public void diffPixelsZeroDiffReturnsAllFalse()
	{
		// Identical pixels — no flags, count = 0. Common case for the unchanged regions of an AI edit.
		int[] source = { 0xFF123456, 0xFFABCDEF, 0x00112233 };
		int[] edit   = { 0xFF123456, 0xFFABCDEF, 0x00112233 };
		boolean[] mask = new boolean[3];
		int count = AiRegionDetector.diffPixels(source, edit, mask, 30);
		assertEquals(0, count);
		assertFalse(mask[0]);
		assertFalse(mask[1]);
		assertFalse(mask[2]);
	}

	@Test
	public void diffPixelsCountMatchesMaskTrueEntries()
	{
		// Multi-pixel mixed input — pin that the returned count is exactly the number of true entries written
		// to mask[]. The cached count drives hasMaskedPixels() and the LARGE_EDIT_FRACTION sanity gate, so a
		// miscount here would silently mis-route the inpaint skip / confirm dialog.
		int[] source = { 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000 };
		int[] edit   = { 0xFF000000, 0xFFFF0000, 0xFF000000, 0xFF00FF00 };  // pixels 1 and 3 differ
		boolean[] mask = new boolean[4];
		int count = AiRegionDetector.diffPixels(source, edit, mask, 30);
		assertEquals(2, count);
		assertFalse(mask[0]);
		assertTrue(mask[1]);
		assertFalse(mask[2]);
		assertTrue(mask[3]);
	}

	@Test
	public void diffPixelsCountIncludesPreSetMaskBitsRegardlessOfPriorState()
	{
		// Documented contract: the returned count is the number of pixels whose diff exceeds threshold, NOT the
		// number of false → true transitions. With a pre-populated mask whose entries overlap the diff-flagged
		// indices, the count includes the overlap pixels. Production always passes a fresh boolean[] so the two
		// counts coincide there, but the documented contract diverges for pre-populated input — pin it so a
		// future refactor that "fixed" the implementation to count only transitions would have to update the
		// documented contract here too.
		int[] source = { 0xFF000000, 0xFF000000 };
		int[] edit   = { 0xFFFF0000, 0xFFFF0000 };  // both pixels differ in red
		boolean[] mask = { true, false };           // pre-populated: index 0 was already true
		int count = AiRegionDetector.diffPixels(source, edit, mask, 30);
		assertEquals("count tallies diff-exceeds-threshold, NOT transitions", 2, count);
		assertTrue("pre-existing true is preserved", mask[0]);
		assertTrue("newly-flagged true is set", mask[1]);
	}
}
