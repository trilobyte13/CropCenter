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
			int cnt0 = ByteBufferUtils.readU16(data, ifd0, isLittleEndian);
			int nextPtr = ifd0 + 2 + cnt0 * 12;
			if (nextPtr + 4 > data.length)
			{
				return DEFAULT_THUMB_BUDGET;
			}
			long ifd1Rel = ByteBufferUtils.readU32(data, nextPtr, isLittleEndian);

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
			int cnt1 = ByteBufferUtils.readU16(data, ifd1, isLittleEndian);
			int oldThumbLen = 0;
			for (int i = 0; i < cnt1; i++)
			{
				int entryOff = ifd1 + 2 + i * 12;
				if (entryOff + 12 > data.length)
				{
					break;
				}
				int tag = ByteBufferUtils.readU16(data, entryOff, isLittleEndian);
				if (tag == 0x0202) // JPEGInterchangeFormatLength
				{
					oldThumbLen = (int) ByteBufferUtils.readU32(data, entryOff + 8, isLittleEndian);
					break;
				}
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

			scanIFD(data, ifdOff, TIFF_HEADER_OFFSET, isLittleEndian, newW, newH, orientation);
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
			// Find IFD0
			long ifd0Rel = ByteBufferUtils.readU32(data, tiffStart + 4, isLittleEndian);
			int ifd0 = (int) (tiffStart + ifd0Rel);
			if (ifd0 < tiffStart || ifd0 + 2 > data.length)
			{
				return data;
			}
			int cnt0 = ByteBufferUtils.readU16(data, ifd0, isLittleEndian);

			// Find IFD1 (next IFD pointer after IFD0)
			int nextPtr = ifd0 + 2 + cnt0 * 12;
			if (nextPtr + 4 > data.length)
			{
				return data;
			}
			long ifd1Rel = ByteBufferUtils.readU32(data, nextPtr, isLittleEndian);
			if (ifd1Rel == 0)
			{
				// No IFD1 — append thumbnail at end of EXIF data.
				// Create minimal IFD1 with JPEGInterchangeFormat and JPEGInterchangeFormatLength.
				if (newThumb == null || newThumb.length == 0)
				{
					return data;
				}
				try
				{
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					out.write(data);

					// IFD1 will be at current end of TIFF data
					int ifd1Off = data.length - tiffStart; // offset relative to TIFF header
					// Write IFD1 pointer at end of IFD0
					byte[] updated = out.toByteArray();
					ByteBufferUtils.writeU32(updated, nextPtr, ifd1Off, isLittleEndian);

					out.reset();
					out.write(updated);

					// IFD1: count=3 entries
					// (Compression, JPEGInterchangeFormat, JPEGInterchangeFormatLength)
					byte[] ifd1Buf = new byte[2 + 3 * 12 + 4]; // count + 3 entries + next IFD
					ByteBufferUtils.writeU16(ifd1Buf, 0, 3, isLittleEndian);
					// Tag 0x0103: Compression = 6 (JPEG)
					ByteBufferUtils.writeU16(ifd1Buf, 2, 0x0103, isLittleEndian);
					ByteBufferUtils.writeU16(ifd1Buf, 4, 3, isLittleEndian); // SHORT
					ByteBufferUtils.writeU32(ifd1Buf, 6, 1, isLittleEndian);
					ByteBufferUtils.writeU16(ifd1Buf, 10, 6, isLittleEndian); // JPEG compression
					// Tag 0x0201: JPEGInterchangeFormat
					int thumbDataOff = ifd1Off + ifd1Buf.length; // thumb starts right after IFD1
					ByteBufferUtils.writeU16(ifd1Buf, 14, 0x0201, isLittleEndian);
					ByteBufferUtils.writeU16(ifd1Buf, 16, 4, isLittleEndian); // LONG
					ByteBufferUtils.writeU32(ifd1Buf, 18, 1, isLittleEndian);
					ByteBufferUtils.writeU32(ifd1Buf, 22, thumbDataOff, isLittleEndian);
					// Tag 0x0202: JPEGInterchangeFormatLength
					ByteBufferUtils.writeU16(ifd1Buf, 26, 0x0202, isLittleEndian);
					ByteBufferUtils.writeU16(ifd1Buf, 28, 4, isLittleEndian); // LONG
					ByteBufferUtils.writeU32(ifd1Buf, 30, 1, isLittleEndian);
					ByteBufferUtils.writeU32(ifd1Buf, 34, newThumb.length, isLittleEndian);
					// Next IFD = 0
					ByteBufferUtils.writeU32(ifd1Buf, 38, 0, isLittleEndian);
					out.write(ifd1Buf);
					out.write(newThumb);

					byte[] result = out.toByteArray();
					// Update APP1 segment length
					int newSegLen = result.length - 2;
					if (newSegLen <= APP1_MAX_PAYLOAD_BYTES)
					{
						result[2] = (byte) ((newSegLen >> 8) & 0xFF);
						result[3] = (byte) (newSegLen & 0xFF);
						Log.d(TAG,
							"Created IFD1 with thumbnail: " + newThumb.length + " bytes");
						return result;
					}
				}
				catch (Exception e)
				{
					Log.w(TAG, "Failed to create IFD1 for thumbnail", e);
				}
				return data;
			}
			int ifd1 = (int) (tiffStart + ifd1Rel);
			if (ifd1 < tiffStart || ifd1 + 2 > data.length)
			{
				return data;
			}

			int cnt1 = ByteBufferUtils.readU16(data, ifd1, isLittleEndian);
			int thumbOffTag = -1;
			int thumbLenTag = -1;
			int oldThumbOff = 0;
			int oldThumbLen = 0;

			for (int i = 0; i < cnt1; i++)
			{
				int entryOff = ifd1 + 2 + i * 12;
				if (entryOff + 12 > data.length)
				{
					break;
				}
				int tag = ByteBufferUtils.readU16(data, entryOff, isLittleEndian);
				if (tag == 0x0201) // JPEGInterchangeFormat
				{
					thumbOffTag = entryOff;
					oldThumbOff = (int) ByteBufferUtils.readU32(data, entryOff + 8, isLittleEndian);
				}
				else if (tag == 0x0202) // JPEGInterchangeFormatLength
				{
					thumbLenTag = entryOff;
					oldThumbLen = (int) ByteBufferUtils.readU32(data, entryOff + 8, isLittleEndian);
				}
			}

			if (thumbOffTag < 0 || thumbLenTag < 0 || oldThumbOff == 0)
			{
				return data;
			}

			// Absolute offset of old thumbnail in the segment data
			int absOldOff = tiffStart + oldThumbOff;
			if (absOldOff < 0 || absOldOff + oldThumbLen > data.length)
			{
				return data;
			}

			// Splice new thumbnail into the segment; trailing data is usually empty since
			// thumbnails live at the end of the EXIF payload.
			byte[] before = new byte[absOldOff];
			System.arraycopy(data, 0, before, 0, absOldOff);

			int afterStart = absOldOff + oldThumbLen;
			int afterLen = data.length - afterStart;
			byte[] after = (afterLen > 0) ? new byte[afterLen] : new byte[0];
			if (afterLen > 0)
			{
				System.arraycopy(data, afterStart, after, 0, afterLen);
			}

			// Combine
			byte[] newData = new byte[before.length + newThumb.length + after.length];
			System.arraycopy(before, 0, newData, 0, before.length);
			System.arraycopy(newThumb, 0, newData, before.length, newThumb.length);
			if (afterLen > 0)
			{
				System.arraycopy(after, 0, newData, before.length + newThumb.length, afterLen);
			}

			// Check APP1 size limit
			int newSegLen = newData.length - 2;
			if (newSegLen > APP1_MAX_PAYLOAD_BYTES)
			{
				// Thumbnail doesn't fit — report how many bytes are available so the caller can
				// regenerate at a smaller size.
				int overhead = newData.length - newThumb.length; // EXIF without thumbnail
				int available = APP1_MAX_SEGMENT_BYTES - overhead;
				Log.w(TAG, "Thumbnail " + newThumb.length + " too large (avail=" + available
					+ "), caller should retry smaller");
				return data; // return unchanged — caller checks and retries
			}

			ByteBufferUtils.writeU32(newData, thumbLenTag + 8, newThumb.length, isLittleEndian);

			// Update APP1 segment length (bytes 2-3)
			newData[2] = (byte) ((newSegLen >> 8) & 0xFF);
			newData[3] = (byte) (newSegLen & 0xFF);

			Log.d(TAG, "Thumbnail replaced: " + oldThumbLen + " → " + newThumb.length
				+ " bytes (APP1=" + newSegLen + ")");
			return newData;
		}
		catch (Exception e)
		{
			Log.w(TAG, "Thumbnail replacement failed", e);
			return data;
		}
	}

	private static void scanIFD(byte[] data, int ifdOff, int tiffStart, boolean isLittleEndian,
		int newW, int newH, int orientation)
	{
		if (ifdOff < 0 || ifdOff + 2 > data.length)
		{
			return;
		}
		int cnt = ByteBufferUtils.readU16(data, ifdOff, isLittleEndian);

		for (int i = 0; i < cnt; i++)
		{
			int entryOff = ifdOff + 2 + i * 12;
			if (entryOff + 12 > data.length)
			{
				break;
			}
			int tag = ByteBufferUtils.readU16(data, entryOff, isLittleEndian);
			int type = ByteBufferUtils.readU16(data, entryOff + 2, isLittleEndian);

			switch (tag)
			{
				case 0x0112 ->
					ByteBufferUtils.writeU16(data, entryOff + 8, orientation, isLittleEndian);
				case 0x0100 -> writeValue(data, entryOff + 8, type, isLittleEndian, newW);
				case 0x0101 -> writeValue(data, entryOff + 8, type, isLittleEndian, newH);
				case 0xA002 -> writeValue(data, entryOff + 8, type, isLittleEndian, newW);
				case 0xA003 -> writeValue(data, entryOff + 8, type, isLittleEndian, newH);
				case 0x8769 ->
				{
					long off = ByteBufferUtils.readU32(data, entryOff + 8, isLittleEndian);
					int subIFD = (int) (tiffStart + off);
					if (subIFD > 0 && subIFD < data.length)
					{
						scanIFD(data, subIFD, tiffStart, isLittleEndian,
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
