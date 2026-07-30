package com.cropcenter.metadata;

import android.util.Log;

import java.util.Arrays;
import java.util.Optional;

/**
 * Extracts the Samsung SEFT trailer from a JPEG file. The SEFT trailer is appended after the last JPEG EOI (after gain
 * map if present). Layout: [SEFT data blocks][SEFH directory][4-byte size LE][4-byte "SEFT" magic]. The size field
 * covers the SEFH DIRECTORY only — the data blocks precede the directory and are addressed by per-entry backward
 * offsets from the SEFH start, so the footer alone cannot locate the trailer start.
 */
public final class SeftExtractor
{
	private static final String TAG = "SeftExtractor";

	// Byte size of the SEFT footer at the end of the file: 4-byte little-endian SEFH-directory length + 4-byte
	// "SEFT" ASCII magic. Centralised so the gain-map walker's `file.length - FOOTER_SIZE` slice cap (in
	// GainMapExtractor) and the SEFT extractor's own `file.length - FOOTER_SIZE` aren't two unconnected `8` magic
	// literals.
	public static final int FOOTER_SIZE = 8;
	// Sentinel returns for gainMapEndAfterPrimary, distinct so each caller keeps its own handling: a missing
	// gain-map SOI is routine (SDR file — GainMapExtractor reports no gain map, SeftExtractor's trailer starts at
	// the primary's EOI), while an empty slice or a failed EOI walk means the post-primary bytes cannot be a
	// complete gain map (both extractors bail; only the walk failure logs).
	static final int GAIN_MAP_ABSENT = -1;
	static final int GAIN_MAP_SLICE_EMPTY = -2;
	static final int GAIN_MAP_WALK_FAILED = -3;

	private SeftExtractor() {}

	/**
	 * Extract raw SEFT trailer bytes from the file.
	 *
	 * @param file        raw bytes of the full Samsung-edited JPEG
	 * @param hasGainMap  true when caller has confirmed a gain map IS present at primary EOI (typically a
	 *                    present result from a prior GainMapExtractor.extract on the same bytes); when
	 *                    false the extractor treats post-primary FF D8 bytes as SEFT data rather than as a
	 *                    gain map. Without this hint, an SDR Samsung file whose SEFT data block starts with
	 *                    an embedded JPEG thumbnail's FF D8 would have its trailer truncated — the
	 *                    extractor would walk past the thumbnail's FF D9 as if it were a gain-map EOI, and
	 *                    the saved file's re-appended SEFT would be missing the thumbnail block.
	 * @return SEFT trailer bytes; empty when no SEFT footer is present, or when the marker walk cannot
	 *         locate the trailer start (malformed primary or gain-map chain) — the trailer is dropped
	 *         rather than shipped truncated
	 */
	public static Optional<byte[]> extract(byte[] file, boolean hasGainMap)
	{
		if (file == null || file.length < 12)
		{
			return Optional.empty();
		}
		if (!hasSeftFooter(file))
		{
			return Optional.empty();
		}

		// Walk the JPEG marker chain forward — first to primary's EOI, then (if a gain map is present) to the
		// gain map's EOI. The SEFT trailer starts immediately after. (Forward walking required because SEFT
		// data can short-circuit a backward FF D9 scan — see JpegMarkerWalker.findEoi.)
		int primaryEnd = JpegMarkerWalker.findPrimaryEoi(file, file.length);
		if (primaryEnd < 0)
		{
			return Optional.empty();
		}

		int trailerStart;
		int gainMapEnd = hasGainMap ? gainMapEndAfterPrimary(file, primaryEnd) : GAIN_MAP_ABSENT;
		if (gainMapEnd == GAIN_MAP_SLICE_EMPTY)
		{
			return Optional.empty();
		}
		if (gainMapEnd == GAIN_MAP_WALK_FAILED)
		{
			// Gain-map JPEG inside the chain is malformed, so the forward walk cannot locate the
			// trailer start. The footer u32 is the SEFH DIRECTORY length only (data blocks precede
			// the directory, addressed by backward offsets from the SEFH start), so it cannot
			// locate the trailer start either — correct recovery would need the backward-offset
			// directory parse. Fail closed: treat the trailer as absent rather than ship a
			// truncated trailer whose directory offsets dangle into gain-map bytes.
			Log.w(TAG, "Gain-map EOI walk failed (malformed gain-map JPEG); dropping SEFT trailer");
			return Optional.empty();
		}
		if (gainMapEnd == GAIN_MAP_ABSENT)
		{
			// No gain map (caller hint is false, OR no FF D8 after primary) — SEFT trailer starts
			// immediately after primary's EOI. The hint matters: an SDR file with a SEFT data block that
			// begins FF D8 (embedded JPEG thumbnail) would otherwise be mis-walked past the thumbnail.
			trailerStart = primaryEnd;
		}
		else
		{
			// Gain map present — the SEFT trailer starts right after its EOI.
			trailerStart = gainMapEnd;
		}

		if (trailerStart >= file.length)
		{
			return Optional.empty();
		}

		byte[] trailer = Arrays.copyOfRange(file, trailerStart, file.length);
		Log.d(TAG, "Extracted SEFT trailer: " + trailer.length + " bytes");
		return Optional.of(trailer);
	}

	/**
	 * True when the file's last 4 bytes are the ASCII string "SEFT" — the Samsung edit-format magic that marks the
	 * end of an editable Samsung JPEG. The 8-byte footer (4-byte LE size + 4-byte "SEFT") sits at the very end of
	 * the file. Used here to gate the SEFT-trailer extraction itself, AND by GainMapExtractor to know whether to
	 * cap the gain-map walk's slice end at len-8 vs file.length.
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

	/**
	 * Locate the byte just past the EOI of the gain-map JPEG that starts at primaryEnd, capping the walk before
	 * the SEFT footer when one is present. The single home for the gain-map boundary math shared by
	 * GainMapExtractor.extract and the trailer-start computation in extract above — their agreement is
	 * load-bearing: this extractor's trailer start must equal GainMapExtractor's gain-map end on the same bytes,
	 * or a save would re-append a trailer sliced at the wrong offset. findEoi scans `file` directly in
	 * [primaryEnd, sliceEnd) — no transient Arrays.copyOfRange, which would allocate ~70 MB on a 100 MB HDR
	 * source.
	 *
	 * @param file       raw bytes of the full Samsung JPEG
	 * @param primaryEnd byte offset just past the primary's EOI (from JpegMarkerWalker.findPrimaryEoi)
	 * @return absolute offset just past the gain map's EOI, or a negative sentinel: GAIN_MAP_ABSENT when no
	 *         FF D8 follows primaryEnd, GAIN_MAP_SLICE_EMPTY when the footer-capped slice leaves no bytes to
	 *         walk, GAIN_MAP_WALK_FAILED when the slice doesn't parse as a JPEG
	 */
	static int gainMapEndAfterPrimary(byte[] file, int primaryEnd)
	{
		if (primaryEnd + 1 >= file.length || (file[primaryEnd] & 0xFF) != JpegMarker.PREFIX
			|| (file[primaryEnd + 1] & 0xFF) != JpegMarker.SOI)
		{
			return GAIN_MAP_ABSENT;
		}
		int sliceEnd = hasSeftFooter(file) ? file.length - FOOTER_SIZE : file.length;
		if (sliceEnd <= primaryEnd)
		{
			return GAIN_MAP_SLICE_EMPTY;
		}
		int gainMapEoiAbs = JpegMarkerWalker.findEoi(file, primaryEnd, sliceEnd);
		if (gainMapEoiAbs < 0)
		{
			return GAIN_MAP_WALK_FAILED;
		}
		return gainMapEoiAbs;
	}
}
