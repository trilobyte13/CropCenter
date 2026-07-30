package com.cropcenter.crop;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for CropExporter.gridLinePixel — the package-private static helper that picks which output pixel each grid line
 * lands on for the baked-in export. The whole reason the function exists (instead of a plain `Math.round((double) dim *
 * i / count)`) is to keep `(i, count − i)` pairs spatially symmetric around `dim / 2`. Java's Math.round is half-up,
 * which would break that symmetry at exact half-integer positions — count=4, dim=10 produces raw values 2.5 and 7.5
 * that BOTH round half-up to 3 and 8 respectively, giving an asymmetric (3, 8) pair around the midpoint at 5 instead of
 * the symmetric (3, 7). The mirror trick (round one side, subtract from dim for the other) fixes this. A regression
 * that drops the trick produces visibly off-centre baked-in grids that diverge from the preview, which the user sees as
 * the grid lines "slipping" toward one edge of the cropped image.
 */
public final class CropExporterGridLineTest
{
	@Test
	public void firstHalfLinesUseDirectRounding()
	{
		// Sanity check that the first-half branch (i * 2 <= count) computes the natural rounded position.
		// count=10 dim=100: line 3 sits at 30 (raw 30.0, no rounding needed); line 5 at 50 (the midpoint).
		assertEquals(30, CropExporter.gridLinePixel(3, 10, 100));
		assertEquals(50, CropExporter.gridLinePixel(5, 10, 100));
	}

	@Test
	public void mirrorPairsAreSymmetricAcrossDimWhenHalfIntegerSensitive()
	{
		// count=4, dim=10: raw positions are 2.5, 5.0, 7.5. A naive Math.round half-up would give (3, 5, 8) —
		// the 2.5 and 7.5 pair would NOT mirror around dim/2 = 5 because 3 + 8 == 11 ≠ 10. The mirror trick
		// computes (3, 5, 10−3) = (3, 5, 7), and 3 + 7 == 10 == dim. Pin both the pair sum and the individual
		// positions so a regression that "simplifies" gridLinePixel to a one-liner is caught.
		assertEquals("line 1 at count=4 dim=10 should be 3", 3, CropExporter.gridLinePixel(1, 4, 10));
		assertEquals("line 3 at count=4 dim=10 should mirror line 1 around dim/2",
			7, CropExporter.gridLinePixel(3, 4, 10));
		assertEquals("mirror pair (1, 3) must sum to dim",
			10, CropExporter.gridLinePixel(1, 4, 10) + CropExporter.gridLinePixel(3, 4, 10));
	}

	@Test
	public void mirrorPairsAreSymmetricForAllNonMiddleIndices()
	{
		// Stress the symmetry invariant across a range of (count, dim) inputs that hit half-integer positions.
		// For every non-middle index i, result(i) + result(count − i) must equal dim — the export's grid lines
		// stay visually centred regardless of count/dim parity.
		int[][] cases =
		{
			{ 4, 10 },   // half-integer trigger (the canonical break case)
			{ 6, 14 },   // half-integer trigger at i ∈ {1, 5}
			{ 8, 12 },   // mixed integer + half-integer positions
			{ 3, 100 },  // odd count — every i has a unique mirror
			{ 9, 17 },   // odd count + odd dim — extreme parity mismatch
			{ 10, 200 }, // large count + dim — confirm no integer overflow
		};
		for (int[] testCase : cases)
		{
			int count = testCase[0];
			int dim = testCase[1];
			for (int i = 1; i < count; i++)
			{
				int mirror = count - i;
				if (i == mirror)
				{
					// Self-mirror — single middle line, no pair to check.
					continue;
				}
				int linePos = CropExporter.gridLinePixel(i, count, dim);
				int mirrorPos = CropExporter.gridLinePixel(mirror, count, dim);
				assertEquals("(" + i + ", " + mirror + ") pair at count=" + count + " dim=" + dim
					+ " must sum to dim", dim, linePos + mirrorPos);
			}
		}
	}

	@Test
	public void singleLineGridProducesValidIndex()
	{
		// count=2: only one internal line at i=1, sitting at the midpoint. dim=10 → position 5; dim=11 →
		// position 6 (round half-up). Defensive — the count=2 case is degenerate but legal input from a
		// rule-of-thirds-style grid with a single internal divider.
		assertEquals(5, CropExporter.gridLinePixel(1, 2, 10));
		assertEquals(6, CropExporter.gridLinePixel(1, 2, 11));
	}
}
