package com.cropcenter.metadata;

import java.io.ByteArrayOutputStream;

/**
 * Byte-endian stream writers shared by the metadata / crop / util fixture builders and tests. TIFF, EXIF, and MPF
 * fixtures write u16 / u32 fields in either byte order; centralising the writers here gives byte-order bugs one home
 * instead of a per-test-class copy. All writers append to a ByteArrayOutputStream, which never throws on write.
 *
 * Not a runtime helper — lives only in the test source set. Public so all test classes (including those outside
 * com.cropcenter.metadata, like CropExporterPngExifTest in com.cropcenter.crop) can share builders without copy-paste.
 */
public final class EndianStreamFixtures
{
	private EndianStreamFixtures() {}

	/**
	 * Write a u16 in the given byte order.
	 *
	 * @param out            destination stream
	 * @param value          low 16 bits are written; higher bits are ignored
	 * @param isLittleEndian true writes least-significant byte first, false most-significant first
	 */
	public static void writeU16(ByteArrayOutputStream out, int value, boolean isLittleEndian)
	{
		if (isLittleEndian)
		{
			writeU16Le(out, value);
		}
		else
		{
			writeU16Be(out, value);
		}
	}

	/**
	 * Write a u16 big-endian (most-significant byte first).
	 *
	 * @param out   destination stream
	 * @param value low 16 bits are written; higher bits are ignored
	 */
	public static void writeU16Be(ByteArrayOutputStream out, int value)
	{
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	/**
	 * Write a u16 little-endian (least-significant byte first).
	 *
	 * @param out   destination stream
	 * @param value low 16 bits are written; higher bits are ignored
	 */
	public static void writeU16Le(ByteArrayOutputStream out, int value)
	{
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
	}

	/**
	 * Write a u32 in the given byte order.
	 *
	 * @param out            destination stream
	 * @param value          low 32 bits are written; long-typed so callers can pass u32 values above
	 *                       Integer.MAX_VALUE without a negative-int cast
	 * @param isLittleEndian true writes least-significant byte first, false most-significant first
	 */
	public static void writeU32(ByteArrayOutputStream out, long value, boolean isLittleEndian)
	{
		if (isLittleEndian)
		{
			writeU32Le(out, value);
		}
		else
		{
			writeU32Be(out, value);
		}
	}

	/**
	 * Write a u32 big-endian (most-significant byte first).
	 *
	 * @param out   destination stream
	 * @param value low 32 bits are written; long-typed so callers can pass u32 values above Integer.MAX_VALUE
	 *              without a negative-int cast
	 */
	public static void writeU32Be(ByteArrayOutputStream out, long value)
	{
		out.write((int) ((value >> 24) & 0xFF));
		out.write((int) ((value >> 16) & 0xFF));
		out.write((int) ((value >> 8) & 0xFF));
		out.write((int) (value & 0xFF));
	}

	/**
	 * Write a u32 little-endian (least-significant byte first).
	 *
	 * @param out   destination stream
	 * @param value low 32 bits are written; long-typed so callers can pass u32 values above Integer.MAX_VALUE
	 *              without a negative-int cast
	 */
	public static void writeU32Le(ByteArrayOutputStream out, long value)
	{
		out.write((int) (value & 0xFF));
		out.write((int) ((value >> 8) & 0xFF));
		out.write((int) ((value >> 16) & 0xFF));
		out.write((int) ((value >> 24) & 0xFF));
	}
}
