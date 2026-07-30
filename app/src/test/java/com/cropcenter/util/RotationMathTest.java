package com.cropcenter.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Tests the 2D rotation math used everywhere a screen / image coordinate has to round-trip through a non-zero rotation.
 * The forward / inverse pair is expected to compose to the identity within float precision; the sub-epsilon
 * short-circuit is expected to leave the input untouched. Both invariants are easy to break with a sign flip in the
 * matrix.
 */
public final class RotationMathTest
{
	private static final float TOL = 1e-3f;

	@Test
	public void atPivotIsAlwaysFixed()
	{
		// Rotating the pivot itself by any angle yields the pivot — basic property of any rotation matrix.
		// Catches a regression where someone accidentally adds the pivot AGAIN at the end (would make the
		// result 2× pivot).
		float[] out = new float[2];
		RotationMath.rotate(50f, 80f, 50f, 80f, 73.5f, out);
		assertEquals(50f, out[0], TOL);
		assertEquals(80f, out[1], TOL);
	}

	@Test
	public void cardinal180Rotation()
	{
		// (3, 4) around origin by 180° = (-3, -4).
		float[] out = new float[2];
		RotationMath.rotate(3f, 4f, 0f, 0f, 180f, out);
		assertEquals(-3f, out[0], TOL);
		assertEquals(-4f, out[1], TOL);
	}

	@Test
	public void cardinal90CwRotation()
	{
		// (1, 0) around origin by +90° (clockwise in Y-down) lands at (0, 1). Manual verification of the sign
		// convention since the docstring explicitly commits to "Canvas.rotate(positive) = clockwise on screen".
		float[] out = new float[2];
		RotationMath.rotate(1f, 0f, 0f, 0f, 90f, out);
		assertEquals(0f, out[0], TOL);
		assertEquals(1f, out[1], TOL);
	}

	@Test
	public void forwardThenInverseIsIdentity()
	{
		float[] mid = new float[2];
		float[] back = new float[2];
		RotationMath.rotate(123.5f, 67.25f, 100f, 100f, 17.3f, mid);
		RotationMath.inverse(mid[0], mid[1], 100f, 100f, 17.3f, back);
		assertEquals(123.5f, back[0], TOL);
		assertEquals(67.25f, back[1], TOL);
	}

	@Test
	public void identityFastPathDoesNotTriggerAboveEpsilon()
	{
		// Above epsilon should go through the trig path. We can't pin the boundary at EPSILON * 1.001 because
		// at that tiny angle the (float) cast in rotate() rounds the result back to the input's exact float
		// value (100 - 3.8e-7 becomes 100.0f), making it indistinguishable from the identity path. So pick an
		// angle large enough that the trig result differs by more than one float ULP at the test's input
		// magnitude — 1° on a 100-unit offset shifts y by ~1.7 units, well above any rounding noise.
		float[] out = new float[2];
		RotationMath.rotate(100f, 0f, 0f, 0f, 1f, out);
		assertNotEquals("expected trig path, got identity", 0f, out[1], 1e-3f);
	}

	@Test
	public void identityFastPathReturnsExactInputBelowEpsilon()
	{
		// Sub-epsilon rotation must use the identity fast path — not just "trig produces approximately the
		// input", but the literal input bytes. Otherwise a chain of sub-epsilon rotations accumulates float
		// drift.
		float[] out = new float[2];
		RotationMath.rotate(7.5f, -2.25f, 50f, 50f, BitmapUtils.ROTATION_EPSILON / 2f, out);
		assertEquals(7.5f, out[0], 0f);
		assertEquals(-2.25f, out[1], 0f);
	}

	@Test
	public void identityFastPathTriggersAtNegativeSubEpsilon()
	{
		float[] out = new float[2];
		RotationMath.rotate(11f, 22f, 0f, 0f, -BitmapUtils.ROTATION_EPSILON / 4f, out);
		assertEquals(11f, out[0], 0f);
		assertEquals(22f, out[1], 0f);
	}

	@Test
	public void inverseEqualsForwardWithNegatedAngle()
	{
		float[] inv = new float[2];
		float[] negFwd = new float[2];
		RotationMath.inverse(50f, 70f, 25f, 25f, 42f, inv);
		RotationMath.rotate(50f, 70f, 25f, 25f, -42f, negFwd);
		assertEquals(inv[0], negFwd[0], TOL);
		assertEquals(inv[1], negFwd[1], TOL);
	}

	@Test
	public void nanRotationPropagatesAsNan()
	{
		// Pin the current contract: NaN rotation in produces NaN dimensions out. The helper has no internal NaN
		// guard — Math.cos(NaN) = NaN and the output multiplications cascade. Callers that need a finite
		// fallback (e.g. ViewportMath.effectiveMinZoom) must check the output before using it. Symmetric
		// Infinity case included so a future short-circuit added for one of them surfaces here for the other.
		float[] out = new float[2];
		RotationMath.rotatedAabbDimensions(100, 200, Float.NaN, out);
		assertEquals("NaN rotation in must propagate to first axis", Float.NaN, out[0], 0f);
		assertEquals("NaN rotation in must propagate to second axis", Float.NaN, out[1], 0f);
		RotationMath.rotatedAabbDimensions(100, 200, Float.POSITIVE_INFINITY, out);
		assertEquals("Infinity rotation in must propagate to first axis", Float.NaN, out[0], 0f);
		assertEquals("Infinity rotation in must propagate to second axis", Float.NaN, out[1], 0f);
	}

	@Test
	public void returnsTheSameArrayForChaining()
	{
		// The Javadoc explicitly says "Returns out for chaining". A regression that allocated a fresh array
		// would silently break callers that rely on the return-value identity.
		float[] out = new float[2];
		float[] returned = RotationMath.rotate(0f, 0f, 0f, 0f, 30f, out);
		assertSame(out, returned);

		float[] returnedInv = RotationMath.inverse(0f, 0f, 0f, 0f, 30f, out);
		assertSame(out, returnedInv);
	}

	@Test
	public void rotatedAabbDimensionsAt45DegreesSquareIsSqrt2TimesSide()
	{
		// Square of side 1000 rotated 45° has AABB sqrt(2) * 1000 on each side.
		float[] out = new float[2];
		RotationMath.rotatedAabbDimensions(1000, 1000, 45f, out);
		float expected = (float) (1000 * Math.sqrt(2));
		assertEquals(expected, out[0], 1e-2f);
		assertEquals(expected, out[1], 1e-2f);
	}

	@Test
	public void rotatedAabbDimensionsAt90DegreesSwapsAxes()
	{
		// At 90°, the bounding box rotates by 90° too, so width / height swap.
		float[] out = new float[2];
		RotationMath.rotatedAabbDimensions(4000, 3000, 90f, out);
		assertEquals(3000f, out[0], 1e-3f);
		assertEquals(4000f, out[1], 1e-3f);
	}

	@Test
	public void rotatedAabbDimensionsAtSubEpsilonCollapsesToUnrotated()
	{
		// Sub-epsilon rotation (< BitmapUtils.ROTATION_EPSILON = 0.005°) must take the identity fast path and
		// return the input dims exactly. A regression that ran the trig branch would shave a fraction off the
		// AABB and the downstream fit-to-view / clamp math would over-shrink the cap at every double-tap.
		float[] out = new float[2];
		RotationMath.rotatedAabbDimensions(4000, 3000, 0.001f, out);
		assertEquals(4000f, out[0], 0f);
		assertEquals(3000f, out[1], 0f);
	}

	@Test
	public void rotatedAabbDimensionsAtZeroReturnsInputDims()
	{
		// Zero rotation must return input dims exactly. Pin the identity case so a regression in the absCos /
		// absSin handling doesn't silently change baseScale on every unrotated fit-to-view.
		float[] out = new float[2];
		RotationMath.rotatedAabbDimensions(4000, 3000, 0f, out);
		assertEquals(4000f, out[0], 0f);
		assertEquals(3000f, out[1], 0f);
	}

	@Test
	public void rotatedAabbDimensionsGeneralAngleMatchesAbsCosSinFormula()
	{
		// 4000x3000 rotated 30°: rotatedW = 4000*cos30 + 3000*sin30 = 3464.10 + 1500 = 4964.10; rotatedH =
		// 4000*sin30 + 3000*cos30 = 2000 + 2598.08 = 4598.08.
		float[] out = new float[2];
		RotationMath.rotatedAabbDimensions(4000, 3000, 30f, out);
		float expectedW = (float) (4000 * Math.cos(Math.toRadians(30)) + 3000 * Math.sin(Math.toRadians(30)));
		float expectedH = (float) (4000 * Math.sin(Math.toRadians(30)) + 3000 * Math.cos(Math.toRadians(30)));
		assertEquals(expectedW, out[0], TOL);
		assertEquals(expectedH, out[1], TOL);
	}

	@Test
	public void rotatedAabbDimensionsNegativeAngleEqualsPositive()
	{
		// AABB is sign-invariant — the |cos| / |sin| absolute values flatten the sign.
		float[] pos = new float[2];
		float[] neg = new float[2];
		RotationMath.rotatedAabbDimensions(4000, 3000, 37.5f, pos);
		RotationMath.rotatedAabbDimensions(4000, 3000, -37.5f, neg);
		assertEquals(pos[0], neg[0], 0f);
		assertEquals(pos[1], neg[1], 0f);
	}

	@Test
	public void snapToHundredthHandlesNegativeInputs()
	{
		// Non-tie negative angles round to the nearest tick like positives — the horizon-detector path
		// passes negated tilts through here. Exact-halfway ties do NOT round symmetrically; that
		// asymmetry is pinned in snapToHundredthNegativeHalfwayRoundsTowardPositiveInfinity below.
		assertEquals(-2.34f, RotationMath.snapToHundredth(-2.341f), 0f);
	}

	@Test
	public void snapToHundredthLeavesAlreadyAlignedValuesUntouched()
	{
		// 0.05 is already on a ruler tick; the round-trip must be exact (within float precision).
		assertEquals(0.05f, RotationMath.snapToHundredth(0.05f), 1e-6f);
	}

	@Test
	public void snapToHundredthMapsZeroToZero()
	{
		assertEquals(0f, RotationMath.snapToHundredth(0f), 0f);
	}

	@Test
	public void snapToHundredthNegativeHalfwayRoundsTowardPositiveInfinity()
	{
		// Math.round adds 0.5 and floors, so ties round toward POSITIVE infinity on both signs:
		// -1.125 → -1.12 while +1.125 → +1.13 — asymmetric, not "round away from zero". 1.125f is exactly
		// representable in binary (9/8), so -112.5f reaches Math.round exactly and the test is
		// deterministic. The horizon-metadata path negates roll before snapping, so the tie direction
		// decides which ruler tick the announced angle lands on; a Math.rint or BigDecimal HALF_UP rewrite
		// would silently shift every negative tie one tick.
		assertEquals(-1.12f, RotationMath.snapToHundredth(-1.125f), 0f);
		assertEquals(1.13f, RotationMath.snapToHundredth(1.125f), 0f);
	}

	@Test
	public void snapToHundredthRoundsHalfwayUp()
	{
		// Math.round rounds halfway cases up (toward positive infinity for positive values). Pin this so a
		// regression to BigDecimal.HALF_EVEN or floor would be caught immediately.
		assertEquals(1.24f, RotationMath.snapToHundredth(1.235f), 0f);
	}

	@Test
	public void snapToHundredthRoundsToTwoDecimalPlaces()
	{
		// Pin the contract: 1.234 → 1.23 (half-up rounding via Math.round — the .004 stays under .5 so it
		// rounds down regardless of half-up vs half-even). Used by the auto-rotate / horizon paths so the
		// announced angle lands on a ruler tick rather than between two of them. Sibling
		// snapToHundredthRoundsHalfwayUp pins the .005 boundary case.
		assertEquals(1.23f, RotationMath.snapToHundredth(1.234f), 0f);
	}
}
