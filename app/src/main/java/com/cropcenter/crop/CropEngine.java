package com.cropcenter.crop;

import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.SelectionPoint;
import java.util.List;

public final class CropEngine {

    private CropEngine() {}

    /**
     * Recompute crop size and clamp center.
     * When cropSizeDirty: computes maximum crop at current AR centered on current center.
     * Otherwise: just ensures center stays in valid bounds.
     */
    public static void recomputeCrop(CropState state) {
        if (!state.hasCenter() || state.getSourceImage() == null) return;

        int imgW = state.getImageWidth();
        int imgH = state.getImageHeight();
        float cx = Math.round(state.getCenterX());
        float cy = Math.round(state.getCenterY());
        cx = Math.max(0, Math.min(imgW, cx));
        cy = Math.max(0, Math.min(imgH, cy));

        if (!state.isCropSizeDirty() && state.getCropW() > 0 && state.getCropH() > 0) {
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

        float cropW, cropH;
        AspectRatio ar = state.getAspectRatio();

        if (!ar.isFree()) {
            float ratio = ar.ratio();
            if (maxH == 0 || maxW / maxH <= ratio) {
                cropW = maxW;
                cropH = Math.round(cropW / ratio);
                if (cropH > maxH) { cropH = maxH; cropW = Math.round(cropH * ratio); }
            } else {
                cropH = maxH;
                cropW = Math.round(cropH * ratio);
                if (cropW > maxW) { cropW = maxW; cropH = Math.round(cropW / ratio); }
            }
        } else {
            cropW = maxW;
            cropH = maxH;
        }

        // Scale down for rotation — check all 4 corners of the crop
        float rotation = state.getRotationDegrees();
        if (rotation != 0f && cropW > 0 && cropH > 0) {
            float scale = maxScaleForRotation(cx, cy, cropW, cropH, imgW, imgH, rotation);
            if (scale < 1f) {
                cropW *= scale;
                cropH *= scale;
            }
        }

        cropW = Math.max(4, cropW);
        cropH = Math.max(4, cropH);

        // Clamp center for free axes — use image bounds (setCenter handles rotation clamping)
        if (!lockedX) cx = Math.max(cropW / 2, Math.min(imgW - cropW / 2, cx));
        if (!lockedY) cy = Math.max(cropH / 2, Math.min(imgH - cropH / 2, cy));

        state.setCropSizeSilent(Math.round(cropW), Math.round(cropH));
        state.setCropSizeDirty(false);
        // setCenter applies rotation-aware clamping via binary search
        state.setCenter(cx, cy);

        // If rotation clamping moved the center significantly, the crop may now be
        // too large for the new center. Re-check and shrink if needed.
        if (rotation != 0f) {
            float finalCx = state.getCenterX();
            float finalCy = state.getCenterY();
            float recheck = maxScaleForRotation(finalCx, finalCy,
                    state.getCropW(), state.getCropH(), imgW, imgH, rotation);
            if (recheck < 0.99f) {
                cropW = state.getCropW() * recheck;
                cropH = state.getCropH() * recheck;
                cropW = Math.max(4, cropW);
                cropH = Math.max(4, cropH);
                state.setCropSizeSilent(Math.round(cropW), Math.round(cropH));
                state.setCenter(finalCx, finalCy);
            }
        }
    }

    /**
     * Find the max scale factor (0-1) so that a crop of (cw*s, ch*s) centered at (cx,cy)
     * fits entirely within a W×H image rotated by rotation degrees.
     * Each crop corner, un-rotated around image center, must be inside [0,W]×[0,H].
     */
    private static float maxScaleForRotation(float cx, float cy, float cw, float ch,
                                              int imgW, int imgH, float rotation) {
        float mx = imgW / 2f, my = imgH / 2f;
        double rad = Math.toRadians(-rotation);
        double cosR = Math.cos(rad), sinR = Math.sin(rad);

        // For each corner offset from center, find max scale
        float[][] offsets = {{-0.5f, -0.5f}, {0.5f, -0.5f}, {-0.5f, 0.5f}, {0.5f, 0.5f}};
        float minScale = 1f;

        for (float[] off : offsets) {
            float px = cx + off[0] * cw;
            float py = cy + off[1] * ch;

            // Un-rotate around image center
            double dx = px - mx, dy = py - my;
            double ux = dx * cosR - dy * sinR + mx;
            double uy = dx * sinR + dy * cosR + my;

            // How much do we need to scale to bring this corner inside bounds?
            // The corner moves as: cx + off*cw*scale, which un-rotates to some point.
            // We need ux in [0, imgW] and uy in [0, imgH].
            // Scale affects the offset from center: (px-cx) = off*cw*scale
            // So un-rotated x = (cx-mx)*cosR - (cy-my)*sinR + off*cw*scale*cosR - off*ch*scale*sinR + mx
            // This is linear in scale.

            // Simpler: compute at scale=1, check if inside, binary search if not
            if (ux < 0 || ux > imgW || uy < 0 || uy > imgH) {
                // Binary search for max scale
                float lo = 0.01f, hi = 1; // min 1% to avoid degenerate 0-size crops
                for (int i = 0; i < 20; i++) {
                    float mid = (lo + hi) / 2f;
                    float tpx = cx + off[0] * cw * mid;
                    float tpy = cy + off[1] * ch * mid;
                    double tdx = tpx - mx, tdy = tpy - my;
                    double tux = tdx * cosR - tdy * sinR + mx;
                    double tuy = tdx * sinR + tdy * cosR + my;
                    if (tux >= 0 && tux <= imgW && tuy >= 0 && tuy <= imgH) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                minScale = Math.min(minScale, lo);
            }
        }
        return minScale;
    }

    /**
     * Auto-compute crop from selection points, respecting lock mode.
     *
     * Both:  center on selection midpoint for both axes (symmetric around points)
     * H:     center horizontally on points, vertically on image center (max height)
     * V:     center vertically on points, horizontally on image center (max width)
     */
    public static void autoComputeFromPoints(CropState state) {
        List<SelectionPoint> points = state.getSelectionPoints();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int active = 0;

        for (SelectionPoint p : points) {
            if (!p.active) continue;
            active++;
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y);
        }

        if (active == 0) return;

        float midPtX = (active == 1) ? minX : (minX + maxX) / 2f;
        float midPtY = (active == 1) ? minY : (minY + maxY) / 2f;

        // Always start centered on the selection midpoint.
        // Locked axes: crop is symmetric around this center.
        // Free axes: crop extends to max available size, but stays centered on
        // the points as much as possible (recomputeCrop clamps if needed to fit).
        float fx = midPtX;
        float fy = midPtY;

        state.markCropSizeDirty();
        state.setCenterUnclamped(fx, fy);
        recomputeCrop(state);
    }
}
