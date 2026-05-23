package com.cropcenter.crop;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.cropcenter.model.CropState;
import com.cropcenter.model.SelectionPoint;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Tests for CropEngine's pure-math helpers — rotatedSelectionMidpoint (public) plus the package-private
 * selectionMidpoint it delegates to. Both feed the auto-rotate / auto-center flow that lays the crop on a
 * user-painted selection. A regression in the bbox math, the rotation application order, or the single-point
 * pixel-snap would mis-place the crop center away from the user-marked feature.
 */
public final class CropEngineTest
{
	private static final float TOL = 1e-3f;

	// ── selectionMidpoint (the un-rotated AABB-midpoint helper) ──

	@Test
	public void selectionMidpointSinglePointReturnsPointCoordinates()
	{
		// Single-point branch: returns the point's coords as-is (no pixel snap — that's the rotated wrapper's
		// job). Pin so a regression that always averaged min/max would round (15.3, 27.8) to (15.3, 27.8)
		// trivially but a regression that snapped to integer would surface here.
		List<SelectionPoint> points = Collections.singletonList(new SelectionPoint(15.3f, 27.8f));
		float[] mid = CropEngine.selectionMidpoint(points);
		assertEquals(15.3f, mid[0], TOL);
		assertEquals(27.8f, mid[1], TOL);
	}

	@Test
	public void selectionMidpointTwoPointsAveragesCoordinates()
	{
		// Two points → midpoint is the average. Pin the count==2 → AABB-midpoint branch (a regression that
		// kept the count==1 branch active for size 2 would return (10, 20) instead of (20, 30)).
		List<SelectionPoint> points = Arrays.asList(
			new SelectionPoint(10f, 20f), new SelectionPoint(30f, 40f));
		float[] mid = CropEngine.selectionMidpoint(points);
		assertEquals(20f, mid[0], TOL);
		assertEquals(30f, mid[1], TOL);
	}

	@Test
	public void selectionMidpointAsymmetricMultiPointReturnsAabbMidpoint()
	{
		// 3 asymmetric points: x ∈ {10, 30, 90} → AABB x=[10..90] → midX = 50; y ∈ {50, 10, 70} → midY = 40.
		// Pin that the function uses AABB midpoint (cheap, predictable) and NOT a true centroid (which
		// would return (43.3, 43.3) here).
		List<SelectionPoint> points = Arrays.asList(
			new SelectionPoint(10f, 50f), new SelectionPoint(30f, 10f), new SelectionPoint(90f, 70f));
		float[] mid = CropEngine.selectionMidpoint(points);
		assertEquals("AABB midX, not centroid", 50f, mid[0], TOL);
		assertEquals("AABB midY, not centroid", 40f, mid[1], TOL);
	}

	@Test
	public void selectionMidpointDegenerateAllSamePointReturnsThatPoint()
	{
		// Edge case: multiple points all at the same location. AABB collapses to a point; midpoint should
		// equal the shared coordinates. count == 5 takes the multi-point branch but minX == maxX so the
		// average degenerates.
		SelectionPoint shared = new SelectionPoint(42f, 17f);
		List<SelectionPoint> points = Arrays.asList(shared, shared, shared, shared, shared);
		float[] mid = CropEngine.selectionMidpoint(points);
		assertEquals(42f, mid[0], TOL);
		assertEquals(17f, mid[1], TOL);
	}

	// ── rotatedSelectionMidpoint (the public wrapper that adds rotation + pixel snap) ──

	@Test
	public void autoComputeFromPointsSyncsAnchorToPostRecomputeCenter()
	{
		// Pin the round-3 fix: autoComputeFromPoints must call setAnchor(centerX, centerY) AFTER
		// recomputeCrop completes, so a subsequent Move-mode rotation or AR change reads the
		// just-placed center rather than a stale anchor from load time or a prior pan. A regression
		// that drops the post-recompute setAnchor would silently snap the crop back toward the
		// stale anchor on the first rotation after a selection-driven recenter.
		CropState state = new CropState();
		// Place an off-center selection so the recompute would visibly shift the anchor.
		state.addSelectionPoint(new SelectionPoint(750f, 250f));
		// Pre-condition: anchor starts at default (0, 0) — not at the selection point.
		assertEquals("test fixture pre-condition: anchor X starts at 0", 0f, state.getAnchorX(), 0f);
		assertEquals("test fixture pre-condition: anchor Y starts at 0", 0f, state.getAnchorY(), 0f);
		CropEngine.autoComputeFromPoints(state);
		// Post-condition: anchor matches the post-recompute center exactly, NOT the pre-recompute
		// midpoint (rotatedSelectionMidpoint output). The setAnchor call reads state.getCenterX/Y
		// directly so the assertion uses the same source.
		assertEquals("anchor X must sync to post-recompute centerX",
			state.getCenterX(), state.getAnchorX(), 0f);
		assertEquals("anchor Y must sync to post-recompute centerY",
			state.getCenterY(), state.getAnchorY(), 0f);
	}

	@Test
	public void rotationAppliedBeforeBboxComputation()
	{
		// 90° clockwise rotation around image center (50, 50): point (50, 0) — top-edge midpoint — rotates
		// to exactly (100, 50) (right-edge midpoint) under Canvas's Y-down convention. RotationMath's
		// double-precision cos(π/2) ≈ 6.12e-17 contributes ~3e-15 to the Y component, which rounds back
		// to exactly 50.0 when cast to float (float epsilon at 50 is ~6e-6, far larger). The pixel-snap
		// is `floor + 0.5` → (100.5, 50.5) deterministically. Pin those exact values; a regression that
		// swapped sin/cos signs or applied rotation after the AABB would land elsewhere.
		List<SelectionPoint> points = Collections.singletonList(new SelectionPoint(50f, 0f));
		float[] mid = CropEngine.rotatedSelectionMidpoint(points, 100, 100, 90f);
		assertEquals("rotated X pixel-snapped to right-edge midpoint", 100.5f, mid[0], 1e-4f);
		assertEquals("rotated Y pixel-snapped to image-center Y", 50.5f, mid[1], 1e-4f);
	}

	@Test
	public void rotationByZeroEqualsRawMidpoint()
	{
		// rotation=0 should produce the same output as the un-rotated midpoint (modulo the single-point pixel
		// snap). Use 2 points so the snap branch doesn't fire.
		List<SelectionPoint> points = Arrays.asList(new SelectionPoint(10f, 20f), new SelectionPoint(30f, 40f));
		float[] zero = CropEngine.rotatedSelectionMidpoint(points, 100, 100, 0f);
		assertEquals(20f, zero[0], TOL);
		assertEquals(30f, zero[1], TOL);
	}

	@Test
	public void singlePointSnapToPixelCenter()
	{
		// Single-point input bypasses the AABB averaging and snaps to pixel center (floor + 0.5). Pin down the
		// snap behavior.
		List<SelectionPoint> points = Collections.singletonList(new SelectionPoint(15.3f, 27.8f));
		float[] mid = CropEngine.rotatedSelectionMidpoint(points, 200, 200, 0f);
		assertEquals(15.5f, mid[0], TOL);
		assertEquals(27.5f, mid[1], TOL);
	}

	@Test
	public void subEpsilonRotationStaysIdentityViaRotationMath()
	{
		// RotationMath.rotate has an identity fast-path below ROTATION_EPSILON (0.005°).
		// rotatedSelectionMidpoint relies on it — so a 0.001° rotation should produce the same numeric result
		// as rotation = 0 on multi-point inputs.
		List<SelectionPoint> points = Arrays.asList(new SelectionPoint(10f, 20f), new SelectionPoint(30f, 40f));
		float[] zero = CropEngine.rotatedSelectionMidpoint(points, 100, 100, 0f);
		float[] sub = CropEngine.rotatedSelectionMidpoint(points, 100, 100, 0.001f);
		assertEquals(zero[0], sub[0], 0f);
		assertEquals(zero[1], sub[1], 0f);
	}
}
