package com.cropcenter.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cropcenter.util.BitmapUtils;

/**
 * Tests for the CropState behaviour that doesn't touch a Bitmap. The big-leverage one
 * is setRotationDegrees: NaN / infinity sanitization, ±180° clamp, and the sub-epsilon
 * snap-to-zero that's the chokepoint for every rotation entry point in the app. A bug
 * in any of these would let a malformed input poison the rotation pipeline.
 */
public class CropStateTest
{
	private static final float EPS = BitmapUtils.ROTATION_EPSILON;

	@Test
	public void clampNegativeOutOfRange()
	{
		CropState state = new CropState();
		state.setRotationDegrees(-2000f);
		assertEquals(-180f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void clampPositiveOutOfRange()
	{
		CropState state = new CropState();
		state.setRotationDegrees(2000f);
		assertEquals(180f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void exactBoundaryValuesAreKept()
	{
		// ±180 is in-range; ±EPSILON is above the snap threshold so it survives.
		CropState state = new CropState();
		state.setRotationDegrees(180f);
		assertEquals(180f, state.getRotationDegrees(), 0f);
		state.setRotationDegrees(-180f);
		assertEquals(-180f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void infinityCollapsesToZero()
	{
		CropState state = new CropState();
		state.setRotationDegrees(Float.POSITIVE_INFINITY);
		assertEquals(0f, state.getRotationDegrees(), 0f);
		state.setRotationDegrees(Float.NEGATIVE_INFINITY);
		assertEquals(0f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void markCropSizeDirtyAfterRotationChange()
	{
		// Rotation changes the rotated-AABB size, so the crop has to be re-fitted.
		// CropState owns this dirty flag and EVERY rotation entry point is expected
		// to flow through this setter so the flag is set uniformly. A regression
		// that bypassed it would leave a stale crop visible after a rotation.
		CropState state = new CropState();
		state.setCropSizeDirty(false);
		assertFalse(state.isCropSizeDirty());
		state.setRotationDegrees(15f);
		assertTrue(state.isCropSizeDirty());
	}

	@Test
	public void nanCollapsesToZero()
	{
		// NaN inputs (e.g., a bad formula in the horizon detector) are sanitized to 0.
		// Without this, NaN would survive the clamp (Math.clamp(NaN, ..) = NaN) and
		// poison every downstream consumer until the next setRotationDegrees call.
		CropState state = new CropState();
		state.setRotationDegrees(Float.NaN);
		assertEquals(0f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void normalRotationIsKept()
	{
		CropState state = new CropState();
		state.setRotationDegrees(15.5f);
		assertEquals(15.5f, state.getRotationDegrees(), 0f);
		state.setRotationDegrees(-90f);
		assertEquals(-90f, state.getRotationDegrees(), 0f);
		state.setRotationDegrees(0.05f);
		assertEquals(0.05f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void subEpsilonNegativeAlsoSnapsToZero()
	{
		CropState state = new CropState();
		state.setRotationDegrees(-EPS / 2f);
		assertEquals(0f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void subEpsilonPositiveSnapsToZero()
	{
		// The chokepoint behaviour: any |deg| < ROTATION_EPSILON collapses to exactly 0.
		// Without this, a 0.003° value from the horizon detector or the precise dialog
		// would survive (CropState stores it), then UiSync would hide the readout AND
		// CropEngine would treat as zero — UI lying about state, ExportPipeline forced
		// into a needless re-encode. The snap is the single chokepoint that prevents
		// that. Verify both directions of sub-epsilon.
		CropState state = new CropState();
		state.setRotationDegrees(EPS / 2f);
		assertEquals(0f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void valueAtEpsilonBoundaryIsKept()
	{
		// Strict less-than in the snap: exactly EPSILON survives. (The boundary value
		// itself is a real-world rotation, not noise — the snap exists to reject
		// values BELOW the smallest user-controllable step.)
		CropState state = new CropState();
		state.setRotationDegrees(EPS);
		assertEquals(EPS, state.getRotationDegrees(), 0f);
	}
}
