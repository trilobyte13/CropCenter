package com.cropcenter.crop;

import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
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
		// In Select mode with points, re-derive the center from the selection midpoint each
		// recompute so rotation/AR changes don't drift the center from the actual selection.
		boolean hasSelection = state.getEditorMode() == EditorMode.SELECT_FEATURE
			&& !state.getSelectionPoints().isEmpty();
		float cx;
		float cy;
		if (hasSelection)
		{
			List<SelectionPoint> points = state.getSelectionPoints();
			// Selection points live in UN-rotated image coords, but the editor draws the image
			// rotated around its center and the exporter rotates the source bitmap before
			// cropping. We want cropCenter in the same (rotated) space the exporter crops in.
			//
			// Order matters: rotation doesn't commute with axis-aligned-bbox, so rotating each
			// point FIRST and then taking the AABB midpoint gives a different (correct) result
			// than rotating the un-rotated AABB midpoint. In V / H / BOTH lock modes the crop
			// is sized symmetrically about this center, so getting it wrong makes the crop
			// visibly asymmetric about the selection (e.g. 13 px top, 29 px bottom).
			float imageMidX = imgW / 2f;
			float imageMidY = imgH / 2f;
			double rad = Math.toRadians(rotation);
			double cosR = Math.cos(rad);
			double sinR = Math.sin(rad);
			float minX = Float.MAX_VALUE;
			float minY = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE;
			float maxY = -Float.MAX_VALUE;
			for (SelectionPoint point : points)
			{
				float rotX = point.x();
				float rotY = point.y();
				if (rotation != 0f)
				{
					float deltaX = rotX - imageMidX;
					float deltaY = rotY - imageMidY;
					rotX = (float) (deltaX * cosR - deltaY * sinR + imageMidX);
					rotY = (float) (deltaX * sinR + deltaY * cosR + imageMidY);
				}
				minX = Math.min(minX, rotX);
				minY = Math.min(minY, rotY);
				maxX = Math.max(maxX, rotX);
				maxY = Math.max(maxY, rotY);
			}
			if (points.size() == 1)
			{
				// Snap single-point center to the pixel's 0.5-grid so cropW is odd and the
				// grid's middle line visually covers the marker. The rotated point isn't on
				// the half-integer grid at non-zero rotation, so snap to the nearest half —
				// close enough that the grid still lands over the marker.
				cx = (float) Math.floor(minX) + 0.5f;
				cy = (float) Math.floor(minY) + 0.5f;
			}
			else
			{
				cx = (minX + maxX) / 2f;
				cy = (minY + maxY) / 2f;
			}
		}
		else
		{
			// Use the stable rotation anchor (set by user drag / image load) rather than
			// state.centerX, which may have been parity-shifted by a previous recompute —
			// reading it back would accumulate 0.5-pixel drift across rotation ticks.
			cx = state.getAnchorX();
			cy = state.getAnchorY();
		}

		// Keep cx/cy continuous here — snapping to the 0.5-grid plus parity-matching cropW
		// would make cropW flip even↔odd as the rotated midpoint crosses a 0.25 boundary,
		// which at any zoom reads as the crop/grid visibly flickering to another position
		// across consecutive frames. The exporter still gets an integer origin via
		// getCropImgX's floor() — the small sub-pixel residue (≤ 1 px) in exported bounds
		// is within spec.
		cx = Math.clamp(cx, 0, imgW);
		cy = Math.clamp(cy, 0, imgH);

		if (!state.isCropSizeDirty() && state.getCropW() > 0 && state.getCropH() > 0)
		{
			// Size locked — keep cropW/cropH, let setCenter clamp cx/cy into the rotated
			// image bounds. No parity snap: cropImgX comes from getCropImgX's floor().
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
				cropH = cropW / ratio;
				if (cropH > maxH)
				{
					cropH = maxH;
					cropW = cropH * ratio;
				}
			}
			else
			{
				cropH = maxH;
				cropW = cropH * ratio;
				if (cropW > maxW)
				{
					cropW = maxW;
					cropH = cropW / ratio;
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
			cx = (cropW < imgW) ? Math.clamp(cx, cropW / 2f, imgW - cropW / 2f) : imgW / 2f;
		}
		if (!lockedY)
		{
			cy = (cropH < imgH) ? Math.clamp(cy, cropH / 2f, imgH - cropH / 2f) : imgH / 2f;
		}

		// (No 0.5-grid re-snap — see the comment above where we skipped the first snap. The
		// clamp leaves cx/cy fractional; cropImgX lands on the pixel grid via getCropImgX's
		// floor(), not via parity matching on the center.)

		// Scale down for rotation — check all 4 corners of the crop
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

		// Round cropW/cropH to the nearest integer. Previously we matched parity to cx/cy so
		// cropImgX was exactly integer; that caused flicker because a smooth rotation sweeping
		// the rotated midpoint across parity boundaries made cropW jump even↔odd (and cx jump
		// by 0.5 px) from one frame to the next. We let cropImgX be computed by
		// getCropImgX's floor() — the resulting export is at most 1 sub-pixel off from the
		// fractional ideal, which matches what users perceive as "the crop is on the feature."
		int cwInt = Math.max(4, Math.round(cropW));
		int chInt = Math.max(4, Math.round(cropH));

		state.setCropSizeSilent(cwInt, chInt);
		state.setCropSizeDirty(false);
		state.setCenter(cx, cy);

		// Second rotation check — re-shrink if the rounded-up size slightly exceeds the
		// rotation fit.
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
				int cwInt2 = Math.max(4, Math.round(cropW));
				int chInt2 = Math.max(4, Math.round(cropH));
				state.setCropSizeSilent(cwInt2, chInt2);
				state.setCenter(finalCx, finalCy);
			}
		}
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
