package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Patches the GContainer Item:Length attribute in the primary's XMP packet to match the actual gain map
 * byte size. The Ultra HDR pipeline preserves the source's XMP byte-identically (so all the hdrgm
 * attributes — Version, GainMapMin / Max, Gamma, OffsetSDR / HDR, HDRCapacityMin / Max — round-trip
 * unchanged), but the Container Item:Length attribute on the gain-map item carries the SOURCE's gain-map
 * size and goes stale the moment we re-encode. Strict GContainer-respecting decoders (Google's
 * libUltraHdr is one) slice the gain map by Item:Length and would decode a truncated stream — dropping
 * HDR boost on a file that's otherwise correct. Samsung Gallery is more lenient and reads the gain map
 * straight from the MPF table, so it doesn't see the bug; not all HDR-aware tooling is.
 *
 * Operates on the raw primary JPEG bytes BEFORE GainMapComposer appends the gain map and patches MPF —
 * the patch may grow / shrink the primary by the digit-count delta of the new size, and downstream
 * MpfPatcher.patch's primarySize must reflect the post-patch length.
 *
 * Single-target match: the patch updates the FIRST Item:Length found in the standard XMP packet. Per
 * the GContainer schema only the GainMap item carries Item:Length (Primary's length is implicit), so
 * a single match is the spec-conformant case. Multi-gain-map XMP packets are rare and would already
 * have been rejected upstream by MpfPatcher's >1 MPType-match guard, leaving the gain map dropped and
 * this patcher uninvoked.
 *
 * Extended XMP fail-closed: when the source emits a >64 KB XMP packet, Adobe Extended XMP splits it
 * across multiple APP1 segments with a different namespace prefix and per-chunk reassembly headers
 * (32-byte GUID + 4-byte total length + 4-byte offset). Patching one of those chunks in-place would
 * desync the per-chunk total-length / offset headers shared across all chunks of the same GUID —
 * complex enough that we instead refuse the whole patch, signal the caller via a null return, and let
 * GainMapComposer drop HDR rather than ship a file with stale Item:Length (Codex round-25 F1). For the
 * standard ~99% case where Item:Length lives in the main XMP packet, the patch path is unchanged.
 */
public final class XmpItemLengthPatcher
{
	/**
	 * Tagged outcome for the per-segment Item:Length scan + patch step. Distinguishes "the attribute
	 * isn't in this segment at all" (caller falls through to Extended XMP scanning) from "the
	 * attribute is here but we can't safely emit a patched segment" (caller fails closed and drops
	 * HDR). Without this distinction, an over-cap or malformed-quote standard-XMP Item:Length would
	 * fall through to the Extended-XMP scan and ship stale Item:Length when Extended XMP didn't
	 * carry the pattern (Codex round-27 F2).
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

	// JPEG APP segment max — segLen field is u16 (max 65535), and segLen INCLUDES the 2 length bytes
	// themselves. A patched XMP that would push the segment field past 65535 can't be emitted as a
	// single APP1 — refuse to patch in that case rather than truncate (Codex round-25 F2: previous
	// 65533 was the body cap, not the segLen field cap, and unnecessarily rejected segLen 65534-65535).
	private static final int APP_MAX_SEG_LEN_FIELD = 65535;
	private static final byte[] EXTENDED_XMP_HEADER_BYTES =
		"http://ns.adobe.com/xmp/extension/\0".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] PATTERN = "Item:Length=".getBytes(StandardCharsets.US_ASCII);
	private static final String TAG = "XmpItemLengthPatcher";
	private static final byte[] XMP_HEADER_BYTES = JpegSegment.XMP_HEADER.getBytes(StandardCharsets.US_ASCII);

	private XmpItemLengthPatcher() {}

	/**
	 * Patch the GContainer Item:Length attribute in primary's standard XMP packet to match gainMapSize.
	 * Returns input unchanged when no XMP segment is found, no Item:Length attribute is present in
	 * either the standard or the Extended XMP packets, the value is already correct, or the patched
	 * segment would exceed the APP1 length-field cap.
	 *
	 * Returns null (fail-closed) when Item:Length lives in an Extended XMP chunk — patching across
	 * chunk-boundary reassembly headers is beyond this helper's scope, and shipping with a stale
	 * Item:Length would silently truncate the gain map for strict GContainer-respecting decoders.
	 * Caller (GainMapComposer) must check for null and drop HDR rather than ship the inconsistent file.
	 *
	 * @param primary     primary JPEG bytes (must start with SOI)
	 * @param gainMapSize size in bytes of the gain map JPEG that will be appended after primary
	 * @return new byte array with Item:Length updated and APP1 segLen patched (length may differ from
	 *         input by the digit-count delta of the new size); the input array reference unchanged
	 *         when no patch was applied; or null when Extended XMP carries Item:Length and the caller
	 *         should drop HDR
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
		// later segments and ship stale data when Extended XMP doesn't carry the pattern (Codex
		// round-28 F1). Each segment is independently passed through patchInSegment; the first
		// patched result wins, any failClosed result short-circuits, and we only fall through to
		// Extended XMP scanning when ALL standard segments report notPresent.
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
				// Item:Length when Extended XMP didn't carry the pattern (Codex round-27 F2).
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
		// boundary and would slip through the per-chunk scan (Codex round-26 F1).
		if (extendedXmpContainsItemLength(primary)
			|| reassembledExtendedXmpContainsItemLength(primary))
		{
			Log.w(TAG, "Item:Length appears to live in Extended XMP — refusing to patch (caller "
				+ "should drop HDR rather than ship stale Item:Length)");
			return null;
		}
		return primary;
	}

	/**
	 * Compare n bytes of two arrays starting at the given offsets. Pure helper — used to short-circuit
	 * the patch when the existing digits already equal the new gain-map size, sparing an allocation.
	 *
	 * @param a    first array
	 * @param aOff start offset into a
	 * @param b    second array
	 * @param bOff start offset into b
	 * @param n    number of bytes to compare
	 * @return true when all n bytes match
	 */
	private static boolean bytesEqual(byte[] a, int aOff, byte[] b, int bOff, int n)
	{
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
		int off = 2;
		while (off < primary.length - 4)
		{
			if ((primary[off] & 0xFF) != 0xFF)
			{
				break;
			}
			int marker = primary[off + 1] & 0xFF;
			if (marker == JpegMarker.EOI || marker == JpegMarker.SOS)
			{
				break;
			}
			if (marker == JpegMarker.STUFFING || marker == JpegMarker.TEM || marker == 0xFF
				|| (marker >= JpegMarker.RST_FIRST && marker <= JpegMarker.RST_LAST))
			{
				off += 2;
				continue;
			}
			if (off + 4 > primary.length)
			{
				break;
			}
			int segLen = ByteBufferUtils.readU16BE(primary, off + 2);
			if (segLen < 2 || off + 2 + segLen > primary.length)
			{
				break;
			}
			if (marker == 0xE1)
			{
				int segTotal = 2 + segLen;
				byte[] segData = new byte[segTotal];
				System.arraycopy(primary, off, segData, 0, segTotal);
				out.add(new JpegSegment(marker, segData));
			}
			off += 2 + segLen;
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
		int off = 2;
		while (off < primary.length - 4)
		{
			if ((primary[off] & 0xFF) != 0xFF)
			{
				break;
			}
			int marker = primary[off + 1] & 0xFF;
			if (marker == JpegMarker.EOI || marker == JpegMarker.SOS)
			{
				break;
			}
			if (marker == JpegMarker.STUFFING || marker == JpegMarker.TEM || marker == 0xFF
				|| (marker >= JpegMarker.RST_FIRST && marker <= JpegMarker.RST_LAST))
			{
				off += 2;
				continue;
			}
			if (off + 4 > primary.length)
			{
				break;
			}
			int segLen = ByteBufferUtils.readU16BE(primary, off + 2);
			if (segLen < 2 || off + 2 + segLen > primary.length)
			{
				break;
			}
			if (marker == 0xE1)
			{
				int bodyStart = off + 4;
				int bodyEnd = off + 2 + segLen;
				if (bodyStart + EXTENDED_XMP_HEADER_BYTES.length <= bodyEnd
					&& bytesEqual(primary, bodyStart, EXTENDED_XMP_HEADER_BYTES, 0,
						EXTENDED_XMP_HEADER_BYTES.length)
					&& findPattern(primary, bodyStart, bodyEnd, PATTERN) >= 0)
				{
					return true;
				}
			}
			off += 2 + segLen;
		}
		return false;
	}

	/**
	 * Walk the JPEG marker chain and return every APP1 segment whose body starts with `headerBytes`
	 * (the patch path uses this multi-result form to handle non-conformant multi-standard-XMP files —
	 * Codex round-28 F1). Stops at
	 * SOS / EOI; tolerates fill bytes and short segments by bailing rather than throwing.
	 *
	 * @param primary     JPEG bytes starting with SOI
	 * @param headerBytes namespace-prefix bytes that identify the desired APP1
	 * @return list of three-element {segStart, bodyStart, bodyEnd} ranges in walk order; empty when
	 *         no matching segment is found
	 */
	private static List<int[]> findAllXmpApp1Segments(byte[] primary, byte[] headerBytes)
	{
		List<int[]> matches = new ArrayList<>();
		int off = 2;
		while (off < primary.length - 4)
		{
			if ((primary[off] & 0xFF) != 0xFF)
			{
				break;
			}
			int marker = primary[off + 1] & 0xFF;
			if (marker == JpegMarker.EOI || marker == JpegMarker.SOS)
			{
				break;
			}
			if (marker == JpegMarker.STUFFING || marker == JpegMarker.TEM || marker == 0xFF
				|| (marker >= JpegMarker.RST_FIRST && marker <= JpegMarker.RST_LAST))
			{
				off += 2;
				continue;
			}
			if (off + 4 > primary.length)
			{
				break;
			}
			int segLen = ByteBufferUtils.readU16BE(primary, off + 2);
			if (segLen < 2 || off + 2 + segLen > primary.length)
			{
				break;
			}
			if (marker == 0xE1)
			{
				int bodyStart = off + 4;
				int bodyEnd = off + 2 + segLen;
				if (bodyStart + headerBytes.length <= bodyEnd
					&& bytesEqual(primary, bodyStart, headerBytes, 0, headerBytes.length))
				{
					matches.add(new int[] { off, bodyStart, bodyEnd });
				}
			}
			off += 2 + segLen;
		}
		return matches;
	}

	/**
	 * Locate the byte offset just past the standard XMP APP1 segment's body containing pattern. Linear
	 * search (the XMP packet is at most ~64 KB; pattern is 12 bytes; ~5µs typical).
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
	 * cap. Extracted so the patch step can be re-used for both the standard XMP segment and (if we
	 * later support it) Extended XMP chunks.
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
		if (newSegLen > APP_MAX_SEG_LEN_FIELD)
		{
			Log.w(TAG, "Patched XMP segLen field would exceed " + APP_MAX_SEG_LEN_FIELD + " ("
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
	 * round-22 logic F2.
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
}
