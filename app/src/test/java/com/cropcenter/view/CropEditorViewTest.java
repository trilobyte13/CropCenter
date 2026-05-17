package com.cropcenter.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for the static helpers inside CropEditorView. The view itself depends on Android Bitmap / Canvas /
 * gesture machinery that's awkward to drive from a JUnit 4 test, but the snap-axis math is pure and
 * package-private exactly so it can be exercised directly.
 *
 * The contract being pinned: snapAxisPreservingTies snaps centerX / centerY to the parity-valid pixel grid
 * (even dim → integer center; odd dim → half-integer) but PRESERVES the input when both candidate snap
 * targets are equidistant. Tie-preservation prevents a SELECT-mode-placed selection point at half-integer
 * P+0.5 from shifting 0.5 px after a no-op MOVE-mode pan (Math.round always biases half-up, which breaks
 * alignment between the grid central line and the selection-point marker).
 */
public final class CropEditorViewTest
{
	private static final float EPSILON = 0.0001f;

	@Test
	public void snapAxisPreservingTiesEvenDimHalfIntegerCenterReturnsCenter()
	{
		// Regression: SELECT-mode tap snapped center to P+0.5; even cropW means the snap targets are P and
		// P+1 (both 0.5 px away). Math.round(P+0.5) = P+1 — half-up bias would shift 0.5 px right and
		// break the grid-line / selection-dot alignment. The tie-preserving snap returns P+0.5 unchanged.
		assertEquals(1445.5f, CropEditorView.snapAxisPreservingTies(1445.5f, 2892), EPSILON);
		assertEquals(0.5f, CropEditorView.snapAxisPreservingTies(0.5f, 100), EPSILON);
	}

	@Test
	public void snapAxisPreservingTiesEvenDimIntegerCenterReturnsCenter()
	{
		// Already at a valid snap target — return unchanged (zero shift).
		assertEquals(1446f, CropEditorView.snapAxisPreservingTies(1446f, 2892), EPSILON);
		assertEquals(0f, CropEditorView.snapAxisPreservingTies(0f, 100), EPSILON);
	}

	@Test
	public void snapAxisPreservingTiesEvenDimNearIntegerSnaps()
	{
		// Drag landed close (but not exactly half-way) to an integer — snap to that integer normally.
		assertEquals(1446f, CropEditorView.snapAxisPreservingTies(1446.3f, 2892), EPSILON);
		assertEquals(1447f, CropEditorView.snapAxisPreservingTies(1446.7f, 2892), EPSILON);
	}

	@Test
	public void snapAxisPreservingTiesOddDimHalfIntegerCenterReturnsCenter()
	{
		// Already at a valid snap target — return unchanged.
		assertEquals(1807.5f, CropEditorView.snapAxisPreservingTies(1807.5f, 3615), EPSILON);
	}

	@Test
	public void snapAxisPreservingTiesOddDimIntegerCenterReturnsCenter()
	{
		// Symmetric tie-case for odd dim: integer P sits half-way between P-0.5 and P+0.5 (both 0.5 px
		// away). The previous formula `floor(P) + 0.5` always biased up — same class of bug. Tie-preserving
		// snap returns P unchanged.
		assertEquals(1807f, CropEditorView.snapAxisPreservingTies(1807f, 3615), EPSILON);
	}

	@Test
	public void snapAxisPreservingTiesOddDimNearHalfIntegerSnaps()
	{
		// Drag landed close to a half-integer — snap to that half-integer normally.
		assertEquals(1807.5f, CropEditorView.snapAxisPreservingTies(1807.3f, 3615), EPSILON);
		assertEquals(1808.5f, CropEditorView.snapAxisPreservingTies(1808.7f, 3615), EPSILON);
	}
}
