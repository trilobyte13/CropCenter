package com.cropcenter.crop;

import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.SelectionPoint;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.RotationMath;

import java.util.List;

/**
 * Computes the crop rectangle from CropState — given the user's selection points, aspect ratio, lock mode (Both / H /
 * V), rotation, and image bounds, derives a center point and width/height that respect all of those constraints. The
 * two public entry points are autoComputeFromPoints (selection-driven) and recomputeCrop (general "given the current
 * state, fit the crop to it"). Pure math: no Bitmap reads, no rendering, no listener firing — callers handle
 * notifyChanged through the CropState setter pattern.
 */
public final class CropEngine
{
	// Minimum crop dimension in pixels. Below this, locked-AR snap collapses similar-tiny ARs to 1×1 at
	// the lower edge, and free-form crops would render as a single bilinear-filtered row that's visually
	// noise. 4 px gives the smallest meaningful "this is still a crop" shape; the constant is consumed
	// cross-file by AspectRatio.snap to enforce the same floor on the snapped result that the per-axis
	// clamps enforce on the pre-snap dims (hoisted to a single chokepoint so a future "raise the
	// floor" change doesn't drift between the two files).
	public static final int MIN_CROP_DIMENSION_PX = 4;

	private CropEngine() {}

	/**
	 * Auto-compute crop from selection points, respecting lock mode.
	 *
	 * Both: center on selection midpoint for both axes (symmetric around points) H: center horizontally on points,
	 * vertically on image center (max height) V: center vertically on points, horizontally on image center (max
	 * width)
	 */
	public static void autoComputeFromPoints(CropState state)
	{
		if (state.isCenterLocked())
		{
			return; // center locked — skip auto-recompute
		}
		if (state.getCenterMode() == CenterMode.LOCKED)
		{
			// Pan mode: crop is frozen while the user drags the viewport. Points may still be added or
			// removed, but the crop box must not resize/relocate until Pan turns off and the chkPan handler
			// fires recomputeForLockChange(), which runs this method again with the real centerMode
			// restored.
			return;
		}
		List<SelectionPoint> points = state.getSelectionPoints();
		if (points.isEmpty())
		{
			return;
		}
		float[] mid = selectionMidpoint(points);

		// Always start centered on the selection midpoint. Locked axes: crop is symmetric around this center.
		// Free axes: crop extends to max available size, but stays centered on the points as much as possible
		// (recomputeCrop clamps if needed to fit).
		state.markCropSizeDirty();
		state.setCenterUnclamped(mid[0], mid[1]);
		recomputeCrop(state);
		// Sync the rotation/drag anchor to the post-recompute center so a later non-selection recompute (e.g.
		// user switches to Move mode then rotates or changes AR) starts from where the selection put the crop,
		// not a stale anchor left over from load time or a prior pan. Without this sync, findCropCenter's
		// non-select path reads the stale anchor and the crop jumps back toward the old center the first time
		// the user rotates after selecting off-center points.
		state.setAnchor(state.getCenterX(), state.getCenterY());
	}

	/**
	 * Recompute crop size and clamp center. When cropSizeDirty: computes maximum crop at current AR centered on
	 * current center. Otherwise: just ensures center stays in valid bounds.
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
			// Size locked — keep cropW / cropH, let setCenter clamp centerX / centerY into the rotated
			// image bounds. No parity snap: cropImageX comes from getCropImageXFloat's floor().
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

		// Free-axis clamp runs BEFORE the rotation shrink so H / V / BOTH stay visibly distinct under rotation.
		// If the rotation shrink ran first, the shrunk crop fits at any mode's requested center, collapsing all
		// modes to the same result — the user-visible behaviour is that "free X" / "free Y" / "free both" all
		// produce identical crops, defeating the whole point of the mode toggle.
		float[] clampedCenter = clampFreeAxes(centerX, centerY, cropW, cropH, imgW, imgH, lockedX, lockedY);
		centerX = clampedCenter[0];
		centerY = clampedCenter[1];

		// Sub-epsilon rotation is drawn / exported as zero by the render pipeline, so skip the rotation-fit
		// shrink here too — otherwise a residual 0.01° would needlessly shrink a crop the user sees as
		// unrotated.
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

		int roundedCropW = Math.max(MIN_CROP_DIMENSION_PX, Math.round(Math.max(MIN_CROP_DIMENSION_PX, cropW)));
		int roundedCropH = Math.max(MIN_CROP_DIMENSION_PX, Math.round(Math.max(MIN_CROP_DIMENSION_PX, cropH)));
		// Snap to exact-integer aspect ratio — eliminates the per-axis ½-pixel drift that produced
		// 0.79989 instead of 0.80000 on locked 4:5 (round(2990.4)=2990, round(3737.5)=3738; the pair
		// drifts off the locked AR even though the float dims were exact). Pass the rounded dims as
		// the snap bounds (no-grow) — computeMaxCropSize already produced the largest crop that fits
		// at the locked center, so allowing snap-up against imgW / imgH would let the snap exceed
		// what fits at the user's anchored center, and the subsequent setCenter clamp would silently
		// drift the locked center inward. No-op for FREE / fractional ARs.
		int[] snapped = state.getAspectRatio().snap(roundedCropW, roundedCropH,
			roundedCropW, roundedCropH);
		state.setCropSizeSilent(snapped[0], snapped[1]);
		state.setCropSizeDirty(false);
		state.setCenter(centerX, centerY);

		recheckRotationFit(state, imgW, imgH, rotation);
	}

	/**
	 * AABB midpoint of the selection points in ROTATED image space — rotate each point around the image center
	 * first, then take the axis-aligned bounding box of the rotated positions, then return its midpoint. Rotation
	 * doesn't commute with axis-aligned-bbox, so this gives a different (correct) result than rotating the
	 * un-rotated midpoint.
	 *
	 * Used by CropEngine.recomputeCrop (Select-mode center derivation) AND by callers that need to match its
	 * framing in other modes (MainActivity.recenterOnSelection when the user switches lock axis in Move mode on a
	 * rotated image). Keeping both paths on the same formula means switching between Select and Move can't shift
	 * the crop's visual position on a rotated image.
	 *
	 * For a single selection point, snaps to the nearest half-integer in rotated space so the grid's middle line
	 * can draw through the marker pixel (onTap already pre-snaps the stored point to pixel+0.5 in un-rotated
	 * coords; under rotation the rotated position is fractional, so a re-snap is needed).
	 *
	 * @param points   selection points in un-rotated image coords
	 * @param imgW     source image width
	 * @param imgH     source image height
	 * @param rotation user-applied rotation in degrees
	 * @return [midX, midY] in ROTATED image space (each input point is forward-rotated around the image center
	 *         and the AABB midpoint is taken there); callers consume this directly to match rotated selection
	 *         framing in CropEngine.recomputeCrop and MainActivity.recenterOnSelection. For a single-point
	 *         input the result is snapped to a pixel-half-integer.
	 */
	public static float[] rotatedSelectionMidpoint(List<SelectionPoint> points, int imgW, int imgH, float rotation)
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
				(float) Math.floor(minX) + 0.5f, (float) Math.floor(minY) + 0.5f
			};
		}
		return new float[] { (minX + maxX) / 2f, (minY + maxY) / 2f };
	}

	/**
	 * Shift free-axis centers so the crop rectangle stays inside the image. Guards against images smaller than the
	 * minimum crop: when cropW ≥ imgW the upper bound falls below the lower bound and Math.clamp would throw — fall
	 * back to centering.
	 *
	 * Package-private so CropEngineGeometryTest can pin the free-axis clamp formula directly. A regression
	 * that flips the locked/free predicates or drops the "cropW >= imgW → center on midpoint" guard would
	 * let the image-larger-than-crop case throw Math.clamp's lo > hi exception instead of degenerate-but-safe
	 * centering.
	 */
	static float[] clampFreeAxes(float centerX, float centerY,
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
	 * Compute the maximum crop size on each axis, subject to lock mode and aspect ratio. Locked axes are symmetric
	 * about the center (so a selection stays framed); free axes get the full image extent and will be clamped /
	 * shifted later by clampFreeAxes.
	 *
	 * Package-private so CropEngineGeometryTest can pin the locked-axis symmetry and AR fit/shrink branches
	 * without instantiating a full CropState.
	 */
	static float[] computeMaxCropSize(AspectRatio ar, float centerX, float centerY,
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
	 * Find the max scale factor (0..1) so that a crop of (cropW*s, cropH*s) centered at (centerX, centerY) fits
	 * entirely within an imgW × imgH image rotated by rotation degrees. Each crop corner, un-rotated around image
	 * center, must land inside [0, imgW] × [0, imgH].
	 *
	 * Package-private so CropEngineGeometryTest can pin the 25-iteration binary search behavior. A
	 * regression that returns 1f for crops that ALREADY extend past the rotated bounds, or loops fewer
	 * iterations and converges short of the actual max, would let rotated exports bleed CANVAS_BG
	 * outside the image rectangle at the saved corners.
	 */
	static float maxScaleForRotation(float centerX, float centerY, float cropW, float cropH,
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

			// If this corner already fits at scale=1, nothing to do. Otherwise binary-search the largest
			// scale factor that brings it inside image bounds.
			if (unrotated[0] < 0 || unrotated[0] > imgW || unrotated[1] < 0 || unrotated[1] > imgH)
			{
				float loScale = 0.01f;           // min 1% to avoid degenerate 0-size crops
				float hiScale = 1f;
				// Iteration cap matches RotatedCropClamp.binarySearchAxis via the shared
				// BINARY_SEARCH_ITERATIONS constant; both bisections need to converge to the same
				// resolution so the rotation-fit guarantees they jointly enforce stay consistent.
				for (int i = 0; i < RotatedCropClamp.BINARY_SEARCH_ITERATIONS; i++)
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

	/**
	 * Derive the crop center for this recompute pass. In Select mode with points, returns the AABB midpoint of the
	 * selection points IN ROTATED IMAGE SPACE via rotatedSelectionMidpoint. In all other cases returns the stable
	 * rotation anchor (the user's intended, un-clamped center).
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
	 * Second rotation-fit pass: integer rounding of cropW / cropH above may push the corners just outside the
	 * rotated image bounds. If maxScaleForRotation reports the post-rounding crop is meaningfully too big (recheck
	 * < 0.99), shrink + re-round + re-commit. Uses the state's current (post-setCenter) values because setCenter's
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
		int refinedCropW = Math.max(MIN_CROP_DIMENSION_PX, Math.round(cropW));
		int refinedCropH = Math.max(MIN_CROP_DIMENSION_PX, Math.round(cropH));
		// Snap to exact-integer AR while forbidding growth — pass refinedCropW / refinedCropH as the
		// max bounds so the snap rounds DOWN to the nearest (Wr·k, Hr·k) that fits the just-shrunk
		// dims. Up-snapping here would re-violate the rotation fit this method exists to enforce.
		int[] snapped = state.getAspectRatio().snap(refinedCropW, refinedCropH, refinedCropW, refinedCropH);
		state.setCropSizeSilent(snapped[0], snapped[1]);
		state.setCenter(finalCenterX, finalCenterY);
	}

	/**
	 * Axis-aligned bounding-box midpoint of a non-empty selection. A single point is its own midpoint; with
	 * multiple points we average the min/max on each axis (cheaper than a true centroid and matches how the crop
	 * engine frames the selection). Private — the only consumer outside CropEngine is rotatedSelectionMidpoint,
	 * which transforms the midpoint through rotation before returning. Direct callers want the rotated form.
	 */
	private static float[] selectionMidpoint(List<SelectionPoint> points)
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
}
