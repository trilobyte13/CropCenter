package com.cropcenter.util;

// Endian-aware read/write helpers for raw byte arrays. Used throughout the metadata pipeline for
// JPEG/TIFF/MPF parsing. All methods validate bounds and throw IndexOutOfBoundsException on
// overflow.
public final class ByteBufferUtils
{
	private ByteBufferUtils() {}

	// ── Endian-dispatched ──

	public static int readU16(byte[] data, int offset, boolean isLittleEndian)
	{
		return isLittleEndian ? readU16LE(data, offset) : readU16BE(data, offset);
	}

	public static long readU32(byte[] data, int offset, boolean isLittleEndian)
	{
		return isLittleEndian ? readU32LE(data, offset) : readU32BE(data, offset);
	}

	public static void writeU16(byte[] data, int offset, int value, boolean isLittleEndian)
	{
		if (isLittleEndian)
		{
			writeU16LE(data, offset, value);
		}
		else
		{
			writeU16BE(data, offset, value);
		}
	}

	public static void writeU32(byte[] data, int offset, long value, boolean isLittleEndian)
	{
		if (isLittleEndian)
		{
			writeU32LE(data, offset, value);
		}
		else
		{
			writeU32BE(data, offset, value);
		}
	}

	// ── Big-endian ──

	public static int readU16BE(byte[] data, int offset)
	{
		checkRead(data, offset, 2);
		return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
	}

	public static long readU32BE(byte[] data, int offset)
	{
		checkRead(data, offset, 4);
		return ((long) (data[offset] & 0xFF) << 24)
			| ((long) (data[offset + 1] & 0xFF) << 16)
			| ((long) (data[offset + 2] & 0xFF) << 8)
			| (data[offset + 3] & 0xFF);
	}

	public static void writeU16BE(byte[] data, int offset, int value)
	{
		checkWrite(data, offset, 2);
		data[offset]     = (byte) ((value >> 8) & 0xFF);
		data[offset + 1] = (byte) (value & 0xFF);
	}

	public static void writeU32BE(byte[] data, int offset, long value)
	{
		checkWrite(data, offset, 4);
		data[offset]     = (byte) ((value >> 24) & 0xFF);
		data[offset + 1] = (byte) ((value >> 16) & 0xFF);
		data[offset + 2] = (byte) ((value >> 8) & 0xFF);
		data[offset + 3] = (byte) (value & 0xFF);
	}

	// ── Little-endian ──

	public static int readU16LE(byte[] data, int offset)
	{
		checkRead(data, offset, 2);
		return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
	}

	public static long readU32LE(byte[] data, int offset)
	{
		checkRead(data, offset, 4);
		return (data[offset] & 0xFF)
			| ((long) (data[offset + 1] & 0xFF) << 8)
			| ((long) (data[offset + 2] & 0xFF) << 16)
			| ((long) (data[offset + 3] & 0xFF) << 24);
	}

	public static void writeU16LE(byte[] data, int offset, int value)
	{
		checkWrite(data, offset, 2);
		data[offset]     = (byte) (value & 0xFF);
		data[offset + 1] = (byte) ((value >> 8) & 0xFF);
	}

	public static void writeU32LE(byte[] data, int offset, long value)
	{
		checkWrite(data, offset, 4);
		data[offset]     = (byte) (value & 0xFF);
		data[offset + 1] = (byte) ((value >> 8) & 0xFF);
		data[offset + 2] = (byte) ((value >> 16) & 0xFF);
		data[offset + 3] = (byte) ((value >> 24) & 0xFF);
	}

	// ── Bounds checks ──

	private static void checkRead(byte[] data, int offset, int length)
	{
		if (data == null || offset < 0 || offset + length > data.length)
		{
			String dataLen = data == null ? "null" : String.valueOf(data.length);
			throw new IndexOutOfBoundsException(
				"read " + length + " at " + offset + ", length=" + dataLen);
		}
	}

	private static void checkWrite(byte[] data, int offset, int length)
	{
		if (data == null || offset < 0 || offset + length > data.length)
		{
			String dataLen = data == null ? "null" : String.valueOf(data.length);
			throw new IndexOutOfBoundsException(
				"write " + length + " at " + offset + ", length=" + dataLen);
		}
	}
}
