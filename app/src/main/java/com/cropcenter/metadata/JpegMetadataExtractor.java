package com.cropcenter.metadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Extracts all APP and COM marker segments from a JPEG file's header. Stops at SOS (FF DA) or EOI (FF D9). Preserves
 * raw segment bytes verbatim.
 */
public final class JpegMetadataExtractor
{
	private JpegMetadataExtractor() {}

	/**
	 * Walk the JPEG marker chain from SOI to SOS / EOI and return every APPn / COM segment as a JpegSegment record
	 * carrying the marker code and the full segment bytes (FF + marker + 2-byte length + body). Stops at the first
	 * SOS (entropy data follows) or EOI; tolerates fill bytes, standalone markers (RST, STUFFING, TEM), and short /
	 * truncated segments by bailing rather than throwing — adversarial inputs return a partial list, never an
	 * exception.
	 *
	 * @param jpeg full JPEG file bytes; non-JPEG inputs (missing SOI) return an empty list
	 * @return ordered list of APPn / COM segments seen before SOS / EOI; raw bytes are sliced into
	 *         each JpegSegment.data() and not defensively copied per the JpegSegment immutability
	 *         convention (see JpegSegment Javadoc)
	 */
	public static List<JpegSegment> extract(byte[] jpeg)
	{
		List<JpegSegment> segments = new ArrayList<>();
		if (jpeg.length < 4 || jpeg[0] != (byte) JpegMarker.PREFIX || jpeg[1] != (byte) JpegMarker.SOI)
		{
			return segments;
		}

		int off = 2;
		while (off < jpeg.length - 3)
		{
			JpegMarkerWalker.HeadSegment step = JpegMarkerWalker.nextHeadSegment(jpeg, off, jpeg.length);
			if (step.kind() == JpegMarkerWalker.HeadKind.NO_MARKER)
			{
				break;
			}
			int marker = step.marker();

			// Stop at SOS or EOI
			if (marker == JpegMarker.SOS || marker == JpegMarker.EOI)
			{
				break;
			}

			// Standalone markers (no length)
			if (step.kind() == JpegMarkerWalker.HeadKind.STANDALONE)
			{
				off = step.next();
				continue;
			}

			if (step.kind() != JpegMarkerWalker.HeadKind.SEGMENT)
			{
				// BAD_LENGTH or OVERRUN — a truncated length field, a sub-2 segLen (must include the 2
				// length bytes per JPEG spec), or a segment claiming bytes past EOF. Bail rather than
				// add a bogus segment that downstream injector / renderers will mis-parse, shifting
				// every following segment by that amount.
				break;
			}

			// Keep APPn (E0-EF) and COM (FE). Saved blob is normalised to the canonical FF + marker +
			// segLen + payload form starting at `markerByteOff - 1` (the last 0xFF before the marker byte).
			// Fill bytes are pure padding per ITU-T T.81 §B.1.1.2 and have no semantic value; dropping them
			// preserves the fixed layout JpegSegment.isExif() / isIcc() / isMpf() / isXmp() require —
			// data[1] is the marker code, data[4..] the payload prefix — and downstream injection re-emits
			// the canonical form, which decoders accept regardless of fill.
			if ((marker >= JpegMarker.APP0 && marker <= JpegMarker.APP_LAST) || marker == JpegMarker.COM)
			{
				int canonicalStart = step.markerByteOff() - 1;
				byte[] segData = Arrays.copyOfRange(jpeg, canonicalStart, step.next());
				segments.add(new JpegSegment(marker, segData));
			}

			off = step.next();
		}
		return segments;
	}
}
