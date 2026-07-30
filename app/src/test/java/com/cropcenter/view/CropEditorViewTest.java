package com.cropcenter.view;

import static org.junit.Assert.assertEquals;

import com.cropcenter.model.SelectionPoint;

import org.junit.Test;

import java.util.List;

/**
 * Tests for the static helpers inside CropEditorView. The view itself depends on Android Bitmap / Canvas / gesture
 * machinery that's awkward to drive from a JUnit 4 test, but the snap-axis math and the long-press removal hit-test
 * are pure and package-private exactly so they can be exercised directly.
 *
 * snapAxisPreservingTies: snaps centerX / centerY to the parity-valid pixel grid (even dim → integer center; odd dim
 * → half-integer) but PRESERVES the input when both candidate snap targets are equidistant. Tie-preservation prevents
 * a SELECT-mode-placed selection point at half-integer P+0.5 from shifting 0.5 px after a no-op MOVE-mode pan
 * (Math.round always biases half-up, which breaks alignment between the grid central line and the selection-point
 * marker).
 *
 * removalTargetIndex: the onLongPress removal target must be the NEAREST point strictly within the touch threshold —
 * removal is a destructive edit feeding autoComputeFromPoints, so deleting a point the user didn't aim at silently
 * moves the crop. Note the pin covers onLongPress semantics only; onTap's inline hit-test removes the FIRST
 * in-threshold point in list order, and unifying the two is a separate spec-first decision.
 */
public final class CropEditorViewTest
{
	private static final float EPSILON = 0.0001f;

	@Test
	public void removalTargetPicksNearestWithinThresholdAndRejectsAtBoundary()
	{
		// Two points inside the hit radius: the NEAREST index wins regardless of list order. The list puts the
		// farther point FIRST so a first-match-wins regression (onTap's divergent inline semantics) fails here.
		List<SelectionPoint> points = List.of(
			new SelectionPoint(20f, 0f),  // dist 20 — within threshold but not nearest
			new SelectionPoint(5f, 0f));  // dist 5 — nearest
		assertEquals("nearest point wins over list order", 1,
			CropEditorView.removalTargetIndex(points, 0f, 0f, 30f));
		// Strict boundary: a point at EXACTLY the threshold distance is a miss; just inside is a hit. A `<=`
		// flip on the threshold comparison fails the first assertion.
		List<SelectionPoint> boundary = List.of(new SelectionPoint(30f, 0f));
		assertEquals("dist == threshold must not remove", -1,
			CropEditorView.removalTargetIndex(boundary, 0f, 0f, 30f));
		assertEquals("just inside the threshold is a hit", 0,
			CropEditorView.removalTargetIndex(boundary, 0f, 0f, 30.001f));
	}

	@Test
	public void removalTargetReturnsMinusOneOnEmptyList()
	{
		// Long-press with no points placed: no target, no exception — the caller skips the removal branch.
		assertEquals(-1, CropEditorView.removalTargetIndex(List.of(), 100f, 100f, 30f));
	}

	@Test
	public void snapAxisPreservingTiesEvenDimHalfIntegerCenterReturnsCenter()
	{
		// Regression: SELECT-mode tap snapped center to P+0.5; even cropW means the snap targets are P and P+1
		// (both 0.5 px away). Math.round(P+0.5) = P+1 — half-up bias would shift 0.5 px right and break the
		// grid-line / selection-dot alignment. The tie-preserving snap returns P+0.5 unchanged.
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
		// Symmetric tie-case for odd dim: integer P sits half-way between P-0.5 and P+0.5 (both 0.5 px away).
		// Tie-preserving snap must return P unchanged — a `floor(P) + 0.5` formula always biases up on this
		// input.
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
