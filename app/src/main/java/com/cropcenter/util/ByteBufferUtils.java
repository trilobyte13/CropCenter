package com.cropcenter.util;

/**
 * Endian-aware read/write helpers for raw byte arrays. Used throughout the metadata pipeline for JPEG/TIFF/MPF parsing.
 * All methods validate bounds and throw IndexOutOfBoundsException on overflow.
 */
public final class ByteBufferUtils
{
	// Default buffer size for streaming copies (URI → cache file, cache file → byte[], etc.). 8 KiB is the
	// conventional InputStream.DEFAULT_BUFFER_SIZE and matches what filesystems and content providers tend to serve
	// per read.
	public static final int IO_BUFFER = 8192;

	private ByteBufferUtils() {}

	// ── Endian-dispatched ──

	/**
	 * Read a 16-bit unsigned int at `offset`, dispatching on `isLittleEndian`. EXIF / TIFF fields set the
	 * endianness in the IFD header; all downstream reads use this dispatcher to avoid branching at every call site.
	 *
	 * @param data           byte buffer
	 * @param offset         start of the 2-byte field
	 * @param isLittleEndian byte-order from the enclosing IFD
	 * @return value in [0, 65535]
	 * @throws IndexOutOfBoundsException when the read would exceed buffer bounds
	 */
	public static int readU16(byte[] data, int offset, boolean isLittleEndian)
	{
		return isLittleEndian ? readU16LE(data, offset) : readU16BE(data, offset);
	}

	/**
	 * Read a 32-bit unsigned int at `offset`, dispatching on `isLittleEndian`. Returns a long so the full u32 range
	 * fits without sign-bit issues.
	 *
	 * @param data           byte buffer
	 * @param offset         start of the 4-byte field
	 * @param isLittleEndian byte-order from the enclosing IFD
	 * @return value in [0, 0xFFFFFFFF] as long
	 * @throws IndexOutOfBoundsException when the read would exceed buffer bounds
	 */
	public static long readU32(byte[] data, int offset, boolean isLittleEndian)
	{
		return isLittleEndian ? readU32LE(data, offset) : readU32BE(data, offset);
	}

	/**
	 * Write a 16-bit unsigned int at `offset`, dispatching on `isLittleEndian`.
	 *
	 * @param data           byte buffer
	 * @param offset         start of the 2-byte field
	 * @param value          unsigned 16-bit value; bits beyond 16 are truncated
	 * @param isLittleEndian byte-order from the enclosing IFD
	 * @throws IndexOutOfBoundsException when the write would exceed buffer bounds
	 */
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

	/**
	 * Write a 32-bit unsigned int at `offset`, dispatching on `isLittleEndian`.
	 *
	 * @param data           byte buffer
	 * @param offset         start of the 4-byte field
	 * @param value          unsigned 32-bit value; bits beyond 32 are truncated
	 * @param isLittleEndian byte-order from the enclosing IFD
	 * @throws IndexOutOfBoundsException when the write would exceed buffer bounds
	 */
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

	/**
	 * Read a big-endian u16 at `offset`.
	 *
	 * @param data   byte buffer
	 * @param offset start of the 2-byte field
	 * @return value in [0, 65535]
	 * @throws IndexOutOfBoundsException when the read would exceed buffer bounds
	 */
	public static int readU16BE(byte[] data, int offset)
	{
		checkRead(data, offset, 2);
		return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
	}

	/**
	 * Read a big-endian u32 at `offset`. Return type is long to fit the full u32 range.
	 *
	 * @param data   byte buffer
	 * @param offset start of the 4-byte field
	 * @return value in [0, 0xFFFFFFFF] as long
	 * @throws IndexOutOfBoundsException when the read would exceed buffer bounds
	 */
	public static long readU32BE(byte[] data, int offset)
	{
		checkRead(data, offset, 4);
		return ((long) (data[offset] & 0xFF) << 24) | ((long) (data[offset + 1] & 0xFF) << 16)
			| ((long) (data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
	}

	/**
	 * Write a big-endian u16 at `offset`. `value` beyond 16 bits is truncated.
	 *
	 * @param data   byte buffer
	 * @param offset start of the 2-byte field
	 * @param value  unsigned 16-bit value
	 * @throws IndexOutOfBoundsException when the write would exceed buffer bounds
	 */
	public static void writeU16BE(byte[] data, int offset, int value)
	{
		checkWrite(data, offset, 2);
		data[offset]     = (byte) ((value >> 8) & 0xFF);
		data[offset + 1] = (byte) (value & 0xFF);
	}

	/**
	 * Write a big-endian u32 at `offset`. `value` beyond 32 bits is truncated.
	 *
	 * @param data   byte buffer
	 * @param offset start of the 4-byte field
	 * @param value  unsigned 32-bit value
	 * @throws IndexOutOfBoundsException when the write would exceed buffer bounds
	 */
	public static void writeU32BE(byte[] data, int offset, long value)
	{
		checkWrite(data, offset, 4);
		data[offset]     = (byte) ((value >> 24) & 0xFF);
		data[offset + 1] = (byte) ((value >> 16) & 0xFF);
		data[offset + 2] = (byte) ((value >> 8) & 0xFF);
		data[offset + 3] = (byte) (value & 0xFF);
	}

	// ── Little-endian ──

	/**
	 * Read a little-endian u16 at `offset`.
	 *
	 * @param data   byte buffer
	 * @param offset start of the 2-byte field
	 * @return value in [0, 65535]
	 * @throws IndexOutOfBoundsException when the read would exceed buffer bounds
	 */
	public static int readU16LE(byte[] data, int offset)
	{
		checkRead(data, offset, 2);
		return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
	}

	/**
	 * Read a little-endian u32 at `offset`. Return type is long to fit the full u32 range.
	 *
	 * @param data   byte buffer
	 * @param offset start of the 4-byte field
	 * @return value in [0, 0xFFFFFFFF] as long
	 * @throws IndexOutOfBoundsException when the read would exceed buffer bounds
	 */
	public static long readU32LE(byte[] data, int offset)
	{
		checkRead(data, offset, 4);
		return (data[offset] & 0xFF) | ((long) (data[offset + 1] & 0xFF) << 8)
			| ((long) (data[offset + 2] & 0xFF) << 16) | ((long) (data[offset + 3] & 0xFF) << 24);
	}

	/**
	 * Write a little-endian u16 at `offset`. `value` beyond 16 bits is truncated.
	 *
	 * @param data   byte buffer
	 * @param offset start of the 2-byte field
	 * @param value  unsigned 16-bit value
	 * @throws IndexOutOfBoundsException when the write would exceed buffer bounds
	 */
	public static void writeU16LE(byte[] data, int offset, int value)
	{
		checkWrite(data, offset, 2);
		data[offset]     = (byte) (value & 0xFF);
		data[offset + 1] = (byte) ((value >> 8) & 0xFF);
	}

	/**
	 * Write a little-endian u32 at `offset`. `value` beyond 32 bits is truncated.
	 *
	 * @param data   byte buffer
	 * @param offset start of the 4-byte field
	 * @param value  unsigned 32-bit value
	 * @throws IndexOutOfBoundsException when the write would exceed buffer bounds
	 */
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
		// `offset + length > data.length` could silently wrap to a negative value for offsets near
		// Integer.MAX_VALUE, bypassing the bounds check. Subtracting on the right (`offset > data.length -
		// length`) can't overflow when length is small positive (2 or 4 in practice here). Defensive — current
		// callers cap offsets via metadata pipeline guards well below the overflow range, but the helper
		// shouldn't rely on that invariant.
		if (data == null || offset < 0 || length < 0 || offset > data.length - length)
		{
			String dataLen = data == null ? "null" : String.valueOf(data.length);
			throw new IndexOutOfBoundsException("read " + length + " at " + offset + ", length=" + dataLen);
		}
	}

	private static void checkWrite(byte[] data, int offset, int length)
	{
		if (data == null || offset < 0 || length < 0 || offset > data.length - length)
		{
			String dataLen = data == null ? "null" : String.valueOf(data.length);
			throw new IndexOutOfBoundsException(
				"write " + length + " at " + offset + ", length=" + dataLen);
		}
	}
}
