package com.cropcenter.metadata;

import com.cropcenter.util.ByteBufferUtils;

import java.util.Optional;

/**
 * Canonical TIFF IFD0 Orientation-entry locator. The single home for the header-validate + IFD0-walk + entry-shape
 * check shared by BitmapUtils.readExifOrientationInternal (JPEG APP1 EXIF),
 * PngMetadataExtractor.extractOrientationInternal (PNG eXIf), and CropExporter.forceTiffOrientationToUpright (raw
 * TIFF rewrite) — one home so the three walk disciplines cannot drift apart. Callers
 * keep their own value handling: the readers read and range-validate the u16 at entryOffset + 8, the writer clones
 * and overwrites it.
 *
 * All offset arithmetic is done in long — PNG eXIf is u31-uncapped, so int-stride wrap is reachable on adversarial
 * chunks, and a u32 IFD offset near 2^32-1 plus a small tiffStart would otherwise wrap to a small positive int that
 * passes bounds checks on the truncated value.
 */
public final class TiffIfd0
{
	/**
	 * A located, shape-valid IFD0 Orientation entry.
	 *
	 * @param entryOffset    absolute offset of the 12-byte IFD entry within the scanned array (tag at
	 *                       entryOffset, type at +2, count at +4, u16 value at +8)
	 * @param isLittleEndian TIFF byte order from the header's II / MM marker
	 */
	public record OrientationEntry(int entryOffset, boolean isLittleEndian) {}

	private TiffIfd0() {}

	/**
	 * Validate a TIFF header and walk IFD0 for the first Orientation (0x0112) entry. Validation gates, in order:
	 * at least 8 header bytes inside [tiffStart, tiffEnd); byte-order marker is exactly II or MM (a mismatched
	 * pair would silently parse offsets with the wrong byte order); TIFF magic 42 at +2 (a coincidental II/MM
	 * match without the magic means the payload isn't TIFF); IFD0 offset in bounds; and the first Orientation
	 * entry shaped as type SHORT with count 1 (any other shape means random bytes would be read as orientation —
	 * real EXIF always emits SHORT/1).
	 *
	 * @param data       byte array containing the TIFF body
	 * @param tiffStart  offset of the TIFF header (byte-order marker) within data
	 * @param tiffEnd    exclusive upper bound of the TIFF body within data (the containing array or chunk end);
	 *                   every offset and entry read is checked against it
	 * @param minIfdRel  minimum accepted IFD0 offset relative to tiffStart — 0 for the orientation readers
	 *                   (any in-bounds offset), 8 for the raw-TIFF writer which rejects an IFD0 overlapping
	 *                   the 8-byte header
	 * @return the first Orientation entry when the header validates and the entry is SHORT/count-1; empty when
	 *         any gate fails or IFD0 carries no Orientation tag
	 */
	public static Optional<OrientationEntry> findOrientationEntry(byte[] data, int tiffStart, int tiffEnd,
		int minIfdRel)
	{
		if (tiffStart + 8 > tiffEnd)
		{
			return Optional.empty();
		}
		int byteOrderHi = data[tiffStart] & 0xFF;
		int byteOrderLo = data[tiffStart + 1] & 0xFF;
		if (!((byteOrderHi == 0x49 && byteOrderLo == 0x49) || (byteOrderHi == 0x4D && byteOrderLo == 0x4D)))
		{
			return Optional.empty();
		}
		boolean isLittleEndian = byteOrderHi == 0x49;
		if (ByteBufferUtils.readU16(data, tiffStart + 2, isLittleEndian) != TiffTag.MAGIC)
		{
			return Optional.empty();
		}
		long ifdRel = ByteBufferUtils.readU32(data, tiffStart + 4, isLittleEndian);
		long absIfd = (long) tiffStart + ifdRel;
		if (absIfd < (long) tiffStart + minIfdRel || absIfd + 2 > tiffEnd)
		{
			return Optional.empty();
		}
		int ifd = (int) absIfd;
		int entryCount = ByteBufferUtils.readU16(data, ifd, isLittleEndian);
		for (int i = 0; i < entryCount; i++)
		{
			long entryLong = (long) ifd + 2 + (long) i * 12;
			if (entryLong + 12 > tiffEnd)
			{
				break;
			}
			int entry = (int) entryLong;
			if (ByteBufferUtils.readU16(data, entry, isLittleEndian) != TiffTag.ORIENTATION)
			{
				continue;
			}
			int entryType = ByteBufferUtils.readU16(data, entry + 2, isLittleEndian);
			long orientationCount = ByteBufferUtils.readU32(data, entry + 4, isLittleEndian);
			if (entryType != TiffTag.TYPE_SHORT || orientationCount != 1)
			{
				return Optional.empty();
			}
			return Optional.of(new OrientationEntry(entry, isLittleEndian));
		}
		return Optional.empty();
	}

	/**
	 * Read the u16 orientation value from a located entry, mapping out-of-range values to upright. Shared by the
	 * JPEG and PNG orientation readers; the raw-TIFF writer reads the value itself because it rewrites any
	 * non-1 value rather than range-validating it.
	 *
	 * @param data  byte array the entry was located in
	 * @param entry shape-valid Orientation entry from findOrientationEntry
	 * @return orientation 1..8, or 1 when the recorded value is outside the EXIF-legal range
	 */
	public static int readOrientation(byte[] data, OrientationEntry entry)
	{
		int orientation = ByteBufferUtils.readU16(data, entry.entryOffset() + 8, entry.isLittleEndian());
		if (orientation < 1 || orientation > 8)
		{
			return 1;
		}
		return orientation;
	}
}
