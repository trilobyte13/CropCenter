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
		// In Select mode with points, re-derive the center from the selection midpoint each
		// recompute so rotation/AR changes don't drift the center from the actual selection.
		boolean hasSelection = state.getEditorMode() == EditorMode.SELECT_FEATURE
			&& !state.getSelectionPoints().isEmpty();
		float cx;
		float cy;
		if (hasSelection)
		{
			List<SelectionPoint> points = state.getSelectionPoints();
			float[] mid = selectionMidpoint(points);
			if (points.size() == 1)
			{
				// Snap single-point center to the pixel's center so cropW is odd and the
				// grid's middle line covers the selection marker's pixel.
				mid[0] = (float) Math.floor(mid[0]) + 0.5f;
				mid[1] = (float) Math.floor(mid[1]) + 0.5f;
			}
			cx = mid[0];
			cy = mid[1];
		}
		else
		{
			// Use the stable rotation anchor (set by user drag / image load) rather than
			// state.centerX, which may have been parity-shifted by a previous recompute —
			// reading it back would accumulate 0.5-pixel drift across rotation ticks.
			cx = state.getAnchorX();
			cy = state.getAnchorY();
		}

		// Snap cx/cy to the 0.5-grid so their parity is well-defined (integer or half-int).
		// cropW/cropH parity below is then matched to cx/cy, guaranteeing cropImgX =
		// cx − cropW/2 is an integer so the crop's bounds always land on pixel boundaries.
		cx = Math.round(cx * 2f) / 2f;
		cy = Math.round(cy * 2f) / 2f;
		cx = Math.clamp(cx, 0, imgW);
		cy = Math.clamp(cy, 0, imgH);

		if (!state.isCropSizeDirty() && state.getCropW() > 0 && state.getCropH() > 0)
		{
			// Size locked — cropW/cropH are fixed, so force cx/cy parity to match.
			cx = snapCenterToParity(cx, state.getCropW());
			cy = snapCenterToParity(cy, state.getCropH());
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

		// Free-axis clamp may have shifted cx/cy to a fractional crop-boundary (cropW/2 is
		// fractional while cropW itself is still fractional here). Re-snap to the 0.5-grid.
		cx = Math.round(cx * 2f) / 2f;
		cy = Math.round(cy * 2f) / 2f;

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

		// Round cropW/cropH and enforce cropImgX = cx − cropW/2 integer (bounds on the
		// pixel grid). Two cases:
		//
		//   hasSelection  — the selection midpoint is the "truth" for cx/cy; we must NOT
		//                   shift it, so cropW/cropH round to the nearest integer whose
		//                   parity matches cx/cy. The size can't freely flip parity.
		//
		//   no selection  — cx/cy came from state (image center, last drag position, …)
		//                   and isn't anchored to any specific pixel. cropW/cropH round
		//                   to nearest integer freely; cx/cy shift by up to 0.5 pixel to
		//                   match the rounded size's parity. This lets the cropped size
		//                   flip even↔odd as rotation scales the crop.
		int cwInt;
		int chInt;
		if (hasSelection)
		{
			cwInt = Math.max(4, roundWithParity(cropW, cx));
			chInt = Math.max(4, roundWithParity(cropH, cy));
			// Edge case: Math.max(4, …) may have pushed the size to a parity that
			// mismatches. Shift cx/cy in that case — 0.5-pixel drift for tiny crops is
			// unavoidable.
			if (parityMismatch(cwInt, cx))
			{
				cx = snapCenterToParity(cx, cwInt);
			}
			if (parityMismatch(chInt, cy))
			{
				cy = snapCenterToParity(cy, chInt);
			}
		}
		else
		{
			cwInt = Math.max(4, Math.round(cropW));
			chInt = Math.max(4, Math.round(cropH));
			cx = snapCenterToParity(cx, cwInt);
			cy = snapCenterToParity(cy, chInt);
		}

		state.setCropSizeSilent(cwInt, chInt);
		state.setCropSizeDirty(false);
		state.setCenter(cx, cy);

		// Second rotation check — re-shrink if the rounded-up size slightly exceeds the
		// rotation fit. Same parity logic as above: anchor cx/cy (hasSelection) or shift
		// them to match the size (no selection).
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
				int cwInt2;
				int chInt2;
				if (hasSelection)
				{
					cwInt2 = Math.max(4, roundWithParity(cropW, finalCx));
					chInt2 = Math.max(4, roundWithParity(cropH, finalCy));
					if (parityMismatch(cwInt2, finalCx))
					{
						finalCx = snapCenterToParity(finalCx, cwInt2);
					}
					if (parityMismatch(chInt2, finalCy))
					{
						finalCy = snapCenterToParity(finalCy, chInt2);
					}
				}
				else
				{
					cwInt2 = Math.max(4, Math.round(cropW));
					chInt2 = Math.max(4, Math.round(cropH));
					finalCx = snapCenterToParity(finalCx, cwInt2);
					finalCy = snapCenterToParity(finalCy, chInt2);
				}
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

	/**
	 * True when `size` and `center` disagree on parity — an odd size needs a half-integer
	 * center (N + 0.5) so cropImgX = center − size/2 is integer; an even size needs an
	 * integer center.
	 */
	private static boolean parityMismatch(int size, float center)
	{
		boolean centerHalfInt = Math.abs(center - (float) Math.floor(center) - 0.5f) < 0.01f;
		boolean sizeOdd = (size & 1) == 1;
		return sizeOdd != centerHalfInt;
	}

	/**
	 * Round `value` to the nearest integer whose parity matches `center`'s 0.5-grid
	 * parity (half-integer center → odd result, integer center → even result). When
	 * Math.round gives the wrong parity, pick whichever of ±1 neighbours is closer to
	 * `value`; tie breaks toward the smaller (safer — smaller crop never overshoots the
	 * rotation fit). Worst-case overshoot is ~0.5 pixel, within cornersInside's 0.5-pixel
	 * epsilon, so setCenter's binary-search clamp won't trigger.
	 */
	private static int roundWithParity(float value, float center)
	{
		int rounded = Math.round(value);
		if (!parityMismatch(rounded, center))
		{
			return rounded;
		}
		int up = rounded + 1;
		int down = rounded - 1;
		return ((up - value) < (value - down)) ? up : down;
	}

	/**
	 * Snap a center coordinate so its fractional part matches the crop size's parity:
	 * even size → integer (pixel boundary), odd size → half-integer (pixel center). This
	 * guarantees cropImgX = center − size / 2 is an integer, so the crop's rendered bounds
	 * fall on whole-pixel boundaries rather than crossing a pixel mid-column.
	 */
	private static float snapCenterToParity(float center, int size)
	{
		if ((size & 1) == 0)
		{
			return Math.round(center);
		}
		return (float) Math.floor(center) + 0.5f;
	}
}
