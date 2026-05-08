package com.cropcenter.metadata;

import com.cropcenter.util.ByteBufferUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts all APP and COM marker segments from a JPEG file's header. Stops at SOS (FF DA) or EOI (FF D9). Preserves
 * raw segment bytes verbatim.
 */
public final class JpegMetadataExtractor
{
	private JpegMetadataExtractor() {}

	public static List<JpegSegment> extract(byte[] jpeg)
	{
		List<JpegSegment> segments = new ArrayList<>();
		if (jpeg.length < 4 || jpeg[0] != (byte) 0xFF || jpeg[1] != (byte) JpegMarker.SOI)
		{
			return segments;
		}

		int off = 2;
		while (off < jpeg.length - 3)
		{
			if ((jpeg[off] & 0xFF) != 0xFF)
			{
				break;
			}
			// Fill bytes (legal per ITU-T T.81 §B.1.1.2) — skip extra 0xFF bytes before reading the marker.
			int markerByteOff = JpegMarkerWalker.skipFillBytes(jpeg, off, jpeg.length);
			if (markerByteOff < 0)
			{
				break;
			}
			int marker = jpeg[markerByteOff] & 0xFF;
			int afterMarker = markerByteOff + 1;

			// Stop at SOS or EOI
			if (marker == JpegMarker.SOS || marker == JpegMarker.EOI)
			{
				break;
			}

			// Standalone markers (no length)
			if (marker == JpegMarker.STUFFING || marker == JpegMarker.TEM
				|| (marker >= JpegMarker.RST_FIRST && marker <= JpegMarker.RST_LAST))
			{
				off = afterMarker;
				continue;
			}

			if (afterMarker + 2 > jpeg.length)
			{
				break;
			}
			int segLen = ByteBufferUtils.readU16BE(jpeg, afterMarker);
			if (segLen < 2)
			{
				// Segment length MUST include the 2 length bytes themselves (JPEG spec). Zero or one
				// means the file is corrupt / malicious — bail rather than add a bogus 2-byte segment
				// that downstream injector / renderers will mis-parse, shifting every following segment
				// by that amount.
				break;
			}

			// Bounds + overflow guard. segLen is u16 so segLen ≤ 65535, no int overflow is possible here
			// in practice — but the afterMarker+segLen sum CAN overflow if a crafted file walks far enough
			// that the offset is near Integer.MAX_VALUE. Bail if the segment claims to extend past EOF
			// rather than continuing the loop with off advanced into negative territory (which would then
			// index a negative offset on the next iteration's marker read, throwing IOOBE).
			int next = afterMarker + segLen;
			if (next > jpeg.length || next < off)
			{
				break;
			}

			// Keep APPn (E0-EF) and COM (FE). Saved blob is normalised to the canonical
			// FF + marker + segLen + payload form starting at `markerByteOff - 1` (the last 0xFF before the
			// marker byte). Fill bytes are pure padding per ITU-T T.81 §B.1.1.2 and have no semantic value;
			// dropping them keeps JpegSegment.isExif() / isIcc() / isMpf() / isXmp() (which read data[1] as
			// the marker code and data[4..] as the payload prefix) consistent with the historical contract
			// — downstream injection re-emits the canonical form, which decoders accept regardless of fill.
			if ((marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE)
			{
				int canonicalStart = markerByteOff - 1;
				int totalLen = next - canonicalStart;
				byte[] segData = new byte[totalLen];
				System.arraycopy(jpeg, canonicalStart, segData, 0, totalLen);
				segments.add(new JpegSegment(marker, segData));
			}

			off = next;
		}
		return segments;
	}
}
