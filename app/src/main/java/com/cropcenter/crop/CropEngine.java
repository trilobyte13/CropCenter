package com.cropcenter.crop;

import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.SelectionPoint;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.RotationMath;

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
		// Sync the rotation/drag anchor to the post-recompute center so a later non-selection
		// recompute (e.g. user switches to Move mode then rotates or changes AR) starts from
		// where the selection put the crop, not a stale anchor left over from load time or a
		// prior pan. Without this sync, findCropCenter's non-select path reads the stale
		// anchor and the crop jumps back toward the old center the first time the user rotates
		// after selecting off-center points.
		state.setAnchor(state.getCenterX(), state.getCenterY());
	}

	/**
	 * Recompute crop size and clamp center.
	 * When cropSizeDirty: computes maximum crop at current AR centered on current center.
	 * Otherwise: just ensures center stays in valid bounds.
	 */
	public static void recomputeCrop(CropState state)
	{
		if (!state.hasCenter() || state.getSourceImage() == null)
		{
			return;
		}

		int imgW = state.getImageWidth();
		int imgH = state.getImageHeight();
		float rotation = state.getRotationDegrees();

		float[] center = findCropCenter(state, imgW, imgH, rotation);
		float centerX = Math.clamp(center[0], 0, imgW);
		float centerY = Math.clamp(center[1], 0, imgH);

		if (!state.isCropSizeDirty() && state.getCropW() > 0 && state.getCropH() > 0)
		{
			// Size locked — keep cropW / cropH, let setCenter clamp centerX / centerY into the
			// rotated image bounds. No parity snap: cropImgX comes from getCropImgX's floor().
			state.setCropSizeSilent(state.getCropW(), state.getCropH());
			state.setCenter(centerX, centerY);
			return;
		}

		CenterMode mode = state.getCenterMode();
		boolean lockedX = (mode == CenterMode.BOTH || mode == CenterMode.HORIZONTAL);
		boolean lockedY = (mode == CenterMode.BOTH || mode == CenterMode.VERTICAL);

		float[] cropSize = computeMaxCropSize(state.getAspectRatio(),
			centerX, centerY, imgW, imgH, lockedX, lockedY);
		float cropW = cropSize[0];
		float cropH = cropSize[1];

		// Free-axis clamp runs BEFORE the rotation shrink so H / V / BOTH stay visibly
		// distinct under rotation — see recomputeCrop_design-note.md. If the rotation
		// shrink ran first, the shrunk crop fits at any mode's requested center, collapsing
		// all modes to the same result.
		float[] clampedCenter = clampFreeAxes(centerX, centerY, cropW, cropH,
			imgW, imgH, lockedX, lockedY);
		centerX = clampedCenter[0];
		centerY = clampedCenter[1];

		// Sub-epsilon rotation is drawn / exported as zero by the render pipeline, so
		// skip the rotation-fit shrink here too — otherwise a residual 0.01° would
		// needlessly shrink a crop the user sees as unrotated.
		boolean effectiveRotation = Math.abs(rotation) >= BitmapUtils.ROTATION_EPSILON;
		if (effectiveRotation && cropW > 0 && cropH > 0)
		{
			float scale = maxScaleForRotation(centerX, centerY, cropW, cropH, imgW, imgH, rotation);
			if (scale < 1f)
			{
				cropW *= scale;
				cropH *= scale;
			}
		}

		int roundedCropW = Math.max(4, Math.round(Math.max(4, cropW)));
		int roundedCropH = Math.max(4, Math.round(Math.max(4, cropH)));
		state.setCropSizeSilent(roundedCropW, roundedCropH);
		state.setCropSizeDirty(false);
		state.setCenter(centerX, centerY);

		recheckRotationFit(state, imgW, imgH, rotation);
	}

	/**
	 * Compute the maximum crop size on each axis, subject to lock mode and aspect ratio.
	 * Locked axes are symmetric about the center (so a selection stays framed); free
	 * axes get the full image extent and will be clamped / shifted later by
	 * clampFreeAxes.
	 */
	private static float[] computeMaxCropSize(AspectRatio ar, float centerX, float centerY,
		int imgW, int imgH, boolean lockedX, boolean lockedY)
	{
		float maxCropW = lockedX ? 2 * Math.min(centerX, imgW - centerX) : imgW;
		float maxCropH = lockedY ? 2 * Math.min(centerY, imgH - centerY) : imgH;

		if (ar.isFree())
		{
			return new float[] { maxCropW, maxCropH };
		}

		float ratio = ar.ratio();
		float cropW;
		float cropH;
		if (maxCropH == 0 || maxCropW / maxCropH <= ratio)
		{
			cropW = maxCropW;
			cropH = cropW / ratio;
			if (cropH > maxCropH)
			{
				cropH = maxCropH;
				cropW = cropH * ratio;
			}
		}
		else
		{
			cropH = maxCropH;
			cropW = cropH * ratio;
			if (cropW > maxCropW)
			{
				cropW = maxCropW;
				cropH = cropW / ratio;
			}
		}
		return new float[] { cropW, cropH };
	}

	/**
	 * Shift free-axis centers so the crop rectangle stays inside the image. Guards
	 * against images smaller than the minimum crop: when cropW ≥ imgW the upper bound
	 * falls below the lower bound and Math.clamp would throw — fall back to centering.
	 */
	private static float[] clampFreeAxes(float centerX, float centerY,
		float cropW, float cropH, int imgW, int imgH, boolean lockedX, boolean lockedY)
	{
		if (!lockedX)
		{
			centerX = (cropW < imgW) ? Math.clamp(centerX, cropW / 2f, imgW - cropW / 2f) : imgW / 2f;
		}
		if (!lockedY)
		{
			centerY = (cropH < imgH) ? Math.clamp(centerY, cropH / 2f, imgH - cropH / 2f) : imgH / 2f;
		}
		return new float[] { centerX, centerY };
	}

	/**
	 * Derive the crop center for this recompute pass. In Select mode with points,
	 * returns the AABB midpoint of the selection points IN ROTATED IMAGE SPACE via
	 * rotatedSelectionMidpoint. In all other cases returns the stable rotation anchor
	 * (the user's intended, un-clamped center).
	 */
	private static float[] findCropCenter(CropState state, int imgW, int imgH, float rotation)
	{
		boolean hasSelection = state.getEditorMode() == EditorMode.SELECT_FEATURE
			&& !state.getSelectionPoints().isEmpty();
		if (!hasSelection)
		{
			return new float[] { state.getAnchorX(), state.getAnchorY() };
		}
		return rotatedSelectionMidpoint(state.getSelectionPoints(), imgW, imgH, rotation);
	}

	/**
	 * AABB midpoint of the selection points in ROTATED image space — rotate each point
	 * around the image center first, then take the axis-aligned bounding box of the
	 * rotated positions, then return its midpoint. Rotation doesn't commute with
	 * axis-aligned-bbox, so this gives a different (correct) result than rotating the
	 * un-rotated midpoint.
	 *
	 * Used by CropEngine.recomputeCrop (Select-mode center derivation) AND by callers
	 * that need to match its framing in other modes (MainActivity.recenterOnSelection
	 * when the user switches lock axis in Move mode on a rotated image). Keeping both
	 * paths on the same formula means switching between Select and Move can't shift
	 * the crop's visual position on a rotated image.
	 *
	 * For a single selection point, snaps to the nearest half-integer in rotated space
	 * so the grid's middle line can draw through the marker pixel (onTap already
	 * pre-snaps the stored point to pixel+0.5 in un-rotated coords; under rotation
	 * the rotated position is fractional, so a re-snap is needed).
	 */
	public static float[] rotatedSelectionMidpoint(List<SelectionPoint> points,
		int imgW, int imgH, float rotation)
	{
		float imageMidX = imgW / 2f;
		float imageMidY = imgH / 2f;
		float[] rotated = new float[2];
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (SelectionPoint point : points)
		{
			RotationMath.rotate(point.x(), point.y(), imageMidX, imageMidY, rotation, rotated);
			minX = Math.min(minX, rotated[0]);
			minY = Math.min(minY, rotated[1]);
			maxX = Math.max(maxX, rotated[0]);
			maxY = Math.max(maxY, rotated[1]);
		}
		if (points.size() == 1)
		{
			return new float[] {
				(float) Math.floor(minX) + 0.5f,
				(float) Math.floor(minY) + 0.5f
			};
		}
		return new float[] { (minX + maxX) / 2f, (minY + maxY) / 2f };
	}

	/**
	 * Second rotation-fit pass: integer rounding of cropW / cropH above may push the
	 * corners just outside the rotated image bounds. If maxScaleForRotation reports the
	 * post-rounding crop is meaningfully too big (recheck < 0.99), shrink + re-round +
	 * re-commit. Uses the state's current (post-setCenter) values because setCenter's
	 * own rotation clamp may have nudged the center.
	 */
	private static void recheckRotationFit(CropState state, int imgW, int imgH, float rotation)
	{
		if (Math.abs(rotation) < BitmapUtils.ROTATION_EPSILON)
		{
			return;
		}
		float finalCenterX = state.getCenterX();
		float finalCenterY = state.getCenterY();
		float recheck = maxScaleForRotation(finalCenterX, finalCenterY,
			state.getCropW(), state.getCropH(), imgW, imgH, rotation);
		if (recheck >= 0.99f)
		{
			return;
		}
		float cropW = state.getCropW() * recheck;
		float cropH = state.getCropH() * recheck;
		int refinedCropW = Math.max(4, Math.round(cropW));
		int refinedCropH = Math.max(4, Math.round(cropH));
		state.setCropSizeSilent(refinedCropW, refinedCropH);
		state.setCenter(finalCenterX, finalCenterY);
	}

	/**
	 * Axis-aligned bounding-box midpoint of a non-empty selection. A single point is its own
	 * midpoint; with multiple points we average the min/max on each axis (cheaper than a
	 * true centroid and matches how the crop engine frames the selection).
	 */
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

	/**
	 * Find the max scale factor (0..1) so that a crop of (cropW*s, cropH*s) centered at
	 * (centerX, centerY) fits entirely within an imgW × imgH image rotated by
	 * rotation degrees. Each crop corner, un-rotated around image center, must land inside
	 * [0, imgW] × [0, imgH].
	 */
	private static float maxScaleForRotation(float centerX, float centerY, float cropW, float cropH,
		int imgW, int imgH, float rotation)
	{
		float imageMidX = imgW / 2f;
		float imageMidY = imgH / 2f;

		// Corner offsets (±½, ±½) define the four crop corners relative to the crop center.
		float[][] cornerOffsets = { { -0.5f, -0.5f }, { 0.5f, -0.5f }, { -0.5f, 0.5f }, { 0.5f, 0.5f } };
		float[] unrotated = new float[2];
		float minScale = 1f;

		for (float[] offset : cornerOffsets)
		{
			float cornerX = centerX + offset[0] * cropW;
			float cornerY = centerY + offset[1] * cropH;

			// Un-rotate the corner around the image midpoint.
			RotationMath.inverse(cornerX, cornerY, imageMidX, imageMidY, rotation, unrotated);

			// If this corner already fits at scale=1, nothing to do. Otherwise binary-search the
			// largest scale factor that brings it inside image bounds.
			if (unrotated[0] < 0 || unrotated[0] > imgW || unrotated[1] < 0 || unrotated[1] > imgH)
			{
				float loScale = 0.01f;           // min 1% to avoid degenerate 0-size crops
				float hiScale = 1f;
				for (int i = 0; i < 20; i++)
				{
					float midScale = (loScale + hiScale) / 2f;
					float testCornerX = centerX + offset[0] * cropW * midScale;
					float testCornerY = centerY + offset[1] * cropH * midScale;
					RotationMath.inverse(testCornerX, testCornerY,
						imageMidX, imageMidY, rotation, unrotated);
					if (unrotated[0] >= 0 && unrotated[0] <= imgW
						&& unrotated[1] >= 0 && unrotated[1] <= imgH)
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
