package com.cropcenter.util;

/**
 * Endian-aware read/write helpers for raw byte arrays. Used throughout the metadata pipeline for JPEG / TIFF / MPF
 * parsing. EXIF / TIFF fields set the endianness in the IFD header; downstream reads dispatch through readU16 / readU32
 * / writeU16 / writeU32 to avoid branching at every call site. u32 reads return long so the full range fits without
 * sign-bit issues. All methods validate bounds and throw IndexOutOfBoundsException on overflow.
 */
public final class ByteBufferUtils
{
	private ByteBufferUtils() {}

	// ── Endian-dispatched ──

	/**
	 * Read a 16-bit unsigned integer at offset, dispatching on the IFD-declared byte order.
	 *
	 * @param data           byte buffer to read from
	 * @param offset         start of the 2-byte field
	 * @param isLittleEndian byte order from the enclosing IFD header
	 * @return value in [0, 65535]
	 * @throws IndexOutOfBoundsException when the read would exceed buffer bounds
	 */
	public static int readU16(byte[] data, int offset, boolean isLittleEndian)
	{
		return isLittleEndian ? readU16LE(data, offset) : readU16BE(data, offset);
	}

	/**
	 * Read a 32-bit unsigned integer at offset, dispatching on the IFD-declared byte order. Returns long so the
	 * full u32 range fits without sign-bit issues.
	 *
	 * @param data           byte buffer to read from
	 * @param offset         start of the 4-byte field
	 * @param isLittleEndian byte order from the enclosing IFD header
	 * @return value in [0, 0xFFFFFFFF] as a long
	 * @throws IndexOutOfBoundsException when the read would exceed buffer bounds
	 */
	public static long readU32(byte[] data, int offset, boolean isLittleEndian)
	{
		return isLittleEndian ? readU32LE(data, offset) : readU32BE(data, offset);
	}

	/**
	 * Write a 16-bit unsigned integer at offset, dispatching on the IFD-declared byte order.
	 *
	 * @param data           byte buffer to write into
	 * @param offset         start of the 2-byte field
	 * @param value          unsigned 16-bit value; bits beyond 16 are truncated
	 * @param isLittleEndian byte order from the enclosing IFD header
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
	 * Write a 32-bit unsigned integer at offset, dispatching on the IFD-declared byte order.
	 *
	 * @param data           byte buffer to write into
	 * @param offset         start of the 4-byte field
	 * @param value          unsigned 32-bit value; bits beyond 32 are truncated
	 * @param isLittleEndian byte order from the enclosing IFD header
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
	 * Read a big-endian 16-bit unsigned integer at offset.
	 *
	 * @param data   byte buffer to read from
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
	 * Read a big-endian 32-bit unsigned integer at offset. Returns long so the full u32 range fits.
	 *
	 * @param data   byte buffer to read from
	 * @param offset start of the 4-byte field
	 * @return value in [0, 0xFFFFFFFF] as a long
	 * @throws IndexOutOfBoundsException when the read would exceed buffer bounds
	 */
	public static long readU32BE(byte[] data, int offset)
	{
		checkRead(data, offset, 4);
		return ((long) (data[offset] & 0xFF) << 24) | ((long) (data[offset + 1] & 0xFF) << 16)
			| ((long) (data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
	}

	/**
	 * Write a big-endian 16-bit unsigned integer at offset.
	 *
	 * @param data   byte buffer to write into
	 * @param offset start of the 2-byte field
	 * @param value  unsigned 16-bit value; bits beyond 16 are truncated
	 * @throws IndexOutOfBoundsException when the write would exceed buffer bounds
	 */
	public static void writeU16BE(byte[] data, int offset, int value)
	{
		checkWrite(data, offset, 2);
		data[offset]     = (byte) ((value >> 8) & 0xFF);
		data[offset + 1] = (byte) (value & 0xFF);
	}

	/**
	 * Write a big-endian 32-bit unsigned integer at offset.
	 *
	 * @param data   byte buffer to write into
	 * @param offset start of the 4-byte field
	 * @param value  unsigned 32-bit value; bits beyond 32 are truncated
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
	 * Read a little-endian 16-bit unsigned integer at offset.
	 *
	 * @param data   byte buffer to read from
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
	 * Read a little-endian 32-bit unsigned integer at offset. Returns long so the full u32 range fits.
	 *
	 * @param data   byte buffer to read from
	 * @param offset start of the 4-byte field
	 * @return value in [0, 0xFFFFFFFF] as a long
	 * @throws IndexOutOfBoundsException when the read would exceed buffer bounds
	 */
	public static long readU32LE(byte[] data, int offset)
	{
		checkRead(data, offset, 4);
		return (data[offset] & 0xFF) | ((long) (data[offset + 1] & 0xFF) << 8)
			| ((long) (data[offset + 2] & 0xFF) << 16) | ((long) (data[offset + 3] & 0xFF) << 24);
	}

	/**
	 * Write a little-endian 16-bit unsigned integer at offset.
	 *
	 * @param data   byte buffer to write into
	 * @param offset start of the 2-byte field
	 * @param value  unsigned 16-bit value; bits beyond 16 are truncated
	 * @throws IndexOutOfBoundsException when the write would exceed buffer bounds
	 */
	public static void writeU16LE(byte[] data, int offset, int value)
	{
		checkWrite(data, offset, 2);
		data[offset]     = (byte) (value & 0xFF);
		data[offset + 1] = (byte) ((value >> 8) & 0xFF);
	}

	/**
	 * Write a little-endian 32-bit unsigned integer at offset.
	 *
	 * @param data   byte buffer to write into
	 * @param offset start of the 4-byte field
	 * @param value  unsigned 32-bit value; bits beyond 32 are truncated
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
		// Integer.MAX_VALUE. Subtracting on the right (`offset > data.length - length`) can't overflow when
		// length is small positive (2 or 4 in practice here).
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
