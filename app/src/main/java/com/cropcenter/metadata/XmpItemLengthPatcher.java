package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Patches the GContainer Item:Length attribute in the primary's XMP packet to match the actual gain map byte size. The
 * Ultra HDR pipeline preserves the source's XMP byte-identically, but the GContainer Item:Length carries the SOURCE's
 * gain-map size and goes stale on re-encode. Strict decoders (Google's libUltraHdr) slice the gain map by Item:Length
 * and decode a truncated stream — dropping HDR boost on a file that's otherwise correct. Samsung Gallery reads from MPF
 * instead and doesn't see the bug.
 *
 * Operates on the raw primary JPEG bytes BEFORE GainMapComposer appends the gain map — the patch may grow / shrink the
 * primary by the digit-count delta, and MpfPatcher.patch's primarySize must reflect the post-patch length.
 *
 * Multi-item packets: a GContainer directory can declare Item:Length on several items (Google MotionPhoto XMP lists the
 * Primary item's length ahead of the gain map's). Every Item:Length scan tolerates spec-legal XML whitespace around the
 * attribute's '='; when a packet carries more than one occurrence, the patch anchors to the element carrying
 * Item:Semantic="GainMap" and fails closed (empty) when zero or several occurrences anchor — one gainMapSize cannot be
 * assigned across many slots, and a first-match guess would rewrite the wrong item's length.
 *
 * Extended XMP fail-closed: when the source emits a >64 KB XMP packet, Adobe Extended XMP splits it across multiple
 * APP1 segments with per-chunk reassembly headers (32-byte GUID + total length + offset). Patching a chunk in-place
 * would desync those headers, so we refuse the whole patch via an empty return and let GainMapComposer drop HDR rather
 * than ship stale Item:Length.
 */
public final class XmpItemLengthPatcher
{
	/**
	 * One APP1 segment's byte offsets from walkApp1Ranges. Named accessors keep the three-offset contract a
	 * compile-time property — a segStart-vs-bodyStart transposition in the TIFF / XMP pointer math would be a
	 * silent off-by-segment bug with bare int[] indexing.
	 *
	 * @param segStart  offset of the canonical leading FF byte of the FF E1 marker pair (the byte just before
	 *                  the marker code, regardless of how many fill bytes preceded it)
	 * @param bodyStart inclusive start of the segment body, just past the 2-byte segLen field
	 * @param bodyEnd   exclusive end of the segment body
	 */
	private record App1Range(int segStart, int bodyStart, int bodyEnd) {}

	/**
	 * Tagged outcome for the per-segment Item:Length scan + patch step. Distinguishes "the attribute isn't in this
	 * segment at all" (caller falls through to Extended XMP scanning) from "the attribute is here but we can't
	 * safely emit a patched segment" (caller fails closed and drops HDR). Without this distinction, an over-cap or
	 * malformed-quote standard-XMP Item:Length would fall through to the Extended-XMP scan and ship stale
	 * Item:Length when Extended XMP didn't carry the pattern.
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
	private static final byte[] GAINMAP_SEMANTIC_VALUE = "GainMap".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] ITEM_LENGTH_NAME = "Item:Length".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] ITEM_SEMANTIC_NAME = "Item:Semantic".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] XMP_HEADER_BYTES = JpegSegment.XMP_HEADER.getBytes(StandardCharsets.US_ASCII);
	// Backward '<' scan bound for GainMap anchoring — generous for any realistic Container:Item open tag.
	private static final int GAINMAP_ANCHOR_WINDOW_BYTES = 1024;

	private XmpItemLengthPatcher() {}

	/**
	 * Patch the GContainer Item:Length attribute in primary's standard XMP packet to match gainMapSize.
	 *
	 * Three return classes callers (esp. GainMapComposer) MUST distinguish:
	 *   1. Present, new byte array — the standard-XMP segment was patched (length may differ by the size's
	 *      digit-count delta).
	 *   2. Present, SAME reference as the input — no Item:Length in standard or Extended XMP (SDR, or an HDR
	 *      source that omits the declaration), or the recorded value is already correct; caller may safely
	 *      append the gain map. The identity distinction (result reference == input reference) is the signal.
	 *   3. Empty (fail-closed) — Item:Length is present but can't be safely rewritten: it's in an Extended XMP
	 *      chunk (per-chunk headers can't be patched in place), OR in standard XMP but unpatchable (patched
	 *      segLen exceeds the APP1 u16 cap, non-quoted value, empty/unterminated digit run, mismatched closing
	 *      quote), OR the packet carries several Item:Length attributes with zero or multiple occurrences
	 *      anchored to an Item:Semantic="GainMap" element (a first-match guess could rewrite the Primary
	 *      item's length). Caller MUST treat empty as "drop HDR" — shipping the gain map with a stale
	 *      Item:Length silently truncates it in strict decoders (Google's libUltraHdr).
	 *
	 * @param primary     primary JPEG bytes (must start with SOI); must be non-null — the former
	 *                    null-passthrough is now an explicit precondition and throws NullPointerException
	 * @param gainMapSize size of the gain map JPEG to be appended; negative is treated as a caller bug and
	 *                    returns primary unchanged
	 * @return patched primary (success); the input reference (Item:Length absent / already correct); or
	 *         empty (fail-closed — caller must drop HDR)
	 */
	public static Optional<byte[]> patch(byte[] primary, int gainMapSize)
	{
		Objects.requireNonNull(primary, "primary");
		if (gainMapSize < 0)
		{
			return Optional.of(primary);
		}
		// Walk ALL standard XMP segments — a spec-conformant Ultra HDR JPEG carries one, but legacy non-Adobe
		// splitters can emit two (e.g., hdrgm:Version in segment #1, GContainer Directory with Item:Length in
		// segment #2). Returning on the first match would miss Item:Length in later segments and ship stale
		// data when Extended XMP doesn't carry the pattern. Each segment is independently passed through
		// patchInSegment; the first patched result wins, any failClosed result short-circuits, and we only fall
		// through to Extended XMP scanning when ALL standard segments report notPresent. The "first patched
		// wins" convention is deliberate: libUltraHdr reads the FIRST standard XMP packet only, so patching it
		// is sufficient — see XmpItemLengthPatcherTest.patchOnFirstSegmentSkipsSecondSegment which pins this
		// contract.
		List<App1Range> standardXmpRanges = findAllXmpApp1Segments(primary, XMP_HEADER_BYTES);
		for (App1Range xmpRange : standardXmpRanges)
		{
			SegmentPatchResult result = patchInSegment(primary, gainMapSize, xmpRange);
			if (result.bytes() != null)
			{
				return Optional.of(result.bytes());
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
				return Optional.empty();
			}
		}
		// No Item:Length in the standard XMP packet (or no standard XMP at all). Check Extended XMP chunks — if
		// any of them carries Item:Length we fail closed because in-place patching would desync the per-chunk
		// reassembly headers. The vast majority of Ultra HDR files put GContainer in the standard packet, so
		// this is a defensive guard rather than a hot path. Two-stage check, mirroring
		// HdrSignature.hasHdrgmInXmp's pattern: a per-chunk scan catches the common case (any single chunk
		// carries a full Item:Length attribute), then a reassembled-bytes scan catches the straddling case
		// where the attribute crosses a chunk boundary and would slip through the per-chunk scan.
		if (extendedXmpContainsItemLength(primary) || reassembledExtendedXmpContainsItemLength(primary))
		{
			Log.w(TAG, "Item:Length appears to live in Extended XMP — refusing to patch (caller "
				+ "should drop HDR rather than ship stale Item:Length)");
			return Optional.empty();
		}
		return Optional.of(primary);
	}

	private static boolean bytesEqual(byte[] a, int aOff, byte[] pattern, int n)
	{
		// Defensive bounds check: callers in this file pass safe offsets by construction, but a future caller
		// passing a shortened pattern would AIOOBE inside the comparison loop — surfacing as a save-pipeline
		// crash on a user file. Return false on negative-length / out-of-range inputs rather than throw.
		if (n < 0 || aOff < 0 || (long) aOff + n > a.length || n > pattern.length)
		{
			return false;
		}
		for (int i = 0; i < n; i++)
		{
			if (a[aOff + i] != pattern[i])
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Walk all APP1 segments in the primary and return them as JpegSegment records, preserving the marker + 2-byte
	 * length prefix at data[0..3] so ExtendedXmpReassembler's namespace-prefix check (which reads from data[4])
	 * lines up with the standard JpegSegment shape. Pure helper — only used for the Extended XMP reassembly
	 * fallback. Linear walk; bails on malformed segLen / EOF.
	 *
	 * @param primary primary JPEG bytes
	 * @return list of APP1 segments (FF E1) found before the first SOS / EOI; empty if no APP1
	 */
	private static List<JpegSegment> collectApp1Segments(byte[] primary)
	{
		List<JpegSegment> out = new ArrayList<>();
		for (App1Range range : walkApp1Ranges(primary))
		{
			// Slice from the canonical leading FF (segStart) through end-of-segment so
			// JpegSegment.data()[0..3] matches the standard FF MARKER + segLen layout that
			// ExtendedXmpReassembler's namespace-prefix check at data[4] depends on.
			byte[] segData = Arrays.copyOfRange(primary, range.segStart(), range.bodyEnd());
			out.add(new JpegSegment(JpegMarker.APP1, segData));
		}
		return out;
	}

	/**
	 * Search [start, end) for an Item:Semantic attribute whose quoted value is exactly "GainMap", tolerating
	 * optional XML whitespace around '=' and either quote style. Requires the closing quote immediately after the
	 * value so supersets like "GainMapMeta" don't false-positive.
	 *
	 * @param data  bytes to scan
	 * @param start inclusive start offset
	 * @param end   exclusive end offset
	 * @return true when a whitespace-tolerant Item:Semantic="GainMap" assignment lies in [start, end)
	 */
	private static boolean containsGainMapSemantic(byte[] data, int start, int end)
	{
		int cursor = start;
		while (cursor < end)
		{
			int nameStart = findPattern(data, cursor, end, ITEM_SEMANTIC_NAME);
			if (nameStart < 0)
			{
				return false;
			}
			int off = skipXmlWhitespace(data, nameStart + ITEM_SEMANTIC_NAME.length, end);
			if (off < end && data[off] == '=')
			{
				off = skipXmlWhitespace(data, off + 1, end);
				if (off < end && (data[off] == '"' || data[off] == '\''))
				{
					byte quote = data[off];
					int valueEnd = off + 1 + GAINMAP_SEMANTIC_VALUE.length;
					if (valueEnd < end && bytesEqual(data, off + 1, GAINMAP_SEMANTIC_VALUE,
							GAINMAP_SEMANTIC_VALUE.length) && data[valueEnd] == quote)
					{
						return true;
					}
				}
			}
			cursor = nameStart + ITEM_SEMANTIC_NAME.length;
		}
		return false;
	}

	/**
	 * Walk APP1 segments looking for any Extended XMP chunk whose body carries an Item:Length attribute
	 * (whitespace-tolerant around '='). Used as a fail-closed gate when the standard XMP packet didn't carry
	 * Item:Length; we refuse to patch rather than ship stale Item:Length data.
	 *
	 * @param primary JPEG bytes
	 * @return true when at least one Extended XMP chunk's body contains an Item:Length attribute
	 */
	private static boolean extendedXmpContainsItemLength(byte[] primary)
	{
		for (App1Range range : walkApp1Ranges(primary))
		{
			int bodyStart = range.bodyStart();
			int bodyEnd = range.bodyEnd();
			if (bodyStart + EXTENDED_XMP_HEADER_BYTES.length <= bodyEnd
				&& bytesEqual(primary, bodyStart, EXTENDED_XMP_HEADER_BYTES,
					EXTENDED_XMP_HEADER_BYTES.length)
				&& !findItemLengthOccurrences(primary, bodyStart, bodyEnd).isEmpty())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Walk the JPEG marker chain and return every APP1 segment whose body starts with `headerBytes` (the patch path
	 * uses this multi-result form to handle non-conformant multi-standard-XMP files). Stops at SOS / EOI; tolerates
	 * fill bytes and short segments by bailing rather than throwing.
	 *
	 * @param primary     JPEG bytes starting with SOI
	 * @param headerBytes namespace-prefix bytes that identify the desired APP1
	 * @return list of matching App1Range entries in walk order; empty when no matching segment is found
	 */
	private static List<App1Range> findAllXmpApp1Segments(byte[] primary, byte[] headerBytes)
	{
		List<App1Range> matches = new ArrayList<>();
		for (App1Range range : walkApp1Ranges(primary))
		{
			int bodyStart = range.bodyStart();
			int bodyEnd = range.bodyEnd();
			if (bodyStart + headerBytes.length <= bodyEnd
				&& bytesEqual(primary, bodyStart, headerBytes, headerBytes.length))
			{
				matches.add(range);
			}
		}
		return matches;
	}

	/**
	 * Scan [start, end) for Item:Length attribute occurrences, tolerating optional XML whitespace around '='
	 * (attribute syntax per XML 1.0 is Name S? '=' S? Value, and serializers in the wild emit the spaced form). A
	 * byte-literal "Item:Length=" scan misses the spaced form and reports not-present — the caller would then
	 * append the gain map with a stale length, a silent fail-open. A name match not followed by optional whitespace
	 * and '=' is prose or a longer attribute name, not an Item:Length attribute, and is skipped.
	 *
	 * @param data  bytes to scan
	 * @param start inclusive start offset
	 * @param end   exclusive end offset
	 * @return {nameStart, afterEquals} pairs in scan order, where afterEquals is the offset just past
	 *         '=' (whitespace before the value not yet skipped); empty when no occurrence exists
	 */
	private static List<int[]> findItemLengthOccurrences(byte[] data, int start, int end)
	{
		List<int[]> occurrences = new ArrayList<>();
		int cursor = start;
		while (cursor < end)
		{
			int nameStart = findPattern(data, cursor, end, ITEM_LENGTH_NAME);
			if (nameStart < 0)
			{
				break;
			}
			int afterName = nameStart + ITEM_LENGTH_NAME.length;
			int equalsOff = skipXmlWhitespace(data, afterName, end);
			if (equalsOff < end && data[equalsOff] == '=')
			{
				occurrences.add(new int[] { nameStart, equalsOff + 1 });
			}
			cursor = afterName;
		}
		return occurrences;
	}

	/**
	 * Naive byte-pattern search within a sub-range of a buffer. Generic bounded scan behind the Item:Length and
	 * Item:Semantic attribute scanners; the search range is bounded by the XMP segment's own offsets so a stray
	 * match in the surrounding APP1 envelope doesn't trigger a patch. Returns -1 rather than throwing on no-match
	 * so callers can treat absent markup as a "nothing to patch" no-op.
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
	 * Decide whether the Item:Length attribute at nameStart belongs to the GainMap container item. Scans backward
	 * (bounded by GAINMAP_ANCHOR_WINDOW_BYTES) for the enclosing element's '<', then searches that same-element
	 * prefix for Item:Semantic="GainMap". Hitting '>' first means the attribute isn't inside an element open tag;
	 * that, or no '<' within the window, reports unanchored so the caller fails closed. Backward-only by design:
	 * the GContainer serializations this patcher targets (Samsung Ultra HDR, Google MotionPhoto) emit Item:Semantic
	 * before Item:Length, and a semantic-after-length shape falls to fail-closed rather than risking a
	 * cross-element false match.
	 *
	 * @param data      bytes containing the XMP packet
	 * @param bodyStart inclusive lower bound for the backward scan (XMP segment body start)
	 * @param nameStart offset of the "Item:Length" attribute name
	 * @return true when the enclosing element carries Item:Semantic="GainMap" before nameStart
	 */
	private static boolean isGainMapAnchored(byte[] data, int bodyStart, int nameStart)
	{
		int windowStart = Math.max(bodyStart, nameStart - GAINMAP_ANCHOR_WINDOW_BYTES);
		for (int i = nameStart - 1; i >= windowStart; i--)
		{
			if (data[i] == '>')
			{
				return false;
			}
			if (data[i] == '<')
			{
				return containsGainMapSemantic(data, i, nameStart);
			}
		}
		return false;
	}

	private static boolean isXmlWhitespace(byte value)
	{
		// XML 1.0 S production: space, tab, CR, LF.
		return value == ' ' || value == '\t' || value == '\r' || value == '\n';
	}

	/**
	 * Try to patch Item:Length inside the given XMP segment range. A packet can carry several Item:Length
	 * attributes — Google MotionPhoto XMP declares one per Container:Item, with the Primary item's "0" ahead of the
	 * gain map's — so a lone occurrence is patched directly while multiple occurrences anchor to the element
	 * carrying Item:Semantic="GainMap". Zero anchored occurrences among several, or more than one, is unresolvable
	 * ambiguity and fails closed.
	 *
	 * @param primary     full JPEG bytes
	 * @param gainMapSize new gain-map size in bytes to write into Item:Length
	 * @param xmpRange    the segment's offsets from findAllXmpApp1Segments
	 * @return SegmentPatchResult describing the outcome — patched bytes (possibly the input array
	 *         when the value was already correct), the not-present sentinel (caller falls through
	 *         to Extended XMP scanning), or the unpatchable sentinel (caller fails closed)
	 */
	private static SegmentPatchResult patchInSegment(byte[] primary, int gainMapSize, App1Range xmpRange)
	{
		int xmpSegStart = xmpRange.segStart();
		int xmpBodyStart = xmpRange.bodyStart();
		int xmpBodyEnd = xmpRange.bodyEnd();

		List<int[]> occurrences = findItemLengthOccurrences(primary, xmpBodyStart, xmpBodyEnd);
		if (occurrences.isEmpty())
		{
			return SegmentPatchResult.notPresent();
		}
		int[] target = occurrences.get(0);
		if (occurrences.size() > 1)
		{
			target = null;
			for (int[] occurrence : occurrences)
			{
				if (!isGainMapAnchored(primary, xmpBodyStart, occurrence[0]))
				{
					continue;
				}
				if (target != null)
				{
					// Two GainMap-anchored Item:Length slots for one gainMapSize — the same
					// one-value-many-slots ambiguity MpfPatcher refuses for multi-gain-map MPF.
					return SegmentPatchResult.failClosed();
				}
				target = occurrence;
			}
			if (target == null)
			{
				// Several Item:Length attributes, none in a GainMap-semantic element — no safe patch
				// site; a first-match guess would rewrite another item's length and ship the gain map's
				// stale one.
				return SegmentPatchResult.failClosed();
			}
		}

		int valueStart = skipXmlWhitespace(primary, target[1], xmpBodyEnd);
		if (valueStart >= xmpBodyEnd)
		{
			// Name and '=' matched but the body ends before the value — present but truncated, can't safely
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
			// Pattern matched but the digit run is empty / unterminated / not closed by the same quote —
			// malformed. Fail-closed rather than fall through.
			return SegmentPatchResult.failClosed();
		}

		byte[] replacement = String.valueOf(gainMapSize).getBytes(StandardCharsets.US_ASCII);
		int oldLen = digitsEnd - digitsStart;
		int newLen = replacement.length;
		// Already correct — return primary as the success path so the caller keeps its allocation.
		if (oldLen == newLen && bytesEqual(primary, digitsStart, replacement, newLen))
		{
			return SegmentPatchResult.patched(primary);
		}

		int oldSegLen = ByteBufferUtils.readU16BE(primary, xmpSegStart + 2);
		int newSegLen = oldSegLen + (newLen - oldLen);
		// Symmetric defensive guard against newSegLen falling below the spec-minimum (a JPEG segment length
		// field includes the 2 length bytes themselves, so the absolute minimum is 2). Currently unreachable on
		// real input — even an empty XMP body still carries the 29-byte namespace prefix — but the asymmetry
		// with the upper-bound check would let a future caller passing a deliberately short-bodied segment
		// write a malformed segLen.
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
		Log.d(TAG, "Patched Item:Length: " + new String(primary, digitsStart, oldLen, StandardCharsets.US_ASCII)
			+ " → " + gainMapSize + " (segLen " + oldSegLen + " → " + newSegLen + ")");
		return SegmentPatchResult.patched(result);
	}

	/**
	 * Reassemble Adobe Extended XMP chunks via ExtendedXmpReassembler and search each GUID group's payload for an
	 * Item:Length attribute (whitespace-tolerant around '='). Catches the straddle case where the attribute
	 * crosses a chunk boundary within one group (the per-chunk scan in extendedXmpContainsItemLength misses it);
	 * groups scan independently so the attribute can't synthesize across two unrelated XMP documents. Same shape
	 * of fix as HdrSignature.hasHdrgmInXmp's reassembled-bytes fallback.
	 *
	 * @param primary primary JPEG bytes
	 * @return true when any Extended XMP GUID group reassembles to bytes containing an Item:Length attribute
	 */
	private static boolean reassembledExtendedXmpContainsItemLength(byte[] primary)
	{
		List<JpegSegment> segments = collectApp1Segments(primary);
		return ExtendedXmpReassembler.reassemble(segments).stream()
			.anyMatch(group -> !findItemLengthOccurrences(group, 0, group.length).isEmpty());
	}

	private static int skipXmlWhitespace(byte[] data, int off, int end)
	{
		while (off < end && isXmlWhitespace(data[off]))
		{
			off++;
		}
		return off;
	}

	/**
	 * Walk the JPEG marker chain and return every APP1 (FF E1) segment as an App1Range. Single chokepoint
	 * replacing three near-identical inline walkers in this class — the duplication surfaced as a real bug class
	 * when one walker missed the fill-byte fix that was applied to the others. The per-segment mechanics (fill
	 * bytes, standalone markers, length validation) come from JpegMarkerWalker.nextHeadSegment; this walker keeps
	 * only the APP1 filter and the SOS / EOI stop. Malformed or truncated segments bail rather than throw —
	 * adversarial inputs return a partial list, never an exception.
	 *
	 * @param primary JPEG bytes starting with SOI
	 * @return ordered list of APP1 ranges; empty when no APP1 is present before SOS / EOI
	 */
	private static List<App1Range> walkApp1Ranges(byte[] primary)
	{
		List<App1Range> ranges = new ArrayList<>();
		int off = 2;
		while (off < primary.length - 4)
		{
			JpegMarkerWalker.HeadSegment step = JpegMarkerWalker.nextHeadSegment(primary, off,
				primary.length);
			if (step.kind() == JpegMarkerWalker.HeadKind.NO_MARKER)
			{
				break;
			}
			int marker = step.marker();
			if (marker == JpegMarker.EOI || marker == JpegMarker.SOS)
			{
				break;
			}
			if (step.kind() == JpegMarkerWalker.HeadKind.STANDALONE)
			{
				off = step.next();
				continue;
			}
			if (step.kind() != JpegMarkerWalker.HeadKind.SEGMENT)
			{
				break;
			}
			if (marker == JpegMarker.APP1)
			{
				int markerByteOff = step.markerByteOff();
				ranges.add(new App1Range(markerByteOff - 1, markerByteOff + 3, step.next()));
			}
			off = step.next();
		}
		return ranges;
	}
}
