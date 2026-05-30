package com.cropcenter.metadata;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reassemble Adobe Extended XMP chunks split across multiple APP1 segments. Vendors emitting XMP larger than
 * the APP1 ~64 KB cap split the body into "Extended XMP" chunks (32-byte GUID + 4-byte total length + 4-byte
 * offset header, namespace prefix `http://ns.adobe.com/xmp/extension/\0`).
 *
 * Single source of truth for the reassembly logic, used by HorizonDetector (Roll / Tilt scanning) and
 * HdrSignature (hdrgm scanning). Without it each scanner would substring-search per chunk and miss an
 * attribute that straddles a chunk boundary or lands past the namespace-declaring chunk. Pure Java (no Android
 * deps) so any package can use it.
 *
 * Layout: [FF E1] [LL LL] [35-byte namespace prefix] [32-byte ASCII GUID] [4-byte total length BE] [4-byte
 * chunk offset BE] [chunk body]. Multiple GUID groups may coexist (rare); each GUID's chunks sort by offset
 * and concatenate, GUIDs in string order. Offset is decoded unsigned so a corrupted top-bit-set offset can't
 * sort before the legitimate offset 0 under signed comparison.
 */
public final class ExtendedXmpReassembler
{
	/**
	 * Single Extended XMP chunk descriptor. Holds the source segment's data byte array plus the chunk's
	 * slice bounds, so reassembly can write into a single ByteArrayOutputStream without intermediate
	 * copies. GUID + offset drive the sort order.
	 */
	private record ExtendedXmpChunk(String guid, long offset, byte[] data, int chunkStart, int chunkLength)
	{
	}

	private ExtendedXmpReassembler() {}

	/**
	 * Concatenate Adobe Extended XMP chunks from `meta` into a single byte buffer, sorted by GUID then
	 * unsigned offset within each GUID group. Returns an empty array (not null) when no Extended XMP
	 * chunks are present, so callers can skip the scan with a single isEmpty check.
	 *
	 * @param meta JPEG segment list from JpegMetadataExtractor.extract; may be null or empty
	 * @return concatenated chunk-body bytes; empty array when no Extended XMP segments are present
	 */
	public static byte[] reassemble(List<JpegSegment> meta)
	{
		if (meta == null || meta.isEmpty())
		{
			return new byte[0];
		}
		int extPrefixLen = JpegSegment.EXTENDED_XMP_HEADER.length();
		// FF E1 LL LL (4) + namespace prefix + 32-byte GUID + 4-byte total len + 4-byte offset.
		// JpegSegment.isExtendedXmp validates the FF E1 + namespace prefix; this is the stricter
		// post-prefix length needed to safely read the GUID + total-length + offset header fields.
		int minHeaderLen = 4 + extPrefixLen + 32 + 4 + 4;

		List<ExtendedXmpChunk> chunks = new ArrayList<>();
		for (JpegSegment seg : meta)
		{
			if (!seg.isExtendedXmp())
			{
				continue;
			}
			byte[] data = seg.data();
			if (data.length < minHeaderLen)
			{
				continue;
			}
			int guidStart = 4 + extPrefixLen;
			String guid = new String(data, guidStart, 32, StandardCharsets.US_ASCII);
			// Skip the 4-byte total-length field (we don't pre-allocate; we just concatenate).
			int offsetStart = guidStart + 32 + 4;
			// Decode as unsigned 32-bit so a top-bit-set offset (corrupt / adversarial input — spec
			// disallows them) sorts AFTER zero rather than before it. Assembled byte-by-byte into a
			// long (Java has no unsigned int) so the high bit can't sign-extend to a negative value.
			long offset = (((long) data[offsetStart] & 0xFF) << 24)
				| (((long) data[offsetStart + 1] & 0xFF) << 16)
				| (((long) data[offsetStart + 2] & 0xFF) << 8)
				| ((long) data[offsetStart + 3] & 0xFF);
			int chunkStart = offsetStart + 4;
			chunks.add(new ExtendedXmpChunk(guid, offset, data, chunkStart, data.length - chunkStart));
		}
		if (chunks.isEmpty())
		{
			return new byte[0];
		}

		// Sort by GUID, then by unsigned offset within each GUID. Out-of-file-order chunks reassemble
		// correctly; multiple GUID groups (rare but spec-legal) reassemble independently.
		chunks.sort(Comparator.comparing(ExtendedXmpChunk::guid)
			.thenComparingLong(ExtendedXmpChunk::offset));

		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		for (ExtendedXmpChunk chunk : chunks)
		{
			buf.write(chunk.data(), chunk.chunkStart(), chunk.chunkLength());
		}
		return buf.toByteArray();
	}
}
