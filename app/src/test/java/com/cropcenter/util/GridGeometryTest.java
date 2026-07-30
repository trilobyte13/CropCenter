package com.cropcenter.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for GridGeometry.mirroredLinePos, the single chokepoint shared by the on-screen GridRenderer and the
 * export-side CropExporter so a previewed grid lands byte-for-byte at the same pixel positions in the saved image. The
 * mirroring branch is the key invariant: without it, an off-centre count-2 grid on odd dims drifts by ±1 px between a
 * renderer that rounds Math.round((double) dim * i / count) directly (rounding bias picks a side) and an exporter that
 * mirrors. Each property here pins a behaviour a "looks reasonable" rewrite could silently regress.
 */
public final class GridGeometryTest
{
	@Test
	public void mirroredLinePosCount1DegenerateNoOpReturnsDim()
	{
		// count=1 is the "single-cell grid, no inner lines" degenerate case. Production callers ensure count >=
		// 2 before invoking the function (GridRenderer / CropExporter both iterate from i=1 to count-1, so
		// count=1 produces zero iterations and the function is never called). Pin the math for the
		// unlikely-but-defensive case where a future caller DOES pass count=1: i=1 triggers the mirror branch
		// (1*2 > 1) and computes dim - round(dim * 0 / 1) = dim. A future defensive `if (count < 2) ...` guard
		// could change this — pin the current behavior so callers that rely on the passthrough see the change.
		assertEquals(100, GridGeometry.mirroredLinePos(1, 1, 100));
		assertEquals(0, GridGeometry.mirroredLinePos(1, 1, 0));
	}

	@Test
	public void mirroredLinePosCount2OnEvenDimLandsAtExactCentre()
	{
		// count=2 on even dim: the single inner line sits exactly at dim/2 with no rounding concern — baseline
		// for the mirror branch's behaviour on the "easy" case.
		assertEquals(50, GridGeometry.mirroredLinePos(1, 2, 100));
		assertEquals(500, GridGeometry.mirroredLinePos(1, 2, 1000));
	}

	@Test
	public void mirroredLinePosCount2OnOddDimLandsAtHalfUpMidline()
	{
		// count=2 on odd dim: the single inner line lands at ceil(dim/2) because Java's Math.round uses half-up
		// rounding (ties go to positive infinity). dim=101 → Math.round(101.0 * 1 / 2) = Math.round(50.5) = 51.
		// The mirror branch doesn't fire here (i*2=2, count=2, 2>2 is false), so this directly exercises the
		// non-mirror branch's rounding. Pinning this catches a regression that swaps in BigDecimal HALF_EVEN
		// (banker's rounding) which would return 50 and shift the grid line by ±1 px on every odd dim, visible
		// to the user as a slight grid drift between renderer and exporter on phones whose preview pixels are
		// an odd count.
		assertEquals(51, GridGeometry.mirroredLinePos(1, 2, 101));
	}

	@Test
	public void mirroredLinePosCount3OnEvenDimSplitsEvenly()
	{
		// count=3 on dim divisible by 3: two inner lines at exactly dim/3 and 2*dim/3.
		assertEquals(100, GridGeometry.mirroredLinePos(1, 3, 300));
		assertEquals(200, GridGeometry.mirroredLinePos(2, 3, 300));
	}

	@Test
	public void mirroredLinePosCount3SymmetryHoldsOnNonDivisibleDim()
	{
		// count=3 on dim=100: 1/3 ≈ 33.33, 2/3 ≈ 66.67. The mirror branch fires for i=2 (i*2=4 > 3), computing
		// 100 - round(100 * 1 / 3) = 100 - 33 = 67. Without the mirror branch, i=2 would give round(100 * 2 /
		// 3) = round(66.66666) = 67 too — same result on this dim. Pick dim where the bias diverges: dim=10,
		// 1/3 = 3.33 → 3, 2/3 = 6.66 → 7 by half-up but 7 by HALF_EVEN too for 6.667. dim=7: 1/3 = 2.33 → 2,
		// 2/3 = 4.67 → 5. mirror: 7 - 2 = 5. Same. dim=8 gives 1/3 = 2.67 → 3, 2/3 = 5.33 → 5 via direct, 8 - 3
		// = 5 via mirror. The mirror invariant holds either way. Assert the SYMMETRY explicitly: pos(1) +
		// pos(2) == dim for every dim across a range that exercises both rounding biases.
		for (int dim = 5; dim <= 200; dim++)
		{
			int p1 = GridGeometry.mirroredLinePos(1, 3, dim);
			int p2 = GridGeometry.mirroredLinePos(2, 3, dim);
			assertEquals("count=3, dim=" + dim + ": p1 + p2 must equal dim", dim, p1 + p2);
		}
	}

	@Test
	public void mirroredLinePosCount4SymmetryHoldsAcrossInnerLines()
	{
		// count=4 has 3 inner lines at 1/4, 2/4, 3/4. The mirror branch fires for i=3 (i*2=6 > 4), leaving i=1
		// on the direct branch and i=2 on the boundary (i*2=4 NOT > 4 → direct). Pin the invariant pos(1) +
		// pos(3) == dim across a range of dims that hit different rounding cases.
		for (int dim = 8; dim <= 400; dim++)
		{
			int p1 = GridGeometry.mirroredLinePos(1, 4, dim);
			int p3 = GridGeometry.mirroredLinePos(3, 4, dim);
			assertEquals("count=4, dim=" + dim + ": p1 + p3 must equal dim", dim, p1 + p3);
		}
	}

	@Test
	public void mirroredLinePosCount9MidlineLandsAtExactCentre()
	{
		// count=9 on dim divisible by 9: position of the centre line (i=4 or i=5, the middle pair straddling
		// the midline). 4/9 of 90 = 40, 5/9 of 90 = 50 — equidistant from the 45 midline.
		assertEquals(40, GridGeometry.mirroredLinePos(4, 9, 90));
		assertEquals(50, GridGeometry.mirroredLinePos(5, 9, 90));
	}

	@Test
	public void mirroredLinePosLargeCountSymmetryHoldsAcrossEveryPair()
	{
		// Exercise a non-trivial real-world setting: 8x8 grid on a 4K-ish dim, every (i, 8-i) pair must be
		// equidistant from the midline. Catches a regression that changes the rounding mode or breaks the
		// mirror branch's `count - i` indexing.
		int dim = 3840;
		int count = 8;
		for (int i = 1; i < count; i++)
		{
			int p = GridGeometry.mirroredLinePos(i, count, dim);
			int pMirror = GridGeometry.mirroredLinePos(count - i, count, dim);
			assertEquals("count=8, dim=3840, i=" + i + ": p + mirror must equal dim", dim, p + pMirror);
		}
	}

	@Test
	public void mirroredLinePosOnZeroDimReturnsZero()
	{
		// dim=0 — no real-world caller produces this, but the math should degenerate cleanly to 0 rather than
		// throw. Pin so a defensive `if (dim <= 0) ...` doesn't break a future caller that relies on the
		// 0-passthrough. Exercises BOTH branches: i*2 ≤ count (direct branch) and i*2 > count (mirror branch
		// with `dim - round(dim * (count-i) / count)`), so a regression that only broke the mirror branch's
		// `dim - mirror` arithmetic on dim=0 (returning -1 from 0 - round(0)) would surface here too.
		assertEquals("direct branch (i*2 <= count)", 0, GridGeometry.mirroredLinePos(1, 3, 0));
		assertEquals("mirror branch (i*2 > count)", 0, GridGeometry.mirroredLinePos(2, 3, 0));
		assertEquals("direct branch deeper i", 0, GridGeometry.mirroredLinePos(4, 9, 0));
		assertEquals("mirror branch deeper i", 0, GridGeometry.mirroredLinePos(5, 9, 0));
	}
}
