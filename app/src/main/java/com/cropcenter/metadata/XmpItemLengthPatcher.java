package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Patches the GContainer Item:Length attribute in the primary's XMP packet to match the actual gain map
 * byte size. The Ultra HDR pipeline preserves the source's XMP byte-identically, but the GContainer
 * Item:Length carries the SOURCE's gain-map size and goes stale on re-encode. Strict decoders (Google's
 * libUltraHdr) slice the gain map by Item:Length and decode a truncated stream — dropping HDR boost on a
 * file that's otherwise correct. Samsung Gallery reads from MPF instead and doesn't see the bug.
 *
 * Operates on the raw primary JPEG bytes BEFORE GainMapComposer appends the gain map — the patch may
 * grow / shrink the primary by the digit-count delta, and MpfPatcher.patch's primarySize must reflect
 * the post-patch length.
 *
 * Extended XMP fail-closed: when the source emits a >64 KB XMP packet, Adobe Extended XMP splits it
 * across multiple APP1 segments with per-chunk reassembly headers (32-byte GUID + total length +
 * offset). Patching a chunk in-place would desync those headers, so we refuse the whole patch via a
 * null return and let GainMapComposer drop HDR rather than ship stale Item:Length.
 */
public final class XmpItemLengthPatcher
{
	/**
	 * Tagged outcome for the per-segment Item:Length scan + patch step. Distinguishes "the attribute
	 * isn't in this segment at all" (caller falls through to Extended XMP scanning) from "the
	 * attribute is here but we can't safely emit a patched segment" (caller fails closed and drops
	 * HDR). Without this distinction, an over-cap or malformed-quote standard-XMP Item:Length would
	 * fall through to the Extended-XMP scan and ship stale Item:Length when Extended XMP didn't
	 * carry the pattern.
	 *
	 * @param bytes       patched primary bytes on success (may be the same reference as input when
	 *                    the value was already correct); null when the patch did not produce output
	 * @param unpatchable true when Item:Length was located in the segment but could not be safely
	 *                    rewritten — fail-closed for the caller; mutually exclusive with bytes != null
	 */
	private record SegmentPatchResult(byte[] bytes, boolean unpatchable)
	{
		static SegmentPatchResult failClosed()
		{
			return new SegmentPatchResult(null, true);
		}

		static SegmentPatchResult notPresent()
		{
			return new SegmentPatchResult(null, false);
		}

		static SegmentPatchResult patched(byte[] bytes)
		{
			return new SegmentPatchResult(bytes, false);
		}
	}

	private static final String TAG = "XmpItemLengthPatcher";
	private static final byte[] EXTENDED_XMP_HEADER_BYTES =
		JpegSegment.EXTENDED_XMP_HEADER.getBytes(StandardCharsets.US_ASCII);
	private static final byte[] PATTERN = "Item:Length=".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] XMP_HEADER_BYTES = JpegSegment.XMP_HEADER.getBytes(StandardCharsets.US_ASCII);

	private XmpItemLengthPatcher() {}

	/**
	 * Patch the GContainer Item:Length attribute in primary's standard XMP packet to match gainMapSize.
	 *
	 * Three return classes callers (esp. GainMapComposer) MUST distinguish:
	 *   1. New byte array — the standard-XMP segment was patched (length may differ by the size's digit-count
	 *      delta).
	 *   2. Input reference unchanged — no Item:Length in standard or Extended XMP (SDR, or an HDR source that
	 *      omits the declaration); caller may safely append the gain map.
	 *   3. null (fail-closed) — Item:Length is present but can't be safely rewritten: it's in an Extended XMP
	 *      chunk (per-chunk headers can't be patched in place), OR in standard XMP but unpatchable (patched
	 *      segLen exceeds the APP1 u16 cap, non-quoted value, empty/unterminated digit run, mismatched closing
	 *      quote). Caller MUST treat null as "drop HDR" — shipping the gain map with a stale Item:Length
	 *      silently truncates it in strict decoders (Google's libUltraHdr).
	 *
	 * @param primary     primary JPEG bytes (must start with SOI); null returns null verbatim
	 * @param gainMapSize size of the gain map JPEG to be appended; negative is treated as a caller bug and
	 *                    returns primary unchanged
	 * @return patched primary (success); the input reference (Item:Length absent / already correct); or null
	 *         (fail-closed — caller must drop HDR)
	 */
	public static byte[] patch(byte[] primary, int gainMapSize)
	{
		if (primary == null || gainMapSize < 0)
		{
			return primary;
		}
		// Walk ALL standard XMP segments — a spec-conformant Ultra HDR JPEG carries one, but legacy
		// non-Adobe splitters can emit two (e.g., hdrgm:Version in segment #1, GContainer Directory
		// with Item:Length in segment #2). Returning on the first match would miss Item:Length in
		// later segments and ship stale data when Extended XMP doesn't carry the pattern. Each segment
		// is independently passed through patchInSegment; the first
		// patched result wins, any failClosed result short-circuits, and we only fall through to
		// Extended XMP scanning when ALL standard segments report notPresent. The "first patched wins"
		// convention is deliberate: libUltraHdr reads the FIRST standard XMP packet only, so patching
		// it is sufficient — see XmpItemLengthPatcherTest.patchOnFirstSegmentSkipsSecondSegment which
		// pins this contract.
		List<int[]> standardXmpRanges = findAllXmpApp1Segments(primary, XMP_HEADER_BYTES);
		for (int[] xmpRange : standardXmpRanges)
		{
			SegmentPatchResult result = patchInSegment(primary, gainMapSize, xmpRange);
			if (result.bytes() != null)
			{
				return result.bytes();
			}
			if (result.unpatchable())
			{
				// Item:Length located in standard XMP but the patched segment would exceed the APP1
				// length-field cap, the value is malformed (truncated quote, non-digit run), or the
				// re-emitted body would otherwise corrupt the segment. Fail closed rather than fall
				// through to Extended XMP — falling through would ship the stale standard-XMP
				// Item:Length when Extended XMP didn't carry the pattern.
				Log.w(TAG, "Item:Length located in standard XMP but unpatchable — refusing to patch "
					+ "(caller should drop HDR rather than ship stale Item:Length)");
				return null;
			}
		}
		// No Item:Length in the standard XMP packet (or no standard XMP at all). Check Extended XMP
		// chunks — if any of them carries Item:Length we fail closed because in-place patching would
		// desync the per-chunk reassembly headers. The vast majority of Ultra HDR files put GContainer
		// in the standard packet, so this is a defensive guard rather than a hot path.
		//
		// Two-stage check, mirroring HdrSignature.hasHdrgmInXmp's pattern: a per-chunk substring scan
		// catches the common case (any single chunk carries the full "Item:Length=" pattern), then a
		// reassembled-bytes scan catches the straddling case where the 12-byte pattern crosses a chunk
		// boundary and would slip through the per-chunk scan.
		if (extendedXmpContainsItemLength(primary)
			|| reassembledExtendedXmpContainsItemLength(primary))
		{
			Log.w(TAG, "Item:Length appears to live in Extended XMP — refusing to patch (caller "
				+ "should drop HDR rather than ship stale Item:Length)");
			return null;
		}
		return primary;
	}

	private static boolean bytesEqual(byte[] a, int aOff, byte[] b, int bOff, int n)
	{
		// Defensive bounds check: callers in this file pass safe offsets by construction, but a
		// future caller passing a shortened pattern would AIOOBE inside the comparison loop —
		// surfacing as a save-pipeline crash on a user file. Return false on negative-length /
		// out-of-range inputs rather than throw.
		if (n < 0 || aOff < 0 || bOff < 0
			|| (long) aOff + n > a.length || (long) bOff + n > b.length)
		{
			return false;
		}
		for (int i = 0; i < n; i++)
		{
			if (a[aOff + i] != b[bOff + i])
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Walk all APP1 segments in the primary and return them as JpegSegment records, preserving the
	 * marker + 2-byte length prefix at data[0..3] so ExtendedXmpReassembler's namespace-prefix check
	 * (which reads from data[4]) lines up with the standard JpegSegment shape. Pure helper — only used
	 * for the Extended XMP reassembly fallback. Linear walk; bails on malformed segLen / EOF.
	 *
	 * @param primary primary JPEG bytes
	 * @return list of APP1 segments (FF E1) found before the first SOS / EOI; empty if no APP1
	 */
	private static List<JpegSegment> collectApp1Segments(byte[] primary)
	{
		List<JpegSegment> out = new ArrayList<>();
		for (int[] range : walkApp1Ranges(primary))
		{
			// Slice from the canonical leading FF (range[0]) through end-of-segment so
			// JpegSegment.data()[0..3] matches the standard FF MARKER + segLen layout that
			// ExtendedXmpReassembler's namespace-prefix check at data[4] depends on.
			int segStart = range[0];
			int bodyEnd = range[2];
			int segTotal = bodyEnd - segStart;
			byte[] segData = new byte[segTotal];
			System.arraycopy(primary, segStart, segData, 0, segTotal);
			out.add(new JpegSegment(JpegMarker.APP1, segData));
		}
		return out;
	}

	/**
	 * Walk APP1 segments looking for any Extended XMP chunk whose body contains the Item:Length pattern.
	 * Used as a fail-closed gate when the standard XMP packet didn't carry Item:Length; we refuse to
	 * patch rather than ship stale Item:Length data.
	 *
	 * @param primary JPEG bytes
	 * @return true when at least one Extended XMP chunk's body contains "Item:Length="
	 */
	private static boolean extendedXmpContainsItemLength(byte[] primary)
	{
		for (int[] range : walkApp1Ranges(primary))
		{
			int bodyStart = range[1];
			int bodyEnd = range[2];
			if (bodyStart + EXTENDED_XMP_HEADER_BYTES.length <= bodyEnd
				&& bytesEqual(primary, bodyStart, EXTENDED_XMP_HEADER_BYTES, 0,
					EXTENDED_XMP_HEADER_BYTES.length)
				&& findPattern(primary, bodyStart, bodyEnd, PATTERN) >= 0)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Walk the JPEG marker chain and return every APP1 segment whose body starts with `headerBytes`
	 * (the patch path uses this multi-result form to handle non-conformant multi-standard-XMP files).
	 * Stops at SOS / EOI; tolerates fill bytes and short segments by bailing rather than throwing.
	 *
	 * @param primary     JPEG bytes starting with SOI
	 * @param headerBytes namespace-prefix bytes that identify the desired APP1
	 * @return list of three-element {segStart, bodyStart, bodyEnd} ranges in walk order; empty when
	 *         no matching segment is found
	 */
	private static List<int[]> findAllXmpApp1Segments(byte[] primary, byte[] headerBytes)
	{
		List<int[]> matches = new ArrayList<>();
		for (int[] range : walkApp1Ranges(primary))
		{
			int bodyStart = range[1];
			int bodyEnd = range[2];
			if (bodyStart + headerBytes.length <= bodyEnd
				&& bytesEqual(primary, bodyStart, headerBytes, 0, headerBytes.length))
			{
				matches.add(range);
			}
		}
		return matches;
	}

	/**
	 * Naive byte-pattern search within a sub-range of a buffer. Used by the XMP item-length patch site
	 * scanner to locate the `<rdf:Description ...>` opening tag inside an XMP segment; the search range
	 * is bounded by the XMP segment's own offsets so a stray match in the surrounding APP1 envelope
	 * doesn't trigger a patch. Returns -1 rather than throwing on no-match so callers can treat absent
	 * markup as a "nothing to patch" no-op.
	 *
	 * @param data    bytes to scan
	 * @param start   inclusive start offset
	 * @param end     exclusive end offset
	 * @param pattern bytes to find
	 * @return start offset of the first match, or -1 when no match is found in [start, end)
	 */
	private static int findPattern(byte[] data, int start, int end, byte[] pattern)
	{
		int limit = end - pattern.length;
		outer:
		for (int i = start; i <= limit; i++)
		{
			for (int j = 0; j < pattern.length; j++)
			{
				if (data[i + j] != pattern[j])
				{
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	/**
	 * Try to patch Item:Length inside the given XMP segment range. Returns a new byte array on
	 * successful patch (may differ in length from primary by the digit-count delta), null when the
	 * segment doesn't contain Item:Length or the patched segment would exceed the APP1 segLen-field
	 * cap.
	 *
	 * @param primary     full JPEG bytes
	 * @param gainMapSize new gain-map size in bytes to write into Item:Length
	 * @param xmpRange    three-element {segStart, bodyStart, bodyEnd} from findAllXmpApp1Segments
	 * @return SegmentPatchResult describing the outcome — patched bytes (possibly the input array
	 *         when the value was already correct), the not-present sentinel (caller falls through
	 *         to Extended XMP scanning), or the unpatchable sentinel (caller fails closed)
	 */
	private static SegmentPatchResult patchInSegment(byte[] primary, int gainMapSize, int[] xmpRange)
	{
		int xmpSegStart = xmpRange[0];
		int xmpBodyStart = xmpRange[1];
		int xmpBodyEnd = xmpRange[2];

		int patternStart = findPattern(primary, xmpBodyStart, xmpBodyEnd, PATTERN);
		if (patternStart < 0)
		{
			return SegmentPatchResult.notPresent();
		}
		int valueStart = patternStart + PATTERN.length;
		if (valueStart >= xmpBodyEnd)
		{
			// Pattern matched but the body ends mid-attribute — present but truncated, can't safely
			// re-emit. Fail-closed rather than fall through.
			return SegmentPatchResult.failClosed();
		}
		byte quote = primary[valueStart];
		if (quote != '"' && quote != '\'')
		{
			// Pattern matched but the value isn't quoted ASCII — malformed XMP attribute. Fail-closed.
			return SegmentPatchResult.failClosed();
		}
		int digitsStart = valueStart + 1;
		int digitsEnd = digitsStart;
		while (digitsEnd < xmpBodyEnd && primary[digitsEnd] >= '0' && primary[digitsEnd] <= '9')
		{
			digitsEnd++;
		}
		if (digitsEnd == digitsStart || digitsEnd >= xmpBodyEnd || primary[digitsEnd] != quote)
		{
			// Pattern matched but the digit run is empty / unterminated / not closed by the same
			// quote — malformed. Fail-closed rather than fall through.
			return SegmentPatchResult.failClosed();
		}

		byte[] replacement = String.valueOf(gainMapSize).getBytes(StandardCharsets.US_ASCII);
		int oldLen = digitsEnd - digitsStart;
		int newLen = replacement.length;
		// Already correct — return primary as the success path so the caller keeps its allocation.
		if (oldLen == newLen && bytesEqual(primary, digitsStart, replacement, 0, newLen))
		{
			return SegmentPatchResult.patched(primary);
		}

		int oldSegLen = ByteBufferUtils.readU16BE(primary, xmpSegStart + 2);
		int newSegLen = oldSegLen + (newLen - oldLen);
		// Symmetric defensive guard against newSegLen falling below the spec-minimum (a JPEG segment
		// length field includes the 2 length bytes themselves, so the absolute minimum is 2). Currently
		// unreachable on real input — even an empty XMP body still carries the 29-byte namespace prefix
		// — but the asymmetry with the upper-bound check would let a future caller passing a deliberately
		// short-bodied segment write a malformed segLen.
		if (newSegLen < 2 || newSegLen > JpegSegment.MAX_SEGMENT_BYTES)
		{
			Log.w(TAG, "Patched XMP segLen field out of range [2, " + JpegSegment.MAX_SEGMENT_BYTES + "] ("
				+ newSegLen + "); refusing to patch");
			return SegmentPatchResult.failClosed();
		}

		int delta = newLen - oldLen;
		byte[] result = new byte[primary.length + delta];
		System.arraycopy(primary, 0, result, 0, digitsStart);
		System.arraycopy(replacement, 0, result, digitsStart, newLen);
		System.arraycopy(primary, digitsEnd, result, digitsStart + newLen, primary.length - digitsEnd);
		ByteBufferUtils.writeU16BE(result, xmpSegStart + 2, newSegLen);
		Log.d(TAG, "Patched Item:Length: "
			+ new String(primary, digitsStart, oldLen, StandardCharsets.US_ASCII)
			+ " → " + gainMapSize + " (segLen " + oldSegLen + " → " + newSegLen + ")");
		return SegmentPatchResult.patched(result);
	}

	/**
	 * Reassemble Adobe Extended XMP chunks via ExtendedXmpReassembler and search the concatenated
	 * payload for the Item:Length pattern. Catches the straddle case where the 12-byte
	 * "Item:Length=" pattern crosses a chunk boundary (the per-chunk scan in
	 * extendedXmpContainsItemLength misses it). Same shape of fix as HdrSignature.hasHdrgmInXmp's
	 * reassembled-bytes fallback.
	 *
	 * @param primary primary JPEG bytes
	 * @return true when Extended XMP reassembles to bytes containing the Item:Length pattern
	 */
	private static boolean reassembledExtendedXmpContainsItemLength(byte[] primary)
	{
		List<JpegSegment> segments = collectApp1Segments(primary);
		byte[] reassembled = ExtendedXmpReassembler.reassemble(segments);
		return reassembled.length > 0
			&& findPattern(reassembled, 0, reassembled.length, PATTERN) >= 0;
	}

	/**
	 * Walk the JPEG marker chain and return every APP1 (FF E1) segment as a {segStart, bodyStart,
	 * bodyEnd} int[] range. Single chokepoint replacing three near-identical inline walkers in this
	 * class — the duplication surfaced as a real bug class when one walker missed the fill-byte fix
	 * that was applied to the others. Stops at SOS / EOI; tolerates
	 * fill bytes (`FF FF MARKER ...`), standalone markers (RST / STUFFING / TEM), and short /
	 * truncated segments by bailing rather than throwing — adversarial inputs return a partial list,
	 * never an exception.
	 *
	 * `segStart` is the canonical leading FF byte of the FF MARKER pair (the byte just before the
	 * marker code), so consumers' `data[segStart .. segStart+1]` is always `[FF, marker]` regardless
	 * of how many fill bytes preceded the marker. `bodyStart` and `bodyEnd` bracket the segment body
	 * that follows the 2-byte segLen field.
	 *
	 * @param primary JPEG bytes starting with SOI
	 * @return ordered list of APP1 ranges; empty when no APP1 is present before SOS / EOI
	 */
	private static List<int[]> walkApp1Ranges(byte[] primary)
	{
		List<int[]> ranges = new ArrayList<>();
		int off = 2;
		while (off < primary.length - 4)
		{
			if ((primary[off] & 0xFF) != 0xFF)
			{
				break;
			}
			int markerByteOff = JpegMarkerWalker.skipFillBytes(primary, off, primary.length);
			if (markerByteOff < 0)
			{
				break;
			}
			int marker = primary[markerByteOff] & 0xFF;
			int afterMarker = markerByteOff + 1;
			if (marker == JpegMarker.EOI || marker == JpegMarker.SOS)
			{
				break;
			}
			if (marker == JpegMarker.STUFFING || marker == JpegMarker.TEM
				|| (marker >= JpegMarker.RST_FIRST && marker <= JpegMarker.RST_LAST))
			{
				off = afterMarker;
				continue;
			}
			if (afterMarker + 2 > primary.length)
			{
				break;
			}
			int segLen = ByteBufferUtils.readU16BE(primary, afterMarker);
			// `next < afterMarker` guards against `afterMarker + segLen` wrapping int-negative on
			// adversarial inputs with primary.length near Integer.MAX_VALUE.
			int next = afterMarker + segLen;
			if (segLen < 2 || next < afterMarker || next > primary.length)
			{
				break;
			}
			if (marker == JpegMarker.APP1)
			{
				ranges.add(new int[] { markerByteOff - 1, afterMarker + 2, next });
			}
			off = next;
		}
		return ranges;
	}
}
