package com.cropcenter.crop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CropState;

import org.junit.Test;

/**
 * Tests for CropEngine's package-private geometry helpers — `clampFreeAxes`, `computeMaxCropSize`, and
 * `maxScaleForRotation`. These are the load-bearing math primitives that `recomputeCrop` composes; without direct
 * coverage a regression in any of them would let the recomputed crop bleed outside the image (background fill showing
 * at corners after save) or collapse the visible difference between lock modes (free X / free Y / free both producing
 * identical crops).
 *
 * `recomputeCrop` itself depends on a Bitmap-backed CropState.getSourceImage() and isn't unit-testable without Android
 * — these helper tests are the closest we can get to pinning recomputeCrop's geometry without dragging in Robolectric.
 */
public final class CropEngineGeometryTest
{
	private static final float TOL = 1e-3f;

	@Test
	public void clampFreeAxesAtNaNRotationFallsBackToImageMidpoint()
	{
		// NaN rotation propagates to rotatedW/rotatedH via cos/sin. The fallback gate
		// `cropW < rotatedW` evaluates false against NaN, so both axes take the imageMid fallback:
		// X = imgW/2, Y = imgH/2. Pin the EXACT result on BOTH axes: only the two-axis exact pin catches
		// a regression that NaN-propagates a single axis — a loose "isNaN OR isFinite" assertion is
		// tautological for every non-Infinity float and misses it. A future explicit NaN guard added at
		// the top of clampFreeAxes should produce the same midpoint fallback to preserve this contract.
		float[] result = CropEngine.clampFreeAxes(50f, 50f, 40f, 60f, 100, 100, false, false, Float.NaN);
		assertEquals("NaN rotation must fall back to imgW/2 on X", 50f, result[0], 0f);
		assertEquals("NaN rotation must fall back to imgH/2 on Y", 50f, result[1], 0f);
	}

	@Test
	public void clampFreeAxesAtRotationFallsBackWhenCropExceedsRotatedAabb()
	{
		// At 30° rotation: rotatedW = 4964. A 5000-wide crop exceeds the rotated AABB on the X axis, so the
		// free-X case takes the fallback and centers on imgW/2 = 2000 (not 0, even though centerX = -200
		// requested). Y axis: cropH=100 fits inside rotatedH=4598, so the normal clamp applies.
		float[] result = CropEngine.clampFreeAxes(-200f, 100f, 5000f, 100f, 4000, 3000, false, false, 30f);
		assertEquals("crop wider than rotated AABB → fallback to image midpoint", 2000f, result[0], TOL);
	}

	@Test
	public void clampFreeAxesAtRotationKeepsCenterInRotatedAabbWhenOutsideBitmap()
	{
		// At 30° rotation, the rotated AABB of a 4000x3000 image extends past the un-rotated bitmap bounds:
		// rotatedW = 4964, rotatedH = 4598. The center range becomes X=[-482, 4482], Y=[-799, 3799]. A
		// selection point whose rotated position is at (1000, -500) — outside the un-rotated [0, imgH] Y
		// bounds but inside the rotated AABB — must NOT be pulled to Y=0 (the un-rotated edge). Both X
		// and Y free, crop is smaller than rotated AABB on each axis.
		float[] result = CropEngine.clampFreeAxes(1000f, -500f, 100f, 100f, 4000, 3000, false, false, 30f);
		assertEquals("X unchanged inside rotated AABB", 1000f, result[0], TOL);
		assertEquals("Y unchanged inside rotated AABB despite being negative", -500f, result[1], TOL);
	}

	@Test
	public void clampFreeAxesBothFree()
	{
		// Neither axis locked — both X and Y get clamped to keep crop fully inside image.
		float[] result = CropEngine.clampFreeAxes(50f, 50f, 40f, 60f, 100, 100, false, false, 0f);
		// cropW=40, half=20 → X clamped to [20, 80] — 50 stays cropH=60, half=30 → Y clamped to [30, 70] — 50
		// stays
		assertEquals(50f, result[0], TOL);
		assertEquals(50f, result[1], TOL);
	}

	@Test
	public void clampFreeAxesCenterFallsBackWhenCropExceedsImage()
	{
		// cropW = imgW: clamp's lo=cropW/2=50, hi=imgW-cropW/2=50 — degenerate (lo==hi). The `cropW < imgW`
		// guard takes the centering fallback. Pin both this degenerate-equal case AND the cropW > imgW case to
		// prove the guard is `<`, not `<=`, and the fallback is `imgW / 2`.
		float[] degenerate = CropEngine.clampFreeAxes(99f, 99f, 100f, 100f, 100, 100, false, false, 0f);
		assertEquals("cropW == imgW → fallback to imgW/2", 50f, degenerate[0], TOL);
		assertEquals("cropH == imgH → fallback to imgH/2", 50f, degenerate[1], TOL);
		float[] tooBig = CropEngine.clampFreeAxes(50f, 50f, 200f, 200f, 100, 100, false, false, 0f);
		assertEquals("cropW > imgW → fallback to imgW/2", 50f, tooBig[0], TOL);
		assertEquals("cropH > imgH → fallback to imgH/2", 50f, tooBig[1], TOL);
	}

	@Test
	public void clampFreeAxesLockedX()
	{
		// X locked → centerX passes through unchanged even if it would otherwise need clamping. Y is clamped.
		// centerX=5 is way off-center but lockedX preserves it.
		float[] result = CropEngine.clampFreeAxes(5f, 5f, 40f, 60f, 100, 100, true, false, 0f);
		assertEquals("X locked → passes through", 5f, result[0], TOL);
		assertEquals("Y free → clamped to [30, 70]", 30f, result[1], TOL);
	}

	@Test
	public void clampFreeAxesShiftsOffCenter()
	{
		// centerX = 10, cropW = 40 → cropX = -10 would go off-image. clamp pushes centerX to 20 (cropX = 0).
		float[] result = CropEngine.clampFreeAxes(10f, 50f, 40f, 60f, 100, 100, false, false, 0f);
		assertEquals(20f, result[0], TOL);
		assertEquals(50f, result[1], TOL);
	}

	@Test
	public void computeMaxCropSize4to5ARFitsByWidth()
	{
		// imgW=400, imgH=1000, AR=4:5=0.8. cropW/cropH ≤ ratio → width-bound. cropW=400, cropH=500.
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.R4_5, 200f, 500f, 400, 1000,
			false, false, 0f);
		assertEquals(400f, result[0], TOL);
		assertEquals(500f, result[1], TOL);
	}

	@Test
	public void computeMaxCropSizeAtNaNRotationPropagatesAsNaNOnBothAxes()
	{
		// NaN rotation produces NaN rotatedW/rotatedH via cos/sin. Free axes return rotatedDims directly — so
		// both X AND Y land as NaN. Pin both axes explicitly: a loose "NaN on at least one axis" assertion
		// would let a regression that NaN's only X (returning a stale finite Y) slip through silently.
		// NaN-equality requires Float.isNaN, not `==`.
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.FREE, 200f, 200f,
			400, 400, false, false, Float.NaN);
		assertTrue("NaN rotation must propagate as NaN on cropW (got " + result[0] + ")",
			Float.isNaN(result[0]));
		assertTrue("NaN rotation must propagate as NaN on cropH (got " + result[1] + ")",
			Float.isNaN(result[1]));
	}

	@Test
	public void computeMaxCropSizeAtRotationFreeAxesReturnsRotatedAabbDims()
	{
		// At 30° rotation on a 4000x3000 image, FREE AR + free axes → the max crop is the rotated AABB
		// dimensions (4964 x 4598). The downstream maxScaleForRotation pass will shrink this to fit inside the
		// actual rotated bitmap; the goal here is just that the pre-shrink max isn't clamped by the un-rotated
		// bitmap rectangle.
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.FREE, 2000f, 1500f,
			4000, 3000, false, false, 30f);
		float expectedW = (float) (4000 * Math.cos(Math.toRadians(30)) + 3000 * Math.sin(Math.toRadians(30)));
		float expectedH = (float) (4000 * Math.sin(Math.toRadians(30)) + 3000 * Math.cos(Math.toRadians(30)));
		assertEquals(expectedW, result[0], TOL);
		assertEquals(expectedH, result[1], TOL);
	}

	@Test
	public void computeMaxCropSizeAtRotationLockedAxisUsesRotatedBounds()
	{
		// At 30° on 4000x3000, locked-Y at centerY=-500 (legitimate screen-aligned position inside the rotated
		// AABB). rotatedH = 4000*sin(30°) + 3000*cos(30°) ≈ 4598.08, topBound = imgH/2 - rotatedH/2 ≈ -799.04,
		// bottomBound ≈ 3799.04. maxCropH = 2 * min(-500 - topBound, bottomBound - (-500)) ≈ 2 * 299.04 =
		// 598.08. Bounding against the un-rotated bitmap instead would compute 2 * min(-500, 3000 - (-500)) =
		// -1000 (degenerate).
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.FREE, 2000f, -500f,
			4000, 3000, false, true, 30f);
		float rotatedH = (float) (4000 * Math.sin(Math.toRadians(30)) + 3000 * Math.cos(Math.toRadians(30)));
		float topBound = 1500f - rotatedH / 2f;
		float expectedMaxH = 2f * Math.min(-500f - topBound, 1500f + rotatedH / 2f - (-500f));
		assertEquals(expectedMaxH, result[1], TOL);
		// Sanity: the degenerate un-rotated-bounds value would be negative; the result must be POSITIVE.
		assertTrue("rotated-bounds max height must be positive at off-bitmap center", result[1] > 0);
	}

	@Test
	public void computeMaxCropSizeAtZeroDimensionsReturnsZeroCrop()
	{
		// imgW=0 or imgH=0 hits the maxCropH-then-divide branch (computeMaxCropSize divides cropW/cropH for the
		// AR check). Pin the EXACT result — both axes must be 0 (rotatedW = imgW = 0 at zero rotation, so
		// free-axis max = 0). A finite-but-positive return like [Float.MAX_VALUE, 1f] would pass a mere
		// isFinite check while still propagating garbage to setCropSizeSilent. Hard-pin zero so a regression
		// that returned anything else surfaces.
		float[] zeroW = CropEngine.computeMaxCropSize(AspectRatio.FREE, 0f, 0f, 0, 100, false, false, 0f);
		assertEquals("zero imgW must produce 0 cropW", 0f, zeroW[0], 0f);
		assertEquals("zero imgW must produce non-negative cropH", 100f, zeroW[1], 0f);
		float[] zeroH = CropEngine.computeMaxCropSize(AspectRatio.FREE, 0f, 0f, 100, 0, false, false, 0f);
		assertEquals("zero imgH must produce non-negative cropW", 100f, zeroH[0], 0f);
		assertEquals("zero imgH must produce 0 cropH", 0f, zeroH[1], 0f);
	}

	@Test
	public void computeMaxCropSizeFitsAspectRatioHeightBound()
	{
		// imgW=200, imgH=100, AR=1:1 → bounded by height: 100x100.
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.R1_1, 100f, 50f, 200, 100, false, false, 0f);
		assertEquals(100f, result[0], TOL);
		assertEquals(100f, result[1], TOL);
	}

	@Test
	public void computeMaxCropSizeFitsAspectRatioWidthBound()
	{
		// imgW=100, imgH=200, AR=1:1 → square fit. Available is 100x200 → bounded by width: 100x100.
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.R1_1, 50f, 100f, 100, 200, false, false, 0f);
		assertEquals(100f, result[0], TOL);
		assertEquals(100f, result[1], TOL);
	}

	@Test
	public void computeMaxCropSizeFreeArYieldsFullImage()
	{
		// FREE ratio with both axes unlocked → full image extent on each axis.
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.FREE, 50f, 50f, 100, 200, false, false, 0f);
		assertEquals(100f, result[0], TOL);
		assertEquals(200f, result[1], TOL);
	}

	@Test
	public void computeMaxCropSizeLockedAxisIsSymmetricAroundCenter()
	{
		// Locked X at centerX=30 in imgW=100 → max symmetric extent = 2*min(30, 70) = 60. Free Y gets full
		// image extent.
		float[] result = CropEngine.computeMaxCropSize(AspectRatio.FREE, 30f, 50f, 100, 200, true, false, 0f);
		assertEquals(60f, result[0], TOL);
		assertEquals(200f, result[1], TOL);
	}

	@Test
	public void maxScaleForRotationAtZeroRotationStillShrinksOversizedCrop()
	{
		// Counter to maxScaleForRotationCenteredCropFitsAtIdentityRotation: that test asserts scale=1f for a
		// fitting crop at rotation=0. Without this companion test, a stub implementation that returned 1f
		// unconditionally at zero rotation would silently pass. Drive a 200x200 crop in a 100x100 image at
		// rotation=0 — the binary search must shrink because the un-rotated crop already exceeds the image
		// bounds. Theoretical max scale: corner extents 100·s land at 50 ± 100·s; slack-aware requirement is
		// 100·s ≤ 50 + 0.5, so s ≤ 50.5/100 = 0.505. Pin closed-form.
		float scale = CropEngine.maxScaleForRotation(50f, 50f, 200f, 200f, 100, 100, 0f);
		assertEquals("oversized crop at zero rotation must shrink via the same code path",
			0.505f, scale, 1e-3f);
	}

	@Test
	public void maxScaleForRotationCenteredCropFitsAtIdentityRotation()
	{
		// A crop equal to image bounds at rotation=0° should fit at scale=1f. All corners land exactly on image
		// edges; the bounds check uses >= 0 and <= imgW/imgH so edge corners pass.
		float scale = CropEngine.maxScaleForRotation(50f, 50f, 100f, 100f, 100, 100, 0f);
		assertEquals(1f, scale, 0f);
	}

	@Test
	public void maxScaleForRotationConvergesToBinarySearchPrecision()
	{
		// Pin convergence: 25 bisection iterations from [0.01, 1.0] converge inside ~1e-6 of the
		// closed-form max scale. Test geometry: 50x50 crop at 45° in 50x50 image with the slack-aware
		// edge tolerance (±0.5 px to match `RotatedCropClamp.cornersInside`). Theoretical max scale:
		// the corner extents 25·√2·s land at 25 ± 25·√2·s; requirement is 25·√2·s ≤ 25 + 0.5
		// (image edge + slack), so s ≤ 25.5/(25·√2) ≈ 0.7212.
		float scale = CropEngine.maxScaleForRotation(25f, 25f, 50f, 50f, 50, 50, 45f);
		float expectedScale = (float) (25.5 / (25.0 * Math.sqrt(2)));
		assertEquals("converged scale must match closed-form (slack-aware)", expectedScale, scale, 1e-5f);
	}

	@Test
	public void maxScaleForRotationShrinksWhenRotatedCropExceedsBounds()
	{
		// A 100x100 image with a 99x99 crop at 45° rotation: the rotated bounding-box of the crop is 99·√2 ≈
		// 140, way larger than the image. The corner extents 49.5·√2·s land at 50 ± 49.5·√2·s; slack-aware
		// requirement is 50 + 49.5·√2·s ≤ 100.5, so s ≤ 50.5/(49.5·√2) ≈ 0.7215. Tight tolerance to catch both
		// (a) the "always returns 1f" trivial regression AND (b) a converge-short regression that lands at 0.7
		// instead of 0.7215.
		float scale = CropEngine.maxScaleForRotation(50f, 50f, 99f, 99f, 100, 100, 45f);
		float expectedScale = (float) (50.5 / (49.5 * Math.sqrt(2)));
		assertEquals("rotated 45° must converge to closed-form max scale (slack-aware)",
			expectedScale, scale, 1e-5f);
		assertTrue("scale must be positive (got " + scale + ")", scale > 0f);
	}

	@Test
	public void recheckRotationFitShrinksAndCommitsWhenPostRoundedCropFitsLooselyAtRotation()
	{
		// Simulate the post-integer-round state recomputeCrop leaves behind: large crop at the image center,
		// modest rotation. maxScaleForRotation should return a recheck factor below 0.99 (the trigger
		// threshold), causing recheckRotationFit to shrink + re-round + re-commit. Pin the AR-snap-no-grow
		// contract DIRECTLY by using a non-FREE AR — the snap call passes the refined dims as the max bounds,
		// so the snap can only round DOWN, never up against the un-rotated image dims. A FREE-AR fixture would
		// pass trivially (snap is a no-op for FREE); R4_5 forces the snap math to actually run, so the no-grow
		// contract is genuinely pinned.
		CropState state = new CropState();
		state.setAspectRatio(AspectRatio.R4_5);
		state.setRotationDegrees(30f);
		// Center on image midpoint (so the rotated bound is symmetric); crop spans the full 4000x3000
		// → corners way past the rotated image at 30°. setCenter without a sourceImage skips its
		// clamp, so we get the raw values stored.
		state.setCenter(2000f, 1500f);
		state.setCropSizeSilent(4000, 3000);
		int initialW = state.getCropW();
		int initialH = state.getCropH();
		CropEngine.recheckRotationFit(state, 4000, 3000, 30f);
		int finalW = state.getCropW();
		int finalH = state.getCropH();
		assertTrue("recheckRotationFit must shrink an over-sized rotated crop (initial " + initialW
			+ "x" + initialH + ", got " + finalW + "x" + finalH + ")",
			finalW < initialW && finalH < initialH);
		assertTrue("post-recheck dims must be positive", finalW > 0 && finalH > 0);
		// AR-snap-no-grow contract: the R4_5 snap was passed `refinedCrop` as max bounds, so the result must
		// satisfy finalW × 5 == finalH × 4 (exact 4:5) AND finalW ≤ refinedCrop in both axes (i.e. snap rounded
		// DOWN, not up). A regression that passed (imgW, imgH) as snap bounds would let snap round UP past
		// refinedCrop, re-violating the rotation-fit invariant.
		assertEquals("snapped dims must respect 4:5 aspect ratio exactly", finalW * 5, finalH * 4);
	}

	@Test
	public void recheckRotationFitSkipsOversizedCropAtSubEpsilonRotation()
	{
		// Sub-epsilon rotation must NOT alter the crop dims even when the recheck would otherwise trigger a
		// shrink. The 4400×3300 crop against a 4000×3000 image is load-bearing: an exactly-fitting 4000×3000
		// crop passes with or without the sub-epsilon guard (maxScaleForRotation returns ~1 either way), so
		// only an OVERSIZED crop detects guard removal — without the guard, the residual 0.001° angle runs
		// the recheck and visibly shrinks the crop to ~4001×3001 even though the user sees it as unrotated.
		CropState state = new CropState();
		state.setRotationDegrees(0f);
		state.setCenter(2000f, 1500f);
		state.setCropSizeSilent(4400, 3300);
		CropEngine.recheckRotationFit(state, 4000, 3000, 0.001f);
		assertEquals("dims must be unchanged under sub-epsilon rotation", 4400, state.getCropW());
		assertEquals("dims must be unchanged under sub-epsilon rotation", 3300, state.getCropH());
	}
}
