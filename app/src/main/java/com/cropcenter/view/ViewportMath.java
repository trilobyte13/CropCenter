package com.cropcenter.view;

import android.view.View;

import com.cropcenter.model.CropState;

/**
 * Viewport coordinate transforms + zoom/pan state for CropEditorView. Owns the baseScale,
 * viewport origin (vpX / vpY), and zoom factor. All conversions assume the image is centered
 * in the view at the viewport origin; screenToImagePixel additionally un-rotates for
 * CropState's rotation so callers can tap-select inside a rotated image.
 *
 * No rendering, no gestures — just math. The hosting View is referenced only for its width
 * and height (the dimensions the math is relative to).
 */
final class ViewportMath
{
	private static final float MIN_ZOOM = 1f;
	private static final float MAX_ZOOM = 256f;

	private final View view;

	private float baseScale = 1f;
	private float vpX = 0; // viewport origin in image space (X)
	private float vpY = 0; // viewport origin in image space (Y)
	private float zoom = 1f;

	ViewportMath(View view)
	{
		this.view = view;
	}

	/**
	 * Clamp vpX / vpY so the viewport stays inside the image bounds. If the image is smaller
	 * than the visible window on an axis, that axis is centered.
	 */
	void clampViewport(CropState state)
	{
		if (state == null || state.getSourceImage() == null)
		{
			return;
		}
		int imgW = state.getImageWidth();
		int imgH = state.getImageHeight();
		float scale = baseScale * zoom;
		float visibleW = view.getWidth() / scale;
		float visibleH = view.getHeight() / scale;

		if (visibleW >= imgW)
		{
			vpX = imgW / 2f;
		}
		else
		{
			vpX = Math.clamp(vpX, visibleW / 2f, imgW - visibleW / 2f);
		}

		if (visibleH >= imgH)
		{
			vpY = imgH / 2f;
		}
		else
		{
			vpY = Math.clamp(vpY, visibleH / 2f, imgH - visibleH / 2f);
		}
	}

	/**
	 * Reset zoom to 1, recompute baseScale to fit the full image in the view, and center the
	 * viewport on the image. Called on image load and on double-tap (outside Select mode).
	 */
	void fitToView(CropState state)
	{
		if (state == null || state.getSourceImage() == null || view.getWidth() == 0)
		{
			return;
		}
		int imgW = state.getImageWidth();
		int imgH = state.getImageHeight();
		baseScale = Math.min((float) view.getWidth() / imgW, (float) view.getHeight() / imgH);
		zoom = 1f;
		vpX = imgW / 2f;
		vpY = imgH / 2f;
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
	 * Convert image (ix, iy) to its rotated screen position — applies the same rotation
	 * around the image center that the editor's onDraw applies to the bitmap. Use this for
	 * overlays (selection points, polygon vertices) whose visual position must track image
	 * content as the user rotates. For overlays defined in image-coord axis-aligned space
	 * (the crop rectangle, dim regions), use imageToScreenX/Y directly.
	 */
	float[] imageToScreenRotated(float ix, float iy, CropState state)
	{
		float scrX = imageToScreenX(ix);
		float scrY = imageToScreenY(iy);
		float rotation = (state == null) ? 0f : state.getRotationDegrees();
		if (rotation == 0f)
		{
			return new float[] { scrX, scrY };
		}
		int imgW = state.getImageWidth();
		int imgH = state.getImageHeight();
		float imgScreenCx = imageToScreenX(imgW / 2f);
		float imgScreenCy = imageToScreenY(imgH / 2f);
		double rad = Math.toRadians(rotation);
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		double dx = scrX - imgScreenCx;
		double dy = scrY - imgScreenCy;
		return new float[] {
			(float) (dx * cos - dy * sin + imgScreenCx),
			(float) (dx * sin + dy * cos + imgScreenCy)
		};
	}

	/**
	 * Convert image X to screen X given the current viewport + zoom.
	 */
	float imageToScreenX(float ix)
	{
		float scale = baseScale * zoom;
		return view.getWidth() / 2f + (ix - vpX) * scale;
	}

	/**
	 * Convert image Y to screen Y given the current viewport + zoom.
	 */
	float imageToScreenY(float iy)
	{
		float scale = baseScale * zoom;
		return view.getHeight() / 2f + (iy - vpY) * scale;
	}

	/**
	 * Pan the viewport by a SCREEN-space delta. Converts to image pixels via the current
	 * zoom, then clamps.
	 */
	void panViewport(float dx, float dy, CropState state)
	{
		float scale = baseScale * zoom;
		vpX -= dx / scale;
		vpY -= dy / scale;
		clampViewport(state);
	}

	/**
	 * Convert a SCREEN point to IMAGE pixel coordinates, accounting for the CropState
	 * rotation applied at draw time. Returns a float[2] of image pixels (possibly outside
	 * image bounds — caller checks).
	 */
	float[] screenToImagePixel(float screenX, float screenY, CropState state)
	{
		float rotation = (state == null) ? 0f : state.getRotationDegrees();
		if (rotation == 0f)
		{
			return new float[] { screenToImageX(screenX), screenToImageY(screenY) };
		}
		int imgW = state.getImageWidth();
		int imgH = state.getImageHeight();
		float scrCx = imageToScreenX(imgW / 2f);
		float scrCy = imageToScreenY(imgH / 2f);
		double rad = Math.toRadians(-rotation);
		double dx = screenX - scrCx;
		double dy = screenY - scrCy;
		double unRotX = dx * Math.cos(rad) - dy * Math.sin(rad) + scrCx;
		double unRotY = dx * Math.sin(rad) + dy * Math.cos(rad) + scrCy;
		return new float[] {
				screenToImageX((float) unRotX),
				screenToImageY((float) unRotY)
		};
	}

	/**
	 * Convert screen X back to image X (no rotation compensation). Callers that need to
	 * handle a rotated image use screenToImagePixel instead.
	 */
	float screenToImageX(float sx)
	{
		float scale = baseScale * zoom;
		return vpX + (sx - view.getWidth() / 2f) / scale;
	}

	/**
	 * Convert screen Y back to image Y (no rotation compensation).
	 */
	float screenToImageY(float sy)
	{
		float scale = baseScale * zoom;
		return vpY + (sy - view.getHeight() / 2f) / scale;
	}

	/**
	 * Zoom at a screen-space focus point, keeping that point stationary under the finger.
	 * Clamps zoom to [1, 256] and re-clamps the viewport against the new scale.
	 */
	void zoomAt(float scaleFactor, float focusX, float focusY, CropState state)
	{
		float imgFocusX = screenToImageX(focusX);
		float imgFocusY = screenToImageY(focusY);
		zoom = Math.clamp(zoom * scaleFactor, MIN_ZOOM, MAX_ZOOM);
		float newImgFocusX = screenToImageX(focusX);
		float newImgFocusY = screenToImageY(focusY);
		vpX += imgFocusX - newImgFocusX;
		vpY += imgFocusY - newImgFocusY;
		clampViewport(state);
	}
}
