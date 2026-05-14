package com.cropcenter.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cropcenter.util.AiRegionDetector.AiMask;

/**
 * Tests for the AiMask record's pure-data accessors. The detect() method itself needs BitmapFactory and isn't
 * unit-testable without Robolectric, but the predicates and counters that callers use to make routing decisions are
 * pure logic and easy to pin.
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
		// AiRegionDetector.detect uses the canonical record constructor directly to avoid re-walking the
		// mask post-detect. Pin the contract: the explicit count is preserved verbatim, even if it doesn't
		// match a recount of the boolean array. This is intentional — the count is the source of truth at
		// construction; the boolean array is just storage.
		boolean[] mask = { true, false, true, false };
		AiMask aiMask = new AiMask(mask, 2, 2, 4, 99);
		assertEquals("explicit count is not recomputed", 99, aiMask.maskedCount());
	}

	@Test
	public void componentAccessorsExposeAllFields()
	{
		// Records auto-generate accessors. Pin them down so a renamed component surfaces here as a compile
		// error instead of at the call site. AiMask.of derives maskedCount from a single walk of the mask;
		// the canonical constructor takes the count directly.
		boolean[] mask = { true, false, true, false };
		AiMask aiMask = AiMask.of(mask, 2, 2, 4);
		assertArrayEquals(mask, aiMask.mask());
		assertEquals(2, aiMask.width());
		assertEquals(2, aiMask.height());
		assertEquals(4, aiMask.sampleSize());
		assertEquals(2, aiMask.maskedCount());
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
	public void hasMaskedPixelsShortCircuitsOnFirstTrue()
	{
		// Predicate returns true on the first flagged pixel — implementation walks the array but bails early.
		// Functionally we just verify it returns true; the short-circuit benefit is performance only and isn't
		// observable in tests.
		boolean[] mask = new boolean[1000];
		mask[0] = true;
		AiMask aiMask = AiMask.of(mask, 10, 100, 4);
		assertTrue(aiMask.hasMaskedPixels());
	}

	@Test
	public void hasMaskedPixelsTrueOnLastPixelOnly()
	{
		// Edge case: only the very last pixel is flagged. The walk has to reach the end before returning true.
		// A regression that bails too early would return false here.
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
}
