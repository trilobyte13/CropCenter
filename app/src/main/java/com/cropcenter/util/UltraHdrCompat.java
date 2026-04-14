package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Ultra HDR support using Android 14+ Gainmap API.
 *
 * Strategy: apply ALL transforms (EXIF rotation, user rotation, crop) to the
 * decoded Bitmap that has the gainmap attached. The platform automatically
 * applies the same transforms to the gainmap when using createBitmap().
 * Then Bitmap.compress() produces a valid Ultra HDR JPEG.
 */
public final class UltraHdrCompat {

    private static final String TAG = "UltraHdrCompat";

    private UltraHdrCompat() {}

    public static boolean isSupported() {
        return true; // minSdk 35, Gainmap API always available
    }

    /**
     * Produce an Ultra HDR JPEG by applying crop+rotation to the original image
     * using platform Bitmap operations that preserve the gainmap.
     *
     * @param originalBytes  raw JPEG bytes of the original HDR image
     * @param quality        JPEG quality (1-100)
     * @param cacheDir       temp directory for file-based decode
     * @param imgW, imgH     expected image dimensions after EXIF rotation
     * @param centerX, centerY  crop center in image coords (post-EXIF-rotation space)
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

            // When no user rotation: keep pixels in sensor orientation (skip EXIF rotation).
            // This preserves the original EXIF orientation tag.
            // Crop coords need to be transformed from display space to sensor space.
            //
            // When user rotation is applied: apply EXIF rotation first (so user rotation
            // is relative to the visual orientation), then user rotation, then crop.
            // Output orientation will be 1 since the rotation changes the pixel orientation.

            boolean autoRotated = (current.getWidth() == imgW && current.getHeight() == imgH);

            if (userRotation != 0f) {
                // Apply EXIF rotation if needed
                if (!autoRotated && exifOrientation > 1) {
                    Matrix m = exifMatrix(exifOrientation);
                    Bitmap rotated = Bitmap.createBitmap(current, 0, 0,
                            current.getWidth(), current.getHeight(), m, true);
                    if (rotated != current) { current.recycle(); current = rotated; }
                    Log.d(TAG, "EXIF rotated: " + current.getWidth() + "x" + current.getHeight());
                }

                // Apply user rotation
                Matrix m = new Matrix();
                m.setRotate(userRotation, current.getWidth() / 2f, current.getHeight() / 2f);
                Bitmap rotated = Bitmap.createBitmap(current, 0, 0,
                        current.getWidth(), current.getHeight(), m, true);
                if (rotated != current) { current.recycle(); current = rotated; }
                Log.d(TAG, "User rotated: " + current.getWidth() + "x" + current.getHeight()
                        + " hasGm=" + current.hasGainmap());
            } else if (autoRotated && exifOrientation > 1) {
                // BitmapFactory auto-rotated. UN-rotate back to sensor orientation
                // so the original EXIF orientation tag remains valid.
                Matrix m = exifMatrix(inverseOrientation(exifOrientation));
                Bitmap unRotated = Bitmap.createBitmap(current, 0, 0,
                        current.getWidth(), current.getHeight(), m, true);
                if (unRotated != current) { current.recycle(); current = unRotated; }
                Log.d(TAG, "Un-rotated to sensor: " + current.getWidth() + "x" + current.getHeight()
                        + " hasGm=" + current.hasGainmap());
            }
            // If !autoRotated && no user rotation: already in sensor orientation, no transform needed.

            // Compute crop coordinates in the current bitmap's coordinate space
            int cx, cy;
            if (userRotation != 0f) {
                // After EXIF + user rotation, bitmap is larger. Map display coords to rotated space.
                cx = current.getWidth() / 2 + Math.round(centerX - imgW / 2f);
                cy = current.getHeight() / 2 + Math.round(centerY - imgH / 2f);
            } else {
                // In sensor space: transform display coords based on EXIF orientation
                int[] sensorCoords = displayToSensor(Math.round(centerX), Math.round(centerY),
                        imgW, imgH, exifOrientation);
                cx = sensorCoords[0];
                cy = sensorCoords[1];
            }

            // For sensor-space crop (no user rotation), swap crop dims if orientation swaps W/H
            int cw = cropW, ch = cropH;
            if (userRotation == 0f && (exifOrientation >= 5 && exifOrientation <= 8)) {
                cw = cropH; ch = cropW; // swap for sensor space
            }

            // Clamp crop center to valid bounds within the current bitmap
            cx = Math.max(cw / 2, Math.min(current.getWidth() - cw / 2, cx));
            cy = Math.max(ch / 2, Math.min(current.getHeight() - ch / 2, cy));
            int sx = Math.max(0, cx - cw / 2);
            int sy = Math.max(0, cy - ch / 2);
            int sw = Math.min(cw, current.getWidth() - sx);
            int sh = Math.min(ch, current.getHeight() - sy);

            if (sw > 0 && sh > 0 && (sx != 0 || sy != 0 || sw != current.getWidth() || sh != current.getHeight())) {
                Bitmap cropped = Bitmap.createBitmap(current, sx, sy, sw, sh);
                if (cropped != current) { current.recycle(); current = cropped; }
                Log.d(TAG, "After crop: " + current.getWidth() + "x" + current.getHeight()
                        + " hasGm=" + current.hasGainmap());
            }

            // Scale to exact output dimensions if needed
            // For sensor space output, target is cw×ch (possibly swapped)
            int targetW = (userRotation != 0f) ? cropW : cw;
            int targetH = (userRotation != 0f) ? cropH : ch;
            if (current.getWidth() != targetW || current.getHeight() != targetH) {
                Bitmap scaled = Bitmap.createScaledBitmap(current, targetW, targetH, true);
                if (scaled != current) { current.recycle(); current = scaled; }
            }

            if (!current.hasGainmap()) {
                Log.w(TAG, "Gainmap lost during transforms");
                return null;
            }

            // Step 4: Compress — produces Ultra HDR JPEG
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

    /** Get the inverse EXIF orientation (to undo an orientation transform). */
    private static int inverseOrientation(int o) {
        switch (o) {
            case 2: return 2; // horizontal flip is its own inverse
            case 3: return 3; // 180° is its own inverse
            case 4: return 4; // vertical flip is its own inverse
            case 5: return 5; // transpose is its own inverse
            case 6: return 8; // 90° CW → 90° CCW
            case 7: return 7; // transverse is its own inverse
            case 8: return 6; // 90° CCW → 90° CW
            default: return 1;
        }
    }

    /**
     * Transform display coordinates (post-EXIF-rotation) to sensor coordinates.
     * This reverses the EXIF orientation transform on a single point.
     */
    private static int[] displayToSensor(int dx, int dy, int dispW, int dispH, int orientation) {
        switch (orientation) {
            case 2: return new int[]{dispW - dx, dy};               // flip H
            case 3: return new int[]{dispW - dx, dispH - dy};       // 180°
            case 4: return new int[]{dx, dispH - dy};               // flip V
            case 5: return new int[]{dy, dx};                        // transpose
            case 6: return new int[]{dispH - dy, dx};                // 90° CW
            case 7: return new int[]{dispH - dy, dispW - dx};        // transverse
            case 8: return new int[]{dy, dispW - dx};                // 90° CCW
            default: return new int[]{dx, dy};                       // normal
        }
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
