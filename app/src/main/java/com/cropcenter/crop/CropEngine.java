package com.cropcenter.crop;

import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.SelectionPoint;

import java.util.List;

public final class CropEngine
{
	private CropEngine() {}

	/**
	 * Auto-compute crop from selection points, respecting lock mode.
	 *
	 * Both:  center on selection midpoint for both axes (symmetric around points)
	 * H:     center horizontally on points, vertically on image center (max height)
	 * V:     center vertically on points, horizontally on image center (max width)
	 */
	public static void autoComputeFromPoints(CropState state)
	{
		if (state.isCenterLocked())
		{
			return; // center locked — skip auto-recompute
		}
		if (state.getCenterMode() == CenterMode.LOCKED)
		{
			// Pan mode: crop is frozen while the user drags the viewport. Points may still be
			// added or removed, but the crop box must not resize/relocate until Pan turns off
			// and the chkPan handler fires recomputeForLockChange(), which runs this method
			// again with the real centerMode restored.
			return;
		}
		List<SelectionPoint> points = state.getSelectionPoints();
		if (points.isEmpty())
		{
			return;
		}
		float[] mid = selectionMidpoint(points);

		// Always start centered on the selection midpoint.
		// Locked axes: crop is symmetric around this center.
		// Free axes: crop extends to max available size, but stays centered on the points as
		// much as possible (recomputeCrop clamps if needed to fit).
		state.markCropSizeDirty();
		state.setCenterUnclamped(mid[0], mid[1]);
		recomputeCrop(state);
	}

	// Recompute crop size and clamp center.
	// When cropSizeDirty: computes maximum crop at current AR centered on current center.
	// Otherwise: just ensures center stays in valid bounds.
	public static void recomputeCrop(CropState state)
	{
		if (!state.hasCenter() || state.getSourceImage() == null)
		{
			return;
		}

		int imgW = state.getImageWidth();
		int imgH = state.getImageHeight();
		float cx = Math.round(state.getCenterX());
		float cy = Math.round(state.getCenterY());
		cx = Math.clamp(cx, 0, imgW);
		cy = Math.clamp(cy, 0, imgH);

		if (!state.isCropSizeDirty() && state.getCropW() > 0 && state.getCropH() > 0)
		{
			// Size locked — just clamp center (setCenter handles rotation)
			state.setCropSizeSilent(state.getCropW(), state.getCropH());
			state.setCenter(cx, cy);
			return;
		}

		// Compute maximum crop
		CenterMode mode = state.getCenterMode();
		boolean lockedX = (mode == CenterMode.BOTH || mode == CenterMode.HORIZONTAL);
		boolean lockedY = (mode == CenterMode.BOTH || mode == CenterMode.VERTICAL);

		// Locked axes: symmetric extent from center (crop stays centered on point).
		// Free axes: full image extent (center will be shifted to fit later).
		float maxW = lockedX ? 2 * Math.min(cx, imgW - cx) : imgW;
		float maxH = lockedY ? 2 * Math.min(cy, imgH - cy) : imgH;

		float cropW;
		float cropH;
		AspectRatio ar = state.getAspectRatio();

		if (!ar.isFree())
		{
			float ratio = ar.ratio();
			if (maxH == 0 || maxW / maxH <= ratio)
			{
				cropW = maxW;
				cropH = Math.round(cropW / ratio);
				if (cropH > maxH)
				{
					cropH = maxH;
					cropW = Math.round(cropH * ratio);
				}
			}
			else
			{
				cropH = maxH;
				cropW = Math.round(cropH * ratio);
				if (cropW > maxW)
				{
					cropW = maxW;
					cropH = Math.round(cropW / ratio);
				}
			}
		}
		else
		{
			cropW = maxW;
			cropH = maxH;
		}

		// Clamp center for free axes — use image bounds (setCenter handles rotation clamping).
		// Guard against images smaller than the minimum crop: when cropW ≥ imgW the upper
		// bound (imgW − cropW/2) falls below the lower bound (cropW/2) and Math.clamp would
		// throw. Fall back to centering. Same for Y.
		//
		// Ordering note: this clamp runs BEFORE the rotation scaling (below) so that H/V/BOTH
		// stay visibly distinct under rotation. If the rotation scale ran first, it would
		// shrink the crop to the max AR-inscribed rectangle at the REQUESTED center, which is
		// a single value regardless of mode — so H's cy-shift would never fire (the shrunk
		// cropH fits at point's cy without needing the shift), collapsing all modes to the
		// same result. Clamping first preserves the mode-specific center offset through the
		// rotation step.
		if (!lockedX)
		{
			cx = (cropW < imgW) ? Math.clamp(cx, cropW / 2, imgW - cropW / 2) : imgW / 2f;
		}
		if (!lockedY)
		{
			cy = (cropH < imgH) ? Math.clamp(cy, cropH / 2, imgH - cropH / 2) : imgH / 2f;
		}

		// Scale down for rotation — check all 4 corners of the crop
		float rotation = state.getRotationDegrees();
		if (rotation != 0f && cropW > 0 && cropH > 0)
		{
			float scale = maxScaleForRotation(cx, cy, cropW, cropH, imgW, imgH, rotation);
			if (scale < 1f)
			{
				cropW *= scale;
				cropH *= scale;
			}
		}

		cropW = Math.max(4, cropW);
		cropH = Math.max(4, cropH);

		state.setCropSizeSilent(Math.round(cropW), Math.round(cropH));
		state.setCropSizeDirty(false);
		// setCenter applies rotation-aware clamping via binary search
		state.setCenter(cx, cy);

		// If rotation clamping moved the center significantly, the crop may now be too large
		// for the new center. Re-check and shrink if needed.
		if (rotation != 0f)
		{
			float finalCx = state.getCenterX();
			float finalCy = state.getCenterY();
			float recheck = maxScaleForRotation(finalCx, finalCy,
				state.getCropW(), state.getCropH(), imgW, imgH, rotation);
			if (recheck < 0.99f)
			{
				cropW = state.getCropW() * recheck;
				cropH = state.getCropH() * recheck;
				cropW = Math.max(4, cropW);
				cropH = Math.max(4, cropH);
				state.setCropSizeSilent(Math.round(cropW), Math.round(cropH));
				state.setCenter(finalCx, finalCy);
			}
		}
	}

	// Axis-aligned bounding-box midpoint of a non-empty selection. A single point is its own
	// midpoint; with multiple points we average the min/max on each axis (cheaper than a
	// true centroid and matches how the crop engine frames the selection).
	public static float[] selectionMidpoint(List<SelectionPoint> points)
	{
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (SelectionPoint point : points)
		{
			minX = Math.min(minX, point.x());
			minY = Math.min(minY, point.y());
			maxX = Math.max(maxX, point.x());
			maxY = Math.max(maxY, point.y());
		}
		int count = points.size();
		float midX = (count == 1) ? minX : (minX + maxX) / 2f;
		float midY = (count == 1) ? minY : (minY + maxY) / 2f;
		return new float[] { midX, midY };
	}

	// Find the max scale factor (0..1) so that a crop of (cropW*s, cropH*s) centered at
	// (centerX, centerY) fits entirely within an imgW × imgH image rotated by
	// rotation degrees. Each crop corner, un-rotated around image center, must land inside
	// [0, imgW] × [0, imgH].
	private static float maxScaleForRotation(float centerX, float centerY, float cropW, float cropH,
		int imgW, int imgH, float rotation)
	{
		float imageMidX = imgW / 2f;
		float imageMidY = imgH / 2f;
		double rad = Math.toRadians(-rotation);
		double cosR = Math.cos(rad);
		double sinR = Math.sin(rad);

		// Corner offsets (±½, ±½) define the four crop corners relative to the crop center.
		float[][] cornerOffsets = { { -0.5f, -0.5f }, { 0.5f, -0.5f }, { -0.5f, 0.5f }, { 0.5f, 0.5f } };
		float minScale = 1f;

		for (float[] offset : cornerOffsets)
		{
			float cornerX = centerX + offset[0] * cropW;
			float cornerY = centerY + offset[1] * cropH;

			// Un-rotate the corner around the image midpoint
			double deltaX = cornerX - imageMidX;
			double deltaY = cornerY - imageMidY;
			double unrotatedX = deltaX * cosR - deltaY * sinR + imageMidX;
			double unrotatedY = deltaX * sinR + deltaY * cosR + imageMidY;

			// If this corner already fits at scale=1, nothing to do. Otherwise binary-search the
			// largest scale factor that brings it inside image bounds.
			if (unrotatedX < 0 || unrotatedX > imgW || unrotatedY < 0 || unrotatedY > imgH)
			{
				float loScale = 0.01f;           // min 1% to avoid degenerate 0-size crops
				float hiScale = 1f;
				for (int i = 0; i < 20; i++)
				{
					float midScale = (loScale + hiScale) / 2f;
					float testCornerX = centerX + offset[0] * cropW * midScale;
					float testCornerY = centerY + offset[1] * cropH * midScale;
					double testDeltaX = testCornerX - imageMidX;
					double testDeltaY = testCornerY - imageMidY;
					double testUnrotatedX = testDeltaX * cosR - testDeltaY * sinR + imageMidX;
					double testUnrotatedY = testDeltaX * sinR + testDeltaY * cosR + imageMidY;
					if (testUnrotatedX >= 0 && testUnrotatedX <= imgW
						&& testUnrotatedY >= 0 && testUnrotatedY <= imgH)
					{
						loScale = midScale;
					}
					else
					{
						hiScale = midScale;
					}
				}
				minScale = Math.min(minScale, loScale);
			}
		}
		return minScale;
	}
}
