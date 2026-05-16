package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts PNG metadata chunks of interest. Currently handles the eXIf chunk (PNG 1.6 spec) which holds raw TIFF
 * data — converted to a synthetic EXIF APP1 JpegSegment so the export path's existing inject pipeline (which keys
 * on JpegSegment.isExif) can round-trip it without a separate code path.
 *
 * Other PNG ancillary chunks (iCCP for ICC profile, iTXt / tEXt / zTXt for textual metadata) are not parsed
 * because (a) the editor doesn't model them and (b) eXIf alone covers the common cases — orientation, GPS,
 * MakerNote — for a PNG that came from a phone-camera or photo-editor.
 *
 * PNG byte structure:
 *   - 8-byte file signature
 *   - chunks, each: length(4 BE) + type(4) + data(length) + CRC(4)
 *
 * The chunk type 'eXIf' (0x65 0x58 0x49 0x66) — lowercase 'e' marks it ancillary, uppercase 'X' marks it public,
 * uppercase 'I' is the reserved bit, lowercase 'f' marks it safe-to-copy across edits.
 */
public final class PngMetadataExtractor
{
	private static final String TAG = "PngMetadataExtractor";

	// 4-byte ASCII "eXIf" chunk-type bytes. Lowercase 'e' marks ancillary, uppercase 'X' marks public,
	// uppercase 'I' is the reserved bit, lowercase 'f' marks safe-to-copy across edits. Shared by
	// `findExifChunk` (the byte-by-byte walk) and `CropExporter.injectPngExifFromTiff` (the chunk writer)
	// so the chunk-type literal lives in one place rather than as parallel inline byte sequences in both
	// files.
	public static final byte[] EXIF_CHUNK_TYPE = { 'e', 'X', 'I', 'f' };

	// Canonical 8-byte PNG file signature (per ISO/IEC 15948 / W3C PNG spec). Centralised here so callers that
	// need to detect PNG bytes — `ImageLoadController.isPngSignature` for format dispatch, the chunk walkers
	// below for header validation — share one constant rather than re-declaring the same 8 hex/ASCII literals.
	public static final byte[] PNG_SIGNATURE = {
		(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
	};

	// Maximum TIFF payload that fits in a JPEG APP1 segment: subtract the 2 length bytes themselves and the
	// 6-byte "Exif\0\0" identifier from the spec-max segment length (JpegSegment.MAX_SEGMENT_BYTES = 65535).
	// Routing through the shared constant rather than inlining 65535 keeps the cap in lockstep with the JPEG
	// writers (ExifPatcher / XmpItemLengthPatcher) that share it.
	private static final int APP1_MAX_TIFF_PAYLOAD = JpegSegment.MAX_SEGMENT_BYTES - 2 - 6;

	private PngMetadataExtractor() {}

	/**
	 * Walk a PNG file's chunks and return any metadata segments worth preserving. Currently returns at most one
	 * entry — a synthetic APP1 EXIF segment built from the eXIf chunk's TIFF payload, wrapped in the canonical
	 * `FF E1 LL LL "Exif\0\0" [TIFF...]` layout that JpegSegment.isExif and CropExporter.injectPngExif both
	 * already understand.
	 *
	 * Returns an empty list when the input isn't a valid PNG, when no eXIf chunk is present, or when a chunk
	 * fails structural validation (length past EOF, length sign-overflow), or when the eXIf payload exceeds the
	 * JPEG APP1 u16 cap (would corrupt PNG → JPEG conversion). Use extractRawTiff to get the TIFF bytes
	 * unconditionally for the PNG → PNG round-trip path. Multiple eXIf chunks are not supported — the first
	 * match wins (PNG spec says SHOULD be at most one).
	 *
	 * @param png raw PNG file bytes
	 * @return at-most-one-element list with the synthetic APP1 EXIF segment, or empty
	 */
	public static List<JpegSegment> extract(byte[] png)
	{
		List<JpegSegment> segments = new ArrayList<>();
		int[] chunk = findExifChunk(png);
		if (chunk == null)
		{
			return segments;
		}
		int dataOff = chunk[0];
		int length = chunk[1];
		// segLen is the APP1 segment-length field per JPEG spec — includes the 2 length bytes themselves PLUS
		// the 6-byte "Exif\0\0" identifier PLUS the TIFF payload. Capped at u16 (65535). PNG eXIf chunks have
		// no such cap (length field is u31) so a PNG with > 64KB EXIF can't round-trip via a single APP1.
		// Skip the synthetic-APP1 emission rather than write a segment with a truncated length field that
		// would corrupt PNG → JPEG conversion. The PNG → PNG path uses extractRawTiff, which has no cap.
		if (length > APP1_MAX_TIFF_PAYLOAD)
		{
			Log.w(TAG, "eXIf chunk too large for JPEG APP1 ("
				+ length + " bytes > " + APP1_MAX_TIFF_PAYLOAD
				+ " cap); skipping synthetic-APP1 emission. PNG → PNG round-trip remains intact via "
				+ "extractRawTiff.");
			return segments;
		}
		int segLen = 2 + 6 + length;
		int totalSegBytes = 2 + segLen; // FF E1 + segLen + payload
		byte[] exifSegBytes = new byte[totalSegBytes];
		exifSegBytes[0] = (byte) 0xFF;
		exifSegBytes[1] = (byte) 0xE1;
		exifSegBytes[2] = (byte) ((segLen >> 8) & 0xFF);
		exifSegBytes[3] = (byte) (segLen & 0xFF);
		exifSegBytes[4] = 'E';
		exifSegBytes[5] = 'x';
		exifSegBytes[6] = 'i';
		exifSegBytes[7] = 'f';
		exifSegBytes[8] = 0;
		exifSegBytes[9] = 0;
		System.arraycopy(png, dataOff, exifSegBytes, 10, length);
		segments.add(new JpegSegment(JpegMarker.APP1, exifSegBytes));
		Log.d(TAG, "Extracted eXIf chunk: " + length + " bytes TIFF data");
		return segments;
	}

	/**
	 * Read EXIF orientation (1..8) from a PNG eXIf chunk's TIFF orientation tag (TiffTag.ORIENTATION). PNG
	 * sources need this to apply orientation rotation to pixels at load time — without it, a PNG with eXIf
	 * orientation=6 (rotate 90 CW) would be displayed in stored orientation while the export side normalises
	 * orientation to 1, baking a permanent sideways rotation into the saved file.
	 *
	 * Returns 1 (upright) when the input isn't a valid PNG, has no eXIf chunk, has malformed TIFF byte-order
	 * bytes, or has no Orientation tag. Mirrors the BitmapUtils.readExifOrientation contract for JPEG.
	 *
	 * @param png raw PNG file bytes
	 * @return orientation 1..8, or 1 when not present / malformed
	 */
	public static int extractOrientation(byte[] png)
	{
		try
		{
			return extractOrientationInternal(png);
		}
		catch (IndexOutOfBoundsException ignored)
		{
			// Mirrors BitmapUtils.readExifOrientation — malformed TIFF (truncated, lying offsets) maps
			// to the same upright fallback as a missing tag.
			return 1;
		}
	}

	/**
	 * Raw TIFF data from the PNG's eXIf chunk, without any APP1 wrapping or size cap. Used by the PNG → PNG
	 * export path which writes a fresh eXIf chunk back into the cropped PNG — no JPEG u16 limit applies, so a
	 * 200KB EXIF block (camera with extensive MakerNote / GPS metadata) round-trips fully. Returns null when
	 * the input isn't a valid PNG or has no eXIf chunk.
	 *
	 * @param png raw PNG file bytes
	 * @return raw TIFF bytes copied out of the eXIf chunk, or null when absent
	 */
	public static byte[] extractRawTiff(byte[] png)
	{
		int[] chunk = findExifChunk(png);
		if (chunk == null)
		{
			return null;
		}
		int dataOff = chunk[0];
		int length = chunk[1];
		byte[] tiff = new byte[length];
		System.arraycopy(png, dataOff, tiff, 0, length);
		return tiff;
	}

	private static int extractOrientationInternal(byte[] png)
	{
		int[] chunk = findExifChunk(png);
		if (chunk == null)
		{
			return 1;
		}
		int tiffStart = chunk[0];
		int tiffLen = chunk[1];
		// Need at least the 8-byte TIFF header (byte-order + magic + IFD0 offset).
		if (tiffLen < 8)
		{
			return 1;
		}
		int byteOrderHi = png[tiffStart] & 0xFF;
		int byteOrderLo = png[tiffStart + 1] & 0xFF;
		boolean isLittleEndian;
		if (byteOrderHi == 0x49 && byteOrderLo == 0x49)
		{
			isLittleEndian = true;
		}
		else if (byteOrderHi == 0x4D && byteOrderLo == 0x4D)
		{
			isLittleEndian = false;
		}
		else
		{
			return 1;
		}
		// TIFF magic = 42 (0x002A). A coincidental II/MM byte-order match without the magic value means the
		// chunk isn't actually TIFF — refuse to read further so a malformed eXIf payload can't make us return
		// a non-1 orientation from random bytes.
		int tiffMagic = ByteBufferUtils.readU16(png, tiffStart + 2, isLittleEndian);
		if (tiffMagic != TiffTag.MAGIC)
		{
			return 1;
		}
		long ifd0Rel = ByteBufferUtils.readU32(png, tiffStart + 4, isLittleEndian);
		long ifd0AbsLong = (long) tiffStart + ifd0Rel;
		// Bounds: the IFD entry-count u16 plus at least one 12-byte entry must fit inside the chunk.
		if (ifd0AbsLong < tiffStart || ifd0AbsLong + 2 > (long) tiffStart + tiffLen)
		{
			return 1;
		}
		int ifd0 = (int) ifd0AbsLong;
		int entryCount = ByteBufferUtils.readU16(png, ifd0, isLittleEndian);
		long tiffEndLong = (long) tiffStart + tiffLen;
		for (int i = 0; i < entryCount; i++)
		{
			// Long-arithmetic stride matches the ExifPatcher hardening; reachable on the
			// uncapped PNG eXIf path where tiffLen can be u31 (~2 GB) and ifd0 can be near MAX_INT.
			// Without this, the int stride would wrap and the bound check evaluate wrap-negative ≯
			// positive, silently falling back to orientation = 1 instead of reading the real entry.
			long entryLong = (long) ifd0 + 2 + (long) i * 12;
			if (entryLong + 12 > tiffEndLong)
			{
				break;
			}
			int entry = (int) entryLong;
			int tag = ByteBufferUtils.readU16(png, entry, isLittleEndian);
			if (tag == TiffTag.ORIENTATION)
			{
				// Orientation must be type SHORT (3), count 1, value 1..8. Any other shape is malformed
				// — a coincidental TiffTag.ORIENTATION entry with the wrong type or count would let us
				// read random bytes as orientation. Real EXIF always emits this entry as SHORT/1.
				int entryType = ByteBufferUtils.readU16(png, entry + 2, isLittleEndian);
				long thisEntryCount = ByteBufferUtils.readU32(png, entry + 4, isLittleEndian);
				if (entryType != TiffTag.TYPE_SHORT || thisEntryCount != 1)
				{
					return 1;
				}
				int orientation = ByteBufferUtils.readU16(png, entry + 8, isLittleEndian);
				if (orientation < 1 || orientation > 8)
				{
					return 1;
				}
				return orientation;
			}
		}
		return 1;
	}

	/**
	 * Walk PNG chunks looking for the eXIf type. Returns a 2-element array { dataOff, length } pointing at the
	 * chunk's data start and length, or null when no valid eXIf chunk is present. The caller has confirmed the
	 * dataOff..dataOff+length range fits inside the PNG byte array — every consumer can read directly without
	 * re-validating bounds.
	 */
	private static int[] findExifChunk(byte[] png)
	{
		if (!hasPngSignature(png))
		{
			return null;
		}
		int off = 8;
		while (off + 8 <= png.length)
		{
			// Chunk length is u31 per PNG spec (top bit reserved 0). Read as long for the bounds math so a
			// signed int of a >2GB length wouldn't sign-flip negative and slip past the past-EOF guard.
			long lengthLong = ((long) (png[off] & 0xFF) << 24)
				| ((long) (png[off + 1] & 0xFF) << 16)
				| ((long) (png[off + 2] & 0xFF) << 8)
				| (png[off + 3] & 0xFF);
			if (lengthLong < 0 || lengthLong > Integer.MAX_VALUE)
			{
				return null;
			}
			int length = (int) lengthLong;
			// chunk = length(4) + type(4) + data(length) + CRC(4)
			long endLong = (long) off + 4L + 4L + length + 4L;
			if (endLong > png.length)
			{
				return null;
			}
			int typeOff = off + 4;
			if (png[typeOff] == EXIF_CHUNK_TYPE[0] && png[typeOff + 1] == EXIF_CHUNK_TYPE[1]
				&& png[typeOff + 2] == EXIF_CHUNK_TYPE[2] && png[typeOff + 3] == EXIF_CHUNK_TYPE[3])
			{
				return new int[] { off + 8, length };
			}
			off = (int) endLong;
		}
		return null;
	}

	private static boolean hasPngSignature(byte[] png)
	{
		if (png == null || png.length < PNG_SIGNATURE.length)
		{
			return false;
		}
		for (int i = 0; i < PNG_SIGNATURE.length; i++)
		{
			if (png[i] != PNG_SIGNATURE[i])
			{
				return false;
			}
		}
		return true;
	}
}
