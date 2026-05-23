package com.cropcenter.view;

import android.view.View;

import com.cropcenter.model.CropState;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.RotationMath;

/**
 * Viewport coordinate transforms + zoom/pan state for CropEditorView. Owns the baseScale, viewport origin (viewportX /
 * viewportY), and zoom factor. All conversions assume the image is centered in the view at the viewport origin;
 * screenToImagePixel additionally un-rotates for CropState's rotation so callers can tap-select inside a rotated image.
 *
 * No rendering, no gestures — just math. The hosting View is referenced only for its width and height (the dimensions
 * the math is relative to). Production callers pass a View via the View-bound constructor; tests construct against a
 * ViewSize fixture so the conversions can be exercised on a host JVM without Robolectric.
 */
final class ViewportMath
{
	/**
	 * Abstracts the View dimensions used by every conversion. Production: a thin adapter around View.getWidth /
	 * getHeight. Tests: a fixture with constant dimensions. Two methods rather than one so the lambda /
	 * functional-interface shape (`int width(); int height();`) stays readable at call sites.
	 */
	interface ViewSize
	{
		int height();

		int width();
	}

	private static final float MAX_ZOOM = 256f;
	private static final float MIN_ZOOM = 1f;

	private final ViewSize size;

	private float baseScale = 1f;
	private float viewportX = 0; // viewport origin in image space (X)
	private float viewportY = 0; // viewport origin in image space (Y)
	private float zoom = 1f;

	/**
	 * View-bound constructor. Wraps view.getWidth() / view.getHeight() in a ViewSize adapter so the rest of the
	 * math reads dimensions through the same chokepoint a unit test would.
	 *
	 * @param view the hosting view; ViewportMath captures only getWidth() / getHeight() reads against it
	 */
	ViewportMath(View view)
	{
		this(new ViewSize()
		{
			@Override
			public int height()
			{
				return view.getHeight();
			}

			@Override
			public int width()
			{
				return view.getWidth();
			}
		});
	}

	/**
	 * Test-friendly constructor — takes the ViewSize directly so unit tests can supply fixed dimensions without a
	 * Context-bound View.
	 *
	 * @param size source of width / height for every screen-space conversion
	 */
	ViewportMath(ViewSize size)
	{
		this.size = size;
	}

	/**
	 * Clamp viewportX / viewportY so the viewport stays inside the image bounds. If the image is smaller than the
	 * visible window on an axis, that axis is centered. Skips clamping when state is null or has no loaded image
	 * (the gesture handlers reach this from any drag callback; a no-image session is a no-op).
	 *
	 * @param state CropState supplying the image dimensions; null / no-image short-circuits without mutating
	 *              the viewport
	 */
	void clampViewport(CropState state)
	{
		if (state == null || state.getSourceImage() == null)
		{
			return;
		}
		clampViewport(state.getImageWidth(), state.getImageHeight(), state.getRotationDegrees());
	}

	/**
	 * Zero-rotation pure-geometry overload. Backward-compat shim for tests / callers that don't have a rotation
	 * to pass; delegates to the rotation-aware overload with rotation = 0.
	 *
	 * @param imgW image width in image pixels (must be ≥ 0; behavior degenerate at 0)
	 * @param imgH image height in image pixels (must be ≥ 0; behavior degenerate at 0)
	 */
	void clampViewport(int imgW, int imgH)
	{
		clampViewport(imgW, imgH, 0f);
	}

	/**
	 * Pure-geometry overload taking image dimensions plus rotation. Clamps the viewport center against the
	 * rotated AABB (`rotatedW = |imgW·cos θ| + |imgH·sin θ|`, `rotatedH = |imgW·sin θ| + |imgH·cos θ|`) rather
	 * than the un-rotated rectangle, so at non-cardinal rotations the user can still pan to the rotated image's
	 * far corners — which by definition extend past `[0, imgW] × [0, imgH]` in screen-aligned image coords.
	 * Without this, fitToView + effectiveMinZoom + the rotation-aware crop math would all let the user fit and
	 * compose against the rotated image while a stale clamp pinned the viewport center to the un-rotated
	 * rectangle, making the rotated corners unreachable at high zoom.
	 *
	 * Sub-epsilon rotations route through `RotationMath.rotatedAabbDimensions`'s identity branch and the clamp
	 * collapses to the un-rotated bounds exactly. The rotated AABB midpoint coincides with `(imgW/2, imgH/2)`,
	 * so the centering branch (`visibleDim >= rotatedDim`) still pins to the same point as the legacy clamp.
	 *
	 * Package-private so ViewportMathTest can pin the rotated clamp range directly.
	 *
	 * @param imgW            image width in image pixels (must be ≥ 0; behavior degenerate at 0)
	 * @param imgH            image height in image pixels (must be ≥ 0; behavior degenerate at 0)
	 * @param rotationDegrees current image rotation; sub-epsilon values collapse to the un-rotated branch via
	 *                        RotationMath's identity short-circuit
	 */
	void clampViewport(int imgW, int imgH, float rotationDegrees)
	{
		float scale = baseScale * zoom;
		float visibleW = size.width() / scale;
		float visibleH = size.height() / scale;
		float[] rotatedDims = RotationMath.rotatedAabbDimensions(imgW, imgH, rotationDegrees, new float[2]);
		float midX = imgW / 2f;
		float midY = imgH / 2f;
		float halfRotatedW = rotatedDims[0] / 2f;
		float halfRotatedH = rotatedDims[1] / 2f;

		if (visibleW >= rotatedDims[0])
		{
			viewportX = midX;
		}
		else
		{
			viewportX = Math.clamp(viewportX, midX - halfRotatedW + visibleW / 2f,
				midX + halfRotatedW - visibleW / 2f);
		}

		if (visibleH >= rotatedDims[1])
		{
			viewportY = midY;
		}
		else
		{
			viewportY = Math.clamp(viewportY, midY - halfRotatedH + visibleH / 2f,
				midY + halfRotatedH - visibleH / 2f);
		}
	}

	/**
	 * CropState overload of `effectiveMinZoom(int, int, float)`. Returns the static `MIN_ZOOM` when state is
	 * null or has no loaded image so the production callers don't have to re-check those conditions.
	 *
	 * @param state CropState providing image dimensions + rotation; null / no-image returns `MIN_ZOOM`
	 * @return the effective zoom-floor under the current rotation
	 */
	float effectiveMinZoom(CropState state)
	{
		if (state == null || state.getSourceImage() == null)
		{
			return MIN_ZOOM;
		}
		return effectiveMinZoom(state.getImageWidth(), state.getImageHeight(), state.getRotationDegrees());
	}

	/**
	 * Lower bound of the zoom range as a function of the image dimensions, current `baseScale`, and rotation.
	 * Returns 1 (= the static `MIN_ZOOM`) at zero rotation or whenever the rotated bounding box fits inside the
	 * view at `baseScale * 1`. When the rotated AABB is bigger than what `baseScale` fits — e.g. when the user
	 * called fit-to-view at rotation 0 and then rotated to 30° — drops the floor to `(view-fit-to-rotated) /
	 * baseScale` so a pinch-out gesture can take the user past `zoom = 1` down to the value where the entire
	 * rotated image fits on screen. This keeps the "the app can always be zoomed out to show the entire image"
	 * contract regardless of the order the user fits-and-rotates in. Sub-epsilon rotations short-circuit
	 * through `RotationMath.rotatedAabbDimensions`'s identity branch, so this returns the static minimum
	 * exactly at the zero-rotation common case.
	 *
	 * Package-private so ViewportMathTest can pin the formula directly and a regression to a hard-coded
	 * floor of 1 (which is what re-introduces the "rotated image extends past the view at fullest zoom-out"
	 * bug) is caught immediately.
	 *
	 * @param imgW            image width in image pixels
	 * @param imgH            image height in image pixels
	 * @param rotationDegrees current image rotation in degrees
	 * @return the effective zoom-floor; equal to `MIN_ZOOM` at zero rotation, lower at non-zero rotation when
	 *         the rotated AABB exceeds the un-rotated baseScale's coverage
	 */
	float effectiveMinZoom(int imgW, int imgH, float rotationDegrees)
	{
		if (baseScale <= 0f || imgW <= 0 || imgH <= 0 || size.width() == 0 || size.height() == 0)
		{
			return MIN_ZOOM;
		}
		float[] rotatedDims = RotationMath.rotatedAabbDimensions(imgW, imgH, rotationDegrees, new float[2]);
		// Defensive second-pass guard. Mathematically rotatedAabbDimensions returns positive values whenever
		// imgW > 0 and imgH > 0 (sin²+cos² = 1, so at least one of |cos| / |sin| is strictly positive at every
		// angle). A NaN rotation propagates as NaN through cos/sin and breaks the > 0 invariant; the form
		// `!(a > 0 && b > 0)` rejects 0, negative, and NaN in one expression (>-comparisons return false for
		// NaN). Falls back to MIN_ZOOM rather than crashing the zoom clamp on NaN.
		if (!(rotatedDims[0] > 0 && rotatedDims[1] > 0))
		{
			return MIN_ZOOM;
		}
		float scaleToFitRotated = Math.min(size.width() / rotatedDims[0], size.height() / rotatedDims[1]);
		return Math.min(MIN_ZOOM, scaleToFitRotated / baseScale);
	}

	/**
	 * Reset zoom to 1, recompute baseScale to fit the full image in the view at its current rotation, and center
	 * the viewport on the image. Called on image load and on double-tap (outside Select mode). No-ops when state
	 * is null, has no loaded image, or the view hasn't been measured yet (size.width() == 0).
	 *
	 * @param state CropState supplying image dimensions + rotation; null / no-image / unmeasured-view
	 *              short-circuits without mutating viewport state
	 */
	void fitToView(CropState state)
	{
		if (state == null || state.getSourceImage() == null || size.width() == 0)
		{
			return;
		}
		fitToView(state.getImageWidth(), state.getImageHeight(), state.getRotationDegrees());
	}

	/**
	 * Zero-rotation pure-geometry overload. Backward-compat shim for tests / callers that don't have a rotation
	 * to pass; delegates to the rotation-aware overload with rotation = 0.
	 *
	 * @param imgW image width in image pixels (must be > 0; behavior undefined at 0)
	 * @param imgH image height in image pixels (must be > 0; behavior undefined at 0)
	 */
	void fitToView(int imgW, int imgH)
	{
		fitToView(imgW, imgH, 0f);
	}

	/**
	 * Pure-geometry overload taking image dimensions plus rotation directly. The rotated image's axis-aligned
	 * bounding box (rotatedW = |imgW·cos θ| + |imgH·sin θ|, rotatedH = |imgW·sin θ| + |imgH·cos θ|) is what
	 * actually has to fit on screen — using the unrotated imgW × imgH gives a baseScale that lets the rotated
	 * image extend past the view's edges at every non-cardinal angle. Sub-epsilon rotations are treated as zero
	 * (and use the unrotated dims directly) so the result matches the legacy two-arg overload exactly at the
	 * zero-rotation common case.
	 *
	 * Package-private so ViewportMathTest can pin the baseScale formula (min of width-ratio / height-ratio
	 * against the rotated bounding box) and the zoom-reset / center-viewport contract without a Bitmap-bound
	 * CropState. Assumes the caller has already null-checked the source image and the view dimensions;
	 * production callers go through the CropState overload above so those guards live in one place.
	 *
	 * @param imgW            image width in image pixels (must be > 0; behavior undefined at 0)
	 * @param imgH            image height in image pixels (must be > 0; behavior undefined at 0)
	 * @param rotationDegrees current image rotation in degrees; sub-epsilon values (|rotation| <
	 *                        BitmapUtils.ROTATION_EPSILON) collapse to the unrotated branch
	 */
	void fitToView(int imgW, int imgH, float rotationDegrees)
	{
		// Sub-epsilon rotations and the trig formula both flow through RotationMath.rotatedAabbDimensions —
		// using the shared helper keeps fit-to-view consistent with CropEngine.computeMaxCropSize and
		// clampFreeAxes, which read the same rotated-bounding-box dimensions for the rotation-aware crop
		// math. Drift between the two callsites would surface as a crop overlay that doesn't quite fit
		// inside the rotated image at the fit-to-view zoom.
		float[] rotatedDims = RotationMath.rotatedAabbDimensions(imgW, imgH, rotationDegrees, new float[2]);
		baseScale = Math.min(size.width() / rotatedDims[0], size.height() / rotatedDims[1]);
		zoom = 1f;
		viewportX = imgW / 2f;
		viewportY = imgH / 2f;
	}

	/**
	 * Fit-to-view base scale — screen pixels per image pixel at zoom = 1.
	 *
	 * @return the current base scale (screen pixels per image pixel at zoom 1)
	 */
	float getBaseScale()
	{
		return baseScale;
	}

	/**
	 * Current zoom factor on top of baseScale. 1 = fit-to-view; capped at MAX_ZOOM.
	 *
	 * @return the current zoom multiplier (1 at fit-to-view, up to MAX_ZOOM)
	 */
	float getZoom()
	{
		return zoom;
	}

	/**
	 * Convert image (ix, iy) to its rotated screen position — applies the same rotation around the image center
	 * that the editor's onDraw applies to the bitmap. Use this for overlays (selection points, polygon vertices)
	 * whose visual position must track image content as the user rotates. For overlays defined in image-coord
	 * axis-aligned space (the crop rectangle, dim regions), use imageToScreenX/Y directly. Writes the rotated
	 * screen coordinates into `out[0]` / `out[1]` and returns `out`; callers in onDraw reuse a per-renderer
	 * scratch buffer so the per-frame path does no allocation.
	 *
	 * @param ix    image X coordinate
	 * @param iy    image Y coordinate
	 * @param state CropState providing the rotation; null treated as zero rotation
	 * @param out   caller-allocated length-2 array; mutated and returned
	 * @return the same `out` reference for chaining
	 */
	float[] imageToScreenRotatedInto(float ix, float iy, CropState state, float[] out)
	{
		float scrX = imageToScreenX(ix);
		float scrY = imageToScreenY(iy);
		float rotation = (state == null) ? 0f : state.getRotationDegrees();
		// Collapse sub-epsilon rotations to the identity branch so tap mapping and rendering agree about
		// whether the image is "really" rotated.
		if (Math.abs(rotation) < BitmapUtils.ROTATION_EPSILON)
		{
			out[0] = scrX;
			out[1] = scrY;
			return out;
		}
		float imageScreenCenterX = imageToScreenX(state.getImageWidth() / 2f);
		float imageScreenCenterY = imageToScreenY(state.getImageHeight() / 2f);
		return RotationMath.rotate(scrX, scrY, imageScreenCenterX, imageScreenCenterY, rotation, out);
	}

	/**
	 * Convert image X to screen X given the current viewport + zoom.
	 *
	 * @param ix image-space X coordinate
	 * @return the corresponding screen-space X coordinate
	 */
	float imageToScreenX(float ix)
	{
		float scale = baseScale * zoom;
		return size.width() / 2f + (ix - viewportX) * scale;
	}

	/**
	 * Convert image Y to screen Y given the current viewport + zoom.
	 *
	 * @param iy image-space Y coordinate
	 * @return the corresponding screen-space Y coordinate
	 */
	float imageToScreenY(float iy)
	{
		float scale = baseScale * zoom;
		return size.height() / 2f + (iy - viewportY) * scale;
	}

	/**
	 * Pan the viewport by a SCREEN-space delta. Converts to image pixels via the current zoom, then clamps.
	 * Skips clamping when state is null or has no loaded image — the pan-arithmetic side still runs, matching
	 * the prior behavior where a pan without an image just updated the viewport variables.
	 *
	 * @param dx    screen-space X delta (positive = pan right; viewport shifts left)
	 * @param dy    screen-space Y delta (positive = pan down; viewport shifts up)
	 * @param state CropState supplying the image dimensions for the post-pan clamp; null / no-image skips the
	 *              clamp but still applies the pan arithmetic
	 */
	void panViewport(float dx, float dy, CropState state)
	{
		float scale = baseScale * zoom;
		viewportX -= dx / scale;
		viewportY -= dy / scale;
		clampViewport(state);
	}

	/**
	 * Zero-rotation pure-geometry overload. Backward-compat shim for tests that don't have a rotation to pass;
	 * delegates to the rotation-aware overload with rotation = 0.
	 *
	 * @param dx   screen-space X delta (positive = pan right; viewport shifts left)
	 * @param dy   screen-space Y delta (positive = pan down; viewport shifts up)
	 * @param imgW image width in image pixels — forwarded to clampViewport
	 * @param imgH image height in image pixels — forwarded to clampViewport
	 */
	void panViewport(float dx, float dy, int imgW, int imgH)
	{
		panViewport(dx, dy, imgW, imgH, 0f);
	}

	/**
	 * Pure-geometry overload taking image dimensions plus rotation directly. Package-private so
	 * ViewportMathTest can pin the screen-delta-to-image-pixels conversion AND the rotation-aware post-pan
	 * clamp (the rotated image's far corners sit outside `[0, imgW] × [0, imgH]` and the un-rotated clamp
	 * would make them unreachable at high zoom).
	 *
	 * @param dx              screen-space X delta (positive = pan right; viewport shifts left)
	 * @param dy              screen-space Y delta (positive = pan down; viewport shifts up)
	 * @param imgW            image width in image pixels — forwarded to clampViewport
	 * @param imgH            image height in image pixels — forwarded to clampViewport
	 * @param rotationDegrees current image rotation; forwarded to the rotation-aware clampViewport
	 */
	void panViewport(float dx, float dy, int imgW, int imgH, float rotationDegrees)
	{
		float scale = baseScale * zoom;
		viewportX -= dx / scale;
		viewportY -= dy / scale;
		clampViewport(imgW, imgH, rotationDegrees);
	}

	/**
	 * Convert a SCREEN point to IMAGE pixel coordinates, accounting for the CropState rotation applied at draw
	 * time. Returns a fresh `float[2]` of image pixels (possibly outside image bounds — caller checks).
	 * Allocates per call — for hot per-frame loops use `screenToImagePixelInto` instead.
	 *
	 * @param screenX screen-space X coordinate
	 * @param screenY screen-space Y coordinate
	 * @param state   CropState providing the rotation; null treated as zero rotation
	 * @return new float[]{imageX, imageY} (possibly outside image bounds; caller checks)
	 */
	float[] screenToImagePixel(float screenX, float screenY, CropState state)
	{
		return screenToImagePixelInto(screenX, screenY, state, new float[2]);
	}

	/**
	 * Out-buffer overload of screenToImagePixel. Writes the un-rotated image-pixel coordinates into `out[0]` /
	 * `out[1]` and returns `out`. Used by the renderer's per-frame visible-bounds AABB walk so onDraw doesn't
	 * allocate a fresh `float[2]` per corner.
	 *
	 * @param screenX screen X coordinate
	 * @param screenY screen Y coordinate
	 * @param state   CropState providing the rotation; null treated as zero rotation
	 * @param out     caller-allocated length-2 array; mutated and returned
	 * @return the same `out` reference for chaining
	 */
	float[] screenToImagePixelInto(float screenX, float screenY, CropState state, float[] out)
	{
		float rotation = (state == null) ? 0f : state.getRotationDegrees();
		// Collapse sub-epsilon rotations to the identity branch. The renderer treats abs(rotation) <
		// ROTATION_EPSILON as unrotated; input mapping must agree or a tiny residual angle skews tap
		// hit-testing, long-press removal, horizon paint mapping, and isInsideRotatedImage while the image is
		// being drawn straight.
		if (Math.abs(rotation) < BitmapUtils.ROTATION_EPSILON)
		{
			out[0] = screenToImageX(screenX);
			out[1] = screenToImageY(screenY);
			return out;
		}
		float screenCenterX = imageToScreenX(state.getImageWidth() / 2f);
		float screenCenterY = imageToScreenY(state.getImageHeight() / 2f);
		// Reuse `out` as the temp un-rotated screen-coords buffer, then convert in-place to image coords.
		// RotationMath.inverse writes into the buffer; screenToImageX/Y read its values before overwriting.
		RotationMath.inverse(screenX, screenY, screenCenterX, screenCenterY, rotation, out);
		float imageX = screenToImageX(out[0]);
		float imageY = screenToImageY(out[1]);
		out[0] = imageX;
		out[1] = imageY;
		return out;
	}

	/**
	 * Convert screen X back to image X (no rotation compensation). Callers that need to handle a rotated image use
	 * screenToImagePixel instead.
	 *
	 * @param sx screen-space X coordinate
	 * @return the corresponding (un-rotated) image-space X coordinate
	 */
	float screenToImageX(float sx)
	{
		float scale = baseScale * zoom;
		return viewportX + (sx - size.width() / 2f) / scale;
	}

	/**
	 * Convert screen Y back to image Y (no rotation compensation).
	 *
	 * @param sy screen-space Y coordinate
	 * @return the corresponding (un-rotated) image-space Y coordinate
	 */
	float screenToImageY(float sy)
	{
		float scale = baseScale * zoom;
		return viewportY + (sy - size.height() / 2f) / scale;
	}

	/**
	 * Zoom at a screen-space focus point, keeping that point stationary under the finger. Clamps zoom against
	 * [effectiveMinZoom(state), MAX_ZOOM] — at rotation, the lower bound drops below 1 so the user can pinch
	 * out far enough to fit the rotated bounding box on screen (the rotated image is bigger than the un-rotated
	 * image, so fit-to-rotated needs an effective scale below baseScale). Re-clamps the viewport against the
	 * new scale. Skips the viewport clamp when state is null or has no loaded image — the zoom + focus-shift
	 * arithmetic still runs.
	 *
	 * @param scaleFactor multiplier applied to the current zoom (clamped post-multiply to
	 *                    [effectiveMinZoom(state), MAX_ZOOM])
	 * @param focusX      screen-space X of the focus point (stays under the finger across the zoom)
	 * @param focusY      screen-space Y of the focus point
	 * @param state       CropState supplying image dimensions + rotation for the zoom floor and post-zoom
	 *                    viewport clamp; null / no-image skips the clamp but still applies the zoom math
	 */
	void zoomAt(float scaleFactor, float focusX, float focusY, CropState state)
	{
		float imgFocusX = screenToImageX(focusX);
		float imgFocusY = screenToImageY(focusY);
		zoom = Math.clamp(zoom * scaleFactor, effectiveMinZoom(state), MAX_ZOOM);
		float newImgFocusX = screenToImageX(focusX);
		float newImgFocusY = screenToImageY(focusY);
		viewportX += imgFocusX - newImgFocusX;
		viewportY += imgFocusY - newImgFocusY;
		clampViewport(state);
	}

	/**
	 * Zero-rotation pure-geometry overload taking image dimensions directly. Backward-compat shim for tests
	 * that don't have a rotation to pass; delegates to the rotation-aware overload with rotation = 0.
	 *
	 * @param scaleFactor multiplier applied to zoom (clamped post-multiply to [MIN_ZOOM=1, MAX_ZOOM=256] at
	 *                    zero rotation)
	 * @param focusX      screen-space X of the focus point (stays under the finger across the zoom)
	 * @param focusY      screen-space Y of the focus point
	 * @param imgW        image width in image pixels — forwarded to the post-zoom viewport clamp
	 * @param imgH        image height in image pixels — forwarded to the post-zoom viewport clamp
	 */
	void zoomAt(float scaleFactor, float focusX, float focusY, int imgW, int imgH)
	{
		zoomAt(scaleFactor, focusX, focusY, imgW, imgH, 0f);
	}

	/**
	 * Pure-geometry overload taking image dimensions plus rotation directly. Package-private so
	 * ViewportMathTest can pin the "focus stays under the finger" identity, the rotation-aware zoom-clamp
	 * lower bound, and the post-zoom viewport clamp — without a Bitmap-bound CropState. At non-zero rotation
	 * the minimum zoom is `min(1, (view-fit-to-rotated-AABB) / baseScale)` so a user who fit-to-view at one
	 * rotation can still zoom out to fit at a later (more extreme) rotation without having to re-fit.
	 *
	 * @param scaleFactor     multiplier applied to zoom (clamped post-multiply to
	 *                        [effectiveMinZoom(imgW, imgH, rotationDegrees), MAX_ZOOM=256])
	 * @param focusX          screen-space X of the focus point (stays under the finger across the zoom)
	 * @param focusY          screen-space Y of the focus point
	 * @param imgW            image width in image pixels — forwarded to the post-zoom viewport clamp
	 * @param imgH            image height in image pixels — forwarded to the post-zoom viewport clamp
	 * @param rotationDegrees current image rotation; rotated AABB shrinks the zoom floor below 1 when needed
	 */
	void zoomAt(float scaleFactor, float focusX, float focusY, int imgW, int imgH, float rotationDegrees)
	{
		float imgFocusX = screenToImageX(focusX);
		float imgFocusY = screenToImageY(focusY);
		zoom = Math.clamp(zoom * scaleFactor, effectiveMinZoom(imgW, imgH, rotationDegrees), MAX_ZOOM);
		float newImgFocusX = screenToImageX(focusX);
		float newImgFocusY = screenToImageY(focusY);
		viewportX += imgFocusX - newImgFocusX;
		viewportY += imgFocusY - newImgFocusY;
		clampViewport(imgW, imgH, rotationDegrees);
	}
}
