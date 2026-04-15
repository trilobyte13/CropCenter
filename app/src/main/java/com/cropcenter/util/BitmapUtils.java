package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Reads EXIF orientation from raw JPEG bytes and rotates the bitmap accordingly.
 * BitmapFactory.decodeByteArray() does NOT auto-apply EXIF rotation.
 */
public final class BitmapUtils {

    private BitmapUtils() {}

    /**
     * Read EXIF orientation tag from raw JPEG bytes.
     * Returns orientation value (1-8), or 1 if not found.
     */
    public static int readExifOrientation(byte[] jpeg) {
        try {
            return readExifOrientationInternal(jpeg);
        } catch (Exception e) {
            return 1;
        }
    }

    private static int readExifOrientationInternal(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 14) return 1;
        if ((jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return 1;

        int off = 2;
        while (off < jpeg.length - 4) {
            if ((jpeg[off] & 0xFF) != 0xFF) return 1;
            int marker = jpeg[off + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) break; // SOS or EOI
            if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                off += 2; continue;
            }
            int segLen = ByteBufferUtils.readU16BE(jpeg, off + 2);

            // APP1 with Exif header
            if (marker == 0xE1 && segLen > 14
                    && jpeg[off+4] == 'E' && jpeg[off+5] == 'x'
                    && jpeg[off+6] == 'i' && jpeg[off+7] == 'f'
                    && jpeg[off+8] == 0 && jpeg[off+9] == 0) {

                int tiffStart = off + 10; // TIFF header
                if (tiffStart + 8 > jpeg.length) return 1;
                boolean le = jpeg[tiffStart] == 0x49; // 'I' = little-endian

                long ifdOff = ByteBufferUtils.readU32(jpeg, tiffStart + 4, le);
                int ifd = (int)(tiffStart + ifdOff);
                if (ifd < tiffStart || ifd + 2 > jpeg.length) return 1;

                int count = ByteBufferUtils.readU16(jpeg, ifd, le);
                for (int i = 0; i < count; i++) {
                    int entry = ifd + 2 + i * 12;
                    if (entry + 12 > jpeg.length) break;
                    int tag = ByteBufferUtils.readU16(jpeg, entry, le);
                    if (tag == 0x0112) { // Orientation
                        return ByteBufferUtils.readU16(jpeg, entry + 8, le);
                    }
                }
                return 1; // EXIF found but no orientation tag
            }
            off += 2 + segLen;
        }
        return 1;
    }

    /**
     * Draw a source bitmap onto a canvas representing the crop window, with optional rotation
     * around the image center. Used by both CropExporter and UltraHdrCompat to ensure
     * identical rendering.
     *
     * @param canvas   output canvas (cropW x cropH)
     * @param src      source bitmap in display orientation
     * @param sx       crop origin X = centerX - cropW/2
     * @param sy       crop origin Y = centerY - cropH/2
     * @param rotation rotation in degrees (0 = no rotation)
     * @param paint    paint for bitmap drawing
     */
    public static void drawCropped(Canvas canvas, Bitmap src, float sx, float sy,
                                    float rotation, Paint paint) {
        float drawX = -sx;
        float drawY = -sy;
        if (rotation != 0f) {
            canvas.save();
            canvas.rotate(rotation,
                    drawX + src.getWidth() / 2f,
                    drawY + src.getHeight() / 2f);
            canvas.drawBitmap(src, drawX, drawY, paint);
            canvas.restore();
        } else {
            int cropW = canvas.getWidth(), cropH = canvas.getHeight();
            int isx = (int) Math.floor(sx);
            int isy = (int) Math.floor(sy);
            int vx1 = Math.max(0, isx);
            int vy1 = Math.max(0, isy);
            int vx2 = Math.min(src.getWidth(), isx + cropW);
            int vy2 = Math.min(src.getHeight(), isy + cropH);
            if (vx2 > vx1 && vy2 > vy1) {
                Rect srcRect = new Rect(vx1, vy1, vx2, vy2);
                Rect dstRect = new Rect(vx1 - isx, vy1 - isy, vx2 - isx, vy2 - isy);
                canvas.drawBitmap(src, srcRect, dstRect, paint);
            }
        }
    }

    /** Build a Matrix for the given EXIF orientation value (1-8). */
    public static Matrix orientationMatrix(int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case 2 -> m.setScale(-1, 1);
            case 3 -> m.setRotate(180);
            case 4 -> m.setScale(1, -1);
            case 5 -> { m.setRotate(90); m.postScale(-1, 1); }
            case 6 -> m.setRotate(90);
            case 7 -> { m.setRotate(-90); m.postScale(-1, 1); }
            case 8 -> m.setRotate(-90);
        }
        return m;
    }

    /**
     * Apply EXIF orientation to a bitmap, returning a correctly rotated bitmap.
     * The input bitmap may be recycled if rotation was needed.
     */
    public static Bitmap applyOrientation(Bitmap bmp, int orientation) {
        if (orientation <= 1 || orientation > 8) return bmp;
        Matrix m = orientationMatrix(orientation);
        Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (rotated != bmp) bmp.recycle();
        return rotated;
    }
}
