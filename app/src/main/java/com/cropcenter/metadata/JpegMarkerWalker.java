package com.cropcenter.metadata;

import com.cropcenter.util.ByteBufferUtils;

/**
 * Canonical JPEG marker walker. Two consolidated walk families live here so a bug fixed once applies to every
 * consumer by construction: findEoi / findPrimaryEoi (the SOS / EOI / RST / segment-length / overflow-guard walk to
 * the byte past a JPEG's EOI, shared by CropExporter, GraftWriter, and GainMapExtractor) and nextHeadSegment (the
 * per-segment head-walk cursor shared by every leading-APP/COM iterator — JpegMetadataExtractor,
 * XmpItemLengthPatcher, MpfPatcher, JpegMetadataInjector, GraftWriter, and BitmapUtils).
 *
 * Static, no state — every call carries its own (file, endBound) pair. The endBound parameter covers both the "scan to
 * file.length" and "scan to SEFT-aware boundary" cases: callers that don't need to bound past a trailer pass
 * file.length; callers that strip a known trailer (GainMapExtractor) pass the trailer's start.
 *
 * Per the JPEG spec (ITU-T T.81 / ISO/IEC 10918-1):
 *   - Markers are FF XX where XX is one of the JpegMarker constants
 *   - Standalone markers (STUFFING, TEM, RST_FIRST..RST_LAST) have no length field — advance by 2
 *   - Segment markers carry a 2-byte big-endian length field that INCLUDES the 2 length bytes themselves —
 *     advance by 2 + segLen
 *   - SOS begins entropy-coded data; walk byte-by-byte (honouring byte-stuff FF 00 and restart markers) until
 *     the next real marker
 */
public final class JpegMarkerWalker
{
	/**
	 * Classification of one head-walk step from nextHeadSegment. Tells the caller which HeadSegment components
	 * carry valid facts — see the nextHeadSegment Javadoc for the component validity per kind.
	 */
	public enum HeadKind
	{
		// No marker byte reachable: file[off] is not 0xFF, or the fill-byte run hits endBound.
		NO_MARKER,
		// Standalone marker (TEM / STUFFING / RST) with no length field.
		STANDALONE,
		// Length-bearing marker whose 2-byte length field extends past endBound or reads below the spec
		// minimum of 2 (the field includes its own 2 bytes per ITU-T T.81 §B.1.1.4).
		BAD_LENGTH,
		// Valid length, but the segment end (markerByteOff + 1 + segLen) lands past endBound.
		OVERRUN,
		// Well-formed length-bearing segment entirely inside [off, endBound).
		SEGMENT
	}

	/**
	 * One classified head-walk step from nextHeadSegment. Component validity depends on kind:
	 *
	 * @param kind          step classification — see HeadKind
	 * @param markerByteOff offset of the marker code byte (past any fill bytes); -1 for NO_MARKER
	 * @param marker        marker code in 0..255; -1 for NO_MARKER
	 * @param segLen        big-endian segment length including its own 2 bytes; -1 unless kind is
	 *                      OVERRUN or SEGMENT
	 * @param next          offset of the next walk position (just past the segment for SEGMENT, just past
	 *                      the marker byte for STANDALONE); -1 for every other kind
	 */
	public record HeadSegment(HeadKind kind, int markerByteOff, int marker, int segLen, int next) {}

	// Sentinel returned by scanSosEntropy when the SOS header itself is malformed (truncated header or
	// wrap-overflow on sosLen). Distinct from "found EOI" (≥ 0) and from "next-marker offset" (negative encoding).
	// Caller propagates a -1 to the outer findPrimaryEoi return.
	private static final int SOS_BAIL = Integer.MIN_VALUE;

	private JpegMarkerWalker() {}

	/**
	 * Walk JPEG markers from `startOff` (must point at SOI, FF D8) to the byte just past the matching EOI (FF D9).
	 * Returns -1 when the JPEG doesn't end cleanly within `endBound` — caller treats that as "no recoverable scan"
	 * rather than reading past EOF.
	 *
	 * Forward marker-chain walking (not a backward FF D9 scan) is required for a JPEG embedded in a container:
	 * SEFT trailers hold embedded thumbnails whose own FF D9 and arbitrary bytes would short-circuit a backward
	 * scan onto the wrong terminator. Walking forward stops only at the first real EOI.
	 *
	 * Hardened against four malformed / adversarial inputs:
	 *   - segLen < 2 (spec requires it include the 2 length bytes; smaller advances by the wrong amount)
	 *   - segLen / sosLen near 65535 with `off` near MAX_INT — the addition wraps to a negative `next` that
	 *     passes loop bounds and triggers a negative-index AIOOBE
	 *   - SOS header truncated mid-length-field — readU16BE would throw before returning
	 *   - leading fill bytes 0xFF before a marker (legal per ITU-T T.81 §B.1.1.2) — mis-reading the second
	 *     0xFF as a marker code would parse a bogus length
	 *
	 * The explicit startOff lets callers scan an embedded JPEG (e.g. a gain-map slice) without an
	 * Arrays.copyOfRange — the walker reads directly from [startOff, endBound).
	 *
	 * @param file     bytes containing a JPEG whose SOI sits at startOff
	 * @param startOff inclusive offset of the SOI marker (advanced past)
	 * @param endBound exclusive upper offset to stop scanning at
	 * @return offset just past the matching EOI (relative to file byte 0, NOT startOff), or -1 when none found
	 */
	public static int findEoi(byte[] file, int startOff, int endBound)
	{
		// Long-arithmetic overflow guard. An adversarial startOff near Integer.MAX_VALUE would let `startOff +
		// 2` wrap into Integer.MIN_VALUE + 0 (signed-overflow), passing the `off < endBound` check with a
		// negative value that then indexes the byte array negatively → AIOOBE. Symmetric to the `next < off`
		// guard further down the walk; placing it up front avoids the unchecked add altogether.
		if (startOff < 0 || startOff > Integer.MAX_VALUE - 2)
		{
			return -1;
		}
		int off = startOff + 2; // skip SOI
		while (off < endBound - 1)
		{
			if ((file[off] & 0xFF) != 0xFF)
			{
				off++;
				continue;
			}
			int markerByteOff = skipFillBytes(file, off, endBound);
			if (markerByteOff < 0)
			{
				return -1;
			}
			int marker = file[markerByteOff] & 0xFF;
			int afterMarker = markerByteOff + 1;
			if (marker == JpegMarker.EOI)
			{
				return afterMarker;
			}
			if (marker == JpegMarker.SOS)
			{
				// scanSosEntropy expects an offset where file[off] == 0xFF and file[off+1] == 0xDA;
				// pass markerByteOff - 1 (the last fill byte, or the original FF if no fill present).
				int sosResult = scanSosEntropy(file, markerByteOff - 1, endBound);
				if (sosResult >= 0)
				{
					// EOI hit inside the entropy stream.
					return sosResult;
				}
				if (sosResult == SOS_BAIL)
				{
					return -1;
				}
				// Decoded as "next real marker offset" — continue outer walk from there.
				off = -sosResult - 1;
				continue;
			}
			if (marker == JpegMarker.STUFFING || marker == JpegMarker.TEM
				|| (marker >= JpegMarker.RST_FIRST && marker <= JpegMarker.RST_LAST))
			{
				off = afterMarker;
				continue;
			}
			if (afterMarker + 1 < endBound)
			{
				int segLen = ByteBufferUtils.readU16BE(file, afterMarker);
				if (segLen < 2)
				{
					return -1;
				}
				int next = afterMarker + segLen;
				if (next < off || next > endBound)
				{
					return -1;
				}
				off = next;
			}
			else
			{
				off = afterMarker;
			}
		}
		return -1;
	}

	/**
	 * Walk JPEG markers from byte 0 to find the byte offset just past the primary's EOI (FF D9). Convenience
	 * wrapper for the most common case — when the JPEG starts at the file's first byte. Delegates to findEoi(file,
	 * 0, endBound) which is the same walk with an explicit start offset for the embedded-JPEG case (gain-map scan
	 * in GainMapExtractor, etc.).
	 *
	 * @param file     full JPEG file bytes (must start with SOI at byte 0)
	 * @param endBound exclusive upper offset to stop scanning at (typically file.length, or the start of a
	 *                 known trailing region like a SEFT trailer)
	 * @return offset just past the primary's EOI, or -1 when no clean EOI is found within endBound
	 */
	public static int findPrimaryEoi(byte[] file, int endBound)
	{
		return findEoi(file, 0, endBound);
	}

	/**
	 * Classify one step of a JPEG head-segment walk at `off`. The single home for the per-segment walk mechanics
	 * (0xFF check, fill-byte skip, standalone-marker detection, big-endian length read with the spec-minimum
	 * segLen ≥ 2 guard, and the long-arithmetic segment-end overrun guard) shared by the head walkers in
	 * JpegMetadataExtractor, XmpItemLengthPatcher, MpfPatcher, JpegMetadataInjector, GraftWriter, and
	 * BitmapUtils.readExifOrientationInternal — one home so a hardening fix (fill bytes, length guards) cannot
	 * land in some walkers and miss others. Callers keep only their own
	 * segment filter, stop condition, and failure convention.
	 *
	 * SOS and EOI are NOT terminal here — they classify like any length-bearing marker and every marker-carrying
	 * kind reports the marker code, so a caller checks its own stop markers (SOS / EOI for segment collectors,
	 * any non-APP / non-COM for scan-start finders) before inspecting the kind.
	 *
	 * @param file     bytes containing the JPEG head to walk
	 * @param off      offset of the expected 0xFF marker prefix; caller must ensure 0 <= off < endBound
	 * @param endBound exclusive upper bound for every read (typically file.length)
	 * @return the classified step; component validity per kind is documented on HeadSegment
	 */
	public static HeadSegment nextHeadSegment(byte[] file, int off, int endBound)
	{
		if ((file[off] & 0xFF) != 0xFF)
		{
			return new HeadSegment(HeadKind.NO_MARKER, -1, -1, -1, -1);
		}
		int markerByteOff = skipFillBytes(file, off, endBound);
		if (markerByteOff < 0)
		{
			return new HeadSegment(HeadKind.NO_MARKER, -1, -1, -1, -1);
		}
		int marker = file[markerByteOff] & 0xFF;
		int afterMarker = markerByteOff + 1;
		if (marker == JpegMarker.STUFFING || marker == JpegMarker.TEM
			|| (marker >= JpegMarker.RST_FIRST && marker <= JpegMarker.RST_LAST))
		{
			return new HeadSegment(HeadKind.STANDALONE, markerByteOff, marker, -1, afterMarker);
		}
		if (afterMarker + 2 > endBound)
		{
			return new HeadSegment(HeadKind.BAD_LENGTH, markerByteOff, marker, -1, -1);
		}
		int segLen = ByteBufferUtils.readU16BE(file, afterMarker);
		if (segLen < 2)
		{
			return new HeadSegment(HeadKind.BAD_LENGTH, markerByteOff, marker, -1, -1);
		}
		// Long arithmetic so a segLen near 65535 with afterMarker near Integer.MAX_VALUE can't wrap into a
		// negative `next` that passes bounds checks and indexes the array negatively on the following step.
		long next = (long) afterMarker + segLen;
		if (next > endBound)
		{
			return new HeadSegment(HeadKind.OVERRUN, markerByteOff, marker, segLen, -1);
		}
		return new HeadSegment(HeadKind.SEGMENT, markerByteOff, marker, segLen, (int) next);
	}

	/**
	 * Skip JPEG marker fill bytes. Per ITU-T T.81 §B.1.1.2: "Any marker may optionally be preceded by any number of
	 * fill bytes, which are bytes assigned code 0xFF." Given an offset where `file[ffOff] == 0xFF`, advance past
	 * any additional 0xFF bytes and return the offset of the actual marker byte (the first non-0xFF byte after the
	 * run). Returns -1 if the run extends past `endBound` without finding a marker byte.
	 *
	 * Without this, a JPEG containing legal `FF FF E1 ...` (one fill byte before APP1) would be mis-read as marker
	 * code 0xFF followed by garbage bytes, causing GainMapExtractor / GraftWriter / CropExporter to misalign or
	 * short-circuit. Real-world Samsung sources observed in the wild without fill bytes; the hazard is shipping
	 * support for any legitimate JPEG that uses this allowance (some encoders emit fill bytes for 16-bit alignment
	 * between markers).
	 *
	 * @param file     JPEG byte array
	 * @param ffOff    offset where `file[ffOff]` is known to be 0xFF
	 * @param endBound exclusive upper bound for the search
	 * @return offset of the marker byte (first non-0xFF after the run), or -1 when the run runs off endBound
	 */
	public static int skipFillBytes(byte[] file, int ffOff, int endBound)
	{
		int markerByteOff = ffOff + 1;
		while (markerByteOff < endBound && (file[markerByteOff] & 0xFF) == 0xFF)
		{
			markerByteOff++;
		}
		if (markerByteOff >= endBound)
		{
			return -1;
		}
		return markerByteOff;
	}

	/**
	 * Scan an SOS segment's entropy-coded data forward from `off` (the FF DA position). Skips over RST (FFD0–FFD7)
	 * and stuffing (FF00) bytes per the JPEG spec; the first non-RST, non-stuff marker either ends the scan (EOI)
	 * or signals the next segment's start.
	 *
	 * @param file     JPEG bytes to walk
	 * @param off      offset of the SOS marker's FF byte (FF DA)
	 * @param endBound exclusive upper bound on the walk — typically `file.length`, OR a caller-imposed
	 *                 cap when probing only a head buffer
	 * @return one of:
	 *   - ≥ 0: offset just past an EOI hit inside the entropy stream
	 *   - SOS_BAIL: the SOS header is malformed — caller bails with -1
	 *   - encoded next-marker offset (negative): the entropy walk hit a non-RST / non-stuff marker that
	 *     signals the scan is done; caller continues outer walk from `(-result - 1)`
	 */
	private static int scanSosEntropy(byte[] file, int off, int endBound)
	{
		// SOS needs 4 bytes (FF DA + 2-byte segLen) before reading sosLen. On a truncated edit that ends
		// mid-SOS-header the readU16BE would throw IndexOutOfBoundsException; bail with SOS_BAIL.
		if (off + 4 > endBound)
		{
			return SOS_BAIL;
		}
		int sosLen = ByteBufferUtils.readU16BE(file, off + 2);
		// Per ITU-T T.81 §B.1.1.4, a marker segment's length includes the 2 length bytes themselves — sosLen <
		// 2 is structurally impossible for a well-formed SOS. Without this guard, sosLen=0 makes scanOff = off
		// + 2 (lands inside the length field) and sosLen=1 makes scanOff = off + 3 (lands inside the SOS header
		// body); both let the entropy walk treat the header bytes as scan content and accept a coincidental FF
		// D9 there as a "valid EOI".
		if (sosLen < 2)
		{
			return SOS_BAIL;
		}
		int scanOff = off + 2 + sosLen;
		// Defensive: a lying or adversarial sosLen plus a large `off` could either produce a scanOff past EOF
		// (handled by the inner loop's bounds) OR an integer-overflow negative scanOff that satisfies `<
		// endBound - 1` and then indexes a negative offset → AIOOBE.
		if (scanOff < off || scanOff > endBound)
		{
			return SOS_BAIL;
		}
		while (scanOff < endBound - 1)
		{
			if ((file[scanOff] & 0xFF) != 0xFF)
			{
				scanOff++;
				continue;
			}
			// Fill bytes can precede restart markers inside the entropy stream too. Skip them so a legal
			// `FF FF D0` (fill + RST0) isn't misread as marker code 0xFF.
			int markerByteOff = skipFillBytes(file, scanOff, endBound);
			if (markerByteOff < 0)
			{
				return SOS_BAIL;
			}
			int next = file[markerByteOff] & 0xFF;
			if (next == JpegMarker.EOI)
			{
				return markerByteOff + 1;
			}
			if (next == JpegMarker.STUFFING
				|| (next >= JpegMarker.RST_FIRST && next <= JpegMarker.RST_LAST))
			{
				scanOff = markerByteOff + 1;
				continue;
			}
			break; // real marker — caller continues outer walk from the FIRST 0xFF (encoded below)
		}
		// Encode "next real marker at scanOff" as a negative number distinct from SOS_BAIL. Decode in caller
		// via `-result - 1`. Defensive cap: scanOff == Integer.MAX_VALUE would encode to Integer.MIN_VALUE,
		// which collides with SOS_BAIL and would mis-route the caller. Unreachable under SafFileHelper's 128MB
		// read cap, but the aliasing of the sentinel space with the encoded-result space is exactly the kind of
		// subtle bug an adversarial input might exploit on a future cap relaxation.
		if (scanOff == Integer.MAX_VALUE)
		{
			return SOS_BAIL;
		}
		return -scanOff - 1;
	}
}
