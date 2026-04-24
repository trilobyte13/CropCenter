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
	// APP1 segment size constraints (per JPEG spec): the 2-byte length field caps total segment
	// bytes at 65535 and payload (segment minus the 2-byte length prefix) at 65533.
	private static final int APP1_MAX_SEGMENT_BYTES = 65535;
	private static final int APP1_MAX_PAYLOAD_BYTES = 65533;
	private static final int DEFAULT_THUMB_BUDGET = 20_000; // used when we can't measure the segment
	private static final int IFD1_ESTIMATED_OVERHEAD = 42; // rough bytes for the IFD1 header we'd add
	private static final int TIFF_HEADER_OFFSET = 10; // bytes from start of APP1 data to the TIFF header

	private ExifPatcher() {}

	/**
	 * Estimate max thumbnail bytes that will fit in the EXIF APP1 segment. Measures the EXIF
	 * size excluding the existing thumbnail, then returns the remaining space within the
	 * 65535-byte APP1 limit.
	 */
	public static int maxThumbnailBytes(List<JpegSegment> segments)
	{
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
			boolean isLittleEndian = data[TIFF_HEADER_OFFSET] == 0x49;

			// Find IFD1 to locate existing thumbnail
			long ifd0Rel = ByteBufferUtils.readU32(data, TIFF_HEADER_OFFSET + 4, isLittleEndian);
			int ifd0 = (int) (TIFF_HEADER_OFFSET + ifd0Rel);
			if (ifd0 < TIFF_HEADER_OFFSET || ifd0 + 2 > data.length)
			{
				return DEFAULT_THUMB_BUDGET; // can't parse, use default
			}
			int ifd0EntryCount = ByteBufferUtils.readU16(data, ifd0, isLittleEndian);
			int nextIfdPointer = ifd0 + 2 + ifd0EntryCount * 12;
			if (nextIfdPointer + 4 > data.length)
			{
				return DEFAULT_THUMB_BUDGET;
			}
			long ifd1Rel = ByteBufferUtils.readU32(data, nextIfdPointer, isLittleEndian);

			if (ifd1Rel == 0)
			{
				// No IFD1: EXIF overhead = current segment + new IFD1 header we'd add.
				// Clamp at 0 — if the current segment alone nearly fills the APP1 budget,
				// there's no room for a thumbnail and we should say so honestly rather than
				// return a negative that relies on the caller to clamp.
				return Math.max(0, APP1_MAX_SEGMENT_BYTES - (data.length + IFD1_ESTIMATED_OVERHEAD));
			}

			int ifd1 = (int) (TIFF_HEADER_OFFSET + ifd1Rel);
			if (ifd1 < TIFF_HEADER_OFFSET || ifd1 + 2 > data.length)
			{
				return DEFAULT_THUMB_BUDGET;
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
				if (tag == 0x0202) // JPEGInterchangeFormatLength
				{
					oldThumbLen = (int) ByteBufferUtils.readU32(
						data, entryOffset + 8, isLittleEndian);
					break;
				}
			}
			// Sanity-clamp: corrupt EXIF can report a thumbnail length beyond the segment
			// itself (or negative after the u32→int cast). Either case produces a negative
			// exifOverhead which inflates the returned budget above APP1_MAX_SEGMENT_BYTES —
			// downstream writers then overflow the 65535-byte APP1 cap. Clamp to [0, data.length].
			if (oldThumbLen < 0 || oldThumbLen > data.length)
			{
				oldThumbLen = 0;
			}
			// Available = APP1_MAX_SEGMENT_BYTES - (current segment size - old thumbnail size)
			int exifOverhead = data.length - oldThumbLen;
			return Math.max(0, APP1_MAX_SEGMENT_BYTES - exifOverhead);
		}
		return DEFAULT_THUMB_BUDGET; // no EXIF segment found, use default
	}

	/**
	 * Patch the EXIF dimensions to newW×newH, normalise orientation to 1
	 * (upright — we bake rotation into the primary JPEG), and optionally replace the thumbnail.
	 *
	 * @param thumbnail new JPEG thumbnail bytes, or null to keep original
	 */
	public static List<JpegSegment> patch(List<JpegSegment> segments, int newW, int newH,
		byte[] thumbnail)
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
			boolean isLittleEndian = data[TIFF_HEADER_OFFSET] == 0x49;

			long ifdOffRel = ByteBufferUtils.readU32(data, TIFF_HEADER_OFFSET + 4, isLittleEndian);
			int ifdOff = (int) (TIFF_HEADER_OFFSET + ifdOffRel);
			if (ifdOff < TIFF_HEADER_OFFSET || ifdOff + 2 > data.length)
			{
				result.add(new JpegSegment(seg.marker(), data));
				continue;
			}

			scanIfd(data, ifdOff, TIFF_HEADER_OFFSET, isLittleEndian, newW, newH, orientation);
			if (thumbnail != null)
			{
				data = replaceThumbnail(data, TIFF_HEADER_OFFSET, isLittleEndian, thumbnail);
			}

			result.add(new JpegSegment(seg.marker(), data));
		}
		return result;
	}

	/**
	 * Replace the EXIF thumbnail JPEG. Rebuilds the APP1 segment with new thumbnail bytes.
	 * Finds IFD1's JPEGInterchangeFormat/Length, replaces the old thumbnail data, and updates
	 * the segment length and tag values.
	 */
	private static byte[] replaceThumbnail(byte[] data, int tiffStart, boolean isLittleEndian, byte[] newThumb)
	{
		try
		{
			long ifd0Rel = ByteBufferUtils.readU32(data, tiffStart + 4, isLittleEndian);
			int ifd0 = (int) (tiffStart + ifd0Rel);
			if (ifd0 < tiffStart || ifd0 + 2 > data.length)
			{
				return data;
			}
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
			return spliceExistingThumbnail(data, tiffStart, (int) (tiffStart + ifd1Rel),
				isLittleEndian, newThumb);
		}
		catch (Exception e)
		{
			Log.w(TAG, "Thumbnail replacement failed", e);
			return data;
		}
	}

	/**
	 * IFD1 does not exist — append a minimal one (3 entries: Compression,
	 * JPEGInterchangeFormat, JPEGInterchangeFormatLength) plus the thumbnail bytes at
	 * the end of the EXIF payload, updating IFD0's next-IFD pointer and the APP1
	 * segment length. No-op return (unchanged `data`) when the thumbnail is absent or
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
			int newSegLen = result.length - 2;
			if (newSegLen > APP1_MAX_PAYLOAD_BYTES)
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
	 * Build the 42-byte IFD1 structure: count(2) + 3 entries(12 each) + next-IFD(4).
	 * Thumbnail data is expected to sit immediately after this structure in the
	 * assembled EXIF payload; thumbDataOff is computed as ifd1Off + the structure size.
	 */
	private static byte[] buildFreshIfd1Header(int ifd1Off, int thumbnailBytes,
		boolean isLittleEndian)
	{
		byte[] ifd1Buf = new byte[2 + 3 * 12 + 4]; // count + 3 entries + next IFD
		ByteBufferUtils.writeU16(ifd1Buf, 0, 3, isLittleEndian);

		// Tag 0x0103: Compression = 6 (JPEG)
		ByteBufferUtils.writeU16(ifd1Buf, 2, 0x0103, isLittleEndian);
		ByteBufferUtils.writeU16(ifd1Buf, 4, 3, isLittleEndian); // SHORT
		ByteBufferUtils.writeU32(ifd1Buf, 6, 1, isLittleEndian);
		ByteBufferUtils.writeU16(ifd1Buf, 10, 6, isLittleEndian); // JPEG compression

		// Tag 0x0201: JPEGInterchangeFormat (offset to thumbnail bytes)
		int thumbDataOff = ifd1Off + ifd1Buf.length;
		ByteBufferUtils.writeU16(ifd1Buf, 14, 0x0201, isLittleEndian);
		ByteBufferUtils.writeU16(ifd1Buf, 16, 4, isLittleEndian); // LONG
		ByteBufferUtils.writeU32(ifd1Buf, 18, 1, isLittleEndian);
		ByteBufferUtils.writeU32(ifd1Buf, 22, thumbDataOff, isLittleEndian);

		// Tag 0x0202: JPEGInterchangeFormatLength
		ByteBufferUtils.writeU16(ifd1Buf, 26, 0x0202, isLittleEndian);
		ByteBufferUtils.writeU16(ifd1Buf, 28, 4, isLittleEndian); // LONG
		ByteBufferUtils.writeU32(ifd1Buf, 30, 1, isLittleEndian);
		ByteBufferUtils.writeU32(ifd1Buf, 34, thumbnailBytes, isLittleEndian);

		// Next IFD = 0 (IFD1 is the last IFD)
		ByteBufferUtils.writeU32(ifd1Buf, 38, 0, isLittleEndian);
		return ifd1Buf;
	}

	/**
	 * IFD1 exists — locate its JPEGInterchangeFormat / Length tags, splice the new
	 * thumbnail bytes in place of the old ones, and update the length tag + the APP1
	 * segment-length header. Returns unchanged `data` in four cases: the IFD1 offset
	 * falls outside the segment buffer; the thumbnail tags (0x0201 / 0x0202) are
	 * missing or record a zero offset; the recorded thumbnail offset / length falls
	 * outside the segment buffer; or the new thumbnail wouldn't fit under
	 * APP1_MAX_PAYLOAD_BYTES (caller retries with a smaller thumbnail).
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

		int newSegLen = newData.length - 2;
		if (newSegLen > APP1_MAX_PAYLOAD_BYTES)
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
	 * Scan IFD1 for JPEGInterchangeFormat (0x0201) and JPEGInterchangeFormatLength
	 * (0x0202). Returns {thumbOffTag, thumbLenTag, oldThumbOff, oldThumbLen} or null
	 * when either tag is missing — also null when oldThumbOff is zero (IFD1 exists
	 * but no thumbnail is recorded).
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
			if (tag == 0x0201) // JPEGInterchangeFormat
			{
				thumbOffTag = entryOffset;
				oldThumbOff = (int) ByteBufferUtils.readU32(
					data, entryOffset + 8, isLittleEndian);
			}
			else if (tag == 0x0202) // JPEGInterchangeFormatLength
			{
				thumbLenTag = entryOffset;
				oldThumbLen = (int) ByteBufferUtils.readU32(
					data, entryOffset + 8, isLittleEndian);
			}
		}
		if (thumbOffTag < 0 || thumbLenTag < 0 || oldThumbOff == 0)
		{
			return null;
		}
		return new int[] { thumbOffTag, thumbLenTag, oldThumbOff, oldThumbLen };
	}

	private static void scanIfd(byte[] data, int ifdOff, int tiffStart, boolean isLittleEndian,
		int newW, int newH, int orientation)
	{
		if (ifdOff < 0 || ifdOff + 2 > data.length)
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

			switch (tag)
			{
				case 0x0112 ->
					ByteBufferUtils.writeU16(data, entryOffset + 8, orientation, isLittleEndian);
				case 0x0100 -> writeValue(data, entryOffset + 8, type, isLittleEndian, newW);
				case 0x0101 -> writeValue(data, entryOffset + 8, type, isLittleEndian, newH);
				case 0xA002 -> writeValue(data, entryOffset + 8, type, isLittleEndian, newW);
				case 0xA003 -> writeValue(data, entryOffset + 8, type, isLittleEndian, newH);
				case 0x8769 ->
				{
					long off = ByteBufferUtils.readU32(data, entryOffset + 8, isLittleEndian);
					int subIfd = (int) (tiffStart + off);
					if (subIfd > 0 && subIfd < data.length)
					{
						scanIfd(data, subIfd, tiffStart, isLittleEndian,
							newW, newH, orientation);
					}
				}
				// other tags left unchanged
				default -> {}
			}
		}

		// Don't follow the next-IFD link (IFD0 → IFD1).
		// IFD1 is the thumbnail IFD — its dimension/orientation tags describe the thumbnail,
		// not the primary. replaceThumbnail() handles the thumbnail data separately.
	}

	private static void writeValue(byte[] data, int off, int type, boolean isLittleEndian, int value)
	{
		if (type == 3)
		{
			ByteBufferUtils.writeU16(data, off, value, isLittleEndian);
		}
		else
		{
			ByteBufferUtils.writeU32(data, off, value, isLittleEndian);
		}
	}
}
