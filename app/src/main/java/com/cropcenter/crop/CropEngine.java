package com.cropcenter.crop;

import android.graphics.Bitmap;

import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.SelectionPoint;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.RotatedCropClamp;
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
	// Local alias for AspectRatio.MIN_CROP_DIMENSION_PX — moved to AspectRatio (model/) so model/
	// doesn't depend on crop/. Alias keeps the existing CropEngine.MIN_CROP_DIMENSION_PX call sites
	// working without the long-name AspectRatio.MIN_CROP_DIMENSION_PX at each Math.max call. The
	// original constant docstring (4-px floor, why) lives on AspectRatio.MIN_CROP_DIMENSION_PX.
	public static final int MIN_CROP_DIMENSION_PX = AspectRatio.MIN_CROP_DIMENSION_PX;

	private CropEngine() {}

	/**
	 * Auto-compute crop from selection points, respecting lock mode.
	 *
	 * Both: center on selection midpoint for both axes (symmetric around points) H: center horizontally on points,
	 * vertically on image center (max height) V: center vertically on points, horizontally on image center (max
	 * width)
	 *
	 * @param state crop state to mutate; reads selection points and rotation, and writes the recomputed
	 *              center, crop size, and rotation/drag anchor. No-op when the center mode is LOCKED (Pin)
	 *              or there are no selection points.
	 */
	public static void autoComputeFromPoints(CropState state)
	{
		if (state.getCenterMode() == CenterMode.LOCKED)
		{
			// Pin mode: crop is frozen while the user drags the viewport. Points may still be added or
			// removed, but the crop box must not resize/relocate until Pin turns off and the btnPin click
			// handler fires recomputeForLockChange(), which runs this method again with the real centerMode
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
	 *
	 * @param state crop state to mutate; reads AR / lock mode / rotation / center / source image, writes the
	 *              clamped center and (when dirty) recomputed crop dimensions. No-op when there is no center or
	 *              no source image installed.
	 */
	public static void recomputeCrop(CropState state)
	{
		// Snapshot the volatile sourceImage reference once. The post-check getImageWidth() /
		// getImageHeight() reads each independently dereference the same volatile field (CropState
		// returns 0 when null), so a bg-thread reset() between the null check and the dimension
		// reads would silently produce imgW=imgH=0, collapse rotatedAabbDimensions to (0, 0), and
		// leave Math.clamp(center, 0, 0) writing a (0, 0) crop center. Snapshot here and read
		// dimensions off the local Bitmap so reset() can null state.sourceImage independently.
		Bitmap source = state.getSourceImage();
		if (!state.hasCenter() || source == null)
		{
			return;
		}

		int imgW = source.getWidth();
		int imgH = source.getHeight();
		float rotation = state.getRotationDegrees();

		// Compute the rotated AABB bounds for centerX / centerY. centerX / centerY live in screen-aligned
		// image space — the same coords that the editor's crop overlay draws into via `imageToScreenX/Y`,
		// which is a linear (un-rotated) map. When the image is rotated, the bitmap pixel at (px, py) appears
		// at the forward-rotated position on screen, which can lie OUTSIDE the un-rotated [0, imgW] ×
		// [0, imgH] bitmap rectangle. So the legitimate range of centerX / centerY is the rotated AABB —
		// `[imgW/2 ± rotatedW/2]` × `[imgH/2 ± rotatedH/2]`. Clamping to the un-rotated bitmap bounds (the
		// old behavior) pulled out-of-bitmap-but-in-AABB selection centers back onto the bitmap edge,
		// causing the crop to be drawn far from the user's selection points at high rotation.
		float[] rotatedDims = RotationMath.rotatedAabbDimensions(imgW, imgH, rotation, new float[2]);
		float halfRotatedW = rotatedDims[0] / 2f;
		float halfRotatedH = rotatedDims[1] / 2f;
		float imageMidX = imgW / 2f;
		float imageMidY = imgH / 2f;
		float leftBound = imageMidX - halfRotatedW;
		float rightBound = imageMidX + halfRotatedW;
		float topBound = imageMidY - halfRotatedH;
		float bottomBound = imageMidY + halfRotatedH;

		float[] center = findCropCenter(state, imgW, imgH, rotation);
		float centerX = Math.clamp(center[0], leftBound, rightBound);
		float centerY = Math.clamp(center[1], topBound, bottomBound);

		if (!state.isCropSizeDirty() && state.getCropW() > 0 && state.getCropH() > 0)
		{
			// Size locked — keep cropW / cropH, let setCenter clamp centerX / centerY into the rotated
			// image bounds. No parity snap: cropImageX is the continuous-float `centerX − cropW / 2f`
			// from getCropImageXFloat; the exporter's float-origin path absorbs the sub-pixel bias.
			state.setCropSizeSilent(state.getCropW(), state.getCropH());
			state.setCenter(centerX, centerY);
			return;
		}

		CenterMode mode = state.getCenterMode();
		boolean lockedX = (mode == CenterMode.BOTH || mode == CenterMode.HORIZONTAL);
		boolean lockedY = (mode == CenterMode.BOTH || mode == CenterMode.VERTICAL);

		float[] cropSize = computeMaxCropSize(state.getAspectRatio(),
			centerX, centerY, imgW, imgH, lockedX, lockedY, rotation);
		float cropW = cropSize[0];
		float cropH = cropSize[1];

		// Free-axis clamp runs BEFORE the rotation shrink so H / V / BOTH stay visibly distinct under rotation.
		// If the rotation shrink ran first, the shrunk crop fits at any mode's requested center, collapsing all
		// modes to the same result — the user-visible behaviour is that "free X" / "free Y" / "free both" all
		// produce identical crops, defeating the whole point of the mode toggle.
		float[] clampedCenter = clampFreeAxes(centerX, centerY, cropW, cropH, imgW, imgH,
			lockedX, lockedY, rotation);
		centerX = clampedCenter[0];
		centerY = clampedCenter[1];

		// Sub-epsilon rotation (below ROTATION_EPSILON = 0.005°) is drawn / exported as zero by the render
		// pipeline, so skip the rotation-fit shrink here too — otherwise a residual sub-epsilon angle would
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
	 * AABB midpoint of the selection points in ROTATED image space — rotate each point around the image center,
	 * take the axis-aligned bounding box there, return its midpoint. Rotation doesn't commute with AABB, so this
	 * differs (correctly) from rotating the un-rotated midpoint.
	 *
	 * Used by CropEngine.recomputeCrop (Select-mode center) and MainActivity.recenterOnSelection (lock-axis
	 * switch in Move mode on a rotated image) — sharing one formula means switching Select↔Move can't shift the
	 * crop's visual position. For a single point, snaps to the nearest half-integer in rotated space so the
	 * grid's middle line draws through the marker pixel (onTap pre-snaps to pixel+0.5 un-rotated; rotation makes
	 * that fractional, so a re-snap is needed).
	 *
	 * @param points   selection points in un-rotated image coords
	 * @param imgW     source image width
	 * @param imgH     source image height
	 * @param rotation user-applied rotation in degrees
	 * @return [midX, midY] in ROTATED image space (single-point input is snapped to a pixel-half-integer)
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
	 * Shift free-axis centers so the crop stays inside the rotated AABB. Guards the degenerate case where the
	 * crop dimension meets or exceeds the AABB extent on its axis (the upper clamp bound would fall ≤ the lower
	 * and Math.clamp would throw) by centering on the image midpoint. Rotation-aware: at zero rotation
	 * rotatedW = imgW and it matches pre-rotation behavior.
	 *
	 * Package-private so CropEngineGeometryTest pins the formula — a regression flipping the locked/free
	 * predicates or dropping the "cropW >= rotatedW → midpoint" guard would let Math.clamp throw lo > hi instead
	 * of centering safely.
	 *
	 * @param centerX         current crop center X (screen-aligned image coords)
	 * @param centerY         current crop center Y (screen-aligned image coords)
	 * @param cropW           crop width in image pixels
	 * @param cropH           crop height in image pixels
	 * @param imgW            source image width
	 * @param imgH            source image height
	 * @param lockedX         true when X must stay symmetric around the anchor (no shift)
	 * @param lockedY         true when Y must stay symmetric around the anchor (no shift)
	 * @param rotationDegrees current rotation; sets the rotated AABB the free axes clamp against
	 * @return [shiftedX, shiftedY] keeping the crop inside the rotated AABB on each free axis
	 */
	static float[] clampFreeAxes(float centerX, float centerY,
		float cropW, float cropH, int imgW, int imgH,
		boolean lockedX, boolean lockedY, float rotationDegrees)
	{
		float[] rotatedDims = RotationMath.rotatedAabbDimensions(imgW, imgH, rotationDegrees, new float[2]);
		float rotatedW = rotatedDims[0];
		float rotatedH = rotatedDims[1];
		float imageMidX = imgW / 2f;
		float imageMidY = imgH / 2f;
		if (!lockedX)
		{
			centerX = (cropW < rotatedW)
				? Math.clamp(centerX, imageMidX - rotatedW / 2f + cropW / 2f,
					imageMidX + rotatedW / 2f - cropW / 2f)
				: imageMidX;
		}
		if (!lockedY)
		{
			centerY = (cropH < rotatedH)
				? Math.clamp(centerY, imageMidY - rotatedH / 2f + cropH / 2f,
					imageMidY + rotatedH / 2f - cropH / 2f)
				: imageMidY;
		}
		return new float[] { centerX, centerY };
	}

	/**
	 * Compute the maximum crop size per axis, subject to lock mode and aspect ratio. Locked axes are symmetric
	 * about the center (keeping a selection framed); free axes get the full rotated-AABB extent (clamped later
	 * by clampFreeAxes). Rotation-aware: at zero rotation the rotated AABB matches the un-rotated bounds; at
	 * non-zero rotation it's bigger, and the resulting crop is shrunk to fit by the downstream
	 * maxScaleForRotation pass.
	 *
	 * Package-private so CropEngineGeometryTest can pin the locked-axis symmetry and AR fit/shrink branches.
	 *
	 * @param ar              aspect ratio constraint; FREE leaves both axes at their maxima
	 * @param centerX         crop center X (screen-aligned image coords)
	 * @param centerY         crop center Y (screen-aligned image coords)
	 * @param imgW            source image width
	 * @param imgH            source image height
	 * @param lockedX         true to size cropW symmetrically around centerX
	 * @param lockedY         true to size cropH symmetrically around centerY
	 * @param rotationDegrees current image rotation; rotated AABB is used for the free-axis maximum
	 * @return [maxCropW, maxCropH] subject to AR + lock mode + rotated bounds
	 */
	static float[] computeMaxCropSize(AspectRatio ar, float centerX, float centerY,
		int imgW, int imgH, boolean lockedX, boolean lockedY, float rotationDegrees)
	{
		float[] rotatedDims = RotationMath.rotatedAabbDimensions(imgW, imgH, rotationDegrees, new float[2]);
		float rotatedW = rotatedDims[0];
		float rotatedH = rotatedDims[1];
		float imageMidX = imgW / 2f;
		float imageMidY = imgH / 2f;
		float leftBound = imageMidX - rotatedW / 2f;
		float rightBound = imageMidX + rotatedW / 2f;
		float topBound = imageMidY - rotatedH / 2f;
		float bottomBound = imageMidY + rotatedH / 2f;
		float maxCropW = lockedX
			? 2 * Math.min(centerX - leftBound, rightBound - centerX)
			: rotatedW;
		float maxCropH = lockedY
			? 2 * Math.min(centerY - topBound, bottomBound - centerY)
			: rotatedH;

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
			// scale factor that brings it inside image bounds. Slack tolerance matches
			// `RotatedCropClamp.cornersInside` — both use a ±0.5-px window so a center that
			// `setCenter`'s rotation-clamp landed JUST inside cornersInside's float-precision tolerance
			// isn't seen as "outside" here, which would then trigger `recheckRotationFit` to
			// unnecessarily shrink the crop by ~1 px on edge-touching rotations. Sharing the slack
			// keeps the two rotation predicates joint enforcing the same invariant.
			if (unrotated[0] < -0.5f || unrotated[0] > imgW + 0.5f
				|| unrotated[1] < -0.5f || unrotated[1] > imgH + 0.5f)
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
					if (unrotated[0] >= -0.5f && unrotated[0] <= imgW + 0.5f
						&& unrotated[1] >= -0.5f && unrotated[1] <= imgH + 0.5f)
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
	 * Second rotation-fit pass: integer rounding of cropW / cropH above may push the corners just outside the
	 * rotated image bounds. If maxScaleForRotation reports the post-rounding crop is meaningfully too big (recheck
	 * < 0.99), shrink + re-round + re-commit. Uses the state's current (post-setCenter) values because setCenter's
	 * own rotation clamp may have nudged the center.
	 *
	 * Package-private so CropEngineGeometryTest can pin the 0.99 threshold AND the AR-snap-no-grow guard
	 * directly — a regression that flips the threshold to ≥ 1f (no recheck ever fires) would let
	 * post-rounding crops bleed CANVAS_BG outside the rotated image at the saved corners; a regression that
	 * passes (imgW, imgH) as snap bounds (instead of the refined dims) would let the AR snap grow past the
	 * just-shrunk fit and re-violate the very invariant this pass exists to enforce.
	 *
	 * @param state    crop state mutated in place; reads the post-setCenter center + crop dims and may rewrite
	 *                 the crop dims and re-commit the center
	 * @param imgW     source image width in pixels
	 * @param imgH     source image height in pixels
	 * @param rotation current image rotation in degrees; sub-epsilon values short-circuit the recheck
	 */
	static void recheckRotationFit(CropState state, int imgW, int imgH, float rotation)
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
	 * engine frames the selection). Package-private so CropEngineTest can pin the single-vs-multi branch
	 * directly — the only production consumer outside CropEngine is rotatedSelectionMidpoint, which transforms
	 * the midpoint through rotation before returning.
	 *
	 * @param points non-empty list of selection points in image-space coordinates; the list is read-only here
	 *               (not modified, not retained)
	 * @return new float[2] holding {midX, midY} in the same coordinate space as the input points
	 */
	static float[] selectionMidpoint(List<SelectionPoint> points)
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
	 * Derive the crop center for this recompute pass. In Select mode with points, returns the AABB midpoint of the
	 * selection points IN ROTATED IMAGE SPACE via rotatedSelectionMidpoint. In all other cases returns the stable
	 * rotation anchor (the user's intended, un-clamped center).
	 *
	 * @param state    crop state; read-only here (editor mode, selection points, and the rotation anchor)
	 * @param imgW     source image width in pixels
	 * @param imgH     source image height in pixels
	 * @param rotation current image rotation in degrees; forwarded to rotatedSelectionMidpoint when a selection
	 *                 is present
	 * @return new float[]{centerX, centerY} in screen-aligned image coordinates
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

}
