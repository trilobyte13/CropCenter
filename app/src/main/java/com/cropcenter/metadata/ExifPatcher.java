package com.cropcenter.metadata;

import android.util.Log;
import com.cropcenter.util.ByteBufferUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Patches EXIF metadata segments in-place:
 *  - Sets Orientation tag to 1 (Normal)
 *  - Updates ImageWidth, ImageLength, PixelXDimension, PixelYDimension
 *  - Replaces thumbnail JPEG with new one if provided
 *  - Preserves all other tags and data verbatim
 */
public final class ExifPatcher {

    private static final String TAG = "ExifPatcher";

    private ExifPatcher() {}

    /**
     * Patch dimensions and orientation. Optionally replace thumbnail.
     * @param thumbnail    new JPEG thumbnail bytes, or null to keep original
     * @param orientation  EXIF orientation value to set (1=normal, or original value to preserve)
     */
    public static List<JpegSegment> patch(List<JpegSegment> segments, int newW, int newH,
                                           byte[] thumbnail, int orientation) {
        List<JpegSegment> result = new ArrayList<>(segments.size());
        for (JpegSegment seg : segments) {
            if (!seg.isExif()) {
                result.add(seg);
                continue;
            }
            byte[] data = seg.data.clone();
            int T = 10; // TIFF header offset
            if (T + 8 > data.length) { result.add(seg); continue; }
            boolean le = data[T] == 0x49;

            long ifdOffRel = ByteBufferUtils.readU32(data, T + 4, le);
            int ifdOff = (int)(T + ifdOffRel);
            if (ifdOff < T || ifdOff + 2 > data.length) {
                result.add(new JpegSegment(seg.marker, data));
                continue;
            }

            // Patch IFD tags
            scanIFD(data, ifdOff, T, le, newW, newH, orientation);

            // Replace thumbnail if provided
            if (thumbnail != null) {
                data = replaceThumbnail(data, T, le, thumbnail);
            }

            result.add(new JpegSegment(seg.marker, data));
        }
        return result;
    }

    /** Convenience: patch without thumbnail, orientation set to 1. */
    public static List<JpegSegment> patch(List<JpegSegment> segments, int newW, int newH) {
        return patch(segments, newW, newH, null, 1);
    }

    /** Convenience: patch with thumbnail, orientation set to 1. */
    public static List<JpegSegment> patch(List<JpegSegment> segments, int newW, int newH,
                                           byte[] thumbnail) {
        return patch(segments, newW, newH, thumbnail, 1);
    }

    private static void scanIFD(byte[] data, int ifdOff, int T, boolean le, int newW, int newH, int orientation) {
        if (ifdOff < 0 || ifdOff + 2 > data.length) return;
        int cnt = ByteBufferUtils.readU16(data, ifdOff, le);

        for (int i = 0; i < cnt; i++) {
            int e = ifdOff + 2 + i * 12;
            if (e + 12 > data.length) break;
            int tag = ByteBufferUtils.readU16(data, e, le);
            int type = ByteBufferUtils.readU16(data, e + 2, le);

            switch (tag) {
                case 0x0112: ByteBufferUtils.writeU16(data, e + 8, orientation, le); break;
                case 0x0100: writeValue(data, e + 8, type, le, newW); break;
                case 0x0101: writeValue(data, e + 8, type, le, newH); break;
                case 0xA002: writeValue(data, e + 8, type, le, newW); break;
                case 0xA003: writeValue(data, e + 8, type, le, newH); break;
                case 0x8769: {
                    long off = ByteBufferUtils.readU32(data, e + 8, le);
                    int subIFD = (int)(T + off);
                    if (subIFD > 0 && subIFD < data.length) scanIFD(data, subIFD, T, le, newW, newH, orientation);
                    break;
                }
            }
        }

        // Follow next-IFD link (IFD0 → IFD1)
        int nextPtr = ifdOff + 2 + cnt * 12;
        if (nextPtr + 4 <= data.length) {
            long next = ByteBufferUtils.readU32(data, nextPtr, le);
            if (next > 0 && T + next < data.length) scanIFD(data, (int)(T + next), T, le, newW, newH, orientation);
        }
    }

    /**
     * Replace the EXIF thumbnail JPEG. Rebuilds the APP1 segment with new thumbnail bytes.
     * Finds IFD1's JPEGInterchangeFormat/Length, replaces the old thumbnail data,
     * and updates the segment length and tag values.
     */
    private static byte[] replaceThumbnail(byte[] data, int T, boolean le, byte[] newThumb) {
        try {
            // Find IFD0
            long ifd0Rel = ByteBufferUtils.readU32(data, T + 4, le);
            int ifd0 = (int)(T + ifd0Rel);
            if (ifd0 < T || ifd0 + 2 > data.length) return data;
            int cnt0 = ByteBufferUtils.readU16(data, ifd0, le);

            // Find IFD1 (next IFD pointer after IFD0)
            int nextPtr = ifd0 + 2 + cnt0 * 12;
            if (nextPtr + 4 > data.length) return data;
            long ifd1Rel = ByteBufferUtils.readU32(data, nextPtr, le);
            if (ifd1Rel == 0) return data; // no IFD1
            int ifd1 = (int)(T + ifd1Rel);
            if (ifd1 < T || ifd1 + 2 > data.length) return data;

            int cnt1 = ByteBufferUtils.readU16(data, ifd1, le);
            int thumbOffTag = -1, thumbLenTag = -1;
            int oldThumbOff = 0, oldThumbLen = 0;

            for (int i = 0; i < cnt1; i++) {
                int e = ifd1 + 2 + i * 12;
                if (e + 12 > data.length) break;
                int tag = ByteBufferUtils.readU16(data, e, le);
                if (tag == 0x0201) { // JPEGInterchangeFormat
                    thumbOffTag = e;
                    oldThumbOff = (int) ByteBufferUtils.readU32(data, e + 8, le);
                } else if (tag == 0x0202) { // JPEGInterchangeFormatLength
                    thumbLenTag = e;
                    oldThumbLen = (int) ByteBufferUtils.readU32(data, e + 8, le);
                }
            }

            if (thumbOffTag < 0 || thumbLenTag < 0 || oldThumbOff == 0) return data;

            // Absolute offset of old thumbnail in the segment data
            int absOldOff = T + oldThumbOff;
            if (absOldOff < 0 || absOldOff + oldThumbLen > data.length) return data;

            // Build new segment: [before thumbnail] [new thumbnail] [after thumbnail]
            // The thumbnail is always at the end of the EXIF data, so "after" is usually empty
            byte[] before = new byte[absOldOff];
            System.arraycopy(data, 0, before, 0, absOldOff);

            int afterStart = absOldOff + oldThumbLen;
            int afterLen = data.length - afterStart;
            byte[] after = (afterLen > 0) ? new byte[afterLen] : new byte[0];
            if (afterLen > 0) System.arraycopy(data, afterStart, after, 0, afterLen);

            // Combine
            byte[] newData = new byte[before.length + newThumb.length + after.length];
            System.arraycopy(before, 0, newData, 0, before.length);
            System.arraycopy(newThumb, 0, newData, before.length, newThumb.length);
            if (afterLen > 0) System.arraycopy(after, 0, newData, before.length + newThumb.length, afterLen);

            // Check APP1 size limit (65535 bytes max for segment length field)
            int newSegLen = newData.length - 2;
            if (newSegLen > 65533) {
                // Thumbnail still too large even after CropExporter's reduction loop.
                // Truncate thumbnail to fit.
                int maxThumbSize = newThumb.length - (newSegLen - 65533);
                if (maxThumbSize < 100) {
                    Log.w(TAG, "Cannot fit any thumbnail in EXIF, removing it");
                    // Remove thumbnail entirely by setting length to 0
                    ByteBufferUtils.writeU32(data, thumbLenTag + 8, 0, le);
                    return data;
                }
                // Rebuild with truncated thumbnail (it'll be invalid JPEG but won't corrupt the file)
                byte[] trimmedThumb = new byte[maxThumbSize];
                System.arraycopy(newThumb, 0, trimmedThumb, 0, maxThumbSize);
                return replaceThumbnail(data, T, le, trimmedThumb);
            }

            // Update thumbnail length to new size
            ByteBufferUtils.writeU32(newData, thumbLenTag + 8, newThumb.length, le);

            // Update APP1 segment length (bytes 2-3)
            newData[2] = (byte)((newSegLen >> 8) & 0xFF);
            newData[3] = (byte)(newSegLen & 0xFF);

            Log.d(TAG, "Thumbnail replaced: " + oldThumbLen + " → " + newThumb.length
                    + " bytes (APP1=" + newSegLen + ")");
            return newData;

        } catch (Exception e) {
            Log.w(TAG, "Thumbnail replacement failed", e);
            return data;
        }
    }

    private static void writeValue(byte[] data, int off, int type, boolean le, int value) {
        if (type == 3) ByteBufferUtils.writeU16(data, off, value, le);
        else ByteBufferUtils.writeU32(data, off, value, le);
    }
}
