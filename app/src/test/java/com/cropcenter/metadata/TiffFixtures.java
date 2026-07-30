package com.cropcenter.metadata;

import static com.cropcenter.metadata.EndianStreamFixtures.writeU16;
import static com.cropcenter.metadata.EndianStreamFixtures.writeU16Le;
import static com.cropcenter.metadata.EndianStreamFixtures.writeU32;
import static com.cropcenter.metadata.EndianStreamFixtures.writeU32Le;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Builders for synthetic TIFF byte regions used by the EXIF / PNG-eXIf orientation and thumbnail tests. The returned
 * arrays are raw TIFF (header through data blocks, no EXIF identifier or APP1 wrapper) — callers wrap them per
 * consumer: "Exif\0\0" + APP1 for JPEG readers, eXIf chunk for PNG readers, raw for CropExporter's TIFF patchers.
 *
 * Not a runtime helper — lives only in the test source set. Public so all test classes (including those outside
 * com.cropcenter.metadata, like BitmapUtilsTest in com.cropcenter.util) can share builders without copy-paste.
 */
public final class TiffFixtures
{
	private TiffFixtures() {}

	/**
	 * Build a minimal 26-byte TIFF carrying a single IFD0 Orientation entry: byte-order pair + magic 42 + IFD0 at
	 * offset 8 with one SHORT / count-1 entry (tag 0x0112, value left-justified in the first 2 bytes of the 4-byte
	 * value field per TIFF 6.0) + next-IFD pointer 0.
	 *
	 * @param isLittleEndian true writes an "II" header, false writes "MM"; every multi-byte field follows the
	 *                       chosen byte order
	 * @param orientation    Orientation tag value (1..8 per EXIF spec, but readers under test accept any u16)
	 * @return 26 TIFF bytes, header through the zero next-IFD pointer
	 */
	public static byte[] orientationTiff(boolean isLittleEndian, int orientation)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (isLittleEndian)
		{
			out.write('I');
			out.write('I');
		}
		else
		{
			out.write('M');
			out.write('M');
		}
		writeU16(out, 42, isLittleEndian);              // TIFF magic
		writeU32(out, 8L, isLittleEndian);              // IFD0 offset = 8
		writeU16(out, 1, isLittleEndian);               // entry count
		writeU16(out, 0x0112, isLittleEndian);          // Orientation
		writeU16(out, 3, isLittleEndian);               // SHORT
		writeU32(out, 1L, isLittleEndian);              // count
		writeU16(out, orientation, isLittleEndian);     // value (left-justified)
		writeU16(out, 0, isLittleEndian);               // padding
		writeU32(out, 0L, isLittleEndian);              // next-IFD = 0
		return out.toByteArray();
	}

	/**
	 * Build a little-endian TIFF with an IFD0 -> IFD1 thumbnail chain: header (II*\0 + IFD0 offset 8) + IFD0 (one
	 * Orientation=6 entry, next-IFD pointer 26) + IFD1 (Compression=6, JPEGInterchangeFormat=68,
	 * JPEGInterchangeFormatLength=reportedThumbLen, next-IFD 0) + thumbnail bytes + trailing bytes. IFD0 spans
	 * offsets 8..26 (2 + 12 + 4), IFD1 spans 26..68 (2 + 36 + 4), so the thumbnail lands at TIFF offset 68 — the
	 * offset chain every consumer's assertions depend on lives here only.
	 *
	 * @param thumbnail        bytes appended at TIFF offset 68; the JPEGInterchangeFormat tag records offset 68
	 *                         regardless of length
	 * @param reportedThumbLen value written into the JPEGInterchangeFormatLength tag; pass thumbnail.length for a
	 *                         consistent layout, or a lying value to model a corrupt length claim
	 * @param trailing         bytes appended after the thumbnail, referenced by no IFD entry (pass an empty array
	 *                         for none) — models MakerNote / SubIFD value blocks past the IFD1 thumbnail
	 * @return TIFF bytes, header through the trailing block
	 * @throws IOException never from the in-memory stream; declared by the OutputStream write contract
	 */
	public static byte[] tiffWithThumbnail(byte[] thumbnail, long reportedThumbLen, byte[] trailing)
		throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write('I');
		out.write('I');
		out.write('*');
		out.write(0);
		writeU32Le(out, 8L);                    // IFD0 offset = 8

		// IFD0: 1 entry (Orientation), next-IFD pointer to IFD1 at offset 26.
		writeU16Le(out, 1);                     // entry count
		writeU16Le(out, 0x0112);                // Orientation
		writeU16Le(out, 3);                     // SHORT
		writeU32Le(out, 1L);                    // count
		writeU16Le(out, 6);                     // value = 6 (Rotate 90 CW)
		writeU16Le(out, 0);                     // padding
		writeU32Le(out, 26L);                   // next-IFD pointer -> IFD1 at offset 26

		// IFD1: 3 entries (Compression, JPEGInterchangeFormat, JPEGInterchangeFormatLength), next-IFD = 0.
		// Layout: 2 (count) + 36 (3 entries) + 4 (next-IFD) = 42 bytes. Thumbnail offset = 26 + 42 = 68.
		int thumbDataOff = 26 + 42;
		writeU16Le(out, 3);                     // entry count
		writeU16Le(out, 0x0103);                // Compression
		writeU16Le(out, 3);                     // SHORT
		writeU32Le(out, 1L);                    // count
		writeU16Le(out, 6);                     // value = 6 (JPEG)
		writeU16Le(out, 0);                     // padding
		writeU16Le(out, 0x0201);                // JPEGInterchangeFormat
		writeU16Le(out, 4);                     // LONG
		writeU32Le(out, 1L);                    // count
		writeU32Le(out, thumbDataOff);          // value = thumbnail offset
		writeU16Le(out, 0x0202);                // JPEGInterchangeFormatLength
		writeU16Le(out, 4);                     // LONG
		writeU32Le(out, 1L);                    // count
		writeU32Le(out, reportedThumbLen);      // value = claimed thumbnail byte count
		writeU32Le(out, 0L);                    // next-IFD pointer = 0

		out.write(thumbnail);
		out.write(trailing);
		return out.toByteArray();
	}
}
