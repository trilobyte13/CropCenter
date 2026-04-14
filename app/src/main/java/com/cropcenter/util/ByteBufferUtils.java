package com.cropcenter.util;

/**
 * Endian-aware read/write helpers for raw byte arrays.
 * Used throughout the metadata pipeline for JPEG/TIFF/MPF parsing.
 * All methods validate bounds and throw IndexOutOfBoundsException on overflow.
 */
public final class ByteBufferUtils {

    private ByteBufferUtils() {}

    private static void checkRead(byte[] d, int off, int len) {
        if (d == null || off < 0 || off + len > d.length) {
            throw new IndexOutOfBoundsException(
                "read " + len + " at " + off + ", length=" + (d == null ? "null" : d.length));
        }
    }

    private static void checkWrite(byte[] d, int off, int len) {
        if (d == null || off < 0 || off + len > d.length) {
            throw new IndexOutOfBoundsException(
                "write " + len + " at " + off + ", length=" + (d == null ? "null" : d.length));
        }
    }

    // ── Big-endian ──

    public static int readU16BE(byte[] d, int off) {
        checkRead(d, off, 2);
        return ((d[off] & 0xFF) << 8) | (d[off + 1] & 0xFF);
    }

    public static long readU32BE(byte[] d, int off) {
        checkRead(d, off, 4);
        return ((long)(d[off] & 0xFF) << 24)
             | ((long)(d[off+1] & 0xFF) << 16)
             | ((long)(d[off+2] & 0xFF) << 8)
             | (d[off+3] & 0xFF);
    }

    public static void writeU16BE(byte[] d, int off, int v) {
        checkWrite(d, off, 2);
        d[off]   = (byte)((v >> 8) & 0xFF);
        d[off+1] = (byte)(v & 0xFF);
    }

    public static void writeU32BE(byte[] d, int off, long v) {
        checkWrite(d, off, 4);
        d[off]   = (byte)((v >> 24) & 0xFF);
        d[off+1] = (byte)((v >> 16) & 0xFF);
        d[off+2] = (byte)((v >> 8) & 0xFF);
        d[off+3] = (byte)(v & 0xFF);
    }

    // ── Little-endian ──

    public static int readU16LE(byte[] d, int off) {
        checkRead(d, off, 2);
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    public static long readU32LE(byte[] d, int off) {
        checkRead(d, off, 4);
        return (d[off] & 0xFF)
             | ((long)(d[off+1] & 0xFF) << 8)
             | ((long)(d[off+2] & 0xFF) << 16)
             | ((long)(d[off+3] & 0xFF) << 24);
    }

    public static void writeU16LE(byte[] d, int off, int v) {
        checkWrite(d, off, 2);
        d[off]   = (byte)(v & 0xFF);
        d[off+1] = (byte)((v >> 8) & 0xFF);
    }

    public static void writeU32LE(byte[] d, int off, long v) {
        checkWrite(d, off, 4);
        d[off]   = (byte)(v & 0xFF);
        d[off+1] = (byte)((v >> 8) & 0xFF);
        d[off+2] = (byte)((v >> 16) & 0xFF);
        d[off+3] = (byte)((v >> 24) & 0xFF);
    }

    // ── Endian-dispatched ──

    public static int readU16(byte[] d, int off, boolean le) {
        return le ? readU16LE(d, off) : readU16BE(d, off);
    }

    public static long readU32(byte[] d, int off, boolean le) {
        return le ? readU32LE(d, off) : readU32BE(d, off);
    }

    public static void writeU16(byte[] d, int off, int v, boolean le) {
        if (le) writeU16LE(d, off, v); else writeU16BE(d, off, v);
    }

    public static void writeU32(byte[] d, int off, long v, boolean le) {
        if (le) writeU32LE(d, off, v); else writeU32BE(d, off, v);
    }
}
