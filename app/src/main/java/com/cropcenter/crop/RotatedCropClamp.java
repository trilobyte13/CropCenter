package com.cropcenter.crop;

/**
 * Pure-function geometry helpers that clamp a crop center to keep the crop rectangle
 * fully inside the source image, accounting for rotation. Extracted from CropState so
 * the rotation-fit math can be unit-tested without instantiating CropState and so the
 * coupling between "what clamping to apply" and "what state to mutate" stays one-way:
 * CropState.setCenter calls these and writes the result; the helpers know nothing
 * about CropState.
 *
 * Two entry points:
 *   - clampAxisAligned: cheap path used when rotation is sub-epsilon
 *   - clampRotated: 25-iteration binary search per axis; falls back to image-center
 *                   when the crop is too large to fit at any position
 */
public final class RotatedCropClamp
{
	// Binary-search iteration cap shared with CropEngine.maxScaleForRotation — both walk the same
	// half-and-half interval-bisection pattern and need to converge to the same resolution so the
	// rotation-fit guarantees they jointly enforce stay numerically consistent. 25 iterations resolves
	// to ~30 ppb of the search range; 20 (the previous value) resolved to ~1 ppm, which produced
	// visible jitter on thin crop slices at high zoom under continuous rotation drag. Hoisted to a
	// single chokepoint so a future "tune the convergence" change lands in one place rather than two
	// files drifting apart.
	static final int BINARY_SEARCH_ITERATIONS = 25;
	// Sign multipliers for the four corner offsets relative to crop center: TL, TR, BL, BR. Hoisted as static
	// constants because cornersInside runs ~25 iterations per axis × 2 axes per binary search × ~50 calls per
	// second during a fling-rotation drag — allocating two 4-element float arrays per call (the previous
	// array-literal pattern) created hundreds of throwaway allocations per second on a hot path.
	private static final float[] CORNER_SIGN_X = { -1f, 1f, -1f, 1f };
	private static final float[] CORNER_SIGN_Y = { -1f, -1f, 1f, 1f };

	private RotatedCropClamp() {}

	/**
	 * Axis-aligned clamp — sub-epsilon rotation is rendered as unrotated by the rest of the stack, so the cheap
	 * axis clamp suffices. Each axis is clamped independently; when cropW >= imgW (crop exceeds image) snap to the
	 * image mid rather than letting Math.clamp throw on inverted bounds.
	 *
	 * @param x     requested center X
	 * @param y     requested center Y
	 * @param cropW crop width in image pixels
	 * @param cropH crop height in image pixels
	 * @param imgW  source image width
	 * @param imgH  source image height
	 * @return [clampedX, clampedY] guaranteed to keep the crop inside the image
	 */
	public static float[] clampAxisAligned(float x, float y, int cropW, int cropH, int imgW, int imgH)
	{
		if (cropW < imgW)
		{
			x = Math.clamp(x, cropW / 2f, imgW - cropW / 2f);
		}
		else
		{
			x = imgW / 2f;
		}
		if (cropH < imgH)
		{
			y = Math.clamp(y, cropH / 2f, imgH - cropH / 2f);
		}
		else
		{
			y = imgH / 2f;
		}
		return new float[] { x, y };
	}

	/**
	 * Rotated-image clamp — binary search along each axis independently so clamping X doesn't affect Y and vice
	 * versa. Each pass walks from the requested position toward image center, picking the farthest-out fraction
	 * whose four crop corners all land inside the source image bounds when un-rotated. When the crop is larger than
	 * the image under the current rotation (no valid position exists), falls back to image-center so the caller
	 * gets a stable, finite result.
	 *
	 * @param x               requested center X
	 * @param y               requested center Y
	 * @param cropW           crop width in image pixels
	 * @param cropH           crop height in image pixels
	 * @param rotationDegrees rotation angle (the rotation applied to the image; the
	 *                        clamp un-rotates corners by negating this internally)
	 * @param imgW            source image width
	 * @param imgH            source image height
	 * @return [clampedX, clampedY] keeping the rotated crop inside the image, or
	 *         [imageMidX, imageMidY] when the crop can't fit at any position
	 */
	public static float[] clampRotated(float x, float y, int cropW, int cropH,
		float rotationDegrees, int imgW, int imgH)
	{
		CropFitContext ctx = CropFitContext.of(cropW, cropH, rotationDegrees, imgW, imgH);

		// Fallback: when even image-center can't hold the crop (cropW / cropH exceed the rotated image's
		// inscribed axis-aligned rectangle), the binary searches below would converge to image-center with
		// corners still outside bounds. Snap to image-center directly — the rotation-fit shrink in
		// CropEngine.recomputeCrop's recheck pass will catch up and size the crop down to fit on the next
		// recompute.
		if (!cornersInside(ctx.imageMidX(), ctx.imageMidY(), ctx))
		{
			return new float[] { ctx.imageMidX(), ctx.imageMidY() };
		}

		if (!cornersInside(x, y, ctx))
		{
			x = binarySearchAxis(x, y, ctx, true);
		}
		if (!cornersInside(x, y, ctx))
		{
			y = binarySearchAxis(x, y, ctx, false);
		}
		return new float[] { x, y };
	}

	/**
	 * 25-iteration binary search for the farthest valid center position along one axis. The search range is fixed
	 * at both ends — `fraction = 0` gives the image center (always valid), `fraction = 1` gives the requested
	 * position. Each iteration tests the midpoint fraction; valid positions advance `loFraction` and update the
	 * best-so-far, invalid positions pull `hiFraction` in. Result: the largest valid fraction's candidate position.
	 * `clampX` true varies X while holding Y fixed; false varies Y.
	 *
	 * @param x      requested center X (held fixed when clampX is false)
	 * @param y      requested center Y (held fixed when clampX is true)
	 * @param ctx    pre-computed crop / rotation / image-bounds context
	 * @param clampX true to vary X holding Y; false to vary Y holding X
	 * @return the farthest-out valid position along the chosen axis
	 */
	private static float binarySearchAxis(float x, float y, CropFitContext ctx, boolean clampX)
	{
		float loFraction = 0f;
		float hiFraction = 1f;
		float safeEndpoint = clampX ? ctx.imageMidX() : ctx.imageMidY();
		float requestedEndpoint = clampX ? x : y;
		float validPosition = safeEndpoint;
		for (int i = 0; i < BINARY_SEARCH_ITERATIONS; i++)
		{
			float midFraction = (loFraction + hiFraction) / 2f;
			float candidate = safeEndpoint + (requestedEndpoint - safeEndpoint) * midFraction;
			float testX = clampX ? candidate : x;
			float testY = clampX ? y : candidate;
			if (cornersInside(testX, testY, ctx))
			{
				validPosition = candidate;
				loFraction = midFraction;
			}
			else
			{
				hiFraction = midFraction;
			}
		}
		return validPosition;
	}

	/**
	 * Check if all 4 corners of the crop rect, when un-rotated around the image center, land inside the image.
	 *
	 * @param centerX candidate crop center X
	 * @param centerY candidate crop center Y
	 * @param ctx     pre-computed crop / rotation / image-bounds context
	 * @return true when every un-rotated corner lies in [-0.5, imgW+0.5] × [-0.5, imgH+0.5]
	 */
	private static boolean cornersInside(float centerX, float centerY, CropFitContext ctx)
	{
		for (int i = 0; i < 4; i++)
		{
			double deltaX = centerX + CORNER_SIGN_X[i] * ctx.halfWidth() - ctx.imageMidX();
			double deltaY = centerY + CORNER_SIGN_Y[i] * ctx.halfHeight() - ctx.imageMidY();
			double unrotatedX = deltaX * ctx.cosR() - deltaY * ctx.sinR() + ctx.imageMidX();
			double unrotatedY = deltaX * ctx.sinR() + deltaY * ctx.cosR() + ctx.imageMidY();
			if (unrotatedX < -0.5 || unrotatedX > ctx.imgW() + 0.5
				|| unrotatedY < -0.5 || unrotatedY > ctx.imgH() + 0.5)
			{
				return false;
			}
		}
		return true;
	}
}
