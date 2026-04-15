package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Ultra HDR support using Android 14+ Gainmap API.
 *
 * Strategy: decode original with gainmap, render onto a cropW×cropH canvas using
 * the exact same rotation/positioning as CropExporter (Canvas.rotate around image
 * center). Apply the identical transform to the gainmap bitmap so gain map and
 * primary are spatially aligned. Compress → Ultra HDR JPEG.
 */
public final class UltraHdrCompat {

    private static final String TAG = "UltraHdrCompat";

    private UltraHdrCompat() {}

    public static boolean isSupported() {
        return true; // minSdk 35, Gainmap API always available
    }

    /**
     * Produce an Ultra HDR JPEG using the same canvas rendering as CropExporter.
     * The gain map undergoes the identical spatial transform as the primary,
     * guaranteeing alignment regardless of rotation or crop position.
     *
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

            // Ensure display orientation
            boolean autoRotated = (current.getWidth() == imgW && current.getHeight() == imgH);
            if (!autoRotated && exifOrientation > 1) {
                Matrix m = BitmapUtils.orientationMatrix(exifOrientation);
                Bitmap rotated = Bitmap.createBitmap(current, 0, 0,
                        current.getWidth(), current.getHeight(), m, true);
                if (rotated != current) { current.recycle(); current = rotated; }
                Log.d(TAG, "EXIF rotated: " + current.getWidth() + "x" + current.getHeight()
                        + " hasGm=" + current.hasGainmap());
            }

            // Capture gainmap info before any destructive operations
            Gainmap srcGm = current.hasGainmap() ? current.getGainmap() : null;
            Bitmap gmBmp = srcGm != null ? srcGm.getGainmapContents() : null;

            // Crop origin — matches CropExporter.export() exactly
            float sx = centerX - cropW / 2f;
            float sy = centerY - cropH / 2f;

            // ── Render primary onto cropW×cropH canvas ──
            // Uses shared BitmapUtils.drawCropped — identical to CropExporter rendering.
            Bitmap output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888, true,
                    ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
            Canvas canvas = new Canvas(output);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            BitmapUtils.drawCropped(canvas, current, sx, sy, userRotation, paint);

            // ── Apply identical transform to gainmap ──
            if (srcGm != null && gmBmp != null) {
                float gmScaleX = (float) gmBmp.getWidth() / current.getWidth();
                float gmScaleY = (float) gmBmp.getHeight() / current.getHeight();
                int gmOutW = Math.max(1, Math.round(cropW * gmScaleX));
                int gmOutH = Math.max(1, Math.round(cropH * gmScaleY));

                Bitmap.Config gmConfig = gmBmp.getConfig() != null
                        ? gmBmp.getConfig() : Bitmap.Config.ARGB_8888;
                Bitmap gmOutput = Bitmap.createBitmap(gmOutW, gmOutH, gmConfig);
                Canvas gmCanvas = new Canvas(gmOutput);
                Paint gmPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

                float gmDrawX = -sx * gmScaleX;
                float gmDrawY = -sy * gmScaleY;

                if (userRotation != 0f) {
                    gmCanvas.save();
                    gmCanvas.rotate(userRotation,
                            gmDrawX + gmBmp.getWidth() / 2f,
                            gmDrawY + gmBmp.getHeight() / 2f);
                    gmCanvas.drawBitmap(gmBmp, gmDrawX, gmDrawY, gmPaint);
                    gmCanvas.restore();
                } else {
                    gmCanvas.drawBitmap(gmBmp, gmDrawX, gmDrawY, gmPaint);
                }

                Gainmap newGm = new Gainmap(gmOutput);
                newGm.setRatioMin(srcGm.getRatioMin()[0], srcGm.getRatioMin()[1], srcGm.getRatioMin()[2]);
                newGm.setRatioMax(srcGm.getRatioMax()[0], srcGm.getRatioMax()[1], srcGm.getRatioMax()[2]);
                newGm.setGamma(srcGm.getGamma()[0], srcGm.getGamma()[1], srcGm.getGamma()[2]);
                newGm.setEpsilonSdr(srcGm.getEpsilonSdr()[0], srcGm.getEpsilonSdr()[1], srcGm.getEpsilonSdr()[2]);
                newGm.setEpsilonHdr(srcGm.getEpsilonHdr()[0], srcGm.getEpsilonHdr()[1], srcGm.getEpsilonHdr()[2]);
                newGm.setDisplayRatioForFullHdr(srcGm.getDisplayRatioForFullHdr());
                newGm.setMinDisplayRatioForHdrTransition(srcGm.getMinDisplayRatioForHdrTransition());

                output.setGainmap(newGm);
                Log.d(TAG, "Gainmap rendered: " + gmOutW + "x" + gmOutH
                        + " (scale " + gmScaleX + "x" + gmScaleY + ")");
            }

            current.recycle(); current = null;

            // Compress → Ultra HDR JPEG
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            output.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            byte[] result = bos.toByteArray();
            output.recycle();

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
