package com.cropcenter.metadata;

import android.util.Log;

import java.util.Arrays;
import java.util.Optional;

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
	 * @return gain map JPEG bytes (starting with FFD8), or empty when no gain map is found
	 */
	public static Optional<byte[]> extract(byte[] file, boolean isHdrSource)
	{
		if (file == null || file.length < 10)
		{
			return Optional.empty();
		}
		if (!isHdrSource)
		{
			// No HDR signature (caller said so) — even if FF D8 follows primary EOI, those bytes are SEFT
			// data (embedded thumbnail / history blob), not a gain map. Trusting the FF D8 alone would
			// mis-extract a thumbnail as a gain map.
			return Optional.empty();
		}

		// Walk primary's marker chain to find its EOI. The walker handles SOS entropy, RST / STUFFING / TEM
		// standalone markers, segment-length math, and overflow guards.
		int primaryEnd = JpegMarkerWalker.findPrimaryEoi(file, file.length);
		if (primaryEnd < 0)
		{
			return Optional.empty();
		}

		// Walk the gain map's own marker chain forward to find ITS EOI. SeftExtractor.gainMapEndAfterPrimary
		// owns the FF D8 probe, the SEFT-aware slice cap, and the forward walk (required because SEFT data can
		// short-circuit a backward FF D9 scan — see JpegMarkerWalker.findEoi Javadoc) — shared with
		// SeftExtractor so the two extractors can't drift on the boundary math. Return value is absolute, so
		// gain-map length is (gainMapEoiAbs - primaryEnd).
		int gainMapEoiAbs = SeftExtractor.gainMapEndAfterPrimary(file, primaryEnd);
		if (gainMapEoiAbs == SeftExtractor.GAIN_MAP_WALK_FAILED)
		{
			Log.w(TAG, "gain map after primary EOI " + primaryEnd
				+ " doesn't parse as a JPEG; treating as no-gain-map");
			return Optional.empty();
		}
		if (gainMapEoiAbs < 0)
		{
			// GAIN_MAP_ABSENT (no FF D8 after primary — only primary plus possibly a SEFT trailer) or
			// GAIN_MAP_SLICE_EMPTY (the footer-capped slice leaves no bytes to walk).
			return Optional.empty();
		}

		byte[] gainMap = Arrays.copyOfRange(file, primaryEnd, gainMapEoiAbs);
		Log.d(TAG, "Extracted gain map: " + gainMap.length + " bytes after primary EOI");
		return Optional.of(gainMap);
	}

}
