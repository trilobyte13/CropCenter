package com.cropcenter.model;

import android.graphics.Bitmap;
import com.cropcenter.metadata.JpegSegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Central state object for the crop editor.
 * Holds all parameters, source image, and extracted metadata.
 */
public class CropState {

    public interface OnStateChangedListener {
        void onStateChanged();
    }

    // ── Source image ──
    private Bitmap sourceImage;
    private byte[] originalFileBytes;
    private String sourceFormat; // "jpeg" or "png"

    // ── Crop parameters ──
    private float centerX;
    private float centerY;
    private int cropW;
    private int cropH;
    private boolean hasCenter;
    private boolean cropSizeDirty = true;
    private float rotationDegrees = 0f; // precise rotation applied to source image

    // ── Settings ──
    private AspectRatio aspectRatio = AspectRatio.R4_5;
    private String originalFilename;
    private String originalFilePath; // absolute path for Samsung Revert
    private long mediaStoreId = -1;  // MediaStore _ID for Samsung Revert
    private CenterMode centerMode = CenterMode.BOTH;
    private EditorMode editorMode = EditorMode.MOVE;
    private boolean centerLocked = false; // when true, auto-recompute from points is suppressed
    private final GridConfig gridConfig = new GridConfig();
    private final ExportConfig exportConfig = new ExportConfig();

    // ── Selection points (feature mode) ──
    private final List<SelectionPoint> selectionPoints = new ArrayList<>();

    // ── Extracted metadata ──
    private List<JpegSegment> jpegMeta = new ArrayList<>();
    private byte[] gainMap;
    private byte[] seftTrailer; // Samsung SEFT trailer (appended after gain map)

    // ── Listener ──
    private OnStateChangedListener listener;

    public void setListener(OnStateChangedListener listener) {
        this.listener = listener;
    }

    private void notifyChanged() {
        if (listener != null) listener.onStateChanged();
    }

    // ── Source image ──

    public Bitmap getSourceImage() { return sourceImage; }
    public void setSourceImage(Bitmap bmp) { this.sourceImage = bmp; notifyChanged(); }

    public byte[] getOriginalFileBytes() { return originalFileBytes; }
    public void setOriginalFileBytes(byte[] bytes) { this.originalFileBytes = bytes; }

    public String getSourceFormat() { return sourceFormat; }
    public void setSourceFormat(String fmt) { this.sourceFormat = fmt; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String name) { this.originalFilename = name; }

    public String getOriginalFilePath() { return originalFilePath; }
    public void setOriginalFilePath(String path) { this.originalFilePath = path; }

    public long getMediaStoreId() { return mediaStoreId; }
    public void setMediaStoreId(long id) { this.mediaStoreId = id; }

    public int getImageWidth() { return sourceImage != null ? sourceImage.getWidth() : 0; }
    public int getImageHeight() { return sourceImage != null ? sourceImage.getHeight() : 0; }

    // ── Crop parameters ──

    public float getCenterX() { return centerX; }
    public float getCenterY() { return centerY; }
    /** Set center without bounds clamping or notification — used before recomputeCrop. */
    public void setCenterUnclamped(float x, float y) {
        this.centerX = x;
        this.centerY = y;
        this.hasCenter = true;
        // No notifyChanged — caller will trigger notify via recomputeCrop → setCenter
    }

    public void setCenter(float x, float y) {
        // Clamp so crop rect stays fully inside the (possibly rotated) image.
        if (sourceImage != null && cropW > 0 && cropH > 0) {
            int imgW = sourceImage.getWidth();
            int imgH = sourceImage.getHeight();

            if (rotationDegrees == 0f) {
                if (cropW < imgW) {
                    x = Math.max(cropW / 2f, Math.min(imgW - cropW / 2f, x));
                } else {
                    x = imgW / 2f;
                }
                if (cropH < imgH) {
                    y = Math.max(cropH / 2f, Math.min(imgH - cropH / 2f, y));
                } else {
                    y = imgH / 2f;
                }
            } else {
                // For rotated images: clamp each axis independently via binary search.
                // This prevents clamping X from affecting Y and vice versa.
                float mx = imgW / 2f, my = imgH / 2f;
                double rad = Math.toRadians(-rotationDegrees);
                double cosR = Math.cos(rad), sinR = Math.sin(rad);
                float hw = cropW / 2f, hh = cropH / 2f;

                // First clamp X (keeping Y fixed)
                if (!cornersInside(x, y, hw, hh, mx, my, cosR, sinR, imgW, imgH)) {
                    float lo = 0f, hi = 1f;
                    float validX = mx;
                    for (int i = 0; i < 25; i++) {
                        float t = (lo + hi) / 2f;
                        float tx = mx + (x - mx) * t;
                        if (cornersInside(tx, y, hw, hh, mx, my, cosR, sinR, imgW, imgH)) {
                            validX = tx; lo = t;
                        } else {
                            hi = t;
                        }
                    }
                    x = validX;
                }

                // Then clamp Y (keeping clamped X fixed)
                if (!cornersInside(x, y, hw, hh, mx, my, cosR, sinR, imgW, imgH)) {
                    float lo = 0f, hi = 1f;
                    float validY = my;
                    for (int i = 0; i < 25; i++) {
                        float t = (lo + hi) / 2f;
                        float ty = my + (y - my) * t;
                        if (cornersInside(x, ty, hw, hh, mx, my, cosR, sinR, imgW, imgH)) {
                            validY = ty; lo = t;
                        } else {
                            hi = t;
                        }
                    }
                    y = validY;
                }
            }
        }
        this.centerX = x;
        this.centerY = y;
        this.hasCenter = true;
        notifyChanged();
    }


    public int getCropW() { return cropW; }
    public int getCropH() { return cropH; }
    public void setCropSize(int w, int h) {
        this.cropW = w;
        this.cropH = h;
        notifyChanged();
    }

    /** Set crop size without triggering listener — used during batch updates in recomputeCrop. */
    public void setCropSizeSilent(int w, int h) {
        this.cropW = w;
        this.cropH = h;
    }

    public boolean hasCenter() { return hasCenter; }

    // ── Settings ──

    public boolean isCropSizeDirty() { return cropSizeDirty; }
    public void setCropSizeDirty(boolean dirty) { this.cropSizeDirty = dirty; }
    public void markCropSizeDirty() { this.cropSizeDirty = true; }

    public float getRotationDegrees() { return rotationDegrees; }
    public void setRotationDegrees(float deg) {
        if (Float.isNaN(deg) || Float.isInfinite(deg)) deg = 0f;
        deg = Math.max(-180f, Math.min(180f, deg));
        this.rotationDegrees = deg;
        this.cropSizeDirty = true;
        notifyChanged();
    }

    public AspectRatio getAspectRatio() { return aspectRatio; }
    public void setAspectRatio(AspectRatio ar) { this.aspectRatio = ar; cropSizeDirty = true; notifyChanged(); }

    public CenterMode getCenterMode() { return centerMode; }
    public void setCenterMode(CenterMode mode) {
        this.centerMode = mode;
        // Don't set cropSizeDirty here — the button handler calls recomputeForLockChange()
        // explicitly. Setting dirty here causes the listener to recompute IMMEDIATELY
        // (runOnUiThread runs inline on UI thread) with the wrong center, racing with
        // the handler's recomputeForLockChange that uses the correct selection midpoint.
        notifyChanged();
    }

    public EditorMode getEditorMode() { return editorMode; }
    public void setEditorMode(EditorMode mode) {
        this.editorMode = mode;
        // Don't set cropSizeDirty — mode changes don't affect crop size
        notifyChanged();
    }

    public boolean isCenterLocked() { return centerLocked; }
    public void setCenterLocked(boolean locked) { this.centerLocked = locked; }

    public GridConfig getGridConfig() { return gridConfig; }
    public ExportConfig getExportConfig() { return exportConfig; }

    // ── Selection points ──

    public List<SelectionPoint> getSelectionPoints() { return selectionPoints; }

    // ── Metadata ──

    public List<JpegSegment> getJpegMeta() { return jpegMeta; }
    public void setJpegMeta(List<JpegSegment> meta) { this.jpegMeta = meta; }

    public byte[] getGainMap() { return gainMap; }
    public void setGainMap(byte[] gm) { this.gainMap = gm; }

    public byte[] getSeftTrailer() { return seftTrailer; }
    public void setSeftTrailer(byte[] seft) { this.seftTrailer = seft; }

    /** Check if all 4 corners of the crop rect, when un-rotated, are inside the image. */
    private static boolean cornersInside(float cx, float cy, float hw, float hh,
                                          float mx, float my, double cosR, double sinR,
                                          int imgW, int imgH) {
        float[] dxs = {-hw, hw, -hw, hw};
        float[] dys = {-hh, -hh, hh, hh};
        for (int i = 0; i < 4; i++) {
            double px = cx + dxs[i] - mx;
            double py = cy + dys[i] - my;
            double ux = px * cosR - py * sinR + mx;
            double uy = px * sinR + py * cosR + my;
            if (ux < -0.5 || ux > imgW + 0.5 || uy < -0.5 || uy > imgH + 0.5) return false;
        }
        return true;
    }

    /** Reset everything for a new image. */
    public void reset() {
        sourceImage = null;
        originalFileBytes = null;
        sourceFormat = null;
        centerX = centerY = 0;
        cropW = cropH = 0;
        hasCenter = false;
        cropSizeDirty = true;
        rotationDegrees = 0f;
        centerLocked = false;
        // aspectRatio preserved — it's a user preference, not image data
        originalFilename = null;
        originalFilePath = null;
        mediaStoreId = -1;
        selectionPoints.clear();
        jpegMeta.clear();
        gainMap = null;
        seftTrailer = null;
    }
}
