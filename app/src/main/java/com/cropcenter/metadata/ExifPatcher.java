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

    /**
     * Estimate max thumbnail bytes that will fit in the EXIF APP1 segment.
     * Measures the EXIF size excluding the existing thumbnail, then returns
     * the remaining space within the 65535-byte APP1 limit.
     */
    public static int maxThumbnailBytes(List<JpegSegment> segments) {
        for (JpegSegment seg : segments) {
            if (!seg.isExif()) continue;
            byte[] data = seg.data;
            int T = 10;
            if (T + 8 > data.length) continue;
            boolean le = data[T] == 0x49;

            // Find IFD1 to locate existing thumbnail
            long ifd0Rel = ByteBufferUtils.readU32(data, T + 4, le);
            int ifd0 = (int)(T + ifd0Rel);
            if (ifd0 < T || ifd0 + 2 > data.length) return 20000; // can't parse, use default
            int cnt0 = ByteBufferUtils.readU16(data, ifd0, le);
            int nextPtr = ifd0 + 2 + cnt0 * 12;
            if (nextPtr + 4 > data.length) return 20000;
            long ifd1Rel = ByteBufferUtils.readU32(data, nextPtr, le);

            if (ifd1Rel == 0) {
                // No IFD1: EXIF overhead = current segment + new IFD1 header (~42 bytes)
                return 65535 - (data.length + 42);
            }

            int ifd1 = (int)(T + ifd1Rel);
            if (ifd1 < T || ifd1 + 2 > data.length) return 20000;
            int cnt1 = ByteBufferUtils.readU16(data, ifd1, le);
            int oldThumbLen = 0;
            for (int i = 0; i < cnt1; i++) {
                int e = ifd1 + 2 + i * 12;
                if (e + 12 > data.length) break;
                int tag = ByteBufferUtils.readU16(data, e, le);
                if (tag == 0x0202) { // JPEGInterchangeFormatLength
                    oldThumbLen = (int) ByteBufferUtils.readU32(data, e + 8, le);
                    break;
                }
            }
            // Available = 65535 - (current segment size - old thumbnail size)
            int exifOverhead = data.length - oldThumbLen;
            return Math.max(0, 65535 - exifOverhead);
        }
        return 20000; // no EXIF segment found, use default
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
                case 0x0112 -> ByteBufferUtils.writeU16(data, e + 8, orientation, le);
                case 0x0100 -> writeValue(data, e + 8, type, le, newW);
                case 0x0101 -> writeValue(data, e + 8, type, le, newH);
                case 0xA002 -> writeValue(data, e + 8, type, le, newW);
                case 0xA003 -> writeValue(data, e + 8, type, le, newH);
                case 0x8769 -> {
                    long off = ByteBufferUtils.readU32(data, e + 8, le);
                    int subIFD = (int)(T + off);
                    if (subIFD > 0 && subIFD < data.length) scanIFD(data, subIFD, T, le, newW, newH, orientation);
                }
                default -> {}
            }
        }

        // Don't follow the next-IFD link (IFD0 → IFD1).
        // IFD1 is the thumbnail IFD — its dimension/orientation tags describe the thumbnail,
        // not the primary. replaceThumbnail() handles the thumbnail data separately.
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
            if (ifd1Rel == 0) {
                // No IFD1 — append thumbnail at end of EXIF data.
                // Create minimal IFD1 with JPEGInterchangeFormat and JPEGInterchangeFormatLength.
                if (newThumb == null || newThumb.length == 0) return data;
                try {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    out.write(data);

                    // IFD1 will be at current end of TIFF data
                    int ifd1Off = data.length - T; // offset relative to TIFF header
                    // Write IFD1 pointer at end of IFD0
                    byte[] updated = out.toByteArray();
                    ByteBufferUtils.writeU32(updated, nextPtr, ifd1Off, le);

                    out.reset();
                    out.write(updated);

                    // IFD1: count=3 entries (Compression, JPEGInterchangeFormat, JPEGInterchangeFormatLength)
                    byte[] ifd1Buf = new byte[2 + 3 * 12 + 4]; // count + 3 entries + next IFD
                    ByteBufferUtils.writeU16(ifd1Buf, 0, 3, le);
                    // Tag 0x0103: Compression = 6 (JPEG)
                    ByteBufferUtils.writeU16(ifd1Buf, 2, 0x0103, le);
                    ByteBufferUtils.writeU16(ifd1Buf, 4, 3, le); // SHORT
                    ByteBufferUtils.writeU32(ifd1Buf, 6, 1, le);
                    ByteBufferUtils.writeU16(ifd1Buf, 10, 6, le); // JPEG compression
                    // Tag 0x0201: JPEGInterchangeFormat
                    int thumbDataOff = ifd1Off + ifd1Buf.length; // thumb starts right after IFD1
                    ByteBufferUtils.writeU16(ifd1Buf, 14, 0x0201, le);
                    ByteBufferUtils.writeU16(ifd1Buf, 16, 4, le); // LONG
                    ByteBufferUtils.writeU32(ifd1Buf, 18, 1, le);
                    ByteBufferUtils.writeU32(ifd1Buf, 22, thumbDataOff, le);
                    // Tag 0x0202: JPEGInterchangeFormatLength
                    ByteBufferUtils.writeU16(ifd1Buf, 26, 0x0202, le);
                    ByteBufferUtils.writeU16(ifd1Buf, 28, 4, le); // LONG
                    ByteBufferUtils.writeU32(ifd1Buf, 30, 1, le);
                    ByteBufferUtils.writeU32(ifd1Buf, 34, newThumb.length, le);
                    // Next IFD = 0
                    ByteBufferUtils.writeU32(ifd1Buf, 38, 0, le);
                    out.write(ifd1Buf);
                    out.write(newThumb);

                    byte[] result = out.toByteArray();
                    // Update APP1 segment length
                    int newSegLen = result.length - 2;
                    if (newSegLen <= 65533) {
                        result[2] = (byte)((newSegLen >> 8) & 0xFF);
                        result[3] = (byte)(newSegLen & 0xFF);
                        Log.d(TAG, "Created IFD1 with thumbnail: " + newThumb.length + " bytes");
                        return result;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to create IFD1 for thumbnail", e);
                }
                return data;
            }
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
                // Thumbnail doesn't fit — report how many bytes are available
                // so the caller can regenerate at a smaller size.
                int overhead = newData.length - newThumb.length; // EXIF without thumbnail
                int available = 65535 - overhead;
                Log.w(TAG, "Thumbnail " + newThumb.length + " too large (avail=" + available
                        + "), caller should retry smaller");
                return data; // return unchanged — caller checks and retries
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
