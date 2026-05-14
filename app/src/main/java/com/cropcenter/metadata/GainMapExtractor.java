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
	 * @param file         raw bytes of the full Samsung Ultra HDR JPEG
	 * @param isHdrSource  true when the caller has confirmed the file carries the XMP hdrgm namespace
	 *                     marker — for parsed-segment callers (ImageLoadController.extractMetadata,
	 *                     GraftWriter.graft) use HdrSignature.hasHdrgmInXmp(meta) which scans XMP
	 *                     segment bodies only; HdrSignature.isHdrSource(byte[]) is a full-file scan
	 *                     reserved for UltraHdrCompat's post-compress diagnostic where the freshly-
	 *                     emitted JPEG can only carry the marker in XMP. The XMP-only narrowing closes
	 *                     a false-positive class where stray "hdrgm" bytes in MakerNote / COM / vendor
	 *                     blob / SEFT history would mis-classify SDR files as HDR. When false the
	 *                     extractor returns null without inspecting post-primary FF D8 bytes — without
	 *                     this gate, an SDR Samsung file whose SEFT data block begins with an embedded
	 *                     JPEG thumbnail's FF D8 would be mis-extracted as a gain map.
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

		// Gain map (when present) starts immediately after primary's EOI with its own SOI. Absence of FF D8
		// here means there's no gain map — only primary plus possibly a SEFT trailer.
		if (primaryEnd + 1 >= file.length
			|| (file[primaryEnd] & 0xFF) != 0xFF || (file[primaryEnd + 1] & 0xFF) != 0xD8)
		{
			return null;
		}

		// Walk the gain map's own marker chain forward to find ITS EOI. Cap the slice end at len-8 when
		// the file carries a SEFT footer to keep the walker out of trailer bytes; without SEFT, the slice
		// runs to file end.
		//
		// Walking forward is structurally sound where the previous backwards-FF-D9 scan from len-9 was not.
		// SEFT data blocks contain arbitrary binary content — embedded thumbnails (themselves JPEGs ending
		// in FF D9) and edit-history blobs — and a backwards scan can land on a stuffed FF D9 inside a
		// thumbnail rather than the real gain-map EOI. Following the JPEG marker chain instead of
		// pattern-matching on raw bytes stops at the first real EOI marker and ignores any byte-stuffed
		// FF D9 within entropy data.
		int sliceEnd = SeftExtractor.hasSeftFooter(file)
			? file.length - SeftExtractor.FOOTER_SIZE
			: file.length;
		if (sliceEnd <= primaryEnd)
		{
			return null;
		}
		// Walk the gain-map's marker chain directly on `file` between [primaryEnd, sliceEnd) — no
		// intermediate Arrays.copyOfRange slice. A 100 MB HDR source with gain map starting at byte 30 MB
		// would otherwise allocate a ~70 MB transient byte[] just to give findPrimaryEoi a buffer whose
		// offset 0 is the gain-map SOI; findEoi(file, primaryEnd, sliceEnd) achieves the same walk on the
		// original array. Return value is absolute (relative to file's byte 0), so the gain-map length is
		// (eoiOff - primaryEnd).
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
