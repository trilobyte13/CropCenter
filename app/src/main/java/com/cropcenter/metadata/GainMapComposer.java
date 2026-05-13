package com.cropcenter.metadata;

import android.util.Log;

/**
 * Appends the HDR gain map JPEG after the primary image and updates MPF offsets.
 */
public final class GainMapComposer
{
	private static final String TAG = "GainMapComposer";

	private GainMapComposer() {}

	/**
	 * Append the gain map to the primary JPEG and fix MPF offsets.
	 *
	 * Returns the primary unchanged in three cases (all of which drop HDR safely):
	 *  - gainMap is null or empty (non-Ultra-HDR source path that still hits this
	 *    method; callers don't need a "no gain map present" special case);
	 *  - `XmpItemLengthPatcher.patch` returns null because the GContainer Item:Length
	 *    attribute can't be safely rewritten — either it lives in Extended XMP, the
	 *    per-chunk pattern straddles a chunk boundary (round-26 F1), OR it lives in
	 *    standard XMP but the segment is unpatchable (over-cap segLen, malformed quote,
	 *    unterminated digit run — round-27 F2 / round-28 F1). Shipping the gain map
	 *    with stale Item:Length silently truncates the gain map in strict GContainer-
	 *    respecting decoders (Google's libUltraHdr is one); dropping HDR before MPF
	 *    patching is the safe choice;
	 *  - MpfPatcher.patch fails (no MPF segment, malformed/unsupported MPF, byte-
	 *    order mismatch, 3+ image MPF without MPType match, negative relative
	 *    offset, etc). Without this guard, compose would ship a JPEG with the
	 *    gain-map bytes appended but no valid MPF entry pointing at them —
	 *    strict decoders (Samsung Gallery's Revert pre-flight) would reject the
	 *    file, lenient decoders that scan for the hdrgm signature would render
	 *    HDR with the wrong offset, and ExportPipeline.reportSuccess (which only
	 *    checks for the hdrgm marker) would still announce "[HDR OK]". Failing
	 *    closed to a clean SDR JPEG is strictly safer than shipping orphaned HDR.
	 *
	 * @param primary  the primary JPEG bytes (with metadata already injected)
	 * @param gainMap  the gain map JPEG bytes (preserved from original file), or
	 *                 null / empty to leave primary unchanged
	 * @return combined JPEG bytes with corrected MPF offsets when both the XMP
	 *         Item:Length patch and MPF patch succeed, or primary verbatim when
	 *         gainMap is absent / Item:Length is unpatchable / MPF patching fails
	 */
	public static byte[] compose(byte[] primary, byte[] gainMap)
	{
		if (gainMap == null || gainMap.length == 0)
		{
			Log.d(TAG, "Compose skipped: gain map absent");
			return primary;
		}
		// Patch the GContainer Item:Length attribute in primary's XMP to match the actual gain-map size
		// BEFORE assembly so MpfPatcher's primarySize calculation reflects the post-patch primary length.
		// Without this, strict GContainer-respecting decoders (Google's libUltraHdr is one) slice the gain
		// map by Item:Length and decode a truncated stream — silent HDR-boost drop on a file that's
		// otherwise correct (Codex round-23 follow-up). MpfPatcher's primarySize cascade is preserved
		// because we re-bind primarySize to patched.length below.
		//
		// Null return means Item:Length couldn't be safely rewritten — either it lives in Extended XMP
		// (>64 KB packet form), or it lives in standard XMP but the segment is unpatchable (over-cap
		// segLen, malformed quote, unterminated digit run). The patcher logs the specific reason at
		// the W level before returning null; we just drop HDR here rather than ship stale Item:Length
		// (Codex round-25 F1, round-27 F2, round-29 logic).
		byte[] patched = XmpItemLengthPatcher.patch(primary, gainMap.length);
		if (patched == null)
		{
			Log.w(TAG, "Compose dropped gain map: XmpItemLengthPatcher refused the patch. Returning "
				+ "primary verbatim to avoid shipping stale Item:Length.");
			return primary;
		}
		int primarySize = patched.length;

		byte[] combined = new byte[primarySize + gainMap.length];
		System.arraycopy(patched, 0, combined, 0, primarySize);
		System.arraycopy(gainMap, 0, combined, primarySize, gainMap.length);

		boolean mpfPatched = MpfPatcher.patch(combined, primarySize);
		if (!mpfPatched)
		{
			Log.w(TAG, "Compose dropped gain map: MPF patch failed (no MPF segment, "
				+ "or malformed/unsupported MPF). Returning primary verbatim to avoid "
				+ "shipping orphaned HDR bytes.");
			return primary;
		}

		Log.d(TAG, "Composed: primary=" + primarySize + " + gainMap=" + gainMap.length
			+ " = " + combined.length + " (MPF patched)");
		return combined;
	}
}
