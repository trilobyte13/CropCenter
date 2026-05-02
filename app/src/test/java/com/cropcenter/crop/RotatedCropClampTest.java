package com.cropcenter.crop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for RotatedCropClamp's pure-math clamps. Both entry points run on every CropState.setCenter call, so a
 * regression that lets the clamp pick a position with corners outside the image bounds silently corrupts every
 * rotated-crop export. The axis-aligned path is cheap and only needs the cropW &gt;= imgW snap-to-mid contract pinned
 * down; the rotated path runs a 25-iteration binary search whose three branches (image-center fallback, X-search,
 * Y-search) all need pinning.
 */
public class RotatedCropClampTest
{
	private static final float TOL = 1e-3f;

	// ── clampAxisAligned ──

	@Test
	public void clampAxisAlignedReturnsRequestedPositionWhenInBounds()
	{
		// Crop 200x150 inside image 1000x800; center (500, 400) is well within bounds. Should pass through
		// unchanged.
		float[] result = RotatedCropClamp.clampAxisAligned(500f, 400f, 200, 150, 1000, 800);
		assertArrayEquals(new float[] { 500f, 400f }, result, TOL);
	}

	@Test
	public void clampAxisAlignedClampsXToLowerBound()
	{
		// Center request X=10 is left of cropW/2 = 100; clamp to 100.
		float[] result = RotatedCropClamp.clampAxisAligned(10f, 400f, 200, 150, 1000, 800);
		assertEquals(100f, result[0], TOL);
		assertEquals(400f, result[1], TOL);
	}

	@Test
	public void clampAxisAlignedClampsXToUpperBound()
	{
		// Center request X=999 is right of imgW - cropW/2 = 900; clamp to 900.
		float[] result = RotatedCropClamp.clampAxisAligned(999f, 400f, 200, 150, 1000, 800);
		assertEquals(900f, result[0], TOL);
		assertEquals(400f, result[1], TOL);
	}

	@Test
	public void clampAxisAlignedClampsYToLowerAndUpperBounds()
	{
		// Y at top edge.
		float[] resultLow = RotatedCropClamp.clampAxisAligned(500f, 0f, 200, 150, 1000, 800);
		assertEquals(75f, resultLow[1], TOL);
		// Y at bottom edge.
		float[] resultHigh = RotatedCropClamp.clampAxisAligned(500f, 9999f, 200, 150, 1000, 800);
		assertEquals(800f - 75f, resultHigh[1], TOL);
	}

	@Test
	public void clampAxisAlignedSnapsXToImageMidWhenCropExceedsImageWidth()
	{
		// cropW (1200) > imgW (1000) — Math.clamp would throw on inverted bounds; instead snap to imgW/2.
		float[] result = RotatedCropClamp.clampAxisAligned(800f, 400f, 1200, 150, 1000, 800);
		assertEquals(500f, result[0], TOL);   // imgW/2 = 500
		assertEquals(400f, result[1], TOL);   // Y still clamped normally
	}

	@Test
	public void clampAxisAlignedSnapsYToImageMidWhenCropExceedsImageHeight()
	{
		// cropH (900) > imgH (800).
		float[] result = RotatedCropClamp.clampAxisAligned(500f, 400f, 200, 900, 1000, 800);
		assertEquals(500f, result[0], TOL);
		assertEquals(400f, result[1], TOL);   // imgH/2 = 400
	}

	@Test
	public void clampAxisAlignedSnapsBothAxesWhenCropExceedsImageOnBoth()
	{
		// Both cropW > imgW and cropH > imgH — both axes snap to image center regardless of requested position.
		float[] result = RotatedCropClamp.clampAxisAligned(0f, 0f, 1200, 900, 1000, 800);
		assertArrayEquals(new float[] { 500f, 400f }, result, TOL);
	}

	@Test
	public void clampAxisAlignedHandlesCropEqualImageEdgeCase()
	{
		// cropW == imgW — predicate is `cropW < imgW` (strict), so this hits the snap-to-mid branch. Pin this
		// down as intentional (the strict-less guards against Math.clamp on equal bounds).
		float[] result = RotatedCropClamp.clampAxisAligned(0f, 400f, 1000, 150, 1000, 800);
		assertEquals(500f, result[0], TOL);
		assertEquals(400f, result[1], TOL);
	}

	// ── clampRotated ──

	@Test
	public void clampRotatedAtZeroDegEqualsAxisAlignedResult()
	{
		// At 0° rotation the rotated-clamp's binary search should converge to (or accept directly) the same
		// position the axis-aligned path returns. The two clamps are equivalent for unrotated input within
		// floating-point tolerance.
		float[] axis = RotatedCropClamp.clampAxisAligned(10f, 400f, 200, 150, 1000, 800);
		float[] rot = RotatedCropClamp.clampRotated(10f, 400f, 200, 150, 0f, 1000, 800);
		// Rotated-clamp can return image-center on the binary-search lo path when corners-inside check passes
		// for the requested position; the corner check at 0° rotation matches axis-aligned bounds, so requested
		// position is valid → rot[0] == 10. But axis path clamps to 100. That's the actual difference: rotated
		// path doesn't clamp on the axis the user requested if corners happen to be inside. So at (10, 400)
		// with rotation = 0, the corners check at center (10, 400) would have corners at (-90, 325) — outside
		// the image — triggering the binary search.
		assertEquals(axis[0], rot[0], 0.5f);   // binary-search converges to ~clamp; allow 0.5px slop
		assertEquals(axis[1], rot[1], 0.5f);
	}

	@Test
	public void clampRotatedAcceptsInBoundsRequestUnchanged()
	{
		// Center well inside image at modest rotation — corners fit even after un-rotation, so no search fires
		// and the requested position passes through unchanged.
		float[] result = RotatedCropClamp.clampRotated(500f, 400f, 100, 100, 5f, 1000, 800);
		assertEquals(500f, result[0], TOL);
		assertEquals(400f, result[1], TOL);
	}

	@Test
	public void clampRotatedFallsBackToImageMidWhenCropTooLargeAtAnyPosition()
	{
		// Crop exceeds rotated image's inscribed bounds: at 45° the image's axis-aligned envelope shrinks, so a
		// 1000x800 image rotated 45° fits a centered crop of at most ~565px (sqrt(2)/2 * 800). A 700x700 crop
		// can't fit anywhere; the clamp should return image-center.
		float[] result = RotatedCropClamp.clampRotated(500f, 400f, 700, 700, 45f, 1000, 800);
		assertEquals(500f, result[0], TOL);   // image-center fallback
		assertEquals(400f, result[1], TOL);
	}

	@Test
	public void clampRotatedPullsXBackTowardCenterOnRotation()
	{
		// Requested position near image edge with a crop large enough that un-rotation pulls a corner outside
		// bounds. At 20° rotation a 200x200 crop centered at (950, 400) produces an un-rotated corner (1000,
		// 500) at ux ≈ 1004 — past imgW + 0.5. Image-center (500, 400) DOES fit (corners stay inside even after
		// un-rotation), so the binary search fires and pulls X toward 500.
		float[] result = RotatedCropClamp.clampRotated(950f, 400f, 200, 200, 20f, 1000, 800);
		assertTrue("expected X pulled back toward 500 from 950, got " + result[0],
			result[0] < 950f && result[0] >= 500f);
		assertEquals(400f, result[1], TOL);
	}

	@Test
	public void clampRotatedPullsYBackTowardCenterOnRotation()
	{
		// Symmetric counterpart: requested Y near image edge gets pulled back along Y axis. At 20° rotation a
		// 200x200 crop centered at (500, 750) un-rotates a corner past imgH; image-center (500, 400) fits, so
		// binary search pulls Y back.
		float[] result = RotatedCropClamp.clampRotated(500f, 750f, 200, 200, 20f, 1000, 800);
		assertEquals(500f, result[0], TOL);
		assertTrue("expected Y pulled back toward 400 from 750, got " + result[1],
			result[1] < 750f && result[1] >= 400f);
	}

	@Test
	public void clampRotatedHandlesNegativeRotationSymmetrically()
	{
		// Negative rotation is the un-rotation direction; the math should handle it the same as positive.
		float[] posRot = RotatedCropClamp.clampRotated(950f, 400f, 200, 200, 20f, 1000, 800);
		float[] negRot = RotatedCropClamp.clampRotated(950f, 400f, 200, 200, -20f, 1000, 800);
		// Symmetric: the X clamp converges to (approximately) the same value regardless of sign.
		assertEquals(posRot[0], negRot[0], 1f);
	}

	@Test
	public void clampRotatedConvergesNearOptimalAfter25Iterations()
	{
		// With 25 binary-search iterations starting from [0, 1] fraction, the resolution is 2^-25 ≈ 3e-8 of the
		// requested-to-mid range. For a 1000-px image that's ~3e-5 px — well below the float-rounding noise of
		// the corner check itself. Verify convergence by constructing a case with a known-correct answer: at 0°
		// the optimal X is exactly the axis-aligned clamp boundary (cropW/2 = 50 for cropW 100).
		float[] result = RotatedCropClamp.clampRotated(0f, 400f, 100, 100, 0f, 1000, 800);
		// Center request (0, 400) → un-rotated corners would have X = -50, outside. Search pulls X toward 500
		// (image-mid) until corners fit. With 0° rotation the optimal X is exactly 50.
		assertEquals(50f, result[0], 0.5f);   // 0.5px slop accommodates float-rounding in corner check
	}
}
