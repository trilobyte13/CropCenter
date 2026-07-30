package com.cropcenter.metadata;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Extracts PNG metadata chunks of interest. Currently handles the eXIf chunk (PNG 1.6 spec) which holds raw TIFF data —
 * converted to a synthetic EXIF APP1 JpegSegment so the export path's existing inject pipeline (which keys on
 * JpegSegment.isExif) can round-trip it without a separate code path.
 *
 * Other PNG ancillary chunks (iCCP for ICC profile, iTXt / tEXt / zTXt for textual metadata) are not parsed because (a)
 * the editor doesn't model them and (b) eXIf alone covers the common cases — orientation, GPS, MakerNote — for a PNG
 * that came from a phone-camera or photo-editor.
 *
 * PNG byte structure:
 *   - 8-byte file signature
 *   - chunks, each: length(4 BE) + type(4) + data(length) + CRC(4)
 *
 * See `EXIF_CHUNK_TYPE` constant Javadoc for the chunk-type bit semantics.
 */
public final class PngMetadataExtractor
{
	/**
	 * One eXIf chunk's location inside the PNG byte array, as returned by findExifChunk. Named accessors keep the
	 * data-offset-vs-length pairing a compile-time contract instead of bare int[] indexing.
	 *
	 * @param dataOff offset of the chunk's first data byte (just past length + type)
	 * @param length  chunk data length in bytes; dataOff..dataOff+length is validated to fit inside the PNG
	 */
	private record ChunkRange(int dataOff, int length) {}

	private static final String TAG = "PngMetadataExtractor";

	// 4-byte ASCII "eXIf" chunk-type bytes. Lowercase 'e' marks ancillary, uppercase 'X' marks public, uppercase
	// 'I' is the reserved bit, lowercase 'f' marks safe-to-copy across edits. Shared by `findExifChunk` (the
	// byte-by-byte walk) and `CropExporter.injectPngExifFromTiffFileToFile` (the chunk writer) so the chunk-type
	// literal lives in one place rather than as parallel inline byte sequences in both files. Mutability contract
	// — callers MUST NOT write through this reference (same convention as JpegSegment.data()): a single stray
	// element write would silently break eXIf chunk detection and emission app-wide.
	public static final byte[] EXIF_CHUNK_TYPE = { 'e', 'X', 'I', 'f' };

	// 4-byte ASCII "IEND" chunk-type bytes. IEND terminates the PNG datastream per the spec — the chunk walk stops
	// there so bytes past it (vendor trailers, appended junk) are never parsed as chunks.
	private static final byte[] IEND_CHUNK_TYPE = { 'I', 'E', 'N', 'D' };

	// Canonical 8-byte PNG file signature (per ISO/IEC 15948 / W3C PNG spec). Private — external callers that need
	// PNG detection go through the hasPngSignature predicate, so the mutable array never escapes this class.
	private static final byte[] PNG_SIGNATURE = {
		(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
	};

	// Maximum TIFF payload that fits in a JPEG APP1 segment: subtract the 2 length bytes themselves and the 6-byte
	// "Exif\0\0" identifier from the spec-max segment length (JpegSegment.MAX_SEGMENT_BYTES = 65535). Routing
	// through the shared constant rather than inlining 65535 keeps the cap in lockstep with the JPEG writers
	// (ExifPatcher / XmpItemLengthPatcher) that share it.
	private static final int APP1_MAX_TIFF_PAYLOAD = JpegSegment.MAX_SEGMENT_BYTES - 2 - 6;

	private PngMetadataExtractor() {}

	/**
	 * Walk a PNG file's chunks and return any metadata segments worth preserving. Currently returns at most one
	 * entry — a synthetic APP1 EXIF segment built from the eXIf chunk's TIFF payload, wrapped in the canonical `FF
	 * E1 LL LL "Exif\0\0" [TIFF...]` layout that `JpegSegment.isExif` and the JPEG inject path
	 * (`JpegMetadataInjector.injectFileToFile`) both already understand.
	 *
	 * Returns an empty list when the input isn't a valid PNG, when no eXIf chunk is present before IEND (the chunk
	 * walk stops at IEND per the PNG spec — post-IEND trailer bytes are never parsed as chunks), when a chunk fails
	 * structural validation (length past EOF, length sign-overflow) or the eXIf chunk fails CRC32 validation
	 * (corrupt metadata drops; pixel decode is unaffected), or when the eXIf payload exceeds the JPEG APP1
	 * u16 cap (would corrupt PNG → JPEG conversion). Use extractRawTiff to get the TIFF bytes unconditionally for
	 * the PNG → PNG round-trip path. Multiple eXIf chunks are not supported — the first match wins (PNG spec says
	 * SHOULD be at most one).
	 *
	 * @param png raw PNG file bytes
	 * @return at-most-one-element list with the synthetic APP1 EXIF segment, or empty
	 */
	public static List<JpegSegment> extract(byte[] png)
	{
		List<JpegSegment> segments = new ArrayList<>();
		Optional<ChunkRange> maybeChunk = findExifChunk(png);
		if (maybeChunk.isEmpty())
		{
			return segments;
		}
		ChunkRange chunk = maybeChunk.orElseThrow();
		int dataOff = chunk.dataOff();
		int length = chunk.length();
		// segLen is the APP1 segment-length field per JPEG spec — includes the 2 length bytes themselves PLUS
		// the 6-byte "Exif\0\0" identifier PLUS the TIFF payload. Capped at u16 (65535). PNG eXIf chunks have
		// no such cap (length field is u31) so a PNG with > 64KB EXIF can't round-trip via a single APP1. Skip
		// the synthetic-APP1 emission rather than write a segment with a truncated length field that would
		// corrupt PNG → JPEG conversion. The PNG → PNG path uses extractRawTiff, which has no cap.
		if (length > APP1_MAX_TIFF_PAYLOAD)
		{
			Log.w(TAG, "eXIf chunk too large for JPEG APP1 (" + length + " bytes > " + APP1_MAX_TIFF_PAYLOAD
				+ " cap); skipping synthetic-APP1 emission. PNG → PNG round-trip remains intact via "
				+ "extractRawTiff.");
			return segments;
		}
		int segLen = 2 + 6 + length;
		int totalSegBytes = 2 + segLen; // FF E1 + segLen + payload
		byte[] exifSegBytes = new byte[totalSegBytes];
		exifSegBytes[0] = (byte) JpegMarker.PREFIX;
		exifSegBytes[1] = (byte) JpegMarker.APP1;
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
	 * Read EXIF orientation (1..8) from a PNG eXIf chunk's TIFF orientation tag (TiffTag.ORIENTATION). PNG sources
	 * need this to apply orientation rotation to pixels at load time — without it, a PNG with eXIf orientation=6
	 * (rotate 90 CW) would be displayed in stored orientation while the export side normalises orientation to 1,
	 * baking a permanent sideways rotation into the saved file.
	 *
	 * Returns 1 (upright) when the input isn't a valid PNG, has no eXIf chunk, has malformed TIFF byte-order bytes,
	 * or has no Orientation tag.
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
			// Malformed TIFF (truncated, lying offsets) maps to the same upright fallback as a missing tag.
			return 1;
		}
	}

	/**
	 * Raw TIFF data from the PNG's eXIf chunk, without any APP1 wrapping or size cap. Used by the PNG → PNG export
	 * path which writes a fresh eXIf chunk back into the cropped PNG — no JPEG u16 limit applies, so a 200KB EXIF
	 * block (camera with extensive MakerNote / GPS metadata) round-trips fully. Returns empty when the input isn't
	 * a valid PNG or has no eXIf chunk.
	 *
	 * @param png raw PNG file bytes
	 * @return raw TIFF bytes copied out of the eXIf chunk, or empty when absent
	 */
	public static Optional<byte[]> extractRawTiff(byte[] png)
	{
		return findExifChunk(png)
			.map(chunk -> Arrays.copyOfRange(png, chunk.dataOff(), chunk.dataOff() + chunk.length()));
	}

	/**
	 * True when png starts with the canonical 8-byte PNG file signature (89 50 4E 47 0D 0A 1A 0A per ISO/IEC
	 * 15948). The single tree-wide PNG-detection predicate — ImageLoadController's format dispatch and the chunk
	 * walkers below both route through it, so the signature literal lives in one place.
	 *
	 * @param png raw bytes; null and shorter-than-signature inputs return false
	 * @return true when the first eight bytes match the PNG file signature
	 */
	public static boolean hasPngSignature(byte[] png)
	{
		int sigLen = PNG_SIGNATURE.length;
		return png != null && png.length >= sigLen && Arrays.equals(png, 0, sigLen, PNG_SIGNATURE, 0, sigLen);
	}

	/**
	 * True when the CRC32 stored in a chunk's trailing 4 bytes matches the CRC computed over its type + data
	 * fields, per the PNG spec. The caller's bounds validation guarantees typeOff + 4 + length + 4 fits inside
	 * png, so the reads here need no re-checking.
	 *
	 * @param png     raw PNG file bytes
	 * @param typeOff offset of the chunk's 4-byte type field
	 * @param length  chunk data length in bytes
	 * @return true when the stored big-endian CRC32 equals the computed value
	 */
	private static boolean chunkCrcValid(byte[] png, int typeOff, int length)
	{
		CRC32 crc = new CRC32();
		crc.update(png, typeOff, 4 + length);
		int crcOff = typeOff + 4 + length;
		long stored = ((long) (png[crcOff] & 0xFF) << 24) | ((long) (png[crcOff + 1] & 0xFF) << 16)
			| ((long) (png[crcOff + 2] & 0xFF) << 8) | (png[crcOff + 3] & 0xFF);
		return crc.getValue() == stored;
	}

	/**
	 * Parse the eXIf chunk's TIFF body for IFD0's Orientation tag. Header, offset, and entry-shape validation
	 * (byte-order marker, TIFF magic, SHORT/count-1) live in the shared TiffIfd0 walker — bounded to the eXIf
	 * chunk's end, since PNG eXIf is u31-uncapped and a lying IFD offset could otherwise walk into unrelated
	 * chunks; this reader keeps only the chunk lookup and the upright fallback.
	 *
	 * @param png raw PNG file bytes
	 * @return orientation 1..8; 1 when the eXIf chunk is absent or any part of the TIFF walk is malformed
	 * @throws IndexOutOfBoundsException on truncated TIFF with lying offsets; the public extractOrientation
	 *                                   wrapper maps it to the same upright fallback
	 */
	private static int extractOrientationInternal(byte[] png)
	{
		Optional<ChunkRange> maybeChunk = findExifChunk(png);
		if (maybeChunk.isEmpty())
		{
			return 1;
		}
		ChunkRange chunk = maybeChunk.orElseThrow();
		int tiffStart = chunk.dataOff();
		return TiffIfd0.findOrientationEntry(png, tiffStart, tiffStart + chunk.length(), 0)
			.map(entry -> TiffIfd0.readOrientation(png, entry))
			.orElse(1);
	}

	/**
	 * Walk PNG chunks looking for the eXIf type. This method validates the chunk's bounds itself — the returned
	 * dataOff..dataOff+length range is guaranteed to fit inside the PNG byte array, so consumers can read directly
	 * without re-validating. The walk stops at IEND (which terminates the datastream per the PNG spec — bytes past
	 * it are not chunks and are never scanned), and a located eXIf chunk must pass CRC32 validation over its
	 * type + data fields — a CRC mismatch means the metadata bytes are corrupt, and every consumer of this helper
	 * drops metadata only (pixel decode is unaffected — fail-open for pixels).
	 *
	 * @param png raw PNG file bytes; null and non-PNG inputs return empty
	 * @return the eXIf chunk's data start and length; empty when the signature is missing, a chunk length is
	 *         malformed (u31 violation or past-EOF), IEND arrives first, the eXIf chunk fails CRC validation, or
	 *         no eXIf chunk exists
	 */
	private static Optional<ChunkRange> findExifChunk(byte[] png)
	{
		if (!hasPngSignature(png))
		{
			return Optional.empty();
		}
		int off = 8;
		while (off + 8 <= png.length)
		{
			// Chunk length is u31 per PNG spec (top bit reserved 0). Read as long for the bounds math so a
			// signed int of a >2GB length wouldn't sign-flip negative and slip past the past-EOF guard.
			long lengthLong = ((long) (png[off] & 0xFF) << 24) | ((long) (png[off + 1] & 0xFF) << 16)
				| ((long) (png[off + 2] & 0xFF) << 8) | (png[off + 3] & 0xFF);
			if (lengthLong < 0 || lengthLong > Integer.MAX_VALUE)
			{
				return Optional.empty();
			}
			int length = (int) lengthLong;
			// chunk = length(4) + type(4) + data(length) + CRC(4)
			long endLong = (long) off + 4L + 4L + length + 4L;
			if (endLong > png.length)
			{
				return Optional.empty();
			}
			int typeOff = off + 4;
			if (typeMatches(png, typeOff, IEND_CHUNK_TYPE))
			{
				// IEND terminates the datastream — an eXIf-shaped byte run past it is trailer junk,
				// not a chunk.
				return Optional.empty();
			}
			if (typeMatches(png, typeOff, EXIF_CHUNK_TYPE))
			{
				if (!chunkCrcValid(png, typeOff, length))
				{
					Log.w(TAG, "eXIf chunk failed CRC validation; dropping metadata "
						+ "(pixel decode unaffected)");
					return Optional.empty();
				}
				return Optional.of(new ChunkRange(off + 8, length));
			}
			off = (int) endLong;
		}
		return Optional.empty();
	}

	/**
	 * True when the four bytes at typeOff equal the given 4-byte chunk-type constant. The caller's bounds check
	 * guarantees typeOff + 4 is inside png.
	 *
	 * @param png     raw PNG file bytes
	 * @param typeOff offset of the chunk's 4-byte type field
	 * @param type    4-byte chunk-type constant to compare against
	 * @return true when all four type bytes match
	 */
	private static boolean typeMatches(byte[] png, int typeOff, byte[] type)
	{
		return png[typeOff] == type[0] && png[typeOff + 1] == type[1]
			&& png[typeOff + 2] == type[2] && png[typeOff + 3] == type[3];
	}
}
