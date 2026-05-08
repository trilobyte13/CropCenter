package com.cropcenter.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the AspectRatio poison-value contract. `isFree()` is documented as a defense against malformed
 * external constructions (Custom AR dialog, future deserialisation paths) — non-positive / non-finite inputs
 * must collapse to "no constraint" so `ratio()` returns 0 and downstream `Math.round(cropW / ratio)` doesn't
 * poison the crop with Integer.MAX_VALUE. The test pins each documented poison case.
 */
public final class AspectRatioTest
{
	@Test
	public void freeConstantIsFree()
	{
		assertTrue("FREE (0, 0) is the canonical no-constraint sentinel", AspectRatio.FREE.isFree());
		assertEquals("free.ratio() returns 0 so callers can short-circuit before dividing",
			0f, AspectRatio.FREE.ratio(), 0f);
	}

	@Test
	public void canonicalPresetsAreNotFreeAndComputeRatio()
	{
		assertFalse(AspectRatio.R1_1.isFree());
		assertFalse(AspectRatio.R4_5.isFree());
		assertFalse(AspectRatio.R16_9.isFree());
		assertEquals(1f, AspectRatio.R1_1.ratio(), 0f);
		assertEquals(0.8f, AspectRatio.R4_5.ratio(), 1e-6f);
		assertEquals(16f / 9f, AspectRatio.R16_9.ratio(), 1e-6f);
	}

	@Test
	public void zeroDimensionCollapsesToFree()
	{
		// External constructions with one zero dim — Custom AR dialog accepts (4, 0) if the user types 0 in
		// the height input. Without the isFree() guard, ratio() would divide-by-zero, returning Infinity, and
		// CropEngine's Math.round(cropW / Infinity) would resolve to 0 — collapsing the crop to a single
		// pixel.
		assertTrue(new AspectRatio(4, 0).isFree());
		assertTrue(new AspectRatio(0, 4).isFree());
		assertEquals(0f, new AspectRatio(4, 0).ratio(), 0f);
		assertEquals(0f, new AspectRatio(0, 4).ratio(), 0f);
	}

	@Test
	public void negativeDimensionCollapsesToFree()
	{
		// Negative inputs from a future deserialisation path or a user typing "-3" in Custom AR. The
		// resulting cropW = source.width * (negative) would land at a negative crop dim, breaking the rest
		// of the pipeline.
		assertTrue(new AspectRatio(-1, -1).isFree());
		assertTrue(new AspectRatio(-4, 5).isFree());
		assertTrue(new AspectRatio(4, -5).isFree());
	}

	@Test
	public void nanCollapsesToFree()
	{
		// `Float.NaN` returns false for every comparison, so `width <= 0 || height <= 0` alone wouldn't
		// catch it. The explicit `!Float.isFinite()` check is what gates this.
		assertTrue(new AspectRatio(Float.NaN, 1).isFree());
		assertTrue(new AspectRatio(1, Float.NaN).isFree());
		assertEquals(0f, new AspectRatio(Float.NaN, 1).ratio(), 0f);
	}

	@Test
	public void infinityCollapsesToFree()
	{
		// Same defense as NaN — `Float.POSITIVE_INFINITY <= 0` is false but the value would still poison
		// downstream math (ratio = Infinity → Math.round(cropW / Infinity) = 0).
		assertTrue(new AspectRatio(Float.POSITIVE_INFINITY, 1).isFree());
		assertTrue(new AspectRatio(1, Float.POSITIVE_INFINITY).isFree());
		assertTrue(new AspectRatio(Float.NEGATIVE_INFINITY, 1).isFree());
	}
}
