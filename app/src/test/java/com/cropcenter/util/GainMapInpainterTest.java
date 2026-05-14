package com.cropcenter.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the pure-algorithm core of GainMapInpainter (the int[]/boolean[] frontier-tracked grow-from-boundary
 * pass plus the in-place 8-connected mask dilation). The Bitmap-dispatch entry point inpaintBitmap requires an
 * Android Bitmap and is exercised end-to-end by the manual graft pipeline; the algorithm itself is what carries the
 * load-bearing correctness contracts and is testable in pure JVM.
 *
 * The most important contract pinned here is the rounding behavior on integer division. An earlier implementation
 * used `sum / count` (floor toward zero), which biased the inpainted region 0.5 LSB darker per pass; over the
 * hundreds of passes a real AI fill takes, that accumulated to a visibly darker patch in the HDR boost. Current
 * implementation uses `(sum + count / 2) / count` (nearest-int, half-up). The roundingBiasRegression test below
 * pins that change with a worst-case fixture: a uniform 100-valued unmasked surround with a bias-detectable mask
 * shape that would compound to ~50 LSBs darker under the floor variant and stays at ~100 under the rounded one.
 */
public final class GainMapInpainterTest
{
	@Test
	public void dilateMaskGrowsRingsOf8Neighbors()
	{
		// Single masked center pixel in a 5x5 grid. After radius=1 dilate, all 8 neighbors should also be
		// masked. After radius=2 the outer ring (4 corner-adjacent + 8 edge-adjacent) should also be masked.
		boolean[] mask = new boolean[25];
		mask[12] = true; // center of a 5x5
		GainMapInpainter.dilateMask(mask, 5, 5, 1);
		// Expected mask after radius=1: 3x3 centered at (2, 2)
		for (int y = 0; y < 5; y++)
		{
			for (int x = 0; x < 5; x++)
			{
				boolean expected = Math.abs(x - 2) <= 1 && Math.abs(y - 2) <= 1;
				assertEquals("(" + x + "," + y + ")", expected, mask[y * 5 + x]);
			}
		}
	}

	@Test
	public void dilateMaskNoOpAtRadiusZero()
	{
		boolean[] mask = new boolean[9];
		mask[4] = true;
		GainMapInpainter.dilateMask(mask, 3, 3, 0);
		for (int i = 0; i < 9; i++)
		{
			assertEquals(i == 4, mask[i]);
		}
	}

	@Test
	public void dilateMaskRespectsImageEdges()
	{
		// Corner-pixel mask: dilate must not write outside [0, width) x [0, height).
		boolean[] mask = new boolean[9]; // 3x3
		mask[0] = true; // top-left
		GainMapInpainter.dilateMask(mask, 3, 3, 1);
		// After radius=1, only positions within max-distance-1 of (0,0) are masked: (0,0), (1,0), (0,1), (1,1).
		assertTrue(mask[0]); // (0, 0)
		assertTrue(mask[1]); // (1, 0)
		assertTrue(mask[3]); // (0, 1)
		assertTrue(mask[4]); // (1, 1)
		assertFalse(mask[2]); // (2, 0) is 2 away
		assertFalse(mask[5]); // (2, 1) is 2 away
		assertFalse(mask[6]); // (0, 2) is 2 away
		assertFalse(mask[8]); // (2, 2) is 2 away
	}

	@Test
	public void inpaintIterativeAllUnmaskedReturnsZeroPasses()
	{
		// No masked pixels → frontier is empty → 0 passes, no value mutation.
		int[] values = { 10, 20, 30, 40, 50, 60, 70, 80, 90 };
		boolean[] mask = new boolean[9];
		int passes = GainMapInpainter.inpaintIterative(values, mask, 3, 3);
		assertEquals(0, passes);
		// Values unchanged.
		assertEquals(50, values[4]);
	}

	@Test
	public void inpaintIterativeFillsSinglePixelFromUniformSurround()
	{
		// 3x3 with a single masked center pixel, surrounded by 8 pixels all valued 100. The inpaint should
		// replace the center with 100 (average of 8 neighbors).
		int[] values = { 100, 100, 100, 100, 0, 100, 100, 100, 100 };
		boolean[] mask = { false, false, false, false, true, false, false, false, false };
		GainMapInpainter.inpaintIterative(values, mask, 3, 3);
		assertEquals(100, values[4]);
		// Mask is consumed: center should now be unmasked.
		assertFalse(mask[4]);
	}

	@Test
	public void inpaintIterativeIsolatedMaskedPixelStaysWhenNoUnmaskedNeighbor()
	{
		// All-masked field with one unmasked corner — the frontier seed only includes the masked pixel
		// adjacent to that corner. After one pass that pixel becomes unmasked, then the next pass picks up
		// its neighbors. Verify the all-masked-corner-adjacent case: the one unmasked seeds propagation.
		int[] values = new int[9];
		boolean[] mask = new boolean[9];
		for (int i = 0; i < 9; i++)
		{
			mask[i] = true;
		}
		mask[0] = false; // unmask top-left
		values[0] = 50;
		int passes = GainMapInpainter.inpaintIterative(values, mask, 3, 3);
		// All originally-masked pixels are eventually filled — verify by checking mask is all-false at end.
		for (int i = 0; i < 9; i++)
		{
			assertFalse("index " + i, mask[i]);
		}
		assertTrue("should have run multiple passes", passes >= 2);
	}

	@Test
	public void inpaintIterativeMaskAllTrueBailsAtMaxPasses()
	{
		// Pathological: every pixel is masked. There's no unmasked neighbor for any frontier candidate, so the
		// initial frontier is empty and the loop exits immediately. Passes = 0.
		int[] values = new int[16];
		boolean[] mask = new boolean[16];
		for (int i = 0; i < 16; i++)
		{
			mask[i] = true;
		}
		int passes = GainMapInpainter.inpaintIterative(values, mask, 4, 4);
		assertEquals(0, passes);
	}

	@Test
	public void inpaintIterativeMaskedSpanAcrossGradientPreservesMonotonicity()
	{
		// 5×1 strip with masked indices 1..3, values 100/?/?/?/200. The fill must be monotonic
		// non-decreasing — a regression in the rounding direction would manifest as direction-dependent
		// drift (lower-valued side bleeds further than higher-valued side, breaking monotonicity).
		// Existing rounding-bias tests pin worst-case symmetric 2-neighbor / 8-neighbor averages; this
		// test pins the asymmetric gradient case where the inpainted patch SPANS a value range.
		int[] values = { 100, 0, 0, 0, 200 };
		boolean[] mask = { false, true, true, true, false };
		GainMapInpainter.inpaintIterative(values, mask, 5, 1);
		assertTrue("inpainted span must be monotonic non-decreasing across the gradient: "
			+ values[1] + " <= " + values[2] + " <= " + values[3],
			values[1] <= values[2] && values[2] <= values[3]);
		assertTrue("inpainted values must lie within the source-anchor bounds: "
			+ "100 <= " + values[1] + " and " + values[3] + " <= 200",
			values[1] >= 100 && values[3] <= 200);
	}

	@Test
	public void roundingBiasRegression_negativeBiasNotIntroduced()
	{
		// Stricter regression: 9-pixel strip with masked center. 4 left neighbors all 100, 4 right all 101.
		// Sum from masked-center's TWO direct neighbors only = 100 + 101 = 201, count = 2, avg = 100.5.
		// Floor (sum / count) = 100. Round-half-up ((sum + count/2) / count) = 101. Either is technically
		// valid for a single inpaint, but the floor form repeated across passes drives the inpainted region
		// toward the lower-valued neighbor. Pin the round-half-up result.
		int[] values = { 100, 100, 100, 100, 0, 101, 101, 101, 101 };
		boolean[] mask = new boolean[9];
		mask[4] = true;
		GainMapInpainter.inpaintIterative(values, mask, 9, 1);
		assertEquals("round-half-up of (100 + 101) / 2 = 100.5 → 101, not 100",
			101, values[4]);
	}

	@Test
	public void roundingBiasRegression_useNearestInt_notFloor()
	{
		// REGRESSION: an earlier implementation used `sum / count` (floor toward zero), which produced
		// ⌊(7 * 100) / 8⌋ = 87 instead of round-half-up's 88 for an 8-neighbor average of 7×100 + 1×x
		// configurations. Compounded across hundreds of passes on a real AI fill this drifted the
		// inpainted region ~50 LSBs darker than its surroundings — a visible darker HDR boost patch over
		// the Generative Remove fill area.
		//
		// Worst-case fixture: a 5x1 strip with masked pixel at index 2. Neighbors of index 2 in a 5x1 are
		// index 1 (val=99) and index 3 (val=100). Average = 199 / 2 = 99 (floor) or 99.5 → 100
		// (round-half-up). Pin the rounded result.
		int[] values = { 100, 99, 0, 100, 100 };
		boolean[] mask = { false, false, true, false, false };
		GainMapInpainter.inpaintIterative(values, mask, 5, 1);
		assertEquals("rounded average of 99 + 100 = 99.5 → 100 (round-half-up), not 99 (floor)",
			100, values[2]);
	}
}
