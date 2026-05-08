package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Patches EXIF metadata segments in-place:
 *  - Sets Orientation tag to 1 (Normal)
 *  - Updates ImageWidth, ImageLength, PixelXDimension, PixelYDimension
 *  - Replaces thumbnail JPEG with new one if provided
 *  - Preserves all other tags and data verbatim
 */
public final class ExifPatcher
{
	private static final String TAG = "ExifPatcher";
	// APP1 segment size constraint: the 2-byte length field caps total segment bytes at 65535.
	private static final int APP1_MAX_SEGMENT_BYTES = 65535;
	private static final int TIFF_HEADER_OFFSET = 10; // bytes from start of APP1 data to the TIFF header

	private ExifPatcher() {}

	/**
	 * Estimate max thumbnail bytes that will fit in the EXIF APP1 segment. Measures the EXIF size excluding the
	 * existing thumbnail, then returns the remaining space within the 65535-byte APP1 limit.
	 *
	 * @param segments JPEG metadata as captured by JpegMetadataExtractor
	 * @return remaining APP1 budget after EXIF overhead, or DEFAULT_THUMB_BUDGET when EXIF
	 *         is missing / unparseable / the byte-order field is malformed
	 */
	public static int maxThumbnailBytes(List<JpegSegment> segments)
	{
		// defaultThumbBudget — fallback when we can't measure the EXIF segment (no EXIF present, malformed
		// byte-order, IFD-offset math fails). 20KB is conservative: most camera thumbnails are 5-15KB and the
		// caller (CropExporter.buildEmbeddedThumbnail) re-checks the actual encoded size against the APP1 cap
		// before injection.
		final int defaultThumbBudget = 20_000;
		// ifd1EstimatedOverhead — rough byte cost of synthesising an IFD1 header (entry count + 2 entries for
		// JPEGInterchangeFormat / Length + 4-byte next-IFD pointer + the 2 length bytes). Used when EXIF
		// exists but has no IFD1, so we'll add one alongside the new thumbnail.
		final int ifd1EstimatedOverhead = 42;
		for (JpegSegment seg : segments)
		{
			if (!seg.isExif())
			{
				continue;
			}
			byte[] data = seg.data();
			if (TIFF_HEADER_OFFSET + 8 > data.length)
			{
				continue;
			}
			// TIFF byte-order marker is 2 bytes — "II" (little) or "MM" (big). Validate both halves; a
			// malformed "IM" / "MI" treated as little-endian would parse every subsequent u32 with wrong
			// byte order and corrupt offset arithmetic.
			int byteOrderHi = data[TIFF_HEADER_OFFSET] & 0xFF;
			int byteOrderLo = data[TIFF_HEADER_OFFSET + 1] & 0xFF;
			if (!((byteOrderHi == 0x49 && byteOrderLo == 0x49)
				|| (byteOrderHi == 0x4D && byteOrderLo == 0x4D)))
			{
				continue;
			}
			boolean isLittleEndian = byteOrderHi == 0x49;

			// IFD0 → IFD1 walk to locate the existing thumbnail entry. On any parse failure here we
			// fall back to defaultThumbBudget rather than zero — corrupt-IFD source EXIF is still
			// preserve-worthy at load / save round-trip, and a non-zero budget keeps the embedded-
			// thumbnail injection from silently dropping just because we couldn't measure it.
			long ifd0Rel = ByteBufferUtils.readU32(data, TIFF_HEADER_OFFSET + 4, isLittleEndian);
			int ifd0 = (int) (TIFF_HEADER_OFFSET + ifd0Rel);
			if (ifd0 < TIFF_HEADER_OFFSET || ifd0 + 2 > data.length)
			{
				return defaultThumbBudget;
			}
			int ifd0EntryCount = ByteBufferUtils.readU16(data, ifd0, isLittleEndian);
			int nextIfdPointer = ifd0 + 2 + ifd0EntryCount * 12;
			if (nextIfdPointer + 4 > data.length)
			{
				return defaultThumbBudget;
			}
			long ifd1Rel = ByteBufferUtils.readU32(data, nextIfdPointer, isLittleEndian);

			if (ifd1Rel == 0)
			{
				// No IFD1: EXIF overhead = current segment + new IFD1 header we'd add. Clamp at 0 — if
				// the current segment alone nearly fills the APP1 budget, there's no room for a
				// thumbnail and we should say so honestly rather than return a negative that relies on
				// the caller to clamp.
				return Math.max(0, APP1_MAX_SEGMENT_BYTES - (data.length + ifd1EstimatedOverhead));
			}

			int ifd1 = (int) (TIFF_HEADER_OFFSET + ifd1Rel);
			if (ifd1 < TIFF_HEADER_OFFSET || ifd1 + 2 > data.length)
			{
				return defaultThumbBudget;
			}
			int ifd1EntryCount = ByteBufferUtils.readU16(data, ifd1, isLittleEndian);
			int oldThumbLen = 0;
			for (int i = 0; i < ifd1EntryCount; i++)
			{
				int entryOffset = ifd1 + 2 + i * 12;
				if (entryOffset + 12 > data.length)
				{
					break;
				}
				int tag = ByteBufferUtils.readU16(data, entryOffset, isLittleEndian);
				if (tag == TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH)
				{
					oldThumbLen = (int) ByteBufferUtils.readU32(
						data, entryOffset + 8, isLittleEndian);
					break;
				}
			}
			// Sanity-clamp: corrupt EXIF can report a thumbnail length beyond the segment itself (or
			// negative after the u32→int cast). Either case produces a negative exifOverhead which inflates
			// the returned budget above APP1_MAX_SEGMENT_BYTES — downstream writers then overflow the
			// 65535-byte APP1 cap. Clamp to [0, data.length].
			if (oldThumbLen < 0 || oldThumbLen > data.length)
			{
				oldThumbLen = 0;
			}
			// Available = APP1_MAX_SEGMENT_BYTES - (current segment size - old thumbnail size)
			int exifOverhead = data.length - oldThumbLen;
			return Math.max(0, APP1_MAX_SEGMENT_BYTES - exifOverhead);
		}
		return defaultThumbBudget; // no EXIF segment found
	}

	/**
	 * Patch the EXIF dimensions to newW×newH, normalise orientation to 1 (upright — we bake rotation into the
	 * primary JPEG), and optionally replace the thumbnail.
	 *
	 * @param segments  source-file segments; EXIF entries are cloned and mutated, all others
	 *                  pass through verbatim
	 * @param newW      post-crop EXIF width
	 * @param newH      post-crop EXIF height
	 * @param thumbnail new JPEG thumbnail bytes, or null to keep the original thumbnail
	 * @return new list with EXIF dimensions / orientation / (optional) thumbnail patched;
	 *         non-EXIF segments are returned by reference
	 */
	public static List<JpegSegment> patch(List<JpegSegment> segments, int newW, int newH, byte[] thumbnail)
	{
		int orientation = 1; // always upright — rotation is baked into the pixels
		List<JpegSegment> result = new ArrayList<>(segments.size());
		for (JpegSegment seg : segments)
		{
			if (!seg.isExif())
			{
				result.add(seg);
				continue;
			}
			byte[] data = seg.data().clone();
			if (TIFF_HEADER_OFFSET + 8 > data.length)
			{
				result.add(seg);
				continue;
			}
			// TIFF byte-order marker is 2 bytes — "II" (little) or "MM" (big). Validate both halves; a
			// malformed "IM" / "MI" treated as little-endian would parse every subsequent u32 with wrong
			// byte order and corrupt offset arithmetic.
			int byteOrderHi = data[TIFF_HEADER_OFFSET] & 0xFF;
			int byteOrderLo = data[TIFF_HEADER_OFFSET + 1] & 0xFF;
			if (!((byteOrderHi == 0x49 && byteOrderLo == 0x49)
				|| (byteOrderHi == 0x4D && byteOrderLo == 0x4D)))
			{
				result.add(seg);
				continue;
			}
			boolean isLittleEndian = byteOrderHi == 0x49;

			long ifdOffRel = ByteBufferUtils.readU32(data, TIFF_HEADER_OFFSET + 4, isLittleEndian);
			// Same long-arithmetic guard as scanIfd's SubIFD pointer: ifdOffRel is u32 (range 0..2^32-1)
			// and the addition `TIFF_HEADER_OFFSET + ifdOffRel` can exceed Integer.MAX_VALUE on adversarial
			// EXIF. Validate the long sum before casting — a malicious 0xFFFFFFFF would truncate to a
			// small-positive int that lands inside the buffer, bypassing the bounds check below.
			long absIfdOff = TIFF_HEADER_OFFSET + ifdOffRel;
			if (ifdOffRel < 0 || absIfdOff < TIFF_HEADER_OFFSET || absIfdOff + 2 > data.length)
			{
				result.add(new JpegSegment(seg.marker(), data));
				continue;
			}
			int ifdOff = (int) absIfdOff;

			scanIfd(data, ifdOff, TIFF_HEADER_OFFSET, isLittleEndian, newW, newH, orientation, 0);
			if (thumbnail != null)
			{
				data = replaceThumbnail(data, TIFF_HEADER_OFFSET, isLittleEndian, thumbnail);
			}

			result.add(new JpegSegment(seg.marker(), data));
		}
		return result;
	}

	/**
	 * Replace the EXIF thumbnail JPEG. Rebuilds the APP1 segment with new thumbnail bytes. Finds IFD1's
	 * JPEGInterchangeFormat/Length, replaces the old thumbnail data, and updates the segment length and tag values.
	 */
	private static byte[] replaceThumbnail(byte[] data, int tiffStart, boolean isLittleEndian, byte[] newThumb)
	{
		try
		{
			long ifd0Rel = ByteBufferUtils.readU32(data, tiffStart + 4, isLittleEndian);
			// Long-arithmetic guard before the int cast — see ExifPatcher.patch and scanIfd for the full
			// rationale. A u32 ifd0Rel of 0xFFFFFFFF would truncate to a small-positive int that satisfies
			// `>= tiffStart` only by luck; computing the long sum first rules out the overflow case.
			long absIfd0 = tiffStart + ifd0Rel;
			if (ifd0Rel < 0 || absIfd0 < tiffStart || absIfd0 + 2 > data.length)
			{
				return data;
			}
			int ifd0 = (int) absIfd0;
			int ifd0EntryCount = ByteBufferUtils.readU16(data, ifd0, isLittleEndian);
			int nextIfdPointer = ifd0 + 2 + ifd0EntryCount * 12;
			if (nextIfdPointer + 4 > data.length)
			{
				return data;
			}
			long ifd1Rel = ByteBufferUtils.readU32(data, nextIfdPointer, isLittleEndian);
			if (ifd1Rel == 0)
			{
				return appendFreshIfd1WithThumbnail(data, tiffStart, nextIfdPointer,
					isLittleEndian, newThumb);
			}
			long absIfd1 = tiffStart + ifd1Rel;
			if (ifd1Rel < 0 || absIfd1 < tiffStart || absIfd1 + 2 > data.length)
			{
				return data;
			}
			return spliceExistingThumbnail(data, tiffStart, (int) absIfd1, isLittleEndian, newThumb);
		}
		catch (Exception e)
		{
			Log.w(TAG, "Thumbnail replacement failed", e);
			return data;
		}
	}

	/**
	 * IFD1 does not exist — append a minimal one (3 entries: Compression, JPEGInterchangeFormat,
	 * JPEGInterchangeFormatLength) plus the thumbnail bytes at the end of the EXIF payload, updating IFD0's
	 * next-IFD pointer and the APP1 segment length. No-op return (unchanged `data`) when the thumbnail is absent or
	 * the result would exceed the APP1 payload cap.
	 */
	private static byte[] appendFreshIfd1WithThumbnail(byte[] data, int tiffStart,
		int nextIfdPointer, boolean isLittleEndian, byte[] newThumb)
	{
		if (newThumb == null || newThumb.length == 0)
		{
			return data;
		}
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(data);

			int ifd1Off = data.length - tiffStart; // offset relative to TIFF header
			byte[] updated = out.toByteArray();
			ByteBufferUtils.writeU32(updated, nextIfdPointer, ifd1Off, isLittleEndian);

			out.reset();
			out.write(updated);
			out.write(buildFreshIfd1Header(ifd1Off, newThumb.length, isLittleEndian));
			out.write(newThumb);

			byte[] result = out.toByteArray();
			// newSegLen is the value written into the 2-byte length field, which per JPEG spec includes the
			// 2 length bytes themselves (so newSegLen == 2 + payload). Cap is 65535 — the maximum value
			// representable in the 2-byte field.
			int newSegLen = result.length - 2;
			if (newSegLen > APP1_MAX_SEGMENT_BYTES)
			{
				return data;
			}
			result[2] = (byte) ((newSegLen >> 8) & 0xFF);
			result[3] = (byte) (newSegLen & 0xFF);
			Log.d(TAG, "Created IFD1 with thumbnail: " + newThumb.length + " bytes");
			return result;
		}
		catch (Exception e)
		{
			Log.w(TAG, "Failed to create IFD1 for thumbnail", e);
			return data;
		}
	}

	/**
	 * Build the 42-byte IFD1 structure: count(2) + 3 entries(12 each) + next-IFD(4). Thumbnail data is expected to
	 * sit immediately after this structure in the assembled EXIF payload; thumbDataOff is computed as ifd1Off + the
	 * structure size.
	 */
	private static byte[] buildFreshIfd1Header(int ifd1Off, int thumbnailBytes, boolean isLittleEndian)
	{
		byte[] ifd1Buf = new byte[2 + 3 * 12 + 4]; // count + 3 entries + next IFD
		ByteBufferUtils.writeU16(ifd1Buf, 0, 3, isLittleEndian);

		// Tag 0x0103: Compression = 6 (JPEG)
		ByteBufferUtils.writeU16(ifd1Buf, 2, 0x0103, isLittleEndian);
		ByteBufferUtils.writeU16(ifd1Buf, 4, TiffTag.TYPE_SHORT, isLittleEndian);
		ByteBufferUtils.writeU32(ifd1Buf, 6, 1, isLittleEndian);
		ByteBufferUtils.writeU16(ifd1Buf, 10, 6, isLittleEndian); // JPEG compression

		// JPEGInterchangeFormat (offset to thumbnail bytes)
		int thumbDataOff = ifd1Off + ifd1Buf.length;
		ByteBufferUtils.writeU16(ifd1Buf, 14, TiffTag.JPEG_INTERCHANGE_FORMAT, isLittleEndian);
		ByteBufferUtils.writeU16(ifd1Buf, 16, TiffTag.TYPE_LONG, isLittleEndian);
		ByteBufferUtils.writeU32(ifd1Buf, 18, 1, isLittleEndian);
		ByteBufferUtils.writeU32(ifd1Buf, 22, thumbDataOff, isLittleEndian);

		// JPEGInterchangeFormatLength
		ByteBufferUtils.writeU16(ifd1Buf, 26, TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH, isLittleEndian);
		ByteBufferUtils.writeU16(ifd1Buf, 28, TiffTag.TYPE_LONG, isLittleEndian);
		ByteBufferUtils.writeU32(ifd1Buf, 30, 1, isLittleEndian);
		ByteBufferUtils.writeU32(ifd1Buf, 34, thumbnailBytes, isLittleEndian);

		// Next IFD = 0 (IFD1 is the last IFD)
		ByteBufferUtils.writeU32(ifd1Buf, 38, 0, isLittleEndian);
		return ifd1Buf;
	}

	/**
	 * IFD1 exists — locate its JPEGInterchangeFormat / Length tags, splice the new thumbnail bytes in place of the
	 * old ones, and update the length tag + the APP1 segment-length header. Returns unchanged `data` in four cases:
	 * the IFD1 offset falls outside the segment buffer; the thumbnail tags (0x0201 / 0x0202) are missing or record
	 * a zero offset; the recorded thumbnail offset / length falls outside the segment buffer; or the new thumbnail
	 * wouldn't fit under APP1_MAX_SEGMENT_BYTES (caller retries with a smaller thumbnail).
	 */
	private static byte[] spliceExistingThumbnail(byte[] data, int tiffStart, int ifd1,
		boolean isLittleEndian, byte[] newThumb)
	{
		if (ifd1 < tiffStart || ifd1 + 2 > data.length)
		{
			return data;
		}

		int[] thumbTags = findThumbnailTags(data, ifd1, isLittleEndian);
		if (thumbTags == null)
		{
			return data;
		}
		int thumbOffTag = thumbTags[0];
		int thumbLenTag = thumbTags[1];
		int oldThumbOff = thumbTags[2];
		int oldThumbLen = thumbTags[3];

		int absOldOff = tiffStart + oldThumbOff;
		if (absOldOff < 0 || absOldOff + oldThumbLen > data.length)
		{
			return data;
		}

		// Splice: [...before...][newThumb][...after (usually empty)...]
		int afterStart = absOldOff + oldThumbLen;
		int afterLen = data.length - afterStart;
		byte[] newData = new byte[absOldOff + newThumb.length + afterLen];
		System.arraycopy(data, 0, newData, 0, absOldOff);
		System.arraycopy(newThumb, 0, newData, absOldOff, newThumb.length);
		if (afterLen > 0)
		{
			System.arraycopy(data, afterStart, newData, absOldOff + newThumb.length, afterLen);
		}

		// newSegLen is the value written into the 2-byte length field, which per JPEG spec includes the 2
		// length bytes themselves (so newSegLen == 2 + payload). Cap is 65535 — the maximum value representable
		// in the 2-byte field.
		int newSegLen = newData.length - 2;
		if (newSegLen > APP1_MAX_SEGMENT_BYTES)
		{
			int overhead = newData.length - newThumb.length;
			int available = APP1_MAX_SEGMENT_BYTES - overhead;
			Log.w(TAG, "Thumbnail " + newThumb.length + " too large (avail=" + available
				+ "), caller should retry smaller");
			return data;
		}

		ByteBufferUtils.writeU32(newData, thumbLenTag + 8, newThumb.length, isLittleEndian);
		newData[2] = (byte) ((newSegLen >> 8) & 0xFF);
		newData[3] = (byte) (newSegLen & 0xFF);
		Log.d(TAG, "Thumbnail replaced: " + oldThumbLen + " → " + newThumb.length
			+ " bytes (APP1=" + newSegLen + ")");
		return newData;
	}

	/**
	 * Scan IFD1 for JPEGInterchangeFormat (0x0201) and JPEGInterchangeFormatLength (0x0202). Returns {thumbOffTag,
	 * thumbLenTag, oldThumbOff, oldThumbLen} or null when either tag is missing — also null when oldThumbOff is
	 * zero (IFD1 exists but no thumbnail is recorded).
	 */
	private static int[] findThumbnailTags(byte[] data, int ifd1, boolean isLittleEndian)
	{
		int ifd1EntryCount = ByteBufferUtils.readU16(data, ifd1, isLittleEndian);
		int thumbOffTag = -1;
		int thumbLenTag = -1;
		int oldThumbOff = 0;
		int oldThumbLen = 0;

		for (int i = 0; i < ifd1EntryCount; i++)
		{
			int entryOffset = ifd1 + 2 + i * 12;
			if (entryOffset + 12 > data.length)
			{
				break;
			}
			int tag = ByteBufferUtils.readU16(data, entryOffset, isLittleEndian);
			if (tag == TiffTag.JPEG_INTERCHANGE_FORMAT)
			{
				thumbOffTag = entryOffset;
				oldThumbOff = (int) ByteBufferUtils.readU32(data, entryOffset + 8, isLittleEndian);
			}
			else if (tag == TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH)
			{
				thumbLenTag = entryOffset;
				oldThumbLen = (int) ByteBufferUtils.readU32(data, entryOffset + 8, isLittleEndian);
			}
		}
		if (thumbOffTag < 0 || thumbLenTag < 0 || oldThumbOff == 0)
		{
			return null;
		}
		return new int[] { thumbOffTag, thumbLenTag, oldThumbOff, oldThumbLen };
	}

	private static void scanIfd(byte[] data, int ifdOff, int tiffStart, boolean isLittleEndian,
		int newW, int newH, int orientation, int depth)
	{
		// Cycle / depth guard. Real-world EXIF has a single ExifSubIFD pointer (0x8769) from IFD0 → ExifSubIFD;
		// corrupted or adversarial files can chain pointers in cycles or deeply nested fan-out, both of which
		// would unbounded-recurse and throw StackOverflowError. Error is uncatchable in our normal Exception
		// catches, so the bg thread crashes and the whole save/apply pipeline dies silently. Cap at 4 levels —
		// well above the legitimate IFD0 → ExifSubIFD → InteropIFD chain.
		if (depth > 4 || ifdOff < 0 || ifdOff + 2 > data.length)
		{
			return;
		}
		int entryCount = ByteBufferUtils.readU16(data, ifdOff, isLittleEndian);

		for (int i = 0; i < entryCount; i++)
		{
			int entryOffset = ifdOff + 2 + i * 12;
			if (entryOffset + 12 > data.length)
			{
				break;
			}
			int tag = ByteBufferUtils.readU16(data, entryOffset, isLittleEndian);
			int type = ByteBufferUtils.readU16(data, entryOffset + 2, isLittleEndian);

			int valueOff = entryOffset + 8;
			switch (tag)
			{
				case TiffTag.ORIENTATION ->
					ByteBufferUtils.writeU16(data, valueOff, orientation, isLittleEndian);
				case TiffTag.IMAGE_WIDTH ->
					writeValue(data, valueOff, type, isLittleEndian, newW);
				case TiffTag.IMAGE_LENGTH ->
					writeValue(data, valueOff, type, isLittleEndian, newH);
				case TiffTag.PIXEL_X_DIMENSION ->
					writeValue(data, valueOff, type, isLittleEndian, newW);
				case TiffTag.PIXEL_Y_DIMENSION ->
					writeValue(data, valueOff, type, isLittleEndian, newH);
				case TiffTag.EXIF_SUB_IFD ->
				{
					long off = ByteBufferUtils.readU32(data, entryOffset + 8, isLittleEndian);
					// `off` is u32 (range 0..2^32-1) read into a long, so the addition `tiffStart +
					// off` can exceed Integer.MAX_VALUE on adversarial EXIF. Validate the
					// long-arithmetic result FIRST and only cast to int after we know it fits —
					// without this guard, a malicious off like 0xFFFFFFFF would cast to a
					// small-positive int that lands inside the buffer (e.g. (10 + 0xFFFFFFFFL)
					// truncates to 9), which would re-enter scanIfd reading EXIF header bytes as
					// IFD entries and writing orientation=1 over arbitrary data.
					long absSubIfd = tiffStart + off;
					// Require absSubIfd >= tiffStart so a sub-tiffStart pointer (e.g., 4) can't
					// recurse into the EXIF/TIFF header bytes — otherwise scanIfd would treat
					// header bytes as IFD entries and write orientation = 1 over the byte-order
					// marker / magic, corrupting the EXIF segment. Matches the IFD0 guard in
					// patch() and replaceThumbnail.
					if (off >= 0 && absSubIfd >= tiffStart && absSubIfd + 2 <= data.length)
					{
						scanIfd(data, (int) absSubIfd, tiffStart, isLittleEndian,
							newW, newH, orientation, depth + 1);
					}
				}
				// other tags left unchanged
				default -> {}
			}
		}

		// Don't follow the next-IFD link (IFD0 → IFD1). IFD1 is the thumbnail IFD — its dimension/orientation
		// tags describe the thumbnail, not the primary. replaceThumbnail() handles the thumbnail data
		// separately.
	}

	private static void writeValue(byte[] data, int off, int type, boolean isLittleEndian, int value)
	{
		if (type == TiffTag.TYPE_SHORT)
		{
			ByteBufferUtils.writeU16(data, off, value, isLittleEndian);
		}
		else
		{
			ByteBufferUtils.writeU32(data, off, value, isLittleEndian);
		}
	}
}
