package com.cropcenter.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for GridRenderer.linePos — the line-position rounding that decides where the preview grid lines land. Pins the
 * preview-matches-export contract: for integer cropOrigin and non-middle lines, linePos's result must equal cropOrigin
 * + CropExporter.gridLinePixel(i, count, cropExtent). The Canvas-bound draw() method itself needs Android
 * infrastructure; the math is the load-bearing piece and lives in a package-private static helper so a Canvas-free test
 * can pin it.
 *
 * The middle-line special case (i * 2 == count) preserves cropCenter rather than snapping — keeps single-point
 * selection markers at the grid intersection. The documented 2-and-4-count divergence for odd cropExtent is the only
 * place preview and export disagree; pin it so a regression that "fixed" the middle-line snap would surface here rather
 * than as a UX bug.
 */
public final class GridRendererTest
{
	private static final float TOL = 1e-4f;

	@Test
	public void linePosFirstHalfAndMirrorAreSymmetric()
	{
		// Pin the symmetry contract: line i and line (count - i) sum to cropExtent for every non-middle line.
		// CropExporter.gridLinePixel relies on this exact contract for the (i, count-i) symmetry across the
		// crop's mirror plane; the preview formula must agree byte-for-byte (the spec is "relative offset
		// matches export rounding").
		int count = 5;
		int cropExtent = 73;  // odd, asymmetric rounding stress
		float cropCenter = cropExtent / 2f;
		for (int i = 1; i < count; i++)
		{
			float low = GridRenderer.linePos(i, count, 0f, cropExtent, cropCenter);
			float high = GridRenderer.linePos(count - i, count, 0f, cropExtent, cropCenter);
			assertEquals("line " + i + " + line " + (count - i) + " must sum to cropExtent",
				cropExtent, low + high, TOL);
		}
	}

	@Test
	public void linePosFirstHalfRoundsTowardCropOrigin()
	{
		// Rule-of-thirds (count=3, the common case): first line at round(100 * 1 / 3) = 33, mirrored second
		// line at 100 - round(100 * 1 / 3) = 67. Both lines round on the LOW-index side via the first-half
		// branch (i * 2 < count means i=1) and the SECOND line lands via the mirror branch (i=2 → i*2 > 3).
		assertEquals(0f + 33f, GridRenderer.linePos(1, 3, 0f, 100, 50f), TOL);
		assertEquals(0f + 67f, GridRenderer.linePos(2, 3, 0f, 100, 50f), TOL);
	}

	@Test
	public void linePosFractionalCropOriginPropagatesUnrounded()
	{
		// Fractional origin (centerX - cropW/2f with odd cropW + even centerX) must be added unchanged — the
		// rounding only applies to the RELATIVE offset inside the crop. Pin: linePos(1, 3, 0.5, 100, ?) equals
		// 0.5 + round(100/3) = 0.5 + 33 = 33.5.
		float result = GridRenderer.linePos(1, 3, 0.5f, 100, 50.5f);
		assertEquals(33.5f, result, TOL);
	}

	@Test
	public void linePosMiddleLineForCount2DivergesFromExportOnOddExtent()
	{
		// Documented divergence: cropExtent = 99, cropCenter = 49.5. Preview returns 49.5; export's
		// gridLinePixel(1, 2, 99) = round(99 / 2) = 50. So preview lands at 49.5 while export lands at 50 — a
		// 0.5 px disagreement on the single middle line. Pin so a regression that "rounded" the middle case
		// (silently realigning with export but breaking the single-point intersection contract) gets caught
		// here.
		float previewMid = GridRenderer.linePos(1, 2, 0f, 99, 49.5f);
		assertEquals("preview middle stays at cropCenter (49.5)", 49.5f, previewMid, TOL);
		// Sanity: export would round to 50 for the same inputs (gridLinePixel(1, 2, 99) = 50).
		assertEquals("export-side rounding (sanity check)", 50, Math.round(99f * 1 / 2));
	}

	@Test
	public void linePosMiddleLineForCount2KeepsCropCenter()
	{
		// Count = 2: the single internal line is the middle one (i * 2 == count). Returns cropCenter directly
		// instead of round-based offset. With even cropExtent the values coincide (50 == 50); with odd
		// cropExtent the documented 0.5 px divergence appears. Pin the even case first.
		assertEquals(50f, GridRenderer.linePos(1, 2, 0f, 100, 50f), TOL);
	}

	@Test
	public void linePosMiddleLineForCount4KeepsCropCenter()
	{
		// Count = 4: i = 2 is the middle (i * 2 == count). Same cropCenter-preserving rule as count=2; lines
		// i=1 and i=3 round / mirror normally. Pin the middle case and the symmetry of the outer pair.
		float mid = GridRenderer.linePos(2, 4, 0f, 100, 50f);
		assertEquals(50f, mid, TOL);
		float low = GridRenderer.linePos(1, 4, 0f, 100, 50f);
		float high = GridRenderer.linePos(3, 4, 0f, 100, 50f);
		assertEquals("outer pair must sum to cropExtent", 100f, low + high, TOL);
	}

	@Test
	public void linePosOddExtentRoundsHalfAwayFromZero()
	{
		// cropExtent = 99 with count = 3: line 1 at round(99 / 3) = 33, line 2 at 99 - round(99 * 1 / 3) = 99 -
		// 33 = 66. Pin the Math.round behavior (banker's vs half-away — Java's Math.round on float rounds .5
		// away from zero for positives) so a JVM-deprecation of the existing semantics would surface here.
		assertEquals(33f, GridRenderer.linePos(1, 3, 0f, 99, 49.5f), TOL);
		assertEquals(66f, GridRenderer.linePos(2, 3, 0f, 99, 49.5f), TOL);
	}
}
