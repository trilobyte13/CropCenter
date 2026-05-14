package com.cropcenter.metadata;

import android.util.Log;

/**
 * Extracts the Samsung SEFT trailer from a JPEG file. The SEFT trailer is appended after the last JPEG EOI (after gain
 * map if present). Layout: [SEFT data blocks][SEFH directory][4-byte size LE][4-byte "SEFT" magic]
 */
public final class SeftExtractor
{
	/**
	 * Byte size of the SEFT footer at the end of the file: 4-byte little-endian size value + 4-byte "SEFT"
	 * ASCII magic. Centralised so the gain-map walker's `file.length - FOOTER_SIZE` slice cap (in
	 * GainMapExtractor) and the SEFT extractor's own `file.length - FOOTER_SIZE` aren't two unconnected
	 * `8` magic literals.
	 */
	public static final int FOOTER_SIZE = 8;

	private static final String TAG = "SeftExtractor";

	private SeftExtractor() {}

	/**
	 * Extract raw SEFT trailer bytes from the file.
	 *
	 * @param file        raw bytes of the full Samsung-edited JPEG
	 * @param hasGainMap  true when caller has confirmed a gain map IS present at primary EOI (typically
	 *                    `gainMap != null` from a prior GainMapExtractor.extract on the same bytes); when
	 *                    false the extractor treats post-primary FF D8 bytes as SEFT data rather than as a
	 *                    gain map. Without this hint, an SDR Samsung file whose SEFT data block starts with
	 *                    an embedded JPEG thumbnail's FF D8 would have its trailer truncated — the
	 *                    extractor would walk past the thumbnail's FF D9 as if it were a gain-map EOI, and
	 *                    the saved file's re-appended SEFT would be missing the thumbnail block.
	 * @return SEFT trailer bytes, or null if not present
	 */
	public static byte[] extract(byte[] file, boolean hasGainMap)
	{
		if (file == null || file.length < 12)
		{
			return null;
		}
		if (!hasSeftFooter(file))
		{
			return null;
		}

		// Find the SEFT trailer's true start by walking the JPEG marker chain forward — first to primary's EOI,
		// then (if a gain map is present) to the gain map's EOI. The SEFT trailer starts immediately after.
		//
		// The previous backwards-FF-D9 scan from len-9 could land on a byte-stuffed FF D9 inside a SEFT data
		// block (which can hold embedded thumbnails — themselves JPEGs ending in FF D9 — and edit-history
		// blobs). Walking the JPEG marker chain instead of pattern-matching on raw bytes ignores byte-stuffed
		// 0xFFD9 within entropy data and stops only at real EOI markers.
		int primaryEnd = JpegMarkerWalker.findPrimaryEoi(file, file.length);
		if (primaryEnd < 0)
		{
			return null;
		}

		int trailerStart;
		if (hasGainMap && primaryEnd + 1 < file.length
			&& (file[primaryEnd] & 0xFF) == 0xFF && (file[primaryEnd + 1] & 0xFF) == 0xD8)
		{
			// Gain map present — walk it to find its EOI; SEFT trailer starts right after. findEoi
			// scans `file` directly in [primaryEnd, sliceEnd) without allocating a Arrays.copyOfRange
			// slice. On a 100 MB HDR source with gain map at byte 30 MB, the previous slice form
			// allocated ~70 MB of transient byte[] just to give findPrimaryEoi a buffer whose offset 0
			// was the gain-map SOI. Sister site GainMapExtractor.extract migrated to findEoi earlier
			// for the same reason.
			int sliceEnd = file.length - FOOTER_SIZE;
			if (sliceEnd <= primaryEnd)
			{
				return null;
			}
			int gainMapEoiAbs = JpegMarkerWalker.findEoi(file, primaryEnd, sliceEnd);
			if (gainMapEoiAbs < 0)
			{
				return null;
			}
			trailerStart = gainMapEoiAbs;
		}
		else
		{
			// No gain map (caller hint is false, OR no FF D8 after primary) — SEFT trailer starts
			// immediately after primary's EOI. The hint matters: an SDR file with a SEFT data block that
			// begins FF D8 (embedded JPEG thumbnail) would otherwise be mis-walked past the thumbnail.
			trailerStart = primaryEnd;
		}

		if (trailerStart >= file.length)
		{
			return null;
		}

		byte[] trailer = new byte[file.length - trailerStart];
		System.arraycopy(file, trailerStart, trailer, 0, trailer.length);
		Log.d(TAG, "Extracted SEFT trailer: " + trailer.length + " bytes");
		return trailer;
	}

	/**
	 * True when the file's last 4 bytes are the ASCII string "SEFT" — the Samsung edit-format magic that marks
	 * the end of an editable Samsung JPEG. The 8-byte footer (4-byte LE size + 4-byte "SEFT") sits at the very
	 * end of the file. Used here to gate the SEFT-trailer extraction itself, AND by GainMapExtractor to know
	 * whether to cap the gain-map walk's slice end at len-8 vs file.length.
	 *
	 * @param file raw bytes of the full Samsung-edited JPEG
	 * @return true when the SEFT footer magic is present at the end of file
	 */
	public static boolean hasSeftFooter(byte[] file)
	{
		int len = file.length;
		return len >= 12 && file[len - 4] == 'S' && file[len - 3] == 'E'
			&& file[len - 2] == 'F' && file[len - 1] == 'T';
	}
}
