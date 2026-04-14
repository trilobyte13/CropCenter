package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import android.graphics.Matrix;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Ultra HDR support using Android 14+ Gainmap API.
 *
 * Strategy: keep the decoded Bitmap in display orientation (BitmapFactory auto-rotates
 * on API 28+). Pure crops via createBitmap(src, x, y, w, h) preserve the gainmap.
 * Only user rotation requires a matrix transform that may lose the gainmap; in that
 * case we recover by cropping the original gainmap and re-attaching it.
 *
 * Output is always in display orientation (EXIF orientation = 1).
 */
public final class UltraHdrCompat {

    private static final String TAG = "UltraHdrCompat";

    private UltraHdrCompat() {}

    public static boolean isSupported() {
        return true; // minSdk 35, Gainmap API always available
    }

    /**
     * Produce an Ultra HDR JPEG by applying crop+rotation to the original image.
     * Output is always in display orientation (EXIF orientation = 1).
     *
     * @param originalBytes  raw JPEG bytes of the original HDR image
     * @param quality        JPEG quality (1-100)
     * @param cacheDir       temp directory for file-based decode
     * @param imgW, imgH     image dimensions in display orientation (after EXIF rotation)
     * @param centerX, centerY  crop center in display coordinates
     * @param cropW, cropH   crop dimensions
     * @param userRotation   user-applied rotation in degrees
     * @param exifOrientation EXIF orientation of the original file
     * @return Ultra HDR JPEG bytes, or null if failed
     */
    public static byte[] compressWithGainmap(byte[] originalBytes, int quality, File cacheDir,
                                              int imgW, int imgH,
                                              float centerX, float centerY,
                                              int cropW, int cropH,
                                              float userRotation, int exifOrientation) {
        Bitmap current = null;
        File tmp = null;
        try {
            // Decode original with gainmap
            tmp = new File(cacheDir, "hdr_src.jpg");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(originalBytes);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
            current = BitmapFactory.decodeFile(tmp.getAbsolutePath(), opts);
            tmp.delete(); tmp = null;

            if (current == null || !current.hasGainmap()) {
                Log.d(TAG, "No gainmap in source");
                return null;
            }

            Log.d(TAG, "Decoded: " + current.getWidth() + "x" + current.getHeight()
                    + " hasGm=" + current.hasGainmap()
                    + " expected=" + imgW + "x" + imgH
                    + " exif=" + exifOrientation);

            // Ensure bitmap is in display orientation.
            // BitmapFactory auto-rotates on API 28+; verify by comparing dimensions.
            boolean autoRotated = (current.getWidth() == imgW && current.getHeight() == imgH);

            if (!autoRotated && exifOrientation > 1) {
                // Not auto-rotated: rotate to display orientation
                Matrix m = exifMatrix(exifOrientation);
                Bitmap rotated = Bitmap.createBitmap(current, 0, 0,
                        current.getWidth(), current.getHeight(), m, true);
                if (rotated != current) { current.recycle(); current = rotated; }
                Log.d(TAG, "EXIF rotated to display: " + current.getWidth() + "x" + current.getHeight()
                        + " hasGm=" + current.hasGainmap());
            }
            // Now `current` is in display orientation (imgW x imgH)

            // Apply user rotation if any
            if (userRotation != 0f) {
                Matrix m = new Matrix();
                m.setRotate(userRotation, current.getWidth() / 2f, current.getHeight() / 2f);
                Bitmap rotated = Bitmap.createBitmap(current, 0, 0,
                        current.getWidth(), current.getHeight(), m, true);
                if (rotated != current) { current.recycle(); current = rotated; }
                Log.d(TAG, "User rotated: " + current.getWidth() + "x" + current.getHeight()
                        + " hasGm=" + current.hasGainmap());
            }

            // Compute crop region in the current bitmap's coordinate space
            int cx, cy;
            if (userRotation != 0f) {
                // After user rotation, bitmap may be larger. Map display coords to rotated space.
                cx = current.getWidth() / 2 + Math.round(centerX - imgW / 2f);
                cy = current.getHeight() / 2 + Math.round(centerY - imgH / 2f);
            } else {
                // In display orientation: use display coords directly
                cx = Math.round(centerX);
                cy = Math.round(centerY);
            }

            // Clamp crop to bitmap bounds
            cx = Math.max(cropW / 2, Math.min(current.getWidth() - cropW / 2, cx));
            cy = Math.max(cropH / 2, Math.min(current.getHeight() - cropH / 2, cy));
            int sx = Math.max(0, cx - cropW / 2);
            int sy = Math.max(0, cy - cropH / 2);
            int sw = Math.min(cropW, current.getWidth() - sx);
            int sh = Math.min(cropH, current.getHeight() - sy);

            if (sw > 0 && sh > 0 && (sx != 0 || sy != 0 || sw != current.getWidth() || sh != current.getHeight())) {
                Bitmap cropped = Bitmap.createBitmap(current, sx, sy, sw, sh);
                if (cropped != current) { current.recycle(); current = cropped; }
                Log.d(TAG, "After crop: " + current.getWidth() + "x" + current.getHeight()
                        + " hasGm=" + current.hasGainmap());
            }

            // Scale to exact output dimensions if needed
            if (current.getWidth() != cropW || current.getHeight() != cropH) {
                Bitmap scaled = Bitmap.createScaledBitmap(current, cropW, cropH, true);
                if (scaled != current) { current.recycle(); current = scaled; }
            }

            // Recovery: if gainmap was lost during matrix transforms (rotation)
            if (!current.hasGainmap()) {
                current = recoverGainmap(current, originalBytes, cacheDir,
                        centerX, centerY, cropW, cropH, imgW, imgH, userRotation);
                if (current == null || !current.hasGainmap()) {
                    Log.w(TAG, "Gainmap recovery failed — no HDR");
                    if (current != null) { current.recycle(); current = null; }
                    return null;
                }
            }

            // Compress — produces Ultra HDR JPEG
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            current.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            byte[] result = bos.toByteArray();
            current.recycle(); current = null;

            if (containsHdrgm(result)) {
                Log.d(TAG, "Ultra HDR: " + result.length + " bytes");
                return result;
            }
            Log.w(TAG, "No hdrgm in compress output");
            return null;

        } catch (Exception e) {
            Log.e(TAG, "compressWithGainmap: " + e.getMessage(), e);
            return null;
        } finally {
            if (current != null) current.recycle();
            if (tmp != null) tmp.delete();
        }
    }

    /**
     * Recover a lost gainmap by re-decoding the original image, cropping the
     * gainmap bitmap to match the output region, and re-attaching it.
     */
    private static Bitmap recoverGainmap(Bitmap current, byte[] originalBytes, File cacheDir,
                                          float centerX, float centerY,
                                          int cropW, int cropH,
                                          int imgW, int imgH, float userRotation) {
        Log.w(TAG, "Gainmap lost during transforms, recovering...");
        Bitmap src2 = null;
        File tmp2 = new File(cacheDir, "hdr_gm_recover.jpg");
        try {
            try (FileOutputStream fos2 = new FileOutputStream(tmp2)) { fos2.write(originalBytes); }
            BitmapFactory.Options opts2 = new BitmapFactory.Options();
            opts2.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
            src2 = BitmapFactory.decodeFile(tmp2.getAbsolutePath(), opts2);
            tmp2.delete();
            if (src2 == null || !src2.hasGainmap()) return current;

            Gainmap srcGm = src2.getGainmap();
            Bitmap gmBmp = srcGm.getGainmapContents();

            // Map the display-space crop region to gainmap coordinates
            float gmScaleX = (float) gmBmp.getWidth() / imgW;
            float gmScaleY = (float) gmBmp.getHeight() / imgH;

            Bitmap croppedGm;
            if (userRotation != 0f) {
                // Rotation makes precise crop mapping complex — scale full gainmap
                croppedGm = Bitmap.createScaledBitmap(gmBmp, current.getWidth(), current.getHeight(), true);
            } else {
                // Pure crop: extract corresponding region from gainmap
                int gmSx = Math.max(0, Math.round((centerX - cropW / 2f) * gmScaleX));
                int gmSy = Math.max(0, Math.round((centerY - cropH / 2f) * gmScaleY));
                int gmSw = Math.round(cropW * gmScaleX);
                int gmSh = Math.round(cropH * gmScaleY);
                gmSw = Math.min(gmSw, gmBmp.getWidth() - gmSx);
                gmSh = Math.min(gmSh, gmBmp.getHeight() - gmSy);
                if (gmSw <= 0 || gmSh <= 0) return current;
                Bitmap subGm = Bitmap.createBitmap(gmBmp, gmSx, gmSy, gmSw, gmSh);
                croppedGm = Bitmap.createScaledBitmap(subGm, current.getWidth(), current.getHeight(), true);
                if (subGm != croppedGm) subGm.recycle();
            }

            Gainmap newGm = new Gainmap(croppedGm);
            newGm.setRatioMin(srcGm.getRatioMin()[0], srcGm.getRatioMin()[1], srcGm.getRatioMin()[2]);
            newGm.setRatioMax(srcGm.getRatioMax()[0], srcGm.getRatioMax()[1], srcGm.getRatioMax()[2]);
            newGm.setGamma(srcGm.getGamma()[0], srcGm.getGamma()[1], srcGm.getGamma()[2]);
            newGm.setEpsilonSdr(srcGm.getEpsilonSdr()[0], srcGm.getEpsilonSdr()[1], srcGm.getEpsilonSdr()[2]);
            newGm.setEpsilonHdr(srcGm.getEpsilonHdr()[0], srcGm.getEpsilonHdr()[1], srcGm.getEpsilonHdr()[2]);
            newGm.setDisplayRatioForFullHdr(srcGm.getDisplayRatioForFullHdr());
            newGm.setMinDisplayRatioForHdrTransition(srcGm.getMinDisplayRatioForHdrTransition());

            // Set gainmap on mutable copy; only recycle current after success
            Bitmap mutable = current.copy(Bitmap.Config.ARGB_8888, true);
            mutable.setGainmap(newGm);
            current.recycle();
            Log.d(TAG, "Gainmap recovered and re-attached");
            return mutable;

        } catch (Exception e) {
            Log.w(TAG, "Gainmap recovery failed", e);
            return current;
        } finally {
            if (src2 != null) src2.recycle();
            tmp2.delete();
        }
    }

    private static Matrix exifMatrix(int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1, -1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(-90); m.postScale(-1, 1); break;
            case 8: m.setRotate(-90); break;
        }
        return m;
    }

    private static boolean containsHdrgm(byte[] data) {
        int limit = Math.min(data.length, 65536);
        for (int i = 0; i < limit - 5; i++) {
            if (data[i] == 'h' && data[i+1] == 'd' && data[i+2] == 'r'
                    && data[i+3] == 'g' && data[i+4] == 'm') {
                return true;
            }
        }
        return false;
    }
}
