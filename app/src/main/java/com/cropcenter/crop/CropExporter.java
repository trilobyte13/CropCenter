package com.cropcenter.crop;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;

import com.cropcenter.metadata.ExifPatcher;
import com.cropcenter.metadata.GainMapComposer;
import com.cropcenter.metadata.JpegMetadataInjector;
import com.cropcenter.metadata.MpfPatcher;
import com.cropcenter.metadata.SeftBuilder;
import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.model.CropState;
import com.cropcenter.model.GridConfig;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.UltraHdrCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Orchestrates the full export pipeline:
 *   1. Render cropped bitmap
 *   2. Compress to JPEG
 *   3. Inject original metadata (EXIF patched, ICC, XMP, MPF preserved)
 *   4. Append gain map and fix MPF offsets
 */
public final class CropExporter {

    private static final String TAG = "CropExporter";

    public static class ExportResult {
        public final byte[] data;
        public final String extension;
        public ExportResult(byte[] data, String extension) {
            this.data = data;
            this.extension = extension;
        }
    }

    private CropExporter() {}

    public static ExportResult export(CropState state, java.io.File cacheDir) throws IOException {
        Bitmap src = state.getSourceImage();
        if (src == null) {
            throw new IOException("No image loaded");
        }

        // If no crop center set, export full image
        int cropW, cropH, sx, sy;
        if (state.hasCenter()) {
            cropW = state.getCropW();
            cropH = state.getCropH();
            float cx = state.getCenterX();
            float cy = state.getCenterY();
            sx = (int) Math.floor(cx - cropW / 2f);
            sy = (int) Math.floor(cy - cropH / 2f);
        } else {
            cropW = src.getWidth();
            cropH = src.getHeight();
            sx = 0;
            sy = 0;
        }

        // Create output bitmap with Display P3 if ICC profile present
        Bitmap.Config config = Bitmap.Config.ARGB_8888;
        Bitmap outBmp;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && state.getIccProfile() != null) {
            outBmp = Bitmap.createBitmap(cropW, cropH, config, true,
                    ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
        } else {
            outBmp = Bitmap.createBitmap(cropW, cropH, config);
        }

        Canvas canvas = new Canvas(outBmp);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(0xFF0D0E14);

        float rotation = state.getRotationDegrees();
        if (rotation != 0f) {
            // Draw rotated: translate so image center aligns with crop center, then rotate
            canvas.save();
            float outCx = cropW / 2f;
            float outCy = cropH / 2f;
            canvas.rotate(rotation, outCx, outCy);
            // Draw the source image centered in the output
            float drawX = outCx - src.getWidth() / 2f;
            float drawY = outCy - src.getHeight() / 2f;
            // Offset for crop position (crop center vs image center)
            float imgCx = src.getWidth() / 2f;
            float imgCy = src.getHeight() / 2f;
            float offX = imgCx - (state.hasCenter() ? state.getCenterX() : imgCx);
            float offY = imgCy - (state.hasCenter() ? state.getCenterY() : imgCy);
            canvas.drawBitmap(src, drawX + offX, drawY + offY, paint);
            canvas.restore();
        } else {
            // No rotation: direct blit
            int vx1 = Math.max(0, sx);
            int vy1 = Math.max(0, sy);
            int vx2 = Math.min(src.getWidth(), sx + cropW);
            int vy2 = Math.min(src.getHeight(), sy + cropH);
            if (vx2 > vx1 && vy2 > vy1) {
                Rect srcRect = new Rect(vx1, vy1, vx2, vy2);
                Rect dstRect = new Rect(vx1 - sx, vy1 - sy, vx2 - sx, vy2 - sy);
                canvas.drawBitmap(src, srcRect, dstRect, paint);
            }
        }

        // Optional grid overlay bake-in (independent of whether grid is visible on screen)
        GridConfig grid = state.getGridConfig();
        if (state.getExportConfig().includeGrid) {
            drawGrid(canvas, cropW, cropH, grid);
        }

        String format = state.getExportConfig().format;
        if ("jpeg".equals(format)) {
            return exportJpeg(state, outBmp, cropW, cropH, cacheDir);
        } else {
            return exportPng(outBmp);
        }
    }

    private static ExportResult exportJpeg(CropState state, Bitmap bmp, int cropW, int cropH,
                                               java.io.File cacheDir) throws IOException {
        int quality = state.getExportConfig().jpegQuality;

        // Generate thumbnail from the rendered bitmap.
        // When preserving EXIF orientation (no user rotation), un-rotate the thumbnail
        // to match sensor orientation (EXIF viewer will re-rotate it for display).
        byte[] originalBytes0 = state.getOriginalFileBytes();
        int exifOr = (originalBytes0 != null) ? com.cropcenter.util.BitmapUtils.readExifOrientation(originalBytes0) : 1;
        Bitmap thumbSrc = bmp;
        if (state.getRotationDegrees() == 0f && exifOr > 1) {
            // Un-rotate to sensor orientation using the INVERSE of the EXIF orientation.
            // The bmp is in display (rotated) space; we need sensor (un-rotated) space.
            int inverseOr;
            switch (exifOr) {
                case 2: inverseOr = 2; break; // flip H is self-inverse
                case 3: inverseOr = 3; break; // 180° is self-inverse
                case 4: inverseOr = 4; break; // flip V is self-inverse
                case 5: inverseOr = 5; break; // transpose is self-inverse
                case 6: inverseOr = 8; break; // 90° CW → 90° CCW
                case 7: inverseOr = 7; break; // transverse is self-inverse
                case 8: inverseOr = 6; break; // 90° CCW → 90° CW
                default: inverseOr = 1; break;
            }
            if (inverseOr > 1) {
                thumbSrc = BitmapUtils.applyOrientation(bmp, inverseOr);
            }
        }
        byte[] thumbnail = generateThumbnail(thumbSrc, 512);
        if (thumbSrc != bmp) thumbSrc.recycle();

        // On API 34+, try to attach gainmap to our rendered bitmap for HDR output.
        // This works for ANY edit (rotation, grid, crop) — the platform regenerates
        // the gain map to match the new pixel content.
        byte[] originalBytes = state.getOriginalFileBytes();
        if (state.getGainMap() != null && originalBytes != null && UltraHdrCompat.isSupported()) {
            float cx = state.hasCenter() ? state.getCenterX() : state.getImageWidth() / 2f;
            float cy = state.hasCenter() ? state.getCenterY() : state.getImageHeight() / 2f;
            int exifOrient = com.cropcenter.util.BitmapUtils.readExifOrientation(originalBytes);
            byte[] hdrResult = UltraHdrCompat.compressWithGainmap(
                    originalBytes, quality, cacheDir,
                    state.getImageWidth(), state.getImageHeight(),
                    cx, cy, cropW, cropH,
                    state.getRotationDegrees(), exifOrient);
            if (hdrResult != null) {
                bmp.recycle();

                // Inject original EXIF (patched) to preserve camera metadata.
                // This shifts byte offsets, so we must fix MPF afterward.
                List<JpegSegment> meta = state.getJpegMeta();
                if (meta != null && !meta.isEmpty()) {
                    try {
                        List<JpegSegment> exifOnly = new java.util.ArrayList<>();
                        // No user rotation: pixels in sensor space, preserve original orientation
                        // User rotation: pixels in display space, orientation = 1
                        int outOrient = (state.getRotationDegrees() == 0f) ? exifOrient : 1;
                        // EXIF dimensions must match the actual pixel dimensions of the output.
                        // When preserving sensor orientation, swap if orientation swaps W/H.
                        int exifW = cropW, exifH = cropH;
                        if (outOrient >= 5 && outOrient <= 8) {
                            exifW = cropH; exifH = cropW;
                        }
                        for (JpegSegment seg : ExifPatcher.patch(meta, exifW, exifH, thumbnail, outOrient)) {
                            if (seg.isExif()) exifOnly.add(seg);
                        }
                        if (!exifOnly.isEmpty()) {
                            int primaryEnd = findPrimaryEnd(hdrResult);
                            if (primaryEnd <= 0) throw new IOException("Cannot find primary EOI");
                            hdrResult = replaceExif(hdrResult, exifOnly);
                            // After EXIF replacement, primary size changed.
                            // Fix MPF offsets to point to the gain map at its new position.
                            int newPrimaryEnd = findPrimaryEnd(hdrResult);
                            if (newPrimaryEnd > 0 && newPrimaryEnd < hdrResult.length) {
                                MpfPatcher.patch(hdrResult, newPrimaryEnd);
                                Log.d(TAG, "EXIF injected + MPF fixed, primary " + primaryEnd + " → " + newPrimaryEnd);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "EXIF injection into HDR failed", e);
                    }
                }

                hdrResult = appendSeftForState(hdrResult, state, cropW, cropH);
                Log.d(TAG, "HDR export: " + hdrResult.length + " bytes");
                return new ExportResult(hdrResult, "jpg");
            }
            Log.d(TAG, "HDR attach failed, falling back to non-HDR");
        }

        // Non-HDR pipeline
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        byte[] jpegBytes = bos.toByteArray();
        bmp.recycle();

        // Inject original metadata
        List<JpegSegment> meta = state.getJpegMeta();
        if (meta != null && !meta.isEmpty()) {
            // Non-HDR path also produces rotated pixels, so orientation = 1
            List<JpegSegment> patched = ExifPatcher.patch(meta, cropW, cropH, thumbnail, 1);
            jpegBytes = JpegMetadataInjector.inject(jpegBytes, patched);
        }

        // Append HDR gain map bytes as fallback (may not be recognized as valid HDR
        // since primary was re-encoded, but preserves the data)
        byte[] gainMap = state.getGainMap();
        if (gainMap != null && gainMap.length > 0) {
            Log.d(TAG, "Appending gain map bytes: " + gainMap.length + " (fallback)");
            jpegBytes = GainMapComposer.compose(jpegBytes, gainMap);
        }

        // Append Samsung SEFT trailer
        jpegBytes = appendSeftForState(jpegBytes, state, cropW, cropH);

        return new ExportResult(jpegBytes, "jpg");
    }

    /**
     * Replace EXIF in a JPEG: strip any existing EXIF APP1, insert ours after SOI.
     * Preserves all non-EXIF APP segments (XMP, MPF, ICC) from the platform output.
     */
    private static byte[] replaceExif(byte[] jpeg, List<JpegSegment> exifSegments) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + 65536);
        // SOI
        out.write(0xFF);
        out.write(0xD8);
        // Our EXIF segments
        for (JpegSegment seg : exifSegments) {
            out.write(seg.data);
        }
        // Copy all non-EXIF segments from the platform output
        int off = 2;
        while (off < jpeg.length - 3) {
            if ((jpeg[off] & 0xFF) != 0xFF) break;
            int m = jpeg[off + 1] & 0xFF;
            if (m == 0xDA || m == 0xD9) break; // SOS or EOI — copy rest as-is
            if (m == 0x00 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { off += 2; continue; }
            int segLen = ((jpeg[off+2] & 0xFF) << 8) | (jpeg[off+3] & 0xFF);
            if (segLen < 2 || off + 2 + segLen > jpeg.length) break;
            int total = 2 + segLen;
            // Skip EXIF APP1 (we replaced it with ours)
            boolean isExif = m == 0xE1 && total > 10
                    && jpeg[off+4] == 'E' && jpeg[off+5] == 'x'
                    && jpeg[off+6] == 'i' && jpeg[off+7] == 'f';
            if (!isExif) {
                out.write(jpeg, off, total);
            }
            off += total;
        }
        // Copy remaining image data (DQT, SOF, SOS, entropy, EOI, gain map)
        if (off < jpeg.length) {
            out.write(jpeg, off, jpeg.length - off);
        }
        return out.toByteArray();
    }

    /**
     * Generate a small JPEG thumbnail from a bitmap.
     * @param maxDim  maximum width or height of the thumbnail
     */
    /**
     * Generate a JPEG thumbnail that fits within maxBytes.
     */
    /**
     * Find the end of the primary JPEG (position after first EOI).
     * Used to determine where the gain map starts.
     */
    private static int findPrimaryEnd(byte[] jpeg) {
        // Walk JPEG markers to find the primary's EOI
        int off = 2; // skip SOI
        while (off < jpeg.length - 1) {
            if ((jpeg[off] & 0xFF) != 0xFF) { off++; continue; }
            int m = jpeg[off + 1] & 0xFF;
            if (m == 0xD9) return off + 2; // EOI found
            if (m == 0xDA) {
                // SOS — scan entropy data for EOI
                if (off + 3 >= jpeg.length) break;
                int sosLen = ((jpeg[off+2] & 0xFF) << 8) | (jpeg[off+3] & 0xFF);
                off += 2 + sosLen;
                while (off < jpeg.length - 1) {
                    if ((jpeg[off] & 0xFF) != 0xFF) { off++; continue; }
                    int next = jpeg[off + 1] & 0xFF;
                    if (next == 0xD9) return off + 2;
                    if (next == 0x00) { off += 2; continue; }
                    if (next >= 0xD0 && next <= 0xD7) { off += 2; continue; }
                    break;
                }
                continue;
            }
            if (m == 0x00 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { off += 2; continue; }
            if (off + 3 < jpeg.length) {
                int segLen = ((jpeg[off+2] & 0xFF) << 8) | (jpeg[off+3] & 0xFF);
                off += 2 + segLen;
            } else {
                off += 2;
            }
        }
        return -1; // not found
    }

    private static byte[] generateThumbnail(Bitmap bmp, int maxDim) {
        return generateThumbnail(bmp, maxDim, 40000); // ~40KB max to leave room in 64KB EXIF
    }

    private static byte[] generateThumbnail(Bitmap bmp, int maxDim, int maxBytes) {
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();

            // Scale to target size once, then try compressing at decreasing quality
            float scale = Math.min((float) maxDim / w, (float) maxDim / h);
            scale = Math.min(scale, 1f); // don't upscale
            int tw = Math.max(1, Math.round(w * scale));
            int th = Math.max(1, Math.round(h * scale));
            Bitmap thumb = Bitmap.createScaledBitmap(bmp, tw, th, true);

            for (int quality = 80; quality >= 40; quality -= 10) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                thumb.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                byte[] result = bos.toByteArray();
                if (result.length <= maxBytes) { if (thumb != bmp) thumb.recycle(); return result; }
            }
            // Still too large — reduce dimensions and try once more
            Bitmap smaller = Bitmap.createScaledBitmap(bmp, tw / 2, th / 2, true);
            if (thumb != bmp) thumb.recycle();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            smaller.compress(Bitmap.CompressFormat.JPEG, 60, bos);
            smaller.recycle();
            byte[] result = bos.toByteArray();
            if (result.length <= maxBytes) return result;
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Thumbnail generation failed", e);
            return null;
        }
    }

    /**
     * Save the original file to shared storage for Samsung Gallery Revert.
     * Uses /storage/emulated/0/.cropcenter/ which is readable by Gallery.
     * Must be called before overwriting the original file.
     */
    public static void saveOriginalBackup(CropState state) {
        if (state.getOriginalFilePath() == null || state.getMediaStoreId() < 0) return;
        byte[] origBytes = state.getOriginalFileBytes();
        if (origBytes == null) return;

        String backupPath = SeftBuilder.generateBackupPath(
                state.getOriginalFilePath(), state.getMediaStoreId());
        java.io.File backupFile = new java.io.File(backupPath);

        // Don't overwrite an existing backup (might be from a previous edit)
        if (backupFile.exists()) {
            Log.d(TAG, "Backup already exists: " + backupPath);
            return;
        }

        try {
            java.io.File dir = backupFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(backupFile)) {
                fos.write(origBytes);
            }
            Log.d(TAG, "Original backed up to: " + backupPath + " (" + origBytes.length + " bytes)");
        } catch (Exception e) {
            Log.w(TAG, "Cannot save backup to " + backupPath + ": " + e.getMessage());
        }
    }

    /** Compute SEFT params from state and delegate to appendSeft. */
    private static byte[] appendSeftForState(byte[] jpeg, CropState state, int cropW, int cropH) {
        int imgW = state.getImageWidth();
        int imgH = state.getImageHeight();
        boolean isCropped = state.hasCenter() && (cropW != imgW || cropH != imgH);

        // Normalized crop params (0-1)
        float cx, cy, cw, ch;
        if (state.hasCenter()) {
            cx = state.getCenterX() / imgW;
            cy = state.getCenterY() / imgH;
            cw = (float) cropW / imgW;
            ch = (float) cropH / imgH;
        } else {
            cx = 0.5f; cy = 0.5f; cw = 1f; ch = 1f;
        }

        // Generate backup path if we have the original file path
        String backupPath = null;
        if (state.getOriginalFilePath() != null && state.getMediaStoreId() >= 0) {
            backupPath = SeftBuilder.generateBackupPath(
                    state.getOriginalFilePath(), state.getMediaStoreId());
        }

        int exifOrient = 1;
        byte[] origBytes = state.getOriginalFileBytes();
        if (origBytes != null) {
            exifOrient = com.cropcenter.util.BitmapUtils.readExifOrientation(origBytes);
        }

        return appendSeft(jpeg, state.getSeftTrailer(), backupPath,
                cx, cy, cw, ch, isCropped, exifOrient);
    }

    /**
     * Append Samsung SEFT trailer.
     * - If existing SEFT: re-append it verbatim (preserves Gallery's Revert data)
     * - If no existing SEFT but have backup info: generate new SEFT for Revert
     * - Otherwise: no SEFT appended
     */
    private static byte[] appendSeft(byte[] jpeg, byte[] existingSeft,
                                      String backupPath, float cx, float cy,
                                      float cw, float ch, boolean isCropped,
                                      int exifRotation) {
        byte[] seft;
        if (existingSeft != null && existingSeft.length > 0) {
            // Re-append existing SEFT verbatim — preserves Gallery's re-edit data and backup path
            seft = existingSeft;
            Log.d(TAG, "Preserving existing SEFT trailer: " + seft.length + " bytes");
        } else if (backupPath != null) {
            // No existing SEFT — generate new one for Galaxy Revert
            seft = SeftBuilder.build(backupPath, cx, cy, cw, ch,
                    isCropped, exifRotation, System.currentTimeMillis(), null);
            if (seft == null) return jpeg;
            Log.d(TAG, "Generated new SEFT trailer: " + seft.length + " bytes");
        } else {
            return jpeg;
        }

        byte[] result = new byte[jpeg.length + seft.length];
        System.arraycopy(jpeg, 0, result, 0, jpeg.length);
        System.arraycopy(seft, 0, result, jpeg.length, seft.length);
        return result;
    }

    private static ExportResult exportPng(Bitmap bmp) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        bmp.recycle();
        return new ExportResult(bos.toByteArray(), "png");
    }

    private static void drawGrid(Canvas canvas, int w, int h, GridConfig grid) {
        Paint gp = new Paint();
        gp.setColor(grid.color);
        gp.setStrokeWidth(grid.lineWidth);
        gp.setStyle(Paint.Style.STROKE);

        for (int i = 1; i < grid.columns; i++) {
            int x = Math.round(w * i / (float) grid.columns);
            canvas.drawLine(x, 0, x, h, gp);
        }
        for (int i = 1; i < grid.rows; i++) {
            int y = Math.round(h * i / (float) grid.rows);
            canvas.drawLine(0, y, w, y, gp);
        }
    }
}
