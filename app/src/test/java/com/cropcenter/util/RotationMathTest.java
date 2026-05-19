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
	public void snapToHundredthHandlesNegativeInputs()
	{
		// Negative angles round symmetrically — the horizon-detector path passes negated tilts through here.
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
