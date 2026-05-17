package com.cropcenter.metadata;

import android.util.Log;

/**
 * Appends the HDR gain map JPEG after the primary image and updates MPF offsets.
 */
public final class GainMapComposer
{
	/**
	 * Tagged outcome from compose(). Disambiguates the three return shapes that previously had to be inferred
	 * from reference identity on the returned byte[] — a leaky contract that broke once compose started
	 * returning the XMP-patched primary on the MPF-fail path (the new array misled `withGainMap != input`
	 * callers into thinking HDR attached when it hadn't).
	 *
	 * @param bytes        the output JPEG bytes — either the primary verbatim, an XMP-patched primary with
	 *                     no gain map appended, or the full primary + gain map concatenation; callers can't
	 *                     tell the cases apart from the bytes alone
	 * @param hdrAttached  true ONLY when the gain map was successfully appended AND MPF offsets point at it.
	 *                     False on every drop path (no gain map, XmpItemLengthPatcher refused, MpfPatcher
	 *                     failed) regardless of whether `bytes` is the input reference or a fresh allocation
	 */
	public record ComposeResult(byte[] bytes, boolean hdrAttached) {}

	private static final String TAG = "GainMapComposer";

	private GainMapComposer() {}

	/**
	 * Append the gain map to the primary JPEG and fix MPF offsets.
	 *
	 * Three HDR-drop branches all return a primary-only JPEG (no gain map appended, ComposeResult.hdrAttached
	 * = false) but with subtle differences in which primary variant is wrapped:
	 *  - gainMap is null or empty (non-Ultra-HDR source path that still hits this method) →
	 *    bytes = input `primary` unchanged. No XMP patching has run.
	 *  - `XmpItemLengthPatcher.patch` returns null because the GContainer Item:Length attribute can't be
	 *    safely rewritten — either it lives in Extended XMP, the per-chunk pattern straddles a chunk
	 *    boundary, OR it lives in standard XMP but the segment is unpatchable (over-cap segLen, malformed
	 *    quote, unterminated digit run). Shipping the gain map with stale Item:Length silently truncates
	 *    the gain map in strict GContainer-respecting decoders (Google's libUltraHdr is one); dropping HDR
	 *    before MPF patching is the safe choice. bytes = input `primary` unchanged — the original XMP is
	 *    preserved because the patcher's refusal means we can't cleanly rewrite it.
	 *  - MpfPatcher.patch fails (no MPF segment, malformed/unsupported MPF, byte-order mismatch, 3+ image
	 *    MPF without MPType match, negative relative offset, etc). Without this guard, compose would ship
	 *    a JPEG with the gain-map bytes appended but no valid MPF entry pointing at them — strict decoders
	 *    (Samsung Gallery's Revert pre-flight) would reject the file, lenient decoders that scan for the
	 *    hdrgm signature would render HDR with the wrong offset. bytes = the XMP-patched `patched` primary
	 *    — Item:Length already reflects the (would-be) gain-map size; today's downstream
	 *    CropExporter.stripHdrSegments drops the HDR-bearing XMP segments wholesale on this branch, so the
	 *    attribute value doesn't ship, but returning `patched` keeps the GainMapComposer contract
	 *    self-consistent (the returned primary's XMP matches what's actually appended — i.e., nothing)
	 *    independent of whether the caller also strips HDR.
	 *
	 * @param primary  the primary JPEG bytes (with metadata already injected)
	 * @param gainMap  the gain map JPEG bytes (preserved from original file), or null / empty to leave
	 *                 primary unchanged
	 * @return tagged result — bytes always set; hdrAttached true ONLY on the full-success path (gain map
	 *         appended AND MPF offsets patched to point at it)
	 */
	public static ComposeResult compose(byte[] primary, byte[] gainMap)
	{
		if (gainMap == null || gainMap.length == 0)
		{
			Log.d(TAG, "Compose skipped: gain map absent");
			return new ComposeResult(primary, false);
		}
		// Patch the GContainer Item:Length attribute in primary's XMP to match the actual gain-map size
		// BEFORE assembly so MpfPatcher's primarySize calculation reflects the post-patch primary length.
		// Without this, strict GContainer-respecting decoders (Google's libUltraHdr is one) slice the gain
		// map by Item:Length and decode a truncated stream — silent HDR-boost drop on a file that's
		// otherwise correct. MpfPatcher's primarySize cascade is preserved
		// because we re-bind primarySize to patched.length below.
		//
		// Null return means Item:Length couldn't be safely rewritten — either it lives in Extended XMP
		// (>64 KB packet form), or it lives in standard XMP but the segment is unpatchable (over-cap
		// segLen, malformed quote, unterminated digit run). The patcher logs the specific reason at
		// the W level before returning null; we drop HDR here rather than ship stale Item:Length.
		byte[] patched = XmpItemLengthPatcher.patch(primary, gainMap.length);
		if (patched == null)
		{
			Log.w(TAG, "Compose dropped gain map: XmpItemLengthPatcher refused the patch. Returning "
				+ "primary verbatim to avoid shipping stale Item:Length.");
			return new ComposeResult(primary, false);
		}
		int primarySize = patched.length;

		byte[] combined = new byte[primarySize + gainMap.length];
		System.arraycopy(patched, 0, combined, 0, primarySize);
		System.arraycopy(gainMap, 0, combined, primarySize, gainMap.length);

		boolean mpfPatched = MpfPatcher.patch(combined, primarySize);
		if (!mpfPatched)
		{
			Log.w(TAG, "Compose dropped gain map: MPF patch failed (no MPF segment, "
				+ "or malformed/unsupported MPF). Returning patched primary verbatim to "
				+ "avoid shipping orphaned HDR bytes.");
			// Return `patched` (the XMP-Item:Length-cleaned primary) rather than `primary` so the
			// HDR-drop output's XMP no longer carries a stale GContainer Item:Length pointing at a
			// gain map that was never appended. hdrAttached=false signals to the caller (CropExporter)
			// to run stripHdrSegments and toast "[HDR dropped]" — the previous reference-inequality
			// detection broke when `patched` was a freshly-allocated array distinct from `primary`,
			// which made `withGainMap != withFullMeta` true on this drop path and silently shipped
			// a JPEG that still claimed HDR but carried no gain map.
			return new ComposeResult(patched, false);
		}

		Log.d(TAG, "Composed: primary=" + primarySize + " + gainMap=" + gainMap.length
			+ " = " + combined.length + " (MPF patched)");
		return new ComposeResult(combined, true);
	}
}
