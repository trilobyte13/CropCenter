package com.cropcenter.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the AspectRatio poison-value contract and the snap helper. `isFree()` is documented as a defense against
 * malformed external constructions (Custom AR dialog, future deserialisation paths) — non-positive / non-finite inputs
 * must collapse to "no constraint" so `ratio()` returns 0 and downstream `Math.round(cropW / ratio)` doesn't poison the
 * crop with Integer.MAX_VALUE. snap() is the integer-pixel exact-AR helper used by CropEngine to eliminate the per-axis
 * ½-pixel drift that produced 0.79989 instead of 0.80000 on locked 4:5 exports — pin its FREE no-op, GCD reduction,
 * optimal k selection, image-bounds cap, and 4-pixel floor here.
 */
public final class AspectRatioTest
{
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
	public void freeConstantIsFree()
	{
		assertTrue("FREE (0, 0) is the canonical no-constraint sentinel", AspectRatio.FREE.isFree());
		assertEquals("free.ratio() returns 0 so callers can short-circuit before dividing",
			0f, AspectRatio.FREE.ratio(), 0f);
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

	@Test
	public void nanCollapsesToFree()
	{
		// `Float.NaN` returns false for every comparison, so `width <= 0 || height <= 0` alone wouldn't catch
		// it. The explicit `!Float.isFinite()` check is what gates this.
		assertTrue(new AspectRatio(Float.NaN, 1).isFree());
		assertTrue(new AspectRatio(1, Float.NaN).isFree());
		assertEquals(0f, new AspectRatio(Float.NaN, 1).ratio(), 0f);
	}

	@Test
	public void negativeDimensionCollapsesToFree()
	{
		// Negative inputs from a future deserialisation path or a user typing "-3" in Custom AR. The resulting
		// cropW = source.width * (negative) would land at a negative crop dim, breaking the rest of the
		// pipeline.
		assertTrue(new AspectRatio(-1, -1).isFree());
		assertTrue(new AspectRatio(-4, 5).isFree());
		assertTrue(new AspectRatio(4, -5).isFree());
	}

	@Test
	public void snap16To9ChoosesNearestK()
	{
		// (1919, 1080) is one pixel off exact 16:9; nearest k = 120 → (1920, 1080).
		assertArrayEquals(new int[] { 1920, 1080 }, AspectRatio.R16_9.snap(1919, 1080, 3000, 3000));
	}

	@Test
	public void snap1To1FloorsBelow4Pixels()
	{
		// 1:1 lock with input (3, 3): kMin = ceil(4/1) = 4, so the snap floors to k=4 → (4, 4) rather than
		// honouring the optimum k=3 that would collapse to (3, 3) below the 4-pixel minimum CropEngine enforces
		// elsewhere.
		int[] snapped = AspectRatio.R1_1.snap(3, 3, 1000, 1000);
		assertArrayEquals(new int[] { 4, 4 }, snapped);
	}

	@Test
	public void snapAlreadyExactArIsIdempotent()
	{
		// (1920, 1080) is exactly 16:9 with k=120 — snap shouldn't move it. Idempotency matters because
		// recomputeCrop runs the snap on every interaction; a snap that perturbs already-exact dims would
		// oscillate the crop on every rotation tick.
		assertArrayEquals(new int[] { 1920, 1080 }, AspectRatio.R16_9.snap(1920, 1080, 1920, 1080));
	}

	@Test
	public void snapAtMaxIntDimensionsDoesNotOverflow()
	{
		// Pins the `(long)` casts on snap's num formula. cropW = 2^27 with 16:9 puts the first product
		// at 16 · 2^27 = 2^31, one past Integer.MAX_VALUE. Int-wrap trace without the casts:
		// 16 · 134_217_728 wraps to Integer.MIN_VALUE; + 9 · 75_497_472 = 679_477_248 (no wrap) gives
		// num = -1_468_006_400 → k = round(num / 337) = -4_356_102 → clamped up to kMin = 1 → snap
		// returns (16, 9) and the exact-identity assertion fails. With the casts, num = 337 · 2^23
		// exactly, k = 2^23, and the already-exact input round-trips unchanged.
		int cropW = 134_217_728; // 16 · 2^23
		int cropH = 75_497_472;  // 9 · 2^23
		assertArrayEquals("huge exact-16:9 input must round-trip (num stays in long range)",
			new int[] { cropW, cropH }, AspectRatio.R16_9.snap(cropW, cropH, cropW, cropH));
	}

	@Test
	public void snapBailsWhenArCannotFitMaxBounds()
	{
		// 16:9 lock on a 8×8 image: kMax = min(8/16, 8/9) = 0; AR can't physically realise inside the bounds at
		// any positive k. The helper falls back to the caller's pre-snap dims rather than triggering
		// Math.clamp's min > max contract violation.
		assertArrayEquals(new int[] { 8, 5 }, AspectRatio.R16_9.snap(8, 5, 8, 8));
	}

	@Test
	public void snapCapsAtMaxWhenOptimumWouldExceedBounds()
	{
		// 16:9 lock on a 3000-wide image fitting horizontally: float crop is (3000, 1687.5) → rounded (3000,
		// 1688). Optimum k = round((16·3000 + 9·1688) / 337) = 188 → (3008, 1692), but 3008 > 3000. Cap at kMax
		// = floor(3000/16) = 187 → (2992, 1683). Stays inside the image.
		int[] snapped = AspectRatio.R16_9.snap(3000, 1688, 3000, 4000);
		assertEquals(2992, snapped[0]);
		assertEquals(1683, snapped[1]);
		assertTrue("snapped width must not exceed maxW=3000", snapped[0] <= 3000);
		assertTrue("snapped height must not exceed maxH=4000", snapped[1] <= 4000);
	}

	@Test
	public void snapFractionalArReturnsInputUnchanged()
	{
		// Non-integer dims (Custom AR with 4.5 / 5) can't yield exact-integer-pixel snapping — fall back to the
		// caller's per-axis round, which is the best available match. Cover BOTH-axes-fractional,
		// width-only-fractional, and height-only-fractional cases so a regression that swaps the OR in `width
		// != round(width) || height != round(height)` for an AND (only short-circuits when BOTH are fractional)
		// gets caught: (4.5, 5) passes either way, but (4, 5.5) and (5.5, 4) would only short-circuit under the
		// OR.
		AspectRatio bothFractional = new AspectRatio(4.5f, 5f);
		assertArrayEquals("both-fractional must short-circuit",
			new int[] { 2990, 3738 }, bothFractional.snap(2990, 3738, 3000, 4000));
		AspectRatio heightFractional = new AspectRatio(4f, 5.5f);
		assertArrayEquals("height-only-fractional must short-circuit (OR predicate)",
			new int[] { 2990, 3738 }, heightFractional.snap(2990, 3738, 3000, 4000));
		AspectRatio widthFractional = new AspectRatio(5.5f, 4f);
		assertArrayEquals("width-only-fractional must short-circuit (OR predicate)",
			new int[] { 2990, 3738 }, widthFractional.snap(2990, 3738, 3000, 4000));
	}

	@Test
	public void snapFreeReturnsInputUnchanged()
	{
		// FREE has no AR to snap to — the helper is a no-op so callers can invoke it unconditionally without
		// first checking isFree().
		assertArrayEquals(new int[] { 2990, 3738 }, AspectRatio.FREE.snap(2990, 3738, 3000, 4000));
		assertArrayEquals(new int[] { 1, 1 }, AspectRatio.FREE.snap(1, 1, 1000, 1000));
	}

	@Test
	public void snapHugeCustomArDenominatorDoesNotOverflow()
	{
		// Pins the `(long)` casts on snap's den formula: 46341² = 2_147_488_281 > Integer.MAX_VALUE (the same
		// 46341 threshold EditorRendererTest.hugeSourceDoesNotOverflowIntoAFalsePass uses). Int-wrap trace
		// without the den casts: 46341·46341 + 1·1 wraps to -2_147_479_014 → k = round(10_737_441_410 /
		// -2_147_479_014) = -5 → clamped up to kMin = 4 → (185_364, 4). Without the num casts instead, num
		// wraps to -2_147_460_478 → k = -1 → same kMin clamp. Either regression fails the exact-identity
		// assertion; intact, k = 5 and the input round-trips.
		AspectRatio huge = new AspectRatio(46341, 1);
		assertArrayEquals("exact 46341:1 input at k=5 must round-trip (den stays in long range)",
			new int[] { 231_705, 5 }, huge.snap(231_705, 5, 231_705, 5));
	}

	@Test
	public void snapNoGrowModeForbidsUpscale()
	{
		// recheckRotationFit calls snap with (cropW, cropH) as the max bounds — forbids growth so the snap
		// can't re-violate the rotation fit. Optimum k for (2990, 3738) at 4:5 is 748 → (2992, 3740), but
		// no-grow forces k = floor(min(2990/4, 3738/5)) = floor(min(747, 747)) = 747 → (2988, 3735).
		int[] snapped = AspectRatio.R4_5.snap(2990, 3738, 2990, 3738);
		assertArrayEquals(new int[] { 2988, 3735 }, snapped);
		assertTrue("snapped width must not grow past max=2990", snapped[0] <= 2990);
		assertTrue("snapped height must not grow past max=3738", snapped[1] <= 3738);
	}

	@Test
	public void snapReducesNonCanonicalArToLowestTerms()
	{
		// (16, 10) is 8:5 in lowest terms; snap should reduce first so k-steps are (8, 5) not (16, 10), giving
		// finer-grained snapping than the unreduced form would. (1599, 1000) → (1600, 1000) at k=200 using
		// reduced (8, 5).
		AspectRatio ar16to10 = new AspectRatio(16, 10);
		int[] snapped = ar16to10.snap(1599, 1000, 3000, 3000);
		assertArrayEquals(new int[] { 1600, 1000 }, snapped);
		assertEquals(1.6f, snapped[0] / (float) snapped[1], 1e-6f);
	}

	@Test
	public void snapUpToOptimumKWhenImageBoundsAllow()
	{
		// Image-bounds cap exposes the optimal-k snap: 4:5 lock with input (2990, 3738), bounds (3000, 4000).
		// Independent Math.round on each axis would leave AR=0.79989; the snap pulls both axes to (4·748,
		// 5·748) = (2992, 3740), AR=0.80000 exact. CropEngine itself uses no-grow bounds to avoid silently
		// drifting a locked center (see snapNoGrowModeForbidsUpscale below); this test pins the snap method's
		// snap-up behaviour for callers that genuinely need it (none today, but the public API supports it).
		int[] snapped = AspectRatio.R4_5.snap(2990, 3738, 3000, 4000);
		assertArrayEquals(new int[] { 2992, 3740 }, snapped);
		assertEquals(0.8f, snapped[0] / (float) snapped[1], 1e-7f);
	}

	@Test
	public void zeroDimensionCollapsesToFree()
	{
		// External constructions with one zero dim — Custom AR dialog accepts (4, 0) if the user types 0 in the
		// height input. Without the isFree() guard, ratio() would divide-by-zero, returning Infinity, and
		// CropEngine's Math.round(cropW / Infinity) would resolve to 0 — collapsing the crop to a single pixel.
		assertTrue(new AspectRatio(4, 0).isFree());
		assertTrue(new AspectRatio(0, 4).isFree());
		assertEquals(0f, new AspectRatio(4, 0).ratio(), 0f);
		assertEquals(0f, new AspectRatio(0, 4).ratio(), 0f);
	}
}
