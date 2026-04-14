package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.Matrix;

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
     * Apply EXIF orientation to a bitmap, returning a correctly rotated bitmap.
     * The input bitmap may be recycled if rotation was needed.
     */
    public static Bitmap applyOrientation(Bitmap bmp, int orientation) {
        if (orientation <= 1 || orientation > 8) return bmp;

        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1, 1); break;                          // flip horizontal
            case 3: m.setRotate(180); break;                            // rotate 180
            case 4: m.setScale(1, -1); break;                          // flip vertical
            case 5: m.setRotate(90); m.postScale(-1, 1); break;        // transpose
            case 6: m.setRotate(90); break;                             // rotate 90 CW
            case 7: m.setRotate(-90); m.postScale(-1, 1); break;       // transverse
            case 8: m.setRotate(-90); break;                            // rotate 90 CCW
        }

        Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (rotated != bmp) bmp.recycle();
        return rotated;
    }
}
