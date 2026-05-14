package com.cropcenter.metadata;

import com.cropcenter.util.ByteBufferUtils;

/**
 * Canonical JPEG marker walker. Consolidates the SOS / EOI / RST / segment-length / overflow-guard logic that
 * previously lived as three separate near-identical implementations across CropExporter, GraftWriter, and
 * GainMapExtractor. A bug class fixed in one site now applies to all three by construction.
 *
 * Static, no state — every call carries its own (file, endBound) pair. The endBound parameter generalises the earlier
 * "scan to file.length" vs "scan to SEFT-aware boundary" split: callers that don't need to bound past a trailer pass
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
	 * Sentinel returned by scanSosEntropy when the SOS header itself is malformed (truncated header or
	 * wrap-overflow on sosLen). Distinct from "found EOI" (≥ 0) and from "next-marker offset" (negative encoding).
	 * Caller propagates a -1 to the outer findPrimaryEoi return.
	 */
	private static final int SOS_BAIL = Integer.MIN_VALUE;

	private JpegMarkerWalker() {}

	/**
	 * Walk JPEG markers from `startOff` (which must point at an SOI marker — FF D8) to find the byte offset
	 * just past the matching EOI (FF D9). Returns -1 when the JPEG starting at startOff doesn't end cleanly
	 * within `endBound` — caller treats this as "no recoverable scan" and surfaces a real failure toast
	 * rather than relying on an undefined post-EOF read.
	 *
	 * Hardened against four classes of malformed / adversarial input:
	 *   - segLen < 2 (per spec the length must include the 2 length bytes themselves; smaller is invalid and
	 *     would advance off by 2 or 3 instead of the real segment size, getting stuck mid-segment)
	 *   - segLen / sosLen near 65535 with `off` near MAX_INT, where the addition wraps to a negative `next`
	 *     that satisfies subsequent loop bounds and triggers a negative-index AIOOBE
	 *   - SOS header truncated mid-length-field — readU16BE would throw IOOBE before returning
	 *   - leading fill bytes 0xFF before a marker (legal per ITU-T T.81 §B.1.1.2) — mis-reading the second 0xFF
	 *     as a marker code would parse a bogus segment length from the next two bytes
	 *
	 * The explicit startOff lets callers scan an embedded JPEG (gain-map slice in an Ultra HDR file's
	 * post-primary bytes, for example) without allocating a fresh Arrays.copyOfRange — the walker reads
	 * directly out of the source array between [startOff, endBound).
	 *
	 * @param file     bytes containing a JPEG whose SOI sits at startOff
	 * @param startOff inclusive offset of the SOI marker (this method advances past it)
	 * @param endBound exclusive upper offset to stop scanning at
	 * @return offset just past the matching EOI (relative to file's byte 0, NOT to startOff), or -1 when no
	 *         clean EOI is found within endBound
	 */
	public static int findEoi(byte[] file, int startOff, int endBound)
	{
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
	 * wrapper for the most common case — when the JPEG starts at the file's first byte. Delegates to
	 * findEoi(file, 0, endBound) which is the same walk with an explicit start offset for the embedded-JPEG
	 * case (gain-map scan in GainMapExtractor, etc.).
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
	 * Skip JPEG marker fill bytes. Per ITU-T T.81 §B.1.1.2: "Any marker may optionally be preceded by any number
	 * of fill bytes, which are bytes assigned code 0xFF." Given an offset where `file[ffOff] == 0xFF`, advance
	 * past any additional 0xFF bytes and return the offset of the actual marker byte (the first non-0xFF byte
	 * after the run). Returns -1 if the run extends past `endBound` without finding a marker byte.
	 *
	 * Without this, a JPEG containing legal `FF FF E1 ...` (one fill byte before APP1) would be mis-read as
	 * marker code 0xFF followed by garbage bytes, causing GainMapExtractor / GraftWriter / CropExporter to
	 * misalign or short-circuit. Real-world Samsung sources observed in the wild without fill bytes; the hazard
	 * is shipping support for any legitimate JPEG that uses this allowance (some encoders emit fill bytes for
	 * 16-bit alignment between markers).
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
	 * Scan an SOS segment's entropy-coded data forward from `off` (the FF DA position).
	 *
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
		// Per ITU-T T.81 §B.1.1.4, a marker segment's length includes the 2 length bytes themselves —
		// sosLen < 2 is structurally impossible for a well-formed SOS. Without this guard, sosLen=0 makes
		// scanOff = off + 2 (lands inside the length field) and sosLen=1 makes scanOff = off + 3 (lands
		// inside the SOS header body); both let the entropy walk treat the header bytes as scan content
		// and accept a coincidental FF D9 there as a "valid EOI". Mirrors the segLen<2 guard in the outer
		// findPrimaryEoi walker (line 100). Same invariant, same defensive shape.
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
		// which collides with SOS_BAIL and would mis-route the caller. Unreachable under SafFileHelper's
		// 128MB read cap, but the aliasing of the sentinel space with the encoded-result space is exactly the
		// kind of subtle bug an adversarial input might exploit on a future cap relaxation.
		if (scanOff == Integer.MAX_VALUE)
		{
			return SOS_BAIL;
		}
		return -scanOff - 1;
	}
}
