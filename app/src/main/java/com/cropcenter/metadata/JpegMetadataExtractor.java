package com.cropcenter.metadata;

import com.cropcenter.util.ByteBufferUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts all APP and COM marker segments from a JPEG file's header.
 * Stops at SOS (FF DA) or EOI (FF D9). Preserves raw segment bytes verbatim.
 */
public final class JpegMetadataExtractor {

    private JpegMetadataExtractor() {}

    public static List<JpegSegment> extract(byte[] jpeg) {
        List<JpegSegment> segments = new ArrayList<>();
        if (jpeg.length < 4 || jpeg[0] != (byte)0xFF || jpeg[1] != (byte)0xD8) {
            return segments;
        }

        int off = 2;
        while (off < jpeg.length - 3) {
            if ((jpeg[off] & 0xFF) != 0xFF) break;
            int m = jpeg[off + 1] & 0xFF;

            // Stop at SOS or EOI
            if (m == 0xDA || m == 0xD9) break;

            // Standalone markers (no length)
            if (m == 0x00 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                off += 2;
                continue;
            }

            int segLen = ByteBufferUtils.readU16BE(jpeg, off + 2);
            int totalLen = 2 + segLen; // FF xx + segment length (includes the 2 length bytes)

            // Keep APPn (E0-EF) and COM (FE)
            if ((m >= 0xE0 && m <= 0xEF) || m == 0xFE) {
                if (off + totalLen <= jpeg.length) {
                    byte[] segData = new byte[totalLen];
                    System.arraycopy(jpeg, off, segData, 0, totalLen);
                    segments.add(new JpegSegment(m, segData));
                }
            }

            off += totalLen;
        }
        return segments;
    }
}
