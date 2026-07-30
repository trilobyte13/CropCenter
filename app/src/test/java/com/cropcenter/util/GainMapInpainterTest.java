package com.cropcenter.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.cropcenter.util.AiRegionDetector.AiMask;

import org.junit.Test;

/**
 * Tests for the pure-algorithm core of GainMapInpainter (the int[]/boolean[] frontier-tracked grow-from-boundary pass
 * plus the in-place 8-connected mask dilation). The Bitmap-dispatch entry point inpaintBitmap requires an Android
 * Bitmap and is exercised end-to-end by the manual graft pipeline; the algorithm itself is what carries the
 * load-bearing correctness contracts and is testable in pure JVM.
 *
 * The most important contract pinned here is the rounding behavior on integer division: the average must be `(sum +
 * count / 2) / count` (nearest-int, half-up). A plain `sum / count` (floor toward zero) biases the inpainted region 0.5
 * LSB darker per pass; over the hundreds of passes a real AI fill takes, that accumulates to a visibly darker patch in
 * the HDR boost. The roundingBiasRegression test below pins the rounding with a worst-case fixture: a uniform
 * 100-valued unmasked surround with a bias-detectable mask shape that would compound to ~50 LSBs darker under the floor
 * variant and stays at ~100 under the rounded one.
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
	public void dilateMaskRadiusTwoGrowsTwoRings()
	{
		// Production runs at DILATE_RADIUS = 2, so the multi-ring accumulation is the load-bearing case:
		// each ring must dilate from the PREVIOUS ring's output, not from the original mask. Hoisting the
		// mask.clone() snapshot out of the ring loop (a plausible perf refactor) is radius-1-identical but
		// halves production dilation — the lost second ring covers the AI-region boundary where pixel
		// diffs fade below threshold, shipping mis-targeted HDR boost in a 1-px halo around every
		// Generative Remove fill. Center pixel of a 7x7 at radius 2 must grow to exactly the 5x5
		// Chebyshev-distance-2 block.
		boolean[] mask = new boolean[49];
		mask[3 * 7 + 3] = true; // center of a 7x7
		GainMapInpainter.dilateMask(mask, 7, 7, 2);
		for (int y = 0; y < 7; y++)
		{
			for (int x = 0; x < 7; x++)
			{
				boolean expected = Math.abs(x - 3) <= 2 && Math.abs(y - 3) <= 2;
				assertEquals("(" + x + "," + y + ")", expected, mask[y * 7 + x]);
			}
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
		// No masked pixels → frontier is empty → 0 passes, no value mutation. Pin all 9 positions so a
		// regression that mutates surrounding pixels (e.g., a stray write into an unmasked neighbor during the
		// rounding-mean accumulator) doesn't slip through with only the center checked.
		int[] original = { 10, 20, 30, 40, 50, 60, 70, 80, 90 };
		int[] values = original.clone();
		boolean[] mask = new boolean[9];
		int passes = GainMapInpainter.inpaintIterative(values, mask, 3, 3);
		assertEquals(0, passes);
		for (int i = 0; i < original.length; i++)
		{
			assertEquals("index " + i + " must be unchanged", original[i], values[i]);
		}
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
		// 5×1 strip with masked indices 1..3, values 100/?/?/?/200. The fill must be monotonic non-decreasing —
		// a regression in the rounding direction would manifest as direction-dependent drift (lower-valued side
		// bleeds further than higher-valued side, breaking monotonicity). Existing rounding-bias tests pin
		// worst-case symmetric 2-neighbor / 8-neighbor averages; this test pins the asymmetric gradient case
		// where the inpainted patch SPANS a value range.
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
	public void inpaintIterativePropagatesFromSingleUnmaskedSeedAcrossField()
	{
		// All-masked field with one unmasked corner — the frontier seed only includes the masked pixels
		// adjacent to that corner. After one pass those become unmasked, then the next pass picks up their
		// neighbors. Pin that propagation reaches every pixel and that the multi-pass cascade actually fires
		// (passes >= 2 for a 3x3 with the seed at one corner).
		int[] values = new int[9];
		boolean[] mask = new boolean[9];
		for (int i = 0; i < 9; i++)
		{
			mask[i] = true;
		}
		mask[0] = false; // unmask top-left
		values[0] = 50;
		int passes = GainMapInpainter.inpaintIterative(values, mask, 3, 3);
		for (int i = 0; i < 9; i++)
		{
			assertFalse("index " + i, mask[i]);
		}
		assertTrue("should have run multiple passes", passes >= 2);
	}

	@Test
	public void roundingBiasRegressionNegativeBiasNotIntroduced()
	{
		// Stricter regression: 9-pixel strip with masked center. 4 left neighbors all 100, 4 right all 101. Sum
		// from masked-center's TWO direct neighbors only = 100 + 101 = 201, count = 2, avg = 100.5. Floor (sum
		// / count) = 100. Round-half-up ((sum + count/2) / count) = 101. Either is technically valid for a
		// single inpaint, but the floor form repeated across passes drives the inpainted region toward the
		// lower-valued neighbor. Pin the round-half-up result.
		int[] values = { 100, 100, 100, 100, 0, 101, 101, 101, 101 };
		boolean[] mask = new boolean[9];
		mask[4] = true;
		GainMapInpainter.inpaintIterative(values, mask, 9, 1);
		assertEquals("round-half-up of (100 + 101) / 2 = 100.5 → 101, not 100", 101, values[4]);
	}

	@Test
	public void roundingBiasRegressionUsesNearestIntNotFloor()
	{
		// REGRESSION GUARD against `sum / count` (floor toward zero). For a 2-neighbor average of 99 + 100 =
		// 199 with count = 2, floor(199 / 2) = 99 while round-half-up gives 100. Compounded across hundreds of
		// passes on a real AI fill the floor variant drifts the inpainted region one LSB darker than its
		// surroundings every iteration — a visible darker HDR boost patch over the Generative Remove fill area.
		// Fixture: a 5x1 strip with masked pixel at index 2. Neighbors of index 2 in a 5x1 are index 1 (val=99)
		// and index 3 (val=100). Average = 199 / 2 = 99.5 → 100 (round-half-up), 99 (floor). Pin the rounded
		// result. The complementary 100/101 case sits in roundingBiasRegressionNegativeBiasNotIntroduced above.
		int[] values = { 100, 99, 0, 100, 100 };
		boolean[] mask = { false, false, true, false, false };
		GainMapInpainter.inpaintIterative(values, mask, 5, 1);
		assertEquals("rounded average of 99 + 100 = 99.5 → 100 (round-half-up), not 99 (floor)",
			100, values[2]);
	}

	// ── scaleMask ──

	@Test
	public void scaleMaskReturnsCloneForSameDimensions()
	{
		// Same-dimensions fast path: returns src.clone(). Pin both the byte-for-byte equality AND the
		// distinct-reference contract — a regression that returned `src` directly would let a caller's later
		// mutation propagate back to the AiMask's stored array.
		boolean[] srcMask = { true, false, true, false };
		AiMask aiMask = new AiMask(srcMask, 2, 2, 4, 2);
		boolean[] result = GainMapInpainter.scaleMask(aiMask, 2, 2);
		assertArrayEquals(srcMask, result);
		assertNotSame("clone, not alias", srcMask, result);
	}

	@Test
	public void scaleMaskUpscales2xWithNearestNeighbor()
	{
		// 2×2 source → 4×4 target. Each source pixel becomes a 2×2 block. Source TL=true → target [0,0], [0,1],
		// [1,0], [1,1] all true. Source TR=false → target [0,2..3], [1,2..3] all false. Etc.
		boolean[] srcMask = { true, false, false, true };  // TL=T, TR=F, BL=F, BR=T
		AiMask aiMask = new AiMask(srcMask, 2, 2, 4, 2);
		boolean[] result = GainMapInpainter.scaleMask(aiMask, 4, 4);
		boolean[] expected = {
			true,  true,  false, false, true,  true,  false, false,
			false, false, true,  true, false, false, true,  true,
		};
		assertArrayEquals(expected, result);
	}

	@Test
	public void scaleMaskDownscalesByNearestNeighbor()
	{
		// 4×4 source → 2×2 target. Each target cell samples the source at (y*srcH/dstH, x*srcW/dstW). For
		// target (0,0): sourceX=0, sourceY=0 → src[0] = true. For target (0,1): sourceX=2, sourceY=0 → src[2] =
		// true. For target (1,0): sourceX=0, sourceY=2 → src[8] = false. For target (1,1): sourceX=2, sourceY=2
		// → src[10] = false.
		boolean[] srcMask = {
			true,  true,  true,  true, true,  true,  true,  true,
			false, false, false, false, false, false, false, false,
		};
		AiMask aiMask = new AiMask(srcMask, 4, 4, 4, 8);
		boolean[] result = GainMapInpainter.scaleMask(aiMask, 2, 2);
		assertArrayEquals(new boolean[] { true, true, false, false }, result);
	}

	@Test
	public void scaleMaskNonUniformAspectRatioChange()
	{
		// 100×200 → 50×50. Width halves, height fourths. Each target cell samples the right source pixel via
		// nearest-neighbor. Plant a single source pixel and verify only one target pixel is set.
		int srcW = 100;
		int srcH = 200;
		boolean[] srcMask = new boolean[srcW * srcH];
		srcMask[50 * srcW + 50] = true;  // source pixel at (50, 50)
		AiMask aiMask = new AiMask(srcMask, srcW, srcH, 4, 1);
		boolean[] result = GainMapInpainter.scaleMask(aiMask, 50, 50);
		// Invariant: nearest-neighbor downsampling LOSES isolated source pixels that fall between sample rows.
		// Target row r samples source row r*srcH/dstH = r*4, so rows 12 and 13 sample source rows 48 and 52 —
		// no target row samples row 50, where the only true pixel sits. The output must be all-false; a
		// smoothing/dilating rescale that "rescued" the pixel would break the mask's pixel-exact registration
		// with the gain map.
		int trueCount = 0;
		for (boolean cell : result)
		{
			if (cell)
			{
				trueCount++;
			}
		}
		assertEquals("the isolated source pixel falls between target sample rows; output is all-false",
			0, trueCount);
	}

	@Test
	public void scaleMaskUsesLongArithmeticForLargeIndexMultiply()
	{
		// Pin the (long) cast on `y * srcHeight`. Inputs designed so the max y * srcH genuinely overflows int:
		// srcH = 1_000_000 and target rows go up to 4999, so max `y * srcH` = 4999 * 1_000_000 = 4.999e9, which
		// exceeds Integer.MAX_VALUE (2.147e9). Without the long cast, the int multiply would wrap to
		// (4_999_000_000 - 4_294_967_296) = 704_032_704, then divide by 5000 to yield sourceY = 140_806 instead
		// of the correct 999_800. The pre-planted source pixel sits at row 999_800; a regression to plain int
		// arithmetic would miss it entirely and the target row 4999 would read false.
		int srcW = 1;
		int srcH = 1_000_000;
		boolean[] srcMask = new boolean[srcW * srcH];
		srcMask[999_800] = true;
		AiMask aiMask = new AiMask(srcMask, srcW, srcH, 4, 1);
		boolean[] result = GainMapInpainter.scaleMask(aiMask, 1, 5000);
		// target row r maps back to source row (long) r * 1_000_000 / 5000 = r * 200. Source row 999_800 lands
		// at target row 999_800 / 200 = 4999 exactly.
		assertTrue("long-arithmetic must locate src row 999_800 at target row 4999; int wrap would "
			+ "produce sourceY=140_806 and miss the planted pixel", result[4999]);
		// Sanity: 9 other target rows should be false (the source has a single true bit far from where they
		// sample).
		assertFalse("target row 0 samples src row 0 (false)", result[0]);
		assertFalse("target row 2000 samples src row 400000 (false)", result[2000]);
	}
}
