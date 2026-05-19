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
	 */
	void clampViewport(CropState state)
	{
		if (state == null || state.getSourceImage() == null)
		{
			return;
		}
		clampViewport(state.getImageWidth(), state.getImageHeight());
	}

	/**
	 * Pure-geometry overload taking image dimensions directly. Package-private so ViewportMathTest can pin the
	 * clamp arithmetic and the centering branch without a Bitmap-bound CropState. Callers from production go
	 * through the CropState overload above so the null-image short-circuit lives in one place.
	 *
	 * @param imgW image width in image pixels (must be ≥ 0; behavior degenerate at 0)
	 * @param imgH image height in image pixels (must be ≥ 0; behavior degenerate at 0)
	 */
	void clampViewport(int imgW, int imgH)
	{
		float scale = baseScale * zoom;
		float visibleW = size.width() / scale;
		float visibleH = size.height() / scale;

		if (visibleW >= imgW)
		{
			viewportX = imgW / 2f;
		}
		else
		{
			viewportX = Math.clamp(viewportX, visibleW / 2f, imgW - visibleW / 2f);
		}

		if (visibleH >= imgH)
		{
			viewportY = imgH / 2f;
		}
		else
		{
			viewportY = Math.clamp(viewportY, visibleH / 2f, imgH - visibleH / 2f);
		}
	}

	/**
	 * Reset zoom to 1, recompute baseScale to fit the full image in the view, and center the viewport on the image.
	 * Called on image load and on double-tap (outside Select mode). No-ops when state is null, has no loaded
	 * image, or the view hasn't been measured yet (size.width() == 0).
	 */
	void fitToView(CropState state)
	{
		if (state == null || state.getSourceImage() == null || size.width() == 0)
		{
			return;
		}
		fitToView(state.getImageWidth(), state.getImageHeight());
	}

	/**
	 * Pure-geometry overload taking image dimensions directly. Package-private so ViewportMathTest can pin the
	 * baseScale formula (min of width-ratio / height-ratio) and the zoom-reset / center-viewport contract
	 * without a Bitmap-bound CropState. Assumes the caller has already null-checked the source image and the
	 * view dimensions; production callers go through the CropState overload above so those guards live in one
	 * place.
	 *
	 * @param imgW image width in image pixels (must be > 0; behavior undefined at 0)
	 * @param imgH image height in image pixels (must be > 0; behavior undefined at 0)
	 */
	void fitToView(int imgW, int imgH)
	{
		baseScale = Math.min((float) size.width() / imgW, (float) size.height() / imgH);
		zoom = 1f;
		viewportX = imgW / 2f;
		viewportY = imgH / 2f;
	}

	/**
	 * Fit-to-view base scale — screen pixels per image pixel at zoom = 1.
	 */
	float getBaseScale()
	{
		return baseScale;
	}

	/**
	 * Current zoom factor on top of baseScale. 1 = fit-to-view; capped at MAX_ZOOM.
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
	 */
	float imageToScreenX(float ix)
	{
		float scale = baseScale * zoom;
		return size.width() / 2f + (ix - viewportX) * scale;
	}

	/**
	 * Convert image Y to screen Y given the current viewport + zoom.
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
	 */
	void panViewport(float dx, float dy, CropState state)
	{
		float scale = baseScale * zoom;
		viewportX -= dx / scale;
		viewportY -= dy / scale;
		clampViewport(state);
	}

	/**
	 * Pure-geometry overload taking image dimensions directly. Package-private so ViewportMathTest can pin the
	 * screen-delta-to-image-pixels conversion and the post-pan clamp without a Bitmap-bound CropState.
	 *
	 * @param dx   screen-space X delta (positive = pan right; viewport shifts left)
	 * @param dy   screen-space Y delta (positive = pan down; viewport shifts up)
	 * @param imgW image width in image pixels — forwarded to clampViewport
	 * @param imgH image height in image pixels — forwarded to clampViewport
	 */
	void panViewport(float dx, float dy, int imgW, int imgH)
	{
		float scale = baseScale * zoom;
		viewportX -= dx / scale;
		viewportY -= dy / scale;
		clampViewport(imgW, imgH);
	}

	/**
	 * Convert a SCREEN point to IMAGE pixel coordinates, accounting for the CropState rotation applied at draw
	 * time. Returns a fresh `float[2]` of image pixels (possibly outside image bounds — caller checks).
	 * Allocates per call — for hot per-frame loops use `screenToImagePixelInto` instead.
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
	 */
	float screenToImageX(float sx)
	{
		float scale = baseScale * zoom;
		return viewportX + (sx - size.width() / 2f) / scale;
	}

	/**
	 * Convert screen Y back to image Y (no rotation compensation).
	 */
	float screenToImageY(float sy)
	{
		float scale = baseScale * zoom;
		return viewportY + (sy - size.height() / 2f) / scale;
	}

	/**
	 * Zoom at a screen-space focus point, keeping that point stationary under the finger. Clamps zoom to [1, 256]
	 * and re-clamps the viewport against the new scale. Skips the viewport clamp when state is null or has no
	 * loaded image — the zoom + focus-shift arithmetic still runs.
	 */
	void zoomAt(float scaleFactor, float focusX, float focusY, CropState state)
	{
		float imgFocusX = screenToImageX(focusX);
		float imgFocusY = screenToImageY(focusY);
		zoom = Math.clamp(zoom * scaleFactor, MIN_ZOOM, MAX_ZOOM);
		float newImgFocusX = screenToImageX(focusX);
		float newImgFocusY = screenToImageY(focusY);
		viewportX += imgFocusX - newImgFocusX;
		viewportY += imgFocusY - newImgFocusY;
		clampViewport(state);
	}

	/**
	 * Pure-geometry overload taking image dimensions directly. Package-private so ViewportMathTest can pin the
	 * "focus stays under the finger" identity, the zoom clamp to [MIN_ZOOM, MAX_ZOOM], and the post-zoom
	 * viewport clamp — without a Bitmap-bound CropState.
	 *
	 * @param scaleFactor multiplier applied to zoom (clamped post-multiply to [1, 256])
	 * @param focusX      screen-space X of the focus point (stays under the finger across the zoom)
	 * @param focusY      screen-space Y of the focus point
	 * @param imgW        image width in image pixels — forwarded to the post-zoom clamp
	 * @param imgH        image height in image pixels — forwarded to the post-zoom clamp
	 */
	void zoomAt(float scaleFactor, float focusX, float focusY, int imgW, int imgH)
	{
		float imgFocusX = screenToImageX(focusX);
		float imgFocusY = screenToImageY(focusY);
		zoom = Math.clamp(zoom * scaleFactor, MIN_ZOOM, MAX_ZOOM);
		float newImgFocusX = screenToImageX(focusX);
		float newImgFocusY = screenToImageY(focusY);
		viewportX += imgFocusX - newImgFocusX;
		viewportY += imgFocusY - newImgFocusY;
		clampViewport(imgW, imgH);
	}
}
