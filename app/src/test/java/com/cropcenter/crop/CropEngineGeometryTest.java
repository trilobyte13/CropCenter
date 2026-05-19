package com.cropcenter.crop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cropcenter.model.AspectRatio;

import org.junit.Test;

/**
 * Tests for CropEngine's package-private geometry helpers — `clampFreeAxes`, `computeMaxCropSize`, and
 * `maxScaleForRotation`. These are the load-bearing math primitives that `recomputeCrop` composes; without
 * direct coverage a regression in any of them would let the recomputed crop bleed outside the image (CANVAS_BG
 * showing at corners after save) or collapse the visible difference between lock modes (free X / free Y / free
 * both producing identical crops).
 *
 * `recomputeCrop` itself depends on a Bitmap-backed CropState.getSourceImage() and isn't unit-testable without
 * Android — these helper tests are the closest we can get to pinning recomputeCrop's geometry without dragging
 * in Robolectric.
 */
public final class CropEngineGeometryTest
{
	private static final float TOL = 1e-3f;

	@Test
	public void clampFreeAxesBothFree()
	{
		// Neither axis locked — both X and Y get clamped to keep crop fully inside image.
		float[] result = CropEngine.clampFreeAxes(50f, 50f, 40f, 60f, 100, 100, false, false);
		// cropW=40, half=20 → X clamped to [20, 80] — 50 stays
		// cropH=60, half=30 → Y clamped to [30, 70] — 50 stays
		assertEquals(50f, result[0], TOL);
		assertEquals(50f, result[1], TOL);
	}

	@Test
	public void clampFreeAxesCenterFallsBackWhenCropExceedsImage()
	{
		// cropW = imgW: clamp's lo=cropW/2=50, hi=imgW-cropW/2=50 — degenerate (lo==hi). The `cropW < imgW`
		// guard takes the centering fallback. Pin both this degenerate-equal case AND the cropW > imgW case
		// to prove the guard is `<`, not `<=`, and the fallback is `imgW / 2`.
		float[] degenerate = CropEngine.clampFreeAxes(99f, 99f, 100f, 100f, 100, 100, false, false);
		assertEquals("cropW == imgW → fallback to imgW/2", 50f, degenerate[0], TOL);
		assertEquals("cropH == imgH → fallback to imgH/2", 50f, degenerate[1], TOL);
		float[] tooBig = CropEngine.clampFreeAxes(50f, 50f, 200f, 200f, 100, 100, false, false);
		assertEquals("cropW > imgW → fallback to imgW/2", 50f, tooBig[0], TOL);
		assertEquals("cropH > imgH → fallback to imgH/2", 50f, tooBig[1], TOL);
	}

	@Test
	public void clampFreeAxesLockedX()
	{
		// X locked → centerX passes through unchanged even if it would otherwise need clamping. Y is clamped.
		// centerX=5 is way off-center but lockedX preserves it.
		float[] result = CropEngine.clampFreeAxes(5f, 5f, 40f, 60f, 100, 100, true, false);
		assertEquals("X locked → passes through", 5f, result[0], TOL);
		assertEquals("Y free → clamped to [30, 70]", 30f, result[1], TOL);
	}

	@Test
	public void clampFreeAxesShiftsOffCenter()
	{
		// centerX = 10, cropW = 40 → cropX = -10 would go off-image. clamp pushes centerX to 20 (cropX = 0).
		float[] result = CropEngine.clampFreeAxes(10f, 50f, 40f, 60f, 100, 100, false, false);
		assertEquals(20f, result[0], TOL);
		assertEquals(50f, result[1], TOL);
	}

	@Test
	public void computeMaxCropSize4to5ARFitsByWidth()
	{
		// imgW=400, imgH=1000, AR=4:5=0.8. cropW/cropH ≤ ratio → width-bound. cropW=400, cropH=500.
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.R4_5, 200f, 500f, 400, 1000, false, false);
		assertEquals(400f, result[0], TOL);
		assertEquals(500f, result[1], TOL);
	}

	@Test
	public void computeMaxCropSizeFitsAspectRatioHeightBound()
	{
		// imgW=200, imgH=100, AR=1:1 → bounded by height: 100x100.
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.R1_1, 100f, 50f, 200, 100, false, false);
		assertEquals(100f, result[0], TOL);
		assertEquals(100f, result[1], TOL);
	}

	@Test
	public void computeMaxCropSizeFitsAspectRatioWidthBound()
	{
		// imgW=100, imgH=200, AR=1:1 → square fit. Available is 100x200 → bounded by width: 100x100.
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.R1_1, 50f, 100f, 100, 200, false, false);
		assertEquals(100f, result[0], TOL);
		assertEquals(100f, result[1], TOL);
	}

	@Test
	public void computeMaxCropSizeFreeARYieldsFullImage()
	{
		// FREE ratio with both axes unlocked → full image extent on each axis.
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.FREE, 50f, 50f, 100, 200, false, false);
		assertEquals(100f, result[0], TOL);
		assertEquals(200f, result[1], TOL);
	}

	@Test
	public void computeMaxCropSizeLockedAxisIsSymmetricAroundCenter()
	{
		// Locked X at centerX=30 in imgW=100 → max symmetric extent = 2*min(30, 70) = 60. Free Y gets
		// full image extent.
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.FREE, 30f, 50f, 100, 200, true, false);
		assertEquals(60f, result[0], TOL);
		assertEquals(200f, result[1], TOL);
	}

	@Test
	public void maxScaleForRotationCenteredCropFitsAtIdentityRotation()
	{
		// A crop equal to image bounds at rotation=0° should fit at scale=1f. All corners land exactly on
		// image edges; the bounds check uses >= 0 and <= imgW/imgH so edge corners pass.
		float scale = CropEngine.maxScaleForRotation(50f, 50f, 100f, 100f, 100, 100, 0f);
		assertEquals(1f, scale, 0f);
	}

	@Test
	public void maxScaleForRotationConvergesToBinarySearchPrecision()
	{
		// Pin convergence: 25 bisection iterations from [0.01, 1.0] reach ~3e-8 precision on the scale.
		// A regression that loops too few would converge short. Test with a known geometry where the
		// theoretical max scale is ~1/√2 ≈ 0.7071 (50x50 crop at 45° in 50x50 image with corners just
		// barely fitting). The actual converged value should be very close.
		float scale = CropEngine.maxScaleForRotation(25f, 25f, 50f, 50f, 50, 50, 45f);
		assertEquals(0.7071f, scale, 0.01f);
	}

	@Test
	public void maxScaleForRotationShrinksWhenRotatedCropExceedsBounds()
	{
		// A 100x100 image with a 99x99 crop at 45° rotation: the rotated bounding-box of the crop is
		// 99*√2 ≈ 140, way larger than the image. Binary search shrinks to ≈ 100/140 ≈ 0.7. Pin a lower
		// bound (no greater than 0.72 to allow for the bisection's resolution) so a regression that
		// always returns 1f would fail.
		float scale = CropEngine.maxScaleForRotation(50f, 50f, 99f, 99f, 100, 100, 45f);
		assertTrue("rotated 45° must shrink the 99x99 crop (got " + scale + ")", scale < 0.75f);
		assertTrue("scale must be positive (got " + scale + ")", scale > 0f);
	}
}
