package com.cropcenter.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.cropcenter.model.CropState;

import org.junit.Test;

/**
 * Tests for ViewportMath's pure-math conversions and viewport-state mutators. Constructed against a FixedViewSize
 * fixture so the math runs on a host JVM without needing a Context-bound View, and the int-dimension overloads of
 * fitToView / clampViewport / panViewport / zoomAt are exercised directly so a Bitmap-less CropState isn't
 * required either. The View-bound + CropState-bound overloads are thin adapters; testing the int-dimension
 * variants exercises every contract the production callers depend on.
 *
 * The math being pinned:
 *   - imageToScreenX/Y and screenToImageX/Y are inverses modulo float precision
 *   - View center is read on every conversion (not cached at construction) so layout-after-construction works
 *   - getBaseScale / getZoom return documented defaults before fitToView
 *   - Conversions are linear in the image coordinate (slope is constant)
 *   - Zero-size view produces numerically-defined (non-NaN) conversions at default state
 *   - fitToView: baseScale = min(viewW/imgW, viewH/imgH); zoom reset to 1; viewport centered on image center
 *   - clampViewport: pins viewport within image bounds; centers axis when image fits within visible window
 *   - panViewport: screen-delta is divided by scale before applying to viewport; post-pan clamp fires
 *   - zoomAt: focus point stays under finger across zoom; zoom clamped to [1, 256]; post-zoom clamp fires
 */
public final class ViewportMathTest
{
	private static final float TOL = 1e-3f;

	@Test
	public void clampViewportCentersAxisWhenImageSmallerThanVisibleWindow()
	{
		// When the image is smaller than the visible window on an axis, that axis is centered on the
		// image center. Image 100x100, view 400x800, scale=1 → visibleW=400, visibleH=800. Both
		// visibles exceed the image dims; both axes center on imgW/2 = imgH/2 = 50.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		math.clampViewport(100, 100);
		assertEquals("X centered on image midX", 50f, math.screenToImageX(200f), TOL);
		assertEquals("Y centered on image midY", 50f, math.screenToImageY(400f), TOL);
	}

	@Test
	public void clampViewportPinsToBoundsWhenImageLargerThanVisibleWindow()
	{
		// fitToView always picks baseScale so the constrained axis has visibleDim == imgDim (the
		// centered branch). To exercise the pin-to-bounds branch we have to zoom in past fit so
		// visibleDim < imgDim. Setup: 1000x2000 image in 400x800 view → baseScale = 0.4, viewportX
		// starts at imgW/2 = 500. zoomAt(2x at view center) doubles zoom → scale = 0.8, viewportX
		// stays at 500 (focus-stationary identity). Now visibleW = 400/0.8 = 500 < imgW = 1000, so
		// the legal clamp range is [visibleW/2, imgW - visibleW/2] = [250, 750]. Pan an absurd dx
		// to drive viewportX past 0, then verify the post-pan clamp pinned it to the LOWER bound
		// (250), not the upper.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		math.fitToView(1000, 2000);
		math.zoomAt(2f, 200f, 400f, 1000, 2000);
		// pan dx=+10000 → viewportX -= 10000/0.8 = 12500 → viewportX = -12000 (way past left edge).
		// clampViewport (called inside panViewport) must pin to the lower bound visibleW/2 = 250.
		math.panViewport(10000f, 0f, 1000, 2000);
		assertEquals("pan past left edge pins viewportX to lower bound (visibleW / 2)",
			250f, math.screenToImageX(200f), TOL);
		// Symmetric: pan past the right edge. Reset by re-fitting + zooming, then pan the OTHER way.
		math.fitToView(1000, 2000);
		math.zoomAt(2f, 200f, 400f, 1000, 2000);
		math.panViewport(-10000f, 0f, 1000, 2000);
		assertEquals("pan past right edge pins viewportX to upper bound (imgW - visibleW / 2)",
			750f, math.screenToImageX(200f), TOL);
	}

	@Test
	public void clampViewportPinsToRotatedAabbBoundsWhenRotated()
	{
		// At 30° rotation on a 1000×2000 image, the rotated AABB is roughly 1866×2732 — it extends past the
		// un-rotated [0, 1000] × [0, 2000] rectangle on both axes. The legacy clamp (against the un-rotated
		// bitmap) would pin viewportX into [visibleW/2, imgW - visibleW/2] = [250, 750], making the rotated
		// corners (which sit at screen-aligned X far outside [0, 1000]) unreachable at zoom > 1. The
		// rotation-aware clamp must permit viewportX inside [midX - rotatedW/2 + visibleW/2,
		// midX + rotatedW/2 - visibleW/2] ≈ [-183, 1183]. Pin both the left- and right-pan extremes.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		math.fitToView(1000, 2000, 30f);
		math.zoomAt(2f, 200f, 400f, 1000, 2000, 30f);
		// scale after fit+2x zoom: baseScale × 2. visibleW = size.width() / scale. The exact lower
		// bound is midX - rotatedW/2 + visibleW/2; compute it from the same formula the clamp uses to
		// pin the contract regardless of the float rounding.
		float baseScale = math.getBaseScale();
		float scale = baseScale * 2f;
		float visibleW = 400f / scale;
		float rotatedW = (float) (1000 * Math.cos(Math.toRadians(30)) + 2000 * Math.sin(Math.toRadians(30)));
		float expectedLeftPin = 500f - rotatedW / 2f + visibleW / 2f;
		float expectedRightPin = 500f + rotatedW / 2f - visibleW / 2f;
		math.panViewport(100000f, 0f, 1000, 2000, 30f);
		assertEquals("rotated clamp must allow viewportX below 0 at the left pin",
			expectedLeftPin, math.screenToImageX(200f), TOL);
		math.fitToView(1000, 2000, 30f);
		math.zoomAt(2f, 200f, 400f, 1000, 2000, 30f);
		math.panViewport(-100000f, 0f, 1000, 2000, 30f);
		assertEquals("rotated clamp must allow viewportX above imgW at the right pin",
			expectedRightPin, math.screenToImageX(200f), TOL);
	}

	@Test
	public void clampViewportPureOverloadDelegatesFromCropStateOverloadWithNullImage()
	{
		// State-bound overload short-circuits on null state — verify by capturing the viewport state
		// before/after and confirming no change. The int-dimension overload would have rewritten
		// viewportX/Y on this input; the State-bound version SKIPS that rewrite.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		float xBefore = math.screenToImageX(200f);
		math.clampViewport((com.cropcenter.model.CropState) null);
		assertEquals("null state must not alter viewport", xBefore, math.screenToImageX(200f), 0f);
	}

	@Test
	public void differentViewDimensionsProduceCorrespondingViewCenters()
	{
		// Pin that the ViewSize abstraction is actually load-bearing — same image-x = 0 should map to
		// different screen coords for different view sizes. A regression that hard-coded a dimension
		// somewhere would surface here.
		FixedViewSize small = new FixedViewSize(200, 400);
		FixedViewSize big = new FixedViewSize(1000, 2000);
		ViewportMath smallMath = new ViewportMath(small);
		ViewportMath bigMath = new ViewportMath(big);
		assertEquals(100f, smallMath.imageToScreenX(0f), TOL);
		assertEquals(500f, bigMath.imageToScreenX(0f), TOL);
		assertEquals(200f, smallMath.imageToScreenY(0f), TOL);
		assertEquals(1000f, bigMath.imageToScreenY(0f), TOL);
	}

	@Test
	public void effectiveMinZoomAtRotationDropsBelowOneWhenBaseScaleSetForUnrotatedImage()
	{
		// User fit-to-view at rotation=0 (baseScale = view/imgDim), then rotated 30°. The rotated AABB now
		// extends past the un-rotated bitmap rectangle, so a pinch-out gesture at static MIN_ZOOM=1 would NOT
		// fit the rotated image. effectiveMinZoom should drop below 1 so the user can pinch further down to
		// where the rotated AABB fits inside the view. Pin the specific ratio: minZoom = (view/rotatedDim) /
		// baseScale = (view/rotatedDim) / (view/imgDim) = imgDim/rotatedDim.
		FixedViewSize size = new FixedViewSize(1000, 1000);
		ViewportMath math = new ViewportMath(size);
		math.fitToView(1000, 1000);                 // rotation=0 fit → baseScale = 1
		float minZoom30 = math.effectiveMinZoom(1000, 1000, 30f);
		// Square 1000x1000 at 30°: rotatedDim = 1000*(cos30 + sin30) = 1366.025.
		// expected min zoom = 1000 / 1366.025 ≈ 0.7321
		float expected = (float) (1.0 / (Math.cos(Math.toRadians(30)) + Math.sin(Math.toRadians(30))));
		assertEquals("rotated min zoom drops below 1 to fit the rotated AABB",
			expected, minZoom30, TOL);
	}

	@Test
	public void effectiveMinZoomAtZeroRotationReturnsStaticMinZoom()
	{
		// Zero rotation collapses to the static MIN_ZOOM = 1, so user behavior at the common unrotated
		// case is unchanged. A regression that introduced a baseScale-relative formula at rotation=0 would
		// shift the zoom-out floor in the user's default workflow.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		math.fitToView(1000, 2000);
		assertEquals(1f, math.effectiveMinZoom(1000, 2000, 0f), TOL);
		// Sub-epsilon rotation matches zero exactly (no float drift).
		assertEquals(1f, math.effectiveMinZoom(1000, 2000, 0.001f), TOL);
	}

	@Test
	public void effectiveMinZoomNeverExceedsOne()
	{
		// Contract: minZoom = min(1, rotatedRatio). A rotation back to 0° (or to a smaller-AABB angle than
		// baseScale was fit to) shouldn't raise minZoom above 1 — that would prevent the user from zooming
		// in past the fit-to-rotated baseScale. Pin the upper-bound enforcement directly.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		// fit-to-view at rotation=45° produces baseScale = view / (imgDim * sqrt(2)) — smaller than the
		// unrotated baseScale. Then querying minZoom at rotation=0 (no rotation, AABB smaller than rotated)
		// would compute ratio > 1; the static min must cap it.
		math.fitToView(1000, 2000, 45f);
		float minAtZero = math.effectiveMinZoom(1000, 2000, 0f);
		assertTrue("minZoom must not exceed 1", minAtZero <= 1f + TOL);
	}

	@Test
	public void effectiveMinZoomShortCircuitsOnDefaultState()
	{
		// At construction (before any fitToView call) baseScale = 1 (the documented default). The
		// short-circuit at the top of effectiveMinZoom is gated on `baseScale <= 0 || imgW <= 0 ||
		// imgH <= 0 || view dim == 0`. Pin each sub-clause:
		//   1. zero imgW → MIN_ZOOM
		//   2. zero imgH → MIN_ZOOM
		//   3. negative imgW → MIN_ZOOM (we don't trust caller-supplied negatives)
		//   4. zero view dim → MIN_ZOOM (view not measured yet)
		// A regression that dropped any sub-clause would yield NaN / Infinity from the division on the
		// next branch and silently propagate through the zoom clamp.
		ViewportMath math = new ViewportMath(new FixedViewSize(400, 800));
		math.fitToView(1000, 2000);  // baseScale > 0 so we exercise the dim short-circuits below
		assertEquals("zero imgW → MIN_ZOOM", 1f, math.effectiveMinZoom(0, 2000, 30f), 0f);
		assertEquals("zero imgH → MIN_ZOOM", 1f, math.effectiveMinZoom(1000, 0, 30f), 0f);
		assertEquals("negative imgW → MIN_ZOOM", 1f, math.effectiveMinZoom(-1, 2000, 30f), 0f);
		// Zero-view check: a fresh ViewportMath against a 0x0 ViewSize hits the size.width()==0 branch.
		ViewportMath zeroView = new ViewportMath(new FixedViewSize(0, 0));
		zeroView.fitToView(1000, 2000);
		assertEquals("zero view → MIN_ZOOM", 1f, zeroView.effectiveMinZoom(1000, 2000, 30f), 0f);
	}

	@Test
	public void effectiveMinZoomStateOverloadReturnsMinZoomOnNullState()
	{
		// CropState overload's null-state branch returns MIN_ZOOM directly without touching baseScale —
		// pin so the production callers (zoomAt with state) don't have to re-check null.
		ViewportMath math = new ViewportMath(new FixedViewSize(400, 800));
		math.fitToView(1000, 2000);
		assertEquals(1f, math.effectiveMinZoom((com.cropcenter.model.CropState) null), 0f);
	}

	@Test
	public void fitToViewBaseScaleTakesMinAcrossNonSquareImageAndView()
	{
		// Landscape image 4000x1000 into portrait view 400x800: width ratio 400/4000=0.1, height ratio
		// 800/1000=0.8. min is 0.1 (width-constrained). Pin so a regression that flipped min for max
		// would zoom too far in and lose the bottom of the image off-screen.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		math.fitToView(4000, 1000);
		assertEquals("width-constrained → smaller ratio wins", 0.1f, math.getBaseScale(), TOL);
	}

	@Test
	public void fitToViewCentersViewportAndComputesBaseScaleAsMinOfDimRatios()
	{
		// fitToView for 1000x2000 image into 400x800 view: baseScale = min(400/1000, 800/2000) = min(0.4, 0.4)
		// = 0.4. zoom resets to 1. viewportX = imgW/2 = 500. viewportY = imgH/2 = 1000. Pin all four.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		math.fitToView(1000, 2000);
		assertEquals("baseScale = min(viewW/imgW, viewH/imgH)", 0.4f, math.getBaseScale(), TOL);
		assertEquals("zoom reset to 1", 1f, math.getZoom(), 0f);
		assertEquals("viewportX centered on image center", 500f, math.screenToImageX(200f), TOL);
		assertEquals("viewportY centered on image center", 1000f, math.screenToImageY(400f), TOL);
	}

	@Test
	public void fitToViewPureOverloadIsCalledFromCropStateOverload()
	{
		// CropState-bound overload short-circuits on null state. We can't construct a CropState with a
		// loaded Bitmap on a JVM, so verify the short-circuit directly: a null state must NOT alter
		// baseScale / zoom / viewport from their construction defaults.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		float baseScaleBefore = math.getBaseScale();
		float zoomBefore = math.getZoom();
		math.fitToView((com.cropcenter.model.CropState) null);
		assertEquals("null state must not change baseScale", baseScaleBefore, math.getBaseScale(), 0f);
		assertEquals("null state must not change zoom", zoomBefore, math.getZoom(), 0f);
	}

	@Test
	public void fitToViewWith45DegreeSquareImageFitsRotatedBoundingBox()
	{
		// 1000x1000 square image rotated 45° has axis-aligned bounding box 1000*sqrt(2) on each side
		// (~= 1414.21). Fitting that into a 1000x1000 view should pick baseScale = 1000 / (1000*sqrt(2))
		// = 1/sqrt(2) ≈ 0.7071. A regression that uses the unrotated dims (baseScale = 1.0) lets the
		// rotated image extend past the view's edges — the literal bug fixed by adding rotation
		// awareness to the fit math.
		FixedViewSize size = new FixedViewSize(1000, 1000);
		ViewportMath math = new ViewportMath(size);
		math.fitToView(1000, 1000, 45f);
		float expected = (float) (1.0 / Math.sqrt(2));
		assertEquals("baseScale fits the 45°-rotated bounding box", expected, math.getBaseScale(), TOL);
		assertEquals("zoom resets to 1", 1f, math.getZoom(), 0f);
	}

	@Test
	public void fitToViewWithRotationFitsRotatedBoundingBoxWithinViewBounds()
	{
		// General contract: for any rotation, the rotated bounding box (widthForFit, heightForFit) must
		// fit inside the view dims after scaling by baseScale. Tests several non-trivial angles on a
		// non-square image to catch axis-swap bugs.
		int imgW = 4000;
		int imgH = 3000;
		FixedViewSize size = new FixedViewSize(800, 600);
		ViewportMath math = new ViewportMath(size);
		float[] anglesDegrees = { 15f, 30f, 60f, 75f, 90f, 135f, 200f, -45f };
		for (float deg : anglesDegrees)
		{
			math.fitToView(imgW, imgH, deg);
			double rad = Math.toRadians(deg);
			double absCos = Math.abs(Math.cos(rad));
			double absSin = Math.abs(Math.sin(rad));
			double rotatedW = imgW * absCos + imgH * absSin;
			double rotatedH = imgW * absSin + imgH * absCos;
			float scale = math.getBaseScale();
			double scaledRotatedW = scale * rotatedW;
			double scaledRotatedH = scale * rotatedH;
			assertTrue("scaled rotated W fits view at " + deg + "°: " + scaledRotatedW
				+ " <= " + size.width(), scaledRotatedW <= size.width() + TOL);
			assertTrue("scaled rotated H fits view at " + deg + "°: " + scaledRotatedH
				+ " <= " + size.height(), scaledRotatedH <= size.height() + TOL);
		}
	}

	@Test
	public void fitToViewWithSubEpsilonRotationCollapsesToUnrotatedFormula()
	{
		// Sub-epsilon rotation (< BitmapUtils.ROTATION_EPSILON = 0.005°) is treated as zero by the rest
		// of the render pipeline, so the fit math collapses to the unrotated branch — produces the same
		// baseScale as the 2-arg overload exactly. Without the collapse a 0.001° rotation would shave a
		// negligible-but-nonzero fraction off baseScale, causing visible-but-inexplicable zoom drift on
		// every double-tap.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath rotMath = new ViewportMath(size);
		ViewportMath plainMath = new ViewportMath(size);
		rotMath.fitToView(1000, 2000, 0.001f);
		plainMath.fitToView(1000, 2000);
		assertEquals("sub-epsilon rotation matches unrotated baseScale",
			plainMath.getBaseScale(), rotMath.getBaseScale(), 0f);
	}

	@Test
	public void getBaseScaleAndGetZoomReturnDefaultsBeforeFitToView()
	{
		// Pin the construction-time defaults. Without fitToView, baseScale = 1, zoom = 1.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		assertEquals(1f, math.getBaseScale(), 0f);
		assertEquals(1f, math.getZoom(), 0f);
	}

	@Test
	public void identicalConversionsAcrossInstanceConstructions()
	{
		// Two instances at the same dimensions and default state produce identical conversions for
		// identical inputs. Pin so a regression that stored state in a static / shared place surfaces
		// here (different instances would interfere).
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath firstInstance = new ViewportMath(size);
		ViewportMath secondInstance = new ViewportMath(size);
		assertEquals(firstInstance.imageToScreenX(123.4f), secondInstance.imageToScreenX(123.4f), 0f);
		assertEquals(firstInstance.screenToImageY(56.7f), secondInstance.screenToImageY(56.7f), 0f);
	}

	@Test
	public void imageToScreenRotatedIntoAppliesRotationAroundImageScreenCenter()
	{
		// With a non-trivial rotation (90°) and a default state where state.getImageWidth/Height return 0
		// (no source bitmap), the rotation center collapses to the view center. A point at image-coord
		// (0, 0) maps to screen-center (viewW/2, viewH/2) = (200, 400) before rotation; rotating around
		// the view center is the identity, so the post-rotation output is still (200, 400). Pin this so a
		// regression that rotated around (0, 0) screen instead of around the image center would surface.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		CropState state = new CropState();
		state.setRotationDegrees(90f);
		float[] out = new float[2];
		math.imageToScreenRotatedInto(0f, 0f, state, out);
		// Pre-rotation: (200, 400). Image center = (0, 0) image-coord → also (200, 400) screen. Rotating
		// (200, 400) around (200, 400) is identity → (200, 400) post-rotation.
		assertEquals(200f, out[0], 1e-3f);
		assertEquals(400f, out[1], 1e-3f);
	}

	@Test
	public void imageToScreenRotatedIntoNinetyDegreesSwapsXAndYAroundImageCenter()
	{
		// At 90° rotation around the view center (= image center when no bitmap loaded), image-coord
		// (100, 0) → pre-rotation screen (200 + 100, 400 + 0) = (300, 400) (delta from center = (100,
		// 0)). RotationMath's CW convention rotates (dx, dy) = (100, 0) to (cos·dx - sin·dy, sin·dx +
		// cos·dy) at θ=90° → (0, 100). Final screen position = (200, 500). Pin this exact transform.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		CropState state = new CropState();
		state.setRotationDegrees(90f);
		float[] out = new float[2];
		math.imageToScreenRotatedInto(100f, 0f, state, out);
		// cos(90°) in float is 6.12e-17, so dx*cos ≈ 0; the result is essentially exact.
		assertEquals(200f, out[0], 1e-3f);
		assertEquals(500f, out[1], 1e-3f);
	}

	@Test
	public void imageToScreenRotatedIntoNullStateTreatedAsZeroRotation()
	{
		// Pin: null state must NOT NPE. The contract is "null state == zero rotation == identity branch".
		// A regression that dereferenced state without a null check would NPE here; one that fell through
		// to the rotation branch would still NPE on state.getImageWidth().
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		float[] out = new float[2];
		math.imageToScreenRotatedInto(100f, 200f, null, out);
		// Identity branch outputs match imageToScreenX/Y directly: 400/2 + 100*1 = 300, 800/2 + 200 = 600.
		assertEquals(300f, out[0], TOL);
		assertEquals(600f, out[1], TOL);
	}

	@Test
	public void imageToScreenRotatedIntoReturnsCallerBufferReference()
	{
		// Pin the alias contract — the function returns the SAME reference passed in `out`, not a fresh
		// array. onDraw relies on this to avoid per-frame allocation; a regression to `return new
		// float[]{...}` would re-introduce GC churn the renderer specifically avoids.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		float[] out = new float[2];
		float[] result = math.imageToScreenRotatedInto(50f, 60f, null, out);
		assertSame("returns the caller's buffer reference", out, result);
		// And the buffer is mutated.
		assertEquals(250f, out[0], TOL);
		assertEquals(460f, out[1], TOL);
	}

	@Test
	public void imageToScreenRotatedIntoSubEpsilonRotationTakesIdentityBranch()
	{
		// Below the ROTATION_EPSILON snap threshold, the function uses the un-rotated screen coords
		// directly (no trig). Pin so the renderer's "treat tiny rotations as zero" agreement holds; a
		// regression that snapped rotation differently between math and renderer would cause tap-mapping
		// drift while the image is being drawn straight. ROTATION_EPSILON is 0.005°, so 0.001° is well
		// below the threshold.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		CropState state = new CropState();
		state.setRotationDegrees(0.001f);
		float[] out = new float[2];
		math.imageToScreenRotatedInto(100f, 0f, state, out);
		// Identity branch: same output as imageToScreenX/Y, no rotation math invoked.
		assertEquals(math.imageToScreenX(100f), out[0], 0f);
		assertEquals(math.imageToScreenY(0f), out[1], 0f);
	}

	@Test
	public void imageToScreenRoundTripsThroughScreenToImage()
	{
		// At any (baseScale, zoom, viewportX, viewportY) state, screenToImageX(imageToScreenX(ix)) must
		// equal ix within float precision. Without a CropState we can only test the no-rotation path (no
		// CropState means no rotation applied). Pin the identity for several (ix, iy) values across the
		// default viewport state (zoom = 1, baseScale = 1, viewport = 0/0).
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		// Default state: baseScale = 1, zoom = 1, viewport = (0, 0).
		float[] testPoints = { 0f, 100f, 200.5f, -50f, 1000f };
		for (float ix : testPoints)
		{
			float sx = math.imageToScreenX(ix);
			float back = math.screenToImageX(sx);
			assertEquals("ix " + ix + " must round-trip through screen X", ix, back, TOL);
		}
	}

	@Test
	public void imageToScreenXCentersZeroOnViewWidth()
	{
		// At default state (viewportX = 0, scale = 1), the image-x=0 maps to view center = viewWidth / 2.
		// Pin so a regression that flipped the "center on viewport" semantics surfaces.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		assertEquals(200f, math.imageToScreenX(0f), TOL);
		assertEquals(400f, math.imageToScreenY(0f), TOL);
	}

	@Test
	public void imageToScreenXIsLinearInImageCoordinate()
	{
		// Slope check: imageToScreenX(ix + 1) - imageToScreenX(ix) is a constant for any ix (= the scale).
		// Pin so a regression that introduced a quadratic / non-linear term in the mapping surfaces here.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		float slopeNearOrigin = math.imageToScreenX(10f) - math.imageToScreenX(9f);
		float slopeFarFromOrigin = math.imageToScreenX(200f) - math.imageToScreenX(199f);
		assertEquals("slope is constant across image space", slopeNearOrigin, slopeFarFromOrigin, TOL);
		assertEquals("at default scale=1, 1 image pixel = 1 screen pixel", 1f, slopeNearOrigin, TOL);
	}

	@Test
	public void maxZoomScalesInverselyWithBaseScale()
	{
		// Each source pixel renders at most MAX_PIXEL_RATIO (64) screen pixels regardless of source
		// size — so a small image (fit at baseScale ≈ 1) caps at zoom 64, while a large image (fit at
		// baseScale ≈ 0.1) caps at zoom 640. Pin both ends so a regression to a flat constant ceiling
		// surfaces immediately.
		FixedViewSize small = new FixedViewSize(1000, 1000);
		ViewportMath smallMath = new ViewportMath(small);
		smallMath.fitToView(1000, 1000); // baseScale = 1
		smallMath.zoomAt(10_000f, 500f, 500f, 1000, 1000);
		assertEquals("small image (baseScale=1) caps at MAX_PIXEL_RATIO = 64",
			64f, smallMath.getZoom(), TOL);

		FixedViewSize large = new FixedViewSize(1000, 1000);
		ViewportMath largeMath = new ViewportMath(large);
		largeMath.fitToView(10000, 10000); // baseScale = 0.1
		largeMath.zoomAt(10_000f, 500f, 500f, 10000, 10000);
		assertEquals("large image (baseScale=0.1) caps at 64 / 0.1 = 640",
			640f, largeMath.getZoom(), TOL);
	}

	@Test
	public void panViewportDividesScreenDeltaByScaleAndClamps()
	{
		// At fitToView, the constrained axis has visibleDim == imgDim so the clamp re-centers any
		// pan attempt back to imgDim/2 (visibleW >= imgW branch). To actually exercise the /scale
		// conversion AND the clamp's pin-to-bounds branch, zoom in first so visibleW < imgW. Setup:
		// 1000x2000 image into 400x800 view. fitToView → baseScale=0.4, viewportX=500. zoomAt(2x at
		// screen-center) → zoom=2, scale=0.8, viewportX stays 500 (focus identity). Now visibleW =
		// 400/0.8 = 500 < imgW=1000 so clamp range is [250, 750]. Pan dx=+20 → viewportX -= 20/0.8
		// = 25 → viewportX = 475 (within the range, no clamp adjustment fires).
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		math.fitToView(1000, 2000);
		math.zoomAt(2f, 200f, 400f, 1000, 2000);
		math.panViewport(20f, 0f, 1000, 2000);
		assertEquals("dx=+20 at scale=0.8 moves viewportX left by 25 to 475",
			475f, math.screenToImageX(200f), TOL);
	}

	@Test
	public void panViewportPureOverloadDelegatesFromCropStateOverloadWithNullImage()
	{
		// State-bound overload skips ONLY the clamp on null state; the pan arithmetic still runs.
		// Verify: starting from default state (viewportX = 0, baseScale = 1, zoom = 1, scale = 1),
		// pan dx=+10 should move viewportX by -10. Reading screenToImageX(200) — the screen-center —
		// should yield -10 (was 0 before; now viewportX = -10).
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		assertEquals("baseline: screen-center maps to viewportX = 0", 0f, math.screenToImageX(200f), 0f);
		math.panViewport(10f, 0f, (com.cropcenter.model.CropState) null);
		assertEquals("pan arithmetic ran even with null state", -10f, math.screenToImageX(200f), TOL);
	}

	@Test
	public void roundTripPreservesPrecisionAtLargeViewDimensions()
	{
		// 4K viewport stress: round-trip through (4096, 2048) view. Pin no float-precision blowup.
		FixedViewSize size = new FixedViewSize(4096, 2048);
		ViewportMath math = new ViewportMath(size);
		float ix = 12345.678f;
		float roundTripped = math.screenToImageX(math.imageToScreenX(ix));
		assertTrue("precision loss must stay under 1e-2 at 4K dims: got " + roundTripped,
			Math.abs(roundTripped - ix) < 1e-2f);
	}

	@Test
	public void screenToImagePixelIntoNullStateTreatedAsZeroRotation()
	{
		// Null state must NOT NPE; treated as zero rotation. Same contract as
		// imageToScreenRotatedIntoNullStateTreatedAsZeroRotation but for the inverse direction.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		float[] out = new float[2];
		math.screenToImagePixelInto(250f, 460f, null, out);
		// Identity branch: screenToImageX/Y directly. (250 - 200) / 1 = 50, (460 - 400) / 1 = 60.
		assertEquals(50f, out[0], TOL);
		assertEquals(60f, out[1], TOL);
	}

	@Test
	public void screenToImagePixelIntoRoundTripsThroughImageToScreenRotatedInto()
	{
		// The inverse contract: for any rotation angle (including the sub-epsilon identity branch),
		// `screenToImagePixelInto(imageToScreenRotatedInto(ix, iy))` must return (ix, iy) modulo float
		// precision. Pin so a regression in EITHER direction's rotation-center / sign / order would
		// surface here — both methods must agree on the same rotation center.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		CropState state = new CropState();
		float[] forward = new float[2];
		float[] back = new float[2];
		for (float rotation : new float[] { 0f, 0.001f, 30f, 45f, 90f, -45f, -90f })
		{
			state.setRotationDegrees(rotation);
			float ix = 75.5f;
			float iy = -42.25f;
			math.imageToScreenRotatedInto(ix, iy, state, forward);
			math.screenToImagePixelInto(forward[0], forward[1], state, back);
			assertEquals("ix round-trips at rotation=" + rotation, ix, back[0], 1e-3f);
			assertEquals("iy round-trips at rotation=" + rotation, iy, back[1], 1e-3f);
		}
	}

	@Test
	public void screenToImagePixelIntoSubEpsilonRotationMatchesUnrotatedPath()
	{
		// The same screen point must yield the same image point whether rotation is 0 or 0.001° — the
		// sub-epsilon snap prevents tap-mapping drift while the renderer draws the image straight. A
		// regression that didn't collapse rotation < ROTATION_EPSILON to identity in BOTH methods would
		// produce different image-coords for a finger touch on a not-actually-rotated image.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		CropState state = new CropState();
		float[] zeroOut = new float[2];
		float[] subEpsOut = new float[2];
		state.setRotationDegrees(0f);
		math.screenToImagePixelInto(123f, 456f, state, zeroOut);
		state.setRotationDegrees(0.001f);
		math.screenToImagePixelInto(123f, 456f, state, subEpsOut);
		assertEquals("sub-epsilon X matches zero-rotation X exactly", zeroOut[0], subEpsOut[0], 0f);
		assertEquals("sub-epsilon Y matches zero-rotation Y exactly", zeroOut[1], subEpsOut[1], 0f);
	}

	@Test
	public void screenToImagePixelReturnsFreshArrayDistinctFromInternalBuffer()
	{
		// The convenience overload screenToImagePixel allocates a fresh float[2] per call. Pin so a
		// regression that returned an internal cached buffer would corrupt callers that stash results
		// across pan/zoom updates. Use assertNotSame for reference-identity — identityHashCode is not
		// guaranteed unique per Object.hashCode contract, so two distinct arrays could theoretically
		// hash-collide and falsely pass an identityHashCode-inequality check.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		float[] a = math.screenToImagePixel(100f, 100f, null);
		float[] b = math.screenToImagePixel(100f, 100f, null);
		assertNotSame("each call allocates a fresh array, not an internal cached buffer", a, b);
		assertEquals("both arrays carry equal values", a[0], b[0], 0f);
		assertEquals("both arrays carry equal values", a[1], b[1], 0f);
	}

	@Test
	public void screenToImageXInvertsTheTranslation()
	{
		// At default state, screenX = viewW/2 maps back to image-x = 0. Pin the inverse formula.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		assertEquals(0f, math.screenToImageX(200f), TOL);
		// screenX = viewW/2 + 50 maps to image-x = 50 (at scale=1).
		assertEquals(50f, math.screenToImageX(250f), TOL);
	}

	@Test
	public void screenToImageYInvertsImageToScreenY()
	{
		// Round-trip on the Y axis. The same scale × baseScale × translation chain.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		for (float iy : new float[] { 0f, 100f, -100f, 500.25f })
		{
			float sy = math.imageToScreenY(iy);
			float back = math.screenToImageY(sy);
			assertEquals("iy " + iy + " round-trips", iy, back, TOL);
		}
	}

	@Test
	public void viewSizeAdapterIsReadOnEveryConversionCall()
	{
		// Pin that ViewSize.width() / height() are called each time, not cached at construction. A
		// regression that captured dimensions in the constructor would break the layout-then-fitToView
		// flow (View's getWidth/getHeight return 0 before layout, and the production CropEditorView
		// constructs ViewportMath in the field initializer — before measure / layout). Use a mutable
		// FixedViewSize to test this.
		MutableViewSize size = new MutableViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		assertEquals(200f, math.imageToScreenX(0f), TOL);
		size.width = 1000;
		assertEquals("ViewSize.width() must be re-read on each call: 1000/2 = 500",
			500f, math.imageToScreenX(0f), TOL);
	}

	@Test
	public void zeroSizedViewProducesNonNanConversions()
	{
		// Edge case: View not yet measured (getWidth/getHeight = 0). At construction default baseScale =
		// 1 and zoom = 1, so scale = 1 (NOT collapsed — the production fitToView guards against zero-width
		// view; the raw imageToScreenX still runs with default state). The conversion formula is
		// `viewW/2 + (ix - viewportX) * scale` → `0 + (100 - 0) * 1 = 100`. Pin: the function must NOT
		// throw / NaN-out, and the result is the deterministic value from the formula at default state.
		FixedViewSize empty = new FixedViewSize(0, 0);
		ViewportMath math = new ViewportMath(empty);
		float result = math.imageToScreenX(100f);
		assertEquals("zero-view, default scale = 1: 0 + (100 - 0) * 1 = 100", 100f, result, TOL);
		// Inverse: viewportX + (sx - viewW/2) / scale = 0 + (100 - 0) / 1 = 100.
		assertEquals(100f, math.screenToImageX(100f), TOL);
	}

	@Test
	public void zoomAtAllowsPinchOutBelowOneWhenRotationRequires()
	{
		// End-to-end: user fits at rotation=0, rotates 30°, pinches out. Without the fix, zoom clamps to 1
		// (hardcoded MIN_ZOOM) and the rotated image extends past the view. With the fix, the clamp's lower
		// bound drops so a sufficiently-large scaleFactor < 1 reduces zoom below 1.
		FixedViewSize size = new FixedViewSize(1000, 1000);
		ViewportMath math = new ViewportMath(size);
		math.fitToView(1000, 1000);                 // baseScale = 1, zoom = 1
		math.zoomAt(0.5f, 500f, 500f, 1000, 1000, 30f);
		float expected = (float) (1.0 / (Math.cos(Math.toRadians(30)) + Math.sin(Math.toRadians(30))));
		assertEquals("pinch-out at rotation clamps to the rotated-AABB min zoom",
			expected, math.getZoom(), TOL);
	}

	@Test
	public void zoomAtClampsToMinAndMaxZoom()
	{
		// Zoom factor multiplied by current zoom is clamped to [MIN_ZOOM=1, maxZoom()]. The upper
		// bound is per-image: maxZoom() = MAX_PIXEL_RATIO / baseScale so each source pixel renders at
		// most MAX_PIXEL_RATIO (64) screen pixels. View 400×800 + image 1000×2000 → baseScale = 0.4,
		// so maxZoom = 64 / 0.4 = 160. From default zoom=1, a 0.01 factor clamps DOWN to 1; a 1000
		// factor clamps UP to 160.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		math.fitToView(1000, 2000);
		math.zoomAt(0.01f, 200f, 400f, 1000, 2000);
		assertEquals("zoom clamps to MIN_ZOOM = 1 on absurdly-low factor", 1f, math.getZoom(), 0f);
		math.zoomAt(1000f, 200f, 400f, 1000, 2000);
		assertEquals("zoom clamps to maxZoom() = 64 / baseScale = 160 on absurdly-high factor",
			160f, math.getZoom(), TOL);
	}

	@Test
	public void zoomAtKeepsFocusPointStationaryWhenViewportClampDoesNotInterfere()
	{
		// The "focus stays under the finger" identity holds when the post-zoom viewport clamp doesn't
		// have to pull the viewport away from the focus. Use a fitToView setup where the focus is at
		// the image-center screen position (200, 400) — the viewport center stays put across the zoom
		// (no clamp interference). Image at center maps to screen-center BEFORE the zoom and AFTER
		// the zoom; image-x of the focus is invariant.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		math.fitToView(1000, 2000);
		// Pre-zoom: screen (200, 400) maps to image (500, 1000) (image center).
		float preFocusImgX = math.screenToImageX(200f);
		float preFocusImgY = math.screenToImageY(400f);
		assertEquals(500f, preFocusImgX, TOL);
		assertEquals(1000f, preFocusImgY, TOL);
		math.zoomAt(2f, 200f, 400f, 1000, 2000);
		// Post-zoom: screen (200, 400) should still map to the same image point (the focus didn't
		// move from under the finger).
		assertEquals("focus image-X stays put", preFocusImgX, math.screenToImageX(200f), TOL);
		assertEquals("focus image-Y stays put", preFocusImgY, math.screenToImageY(400f), TOL);
	}

	@Test
	public void zoomAtPureOverloadDelegatesFromCropStateOverloadWithNullImage()
	{
		// Like panViewport's State-bound overload, zoomAt's null-state path runs the zoom + viewport-shift
		// arithmetic but skips the post-zoom clamp. Verify by checking that zoom landed at the clamped
		// value and the focus identity holds at default scale=1.
		FixedViewSize size = new FixedViewSize(400, 800);
		ViewportMath math = new ViewportMath(size);
		// Default state: baseScale = 1, zoom = 1, viewportX = 0, viewportY = 0.
		math.zoomAt(2f, 200f, 400f, (com.cropcenter.model.CropState) null);
		assertEquals("zoom clamped to 2 (within [1, 256])", 2f, math.getZoom(), 0f);
		// At zoom=2, screen-center (200, 400) maps back to viewportX/Y. The zoomAt shift kept the focus
		// stationary so (200, 400) still maps to the pre-zoom image point — which was (0, 0) at default
		// viewport. Confirm.
		assertEquals("focus point stays under finger across zoom", 0f, math.screenToImageX(200f), TOL);
		assertEquals("focus point stays under finger across zoom", 0f, math.screenToImageY(400f), TOL);
	}

	/**
	 * Constant-dimensions ViewSize fixture. The simplest possible test double.
	 */
	private static final class FixedViewSize implements ViewportMath.ViewSize
	{
		private final int height;
		private final int width;

		FixedViewSize(int width, int height)
		{
			this.width = width;
			this.height = height;
		}

		@Override
		public int height()
		{
			return height;
		}

		@Override
		public int width()
		{
			return width;
		}
	}

	/**
	 * Mutable ViewSize fixture — lets one test exercise the "ViewSize is re-read on every call" contract by
	 * changing dimensions between conversions.
	 */
	private static final class MutableViewSize implements ViewportMath.ViewSize
	{
		int height;
		int width;

		MutableViewSize(int width, int height)
		{
			this.width = width;
			this.height = height;
		}

		@Override
		public int height()
		{
			return height;
		}

		@Override
		public int width()
		{
			return width;
		}
	}
}
