package com.cropcenter.metadata;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reassemble Adobe Extended XMP chunks split across multiple APP1 segments. Vendors who emit XMP packets
 * larger than the JPEG APP1 ~64 KB cap split the body into "Extended XMP" chunks identified by a 32-byte
 * GUID + 4-byte total length + 4-byte offset header. Each chunk is one APP1 segment with the namespace
 * prefix `http://ns.adobe.com/xmp/extension/\0`.
 *
 * Single source of truth for the GUID + offset reassembly logic, used by both `HorizonDetector` (Roll /
 * Tilt scanning across chunk boundaries — Codex round-21 F1) and `HdrSignature` (hdrgm marker scanning —
 * Codex round-22 logic F2). Without reassembly each scanner would substring-search per chunk; an attribute
 * that straddles a chunk boundary OR lands past the chunk holding the namespace declaration would be
 * silently missed.
 *
 * Pure Java — no Android dependencies — so callers in any package can use it without dragging UI surface.
 *
 * Layout of an Extended XMP segment:
 *   [FF E1] [LL LL] [namespace prefix — 35 bytes] [32-byte ASCII GUID] [4-byte total length, big-endian]
 *   [4-byte chunk offset, big-endian] [chunk body]
 *
 * Per spec, multiple GUID groups may coexist (rare — typically one per file). Each GUID's chunks are
 * sorted by offset and concatenated; different GUIDs land contiguously after each other in GUID-string
 * order. The offset is decoded as unsigned (offsets are file positions and always >= 0; spec disallows
 * negative values, but a corrupted top-bit-set offset would otherwise sort BEFORE the legitimate offset
 * 0 under signed int comparison — Codex round-22 logic F3).
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

	private static final String EXT_PREFIX = "http://ns.adobe.com/xmp/extension/\0";

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
		int extPrefixLen = EXT_PREFIX.length();
		// FF E1 LL LL (4) + namespace prefix + 32-byte GUID + 4-byte total len + 4-byte offset
		int minHeaderLen = 4 + extPrefixLen + 32 + 4 + 4;

		List<ExtendedXmpChunk> chunks = new ArrayList<>();
		for (JpegSegment seg : meta)
		{
			byte[] data = seg.data();
			if (seg.marker() != 0xE1 || data.length < minHeaderLen)
			{
				continue;
			}
			boolean prefixMatches = true;
			for (int i = 0; i < extPrefixLen; i++)
			{
				if ((data[4 + i] & 0xFF) != EXT_PREFIX.charAt(i))
				{
					prefixMatches = false;
					break;
				}
			}
			if (!prefixMatches)
			{
				continue;
			}
			int guidStart = 4 + extPrefixLen;
			String guid = new String(data, guidStart, 32, StandardCharsets.US_ASCII);
			// Skip the 4-byte total-length field (we don't pre-allocate; we just concatenate).
			int offsetStart = guidStart + 32 + 4;
			// Decode as unsigned 32-bit so a top-bit-set offset (corrupt / adversarial input — spec
			// disallows them) sorts AFTER zero rather than before it (Codex round-22 logic F3). Stored
			// as long since Java has no unsigned int; the 0xFFFFFFFFL mask widens the sign-extended
			// int to its unsigned interpretation.
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
