package com.cropcenter.crop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cropcenter.model.SelectionPoint;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Tests for CropEngine's pure-math helpers — specifically rotatedSelectionMidpoint, which is the public API consumers
 * reach for and indirectly exercises the now-private selectionMidpoint logic. Both feed the auto-rotate / auto-center
 * flow that lays the crop on a user-painted selection. A regression in the bbox math, the rotation application order,
 * or the single-point pixel-snap would mis-place the crop center away from the user-marked feature.
 */
public final class CropEngineTest
{
	private static final float TOL = 1e-3f;

	@Test
	public void multiPointBboxMidpointAtZeroRotation()
	{
		// 3 points forming an asymmetric triangle: (10, 10), (20, 30), (40, 20). AABB is x=[10..40], y=[10..30]
		// → midpoint (25, 20).
		List<SelectionPoint> points = Arrays.asList(
			new SelectionPoint(10f, 10f), new SelectionPoint(20f, 30f), new SelectionPoint(40f, 20f));
		float[] mid = CropEngine.rotatedSelectionMidpoint(points, 100, 100, 0f);
		assertEquals(25f, mid[0], TOL);
		assertEquals(20f, mid[1], TOL);
	}

	@Test
	public void rotationAppliedBeforeBboxComputation()
	{
		// 90° clockwise rotation around image center (50, 50). A point at (50, 0) — top- edge midpoint — should
		// rotate to (100, 50) (right-edge midpoint). Verify the rotated single-point output reflects this
		// (pixel-snapped to floor + 0.5).
		List<SelectionPoint> points = Collections.singletonList(new SelectionPoint(50f, 0f));
		float[] mid = CropEngine.rotatedSelectionMidpoint(points, 100, 100, 90f);
		// floor(100) + 0.5 = 100.5 — but float precision on cos(90°) ≠ exactly 0 means the rotated x lands very
		// close to 100. floor() then gives either 99 or 100 by rounding direction. Accept either pixel-snapped
		// value to keep the test stable across float-precision deltas.
		assertEquals(0f, mid[0] - (float) Math.floor(mid[0]), 0.5001f);
		assertTrue("rotated x near 100 (got " + mid[0] + ")", Math.abs(mid[0] - 100.5f) < 1.5f);
		// Y at 90° on (50, 0) rotated around (50, 50) lands near 50 — pixel-snapped to 50.5.
		assertEquals(50.5f, mid[1], 1.0f);
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
	public void rotationByZeroEqualsRawMidpointSinglePoint()
	{
		// Single-point branch pixel-snaps to floor + 0.5. With rotation=0, point (10.7, 20.3) stays put; the
		// pixel-snap converts to (10.5, 20.5).
		List<SelectionPoint> points = Collections.singletonList(new SelectionPoint(10.7f, 20.3f));
		float[] mid = CropEngine.rotatedSelectionMidpoint(points, 100, 100, 0f);
		assertEquals(10.5f, mid[0], TOL);
		assertEquals(20.5f, mid[1], TOL);
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

	@Test
	public void multiPointMidpointDiffersFromSinglePointSnap()
	{
		// Sanity: a 2-point bbox midpoint should NOT pixel-snap. Use input that produces a non-half-integer
		// midpoint and assert it survives unrounded.
		List<SelectionPoint> points = Arrays.asList(
			new SelectionPoint(0.0f, 0.0f), new SelectionPoint(7.0f, 11.0f));
		float[] mid = CropEngine.rotatedSelectionMidpoint(points, 100, 100, 0f);
		assertEquals(3.5f, mid[0], TOL);
		assertEquals(5.5f, mid[1], TOL);
		// Verify these are NOT the floor+0.5 snap output (which would be (0.5, 0.5) for the single-point branch
		// on the first input).
		assertNotEquals(0.5f, mid[0], TOL);
	}
}
