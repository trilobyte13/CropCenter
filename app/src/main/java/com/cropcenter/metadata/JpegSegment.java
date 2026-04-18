package com.cropcenter.metadata;

/**
 * Represents a single JPEG marker segment (APPn or COM).
 * The data array includes the full segment: FF marker + length + payload.
 */
public class JpegSegment {
    public final int marker;  // e.g. 0xE1 for APP1
    public final byte[] data; // complete segment bytes including FF xx LL LL ...

    public JpegSegment(int marker, byte[] data) {
        this.marker = marker;
        this.data = data;
    }

    /** Check if this is an EXIF APP1 segment (starts with "Exif\0\0"). */
    public boolean isExif() {
        return marker == 0xE1 && data.length >= 10
            && data[4] == 'E' && data[5] == 'x' && data[6] == 'i'
            && data[7] == 'f' && data[8] == 0 && data[9] == 0;
    }

    /** Check if this is an XMP APP1 segment. */
    public boolean isXmp() {
        if (marker != 0xE1 || data.length < 33) return false;
        String id = "http://ns.adobe.com/xap/1.0/\0";
        for (int i = 0; i < id.length(); i++) {
            if ((data[4 + i] & 0xFF) != id.charAt(i)) return false;
        }
        return true;
    }

    /** Check if this is an MPF APP2 segment (starts with "MPF\0"). */
    public boolean isMpf() {
        return marker == 0xE2 && data.length >= 8
            && data[4] == 'M' && data[5] == 'P' && data[6] == 'F' && data[7] == 0;
    }

    /** Check if this is an ICC Profile APP2 segment (starts with "ICC_PROFILE\0"). */
    public boolean isIcc() {
        if (marker != 0xE2 || data.length < 18) return false;
        String id = "ICC_PROFILE\0";
        for (int i = 0; i < id.length(); i++) {
            if ((data[4 + i] & 0xFF) != id.charAt(i)) return false;
        }
        return true;
    }
}
