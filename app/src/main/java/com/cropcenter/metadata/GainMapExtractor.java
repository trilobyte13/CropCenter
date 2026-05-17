package com.cropcenter.metadata;

import android.util.Log;

/**
 * Extracts the HDR gain map from a Samsung Ultra HDR JPEG file. The gain map is a secondary JPEG stored between the
 * primary image's EOI and any trailing data (e.g., Samsung SEFT trailer).
 *
 * File layout:
 *   [primary JPEG FFD8...FFD9][gain map JPEG FFD8...FFD9][SEFT data blocks][SEFH][len][SEFT]
 */
public final class GainMapExtractor
{
	private static final String TAG = "GainMapExtractor";

	private GainMapExtractor() {}

	/**
	 * Extract the gain map JPEG from the raw file bytes.
	 *
	 * @param file        raw bytes of the full Samsung Ultra HDR JPEG
	 * @param isHdrSource true when the file carries the XMP hdrgm namespace marker. Required to avoid
	 *                    mis-extracting a SEFT thumbnail's FF D8 as a gain map.
	 * @return gain map JPEG bytes (starting with FFD8), or null if not found
	 */
	public static byte[] extract(byte[] file, boolean isHdrSource)
	{
		if (file == null || file.length < 10)
		{
			return null;
		}
		if (!isHdrSource)
		{
			// No HDR signature (caller said so) — even if FF D8 follows primary EOI, those bytes are SEFT
			// data (embedded thumbnail / history blob), not a gain map. Trusting the FF D8 alone would
			// mis-extract a thumbnail as a gain map.
			return null;
		}

		// Walk primary's marker chain to find its EOI. The walker handles SOS entropy, RST / STUFFING / TEM
		// standalone markers, segment-length math, and overflow guards.
		int primaryEnd = JpegMarkerWalker.findPrimaryEoi(file, file.length);
		if (primaryEnd < 0)
		{
			return null;
		}

		// Gain map (when present) starts immediately after primary's EOI with its own SOI. Absence here
		// means there's no gain map — only primary plus possibly a SEFT trailer.
		if (primaryEnd + 1 >= file.length
			|| (file[primaryEnd] & 0xFF) != JpegMarker.PREFIX
			|| (file[primaryEnd + 1] & 0xFF) != JpegMarker.SOI)
		{
			return null;
		}

		// Walk the gain map's own marker chain forward to find ITS EOI. Cap the slice end at len-8 when
		// the file carries a SEFT footer; without SEFT, the slice runs to file end. (Forward marker-chain
		// walking required because SEFT data can short-circuit a backward FF D9 scan — see
		// JpegMarkerWalker.findEoi Javadoc.)
		int sliceEnd = SeftExtractor.hasSeftFooter(file)
			? file.length - SeftExtractor.FOOTER_SIZE
			: file.length;
		if (sliceEnd <= primaryEnd)
		{
			return null;
		}
		// Walk the gain-map's marker chain directly on file in [primaryEnd, sliceEnd) — no transient
		// Arrays.copyOfRange (would allocate ~70 MB on a 100 MB HDR source). Return value is absolute,
		// so gain-map length is (eoiOff - primaryEnd).
		int gainMapEoiAbs = JpegMarkerWalker.findEoi(file, primaryEnd, sliceEnd);
		if (gainMapEoiAbs < 0)
		{
			Log.w(TAG, "gain map between primary EOI " + primaryEnd + " and " + sliceEnd
				+ " doesn't parse as a JPEG; treating as no-gain-map");
			return null;
		}

		int gainMapLen = gainMapEoiAbs - primaryEnd;
		byte[] gainMap = new byte[gainMapLen];
		System.arraycopy(file, primaryEnd, gainMap, 0, gainMapLen);
		Log.d(TAG, "Extracted gain map: " + gainMapLen + " bytes after primary EOI");
		return gainMap;
	}

}
