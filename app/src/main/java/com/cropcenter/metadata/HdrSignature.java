package com.cropcenter.metadata;

import java.util.List;

/**
 * Detect the XMP "hdrgm" namespace marker — the canonical signature of an Ultra HDR gain map. Used to gate
 * gain-map extraction so a non-HDR Samsung file whose SEFT data block starts with FF D8 (typically an embedded
 * JPEG thumbnail) isn't mis-extracted as a gain map.
 *
 * Pure Java — no Android dependencies — so the metadata extractors and GraftWriter can call it without dragging
 * in the Android Bitmap / Gainmap surface that lives in util/UltraHdrCompat (which delegates to this helper for
 * the post-compress diagnostic full-file scan).
 *
 * Three predicates are exposed for different call sites:
 *
 *   - hasHdrgmInXmp(List of JpegSegment) — load-time gate. Walks ONLY the XMP APP1 segments (standard +
 *     extended) where the hdrgm namespace declaration legitimately lives, so a stray "hdrgm" 5-byte
 *     sequence in MakerNote, COM, vendor blob, or SEFT edit history doesn't false-positive. This is what
 *     ImageLoadController.extractMetadata and GraftWriter.graft use to decide whether post-primary FF D8
 *     bytes are a gain map.
 *
 *   - isHdrgmXmpSegment(JpegSegment) — per-segment predicate. Used by CropExporter.stripHdrSegments on
 *     the HDR-drop path so the saved JPEG's metadata doesn't claim HDR the file no longer carries
 *     to downstream consumers. Same XMP-restricted scan as hasHdrgmInXmp, exposed as a single-segment
 *     callable for filter-loop call sites.
 *
 *   - isHdrSource(byte[]) — full-file scan. Used by UltraHdrCompat post Bitmap.compress to verify the
 *     compressed output emitted the hdrgm marker. The output is fresh and well-formed so a broader scan is
 *     fine; segment parsing on every export to gate a diagnostic log line is wasted work.
 */
public final class HdrSignature
{
	private HdrSignature() {}

	/**
	 * Walk the XMP APP1 segments (standard + extended) looking for the "hdrgm" namespace marker. The hdrgm
	 * declaration is an Ultra HDR gain-map source's ONLY structurally-correct location for that 5-byte
	 * sequence — anywhere else in the file (MakerNote, COM, vendor blob, SEFT edit history) is either
	 * coincidence or non-HDR vendor data. ImageLoadController.extractMetadata and GraftWriter.graft both
	 * gate gain-map extraction on this precise check so an SDR file with `hdrgm` outside XMP isn't
	 * mis-classified as Ultra HDR.
	 *
	 * Walks both standard XMP (via JpegSegment.isXmp) and Extended XMP (the secondary-segment shape
	 * that carries any XMP overflow past the JPEG APP1 ~64 KB cap). A vendor whose hdrgm declaration
	 * ended up in the extension segment would otherwise be silently mis-classified as SDR.
	 *
	 * @param segments parsed JPEG segments from JpegMetadataExtractor.extract; may be null or empty
	 * @return true when at least one XMP APP1 segment (standard or extended) contains "hdrgm" in its body
	 */
	public static boolean hasHdrgmInXmp(List<JpegSegment> segments)
	{
		if (segments == null)
		{
			return false;
		}
		// Per-segment scan first (cheap and covers the ~99% case where every Extended XMP chunk
		// independently carries an "hdrgm" occurrence — well-formed Ultra HDR packets repeat the marker
		// across xmlns + Version + HDRCapacityMin/Max so straddling ALL of them is statistically
		// unreachable).
		for (JpegSegment seg : segments)
		{
			if (isHdrgmXmpSegment(seg))
			{
				return true;
			}
		}
		// Reassembled Extended XMP fallback: covers the case where the marker straddles a chunk boundary
		// or the file's only "hdrgm" occurrence happens to land in a chunk that the per-segment scan
		// missed. Same shape of reassembly fallback used by HorizonDetector; lifted to a shared helper
		// so both paths stay in lockstep.
		byte[] reassembled = ExtendedXmpReassembler.reassemble(segments);
		return reassembled.length > 0 && containsHdrgm(reassembled);
	}

	/**
	 * Scan data for the XMP "hdrgm" namespace marker anywhere in the byte array. Linear (~5ms for 20MB).
	 * Used by UltraHdrCompat as a post-compress sanity flag on the export pipeline's output bytes — at
	 * that point the bytes are a freshly-emitted JPEG with well-formed XMP, so a coarse full-file scan is
	 * cheaper than re-parsing segments. NOT for load-time HDR gating — use hasHdrgmInXmp(...) instead so
	 * a stray "hdrgm" outside XMP doesn't false-positive.
	 *
	 * @param data full file bytes to scan
	 * @return true when the XMP "hdrgm" namespace marker is present anywhere in data
	 */
	public static boolean isHdrSource(byte[] data)
	{
		return containsHdrgm(data);
	}

	/**
	 * Per-segment predicate: true when seg is an XMP APP1 segment (standard or extended) whose body
	 * contains the "hdrgm" namespace marker. Exposed so CropExporter.stripHdrSegments can drop both kinds
	 * of HDR-claiming XMP — without this, an HDR-drop save (HDR source where the gain-map composition
	 * skipped or failed) could leave Extended-XMP `hdrgm` declarations in the output and lie about HDR
	 * presence to downstream apps and the app's own ExportPipeline.reportSuccess HDR-OK toast.
	 *
	 * @param seg JPEG segment to test
	 * @return true when seg is an XMP segment (standard or extended) carrying "hdrgm" in its body
	 */
	public static boolean isHdrgmXmpSegment(JpegSegment seg)
	{
		return (seg.isXmp() || seg.isExtendedXmp()) && containsHdrgm(seg.data());
	}

	/**
	 * Linear byte-pattern match for the 5-byte ASCII "hdrgm" sequence. Shared by isHdrSource (full file),
	 * hasHdrgmInXmp's standard-XMP branch, and hasHdrgmInXmp's extended-XMP branch.
	 *
	 * @param data byte array to scan; null returns false without indexing
	 * @return true when the 5-byte sequence is found anywhere in data
	 */
	private static boolean containsHdrgm(byte[] data)
	{
		if (data == null)
		{
			return false;
		}
		// For a 5-byte pattern, last valid start index is (length - 5), so the exclusive loop bound is
		// (length - 4).
		int limit = data.length - 4;
		for (int i = 0; i < limit; i++)
		{
			if (data[i] == 'h' && data[i + 1] == 'd' && data[i + 2] == 'r'
				&& data[i + 3] == 'g' && data[i + 4] == 'm')
			{
				return true;
			}
		}
		return false;
	}

}
