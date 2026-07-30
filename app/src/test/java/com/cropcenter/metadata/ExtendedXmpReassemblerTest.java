package com.cropcenter.metadata;

import static com.cropcenter.metadata.JpegFixtures.concat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tests for ExtendedXmpReassembler — the single chokepoint that reassembles Adobe Extended XMP chunks split across
 * multiple APP1 segments. Used transitively by HorizonDetector (roll/tilt scanning across chunk boundaries) and
 * HdrSignature (hdrgm marker scanning), so a regression here silently breaks Auto Rotate horizon detection AND Ultra
 * HDR gating on real-world Samsung Ultra HDR sources whose XMP body exceeds the JPEG APP1 ~64 KB cap.
 *
 * The unsigned-offset sort is the load-bearing invariant — a regression that switches to signed comparison would let a
 * top-bit-set adversarial offset sort BEFORE the legitimate offset 0, producing a reassembled body whose tag-attribute
 * scanners find nothing (Roll/Tilt detection and hdrgm detection both silently fail). Pinned here so a future "simplify
 * the comparator" refactor catches itself.
 */
public final class ExtendedXmpReassemblerTest
{
	private static final String GUID_A = "A1B2C3D4E5F60718293A4B5C6D7E8F90";

	private static final String GUID_B = "B1B2C3D4E5F60718293A4B5C6D7E8F90";

	@Test
	public void chunksReassembleInUnsignedOffsetOrderWithinGuid() throws IOException
	{
		// Out-of-order chunks in the input list must reassemble in ascending unsigned-offset order. Input
		// order: B-body at offset 100, A-body at offset 0. Expected output: A-body before B-body within the
		// single GUID group. Confirms the sort actually fires (without it, input order would dominate).
		List<JpegSegment> meta = new ArrayList<>();
		meta.add(extendedXmpSegment(GUID_A, 100, CHUNK_B_BODY));
		meta.add(extendedXmpSegment(GUID_A, 0, CHUNK_A_BODY));

		List<byte[]> groups = ExtendedXmpReassembler.reassemble(meta);
		assertEquals("one GUID → one group buffer", 1, groups.size());
		assertArrayEquals(concat(CHUNK_A_BODY, CHUNK_B_BODY), groups.get(0));
	}

	@Test
	public void emptyMetaReturnsEmptyList()
	{
		assertEquals(0, ExtendedXmpReassembler.reassemble(null).size());
		assertEquals(0, ExtendedXmpReassembler.reassemble(Collections.emptyList()).size());
	}

	@Test
	public void minHeaderLenBoundarySkips78Accepts79() throws IOException
	{
		// Exact minHeaderLen boundary: 4 (FF E1 LL LL) + 35 (namespace prefix) + 32 (GUID) + 4 (total length) +
		// 4 (offset) = 79. A 78-byte segment ends one byte inside the offset field — the length guard must skip
		// it (reading the offset would AIOOBE at data[78]). A 79-byte segment is a complete header with a
		// zero-length body: accepted without throwing, contributing zero bytes. The 80-byte sibling carries the
		// smallest non-empty body, pinning that acceptance starts immediately past the header — a guard
		// tightened past 79 would drop its byte from the output.
		ByteArrayOutputStream truncatedPayload = new ByteArrayOutputStream();
		truncatedPayload.write(JpegSegment.EXTENDED_XMP_HEADER.getBytes(StandardCharsets.US_ASCII));
		truncatedPayload.write(GUID_A.getBytes(StandardCharsets.US_ASCII));
		// 4-byte total-length field + only 3 of the 4 offset bytes.
		truncatedPayload.write(new byte[7]);
		JpegSegment seg78 = new JpegSegment(0xE1,
			JpegFixtures.appSegment(0xE1, truncatedPayload.toByteArray()));
		assertEquals(78, seg78.data().length);

		JpegSegment seg79 = extendedXmpSegment(GUID_A, 0, new byte[0]);
		assertEquals(79, seg79.data().length);
		JpegSegment seg80 = extendedXmpSegment(GUID_A, 100, new byte[]{ 0x42 });
		assertEquals(80, seg80.data().length);

		List<JpegSegment> meta = new ArrayList<>();
		meta.add(seg78);
		meta.add(seg79);
		meta.add(seg80);

		List<byte[]> groups = ExtendedXmpReassembler.reassemble(meta);
		assertEquals(1, groups.size());
		assertArrayEquals(new byte[]{ 0x42 }, groups.get(0));
	}

	@Test
	public void multipleGuidGroupsNeverConcatenateAcrossTheGroupBoundary() throws IOException
	{
		// The cross-group-synthesis hazard the per-GUID contract exists for: group A's document ends with
		// "…hdr" and group B's begins with "gm…". A single concatenated buffer would contain the 5-byte
		// "hdrgm" sequence even though NEITHER document carries it — fabricating an Ultra HDR signature from
		// two unrelated XMP documents. Per-GUID buffers must each lack the synthesized sequence.
		byte[] tailHdr = "<a:Desc attr=\"hdr".getBytes(StandardCharsets.US_ASCII);
		byte[] headGm = "gm-ish\"/>".getBytes(StandardCharsets.US_ASCII);
		List<JpegSegment> meta = new ArrayList<>();
		meta.add(extendedXmpSegment(GUID_A, 0, tailHdr));
		meta.add(extendedXmpSegment(GUID_B, 0, headGm));

		List<byte[]> groups = ExtendedXmpReassembler.reassemble(meta);
		assertEquals(2, groups.size());
		byte[] needle = "hdrgm".getBytes(StandardCharsets.US_ASCII);
		for (byte[] group : groups)
		{
			assertEquals("no group buffer may contain the sequence synthesized across the boundary",
				-1, JpegFixtures.indexOfSubsequence(group, needle));
		}
	}

	@Test
	public void multipleGuidGroupsReassembleIntoSeparateBuffersInGuidOrder() throws IOException
	{
		// Two GUID groups (rare but spec-legal). Each GUID's chunks reassemble independently into their OWN
		// buffer — one XMP document per group — and the buffers come back in GUID-lexicographic order.
		// GUID_A < GUID_B (first byte 'A' < 'B'), so GUID_A's buffer is first regardless of input order.
		List<JpegSegment> meta = new ArrayList<>();
		meta.add(extendedXmpSegment(GUID_B, 0, CHUNK_B_BODY));
		meta.add(extendedXmpSegment(GUID_A, 0, CHUNK_A_BODY));

		List<byte[]> groups = ExtendedXmpReassembler.reassemble(meta);
		assertEquals("two GUIDs → two group buffers", 2, groups.size());
		assertArrayEquals(CHUNK_A_BODY, groups.get(0));
		assertArrayEquals(CHUNK_B_BODY, groups.get(1));
	}

	@Test
	public void noExtendedXmpChunksReturnsEmptyList() throws IOException
	{
		// Non-extended-XMP segments (a plain EXIF segment) are skipped, so reassembly produces empty output.
		List<JpegSegment> meta = new ArrayList<>();
		meta.add(new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, JpegFixtures.exifAppPayload())));
		assertEquals(0, ExtendedXmpReassembler.reassemble(meta).size());
	}

	@Test
	public void topBitSetOffsetSortsAfterZeroUnderUnsignedComparison() throws IOException
	{
		// Unsigned-offset regression test. Two chunks of the same GUID — one at offset 0 (legit chunk-zero of
		// any well-formed Extended XMP packet), the other at offset 0x80000010 (top-bit set). Under signed int
		// comparison 0x80000010 sorts BEFORE 0 (as the negative int -2_147_483_632), which would corrupt the
		// reassembled body's order — chunk-A's content would appear AFTER chunk-B's content even though chunk-A
		// is at the legitimate file-start offset. Unsigned comparison treats 0x80000010 as the larger value and
		// preserves the chunk-A-then-chunk-B order.
		List<JpegSegment> meta = new ArrayList<>();
		meta.add(extendedXmpSegment(GUID_A, 0, CHUNK_A_BODY));
		meta.add(extendedXmpSegment(GUID_A, 0x80000010L, CHUNK_B_BODY));

		List<byte[]> groups = ExtendedXmpReassembler.reassemble(meta);
		assertEquals(1, groups.size());
		// Chunk-A's body MUST appear before chunk-B's body in the reassembled group.
		assertArrayEquals(concat(CHUNK_A_BODY, CHUNK_B_BODY), groups.get(0));
	}

	@Test
	public void truncatedExtendedXmpChunkSkippedWithoutThrow() throws IOException
	{
		// Build a segment whose body passes `isExtendedXmp` (has FF E1 marker + the 35-byte extension namespace
		// prefix at the right offset) but whose total data length is below minHeaderLen = 4 + 35 + 32 + 4 + 4 =
		// 79. The reassembler's `data.length < minHeaderLen` guard must skip the chunk rather than read past
		// end-of-buffer for the GUID / offset fields. A regression that drops the bounds check would AIOOBE on
		// truncated source segments — possible from malformed cloud-provider streams that cut mid-chunk.
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write(JpegSegment.EXTENDED_XMP_HEADER.getBytes(StandardCharsets.US_ASCII));
		// No GUID / totalLen / offset bytes — the segment ends right after the namespace prefix. Total
		// data.length = 4 (FF E1 LL LL) + 35 (prefix) = 39, well under minHeaderLen=79.
		JpegSegment truncated = new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, payload.toByteArray()));

		List<byte[]> groups = ExtendedXmpReassembler.reassemble(Collections.singletonList(truncated));

		assertNotNull(groups);
		assertEquals("truncated chunk skipped, no GUIDs collected → empty reassembly", 0, groups.size());
	}

	/**
	 * Build an Extended XMP APP1 segment carrying `body` as its chunk content. Layout: FF E1 LL LL +
	 * JpegSegment.EXTENDED_XMP_HEADER + 32-byte GUID + 4-byte total-length (big-endian) + 4-byte offset
	 * (big-endian) + chunk body. The total-length field is filled with the body's length here — the reassembler
	 * ignores it for the bound calculation (it concatenates whatever bytes follow the offset field through the end
	 * of the segment), so the exact value doesn't matter for these tests.
	 *
	 * @param guid   32-character ASCII GUID grouping chunks of one logical XMP document
	 * @param offset chunk position within the reassembled document, written as an unsigned 32-bit big-endian field
	 * @param body   chunk content appended after the offset field
	 * @return Extended XMP APP1 segment ready for the reassembler
	 * @throws IOException never from the in-memory stream; declared by the OutputStream write contract
	 */
	private static JpegSegment extendedXmpSegment(String guid, long offset, byte[] body) throws IOException
	{
		return new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1,
			JpegFixtures.extendedXmpChunk(guid, body.length, offset, body)));
	}

	private static final byte[] CHUNK_A_BODY = "<chunk-A-content>".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] CHUNK_B_BODY = "<chunk-B-content>".getBytes(StandardCharsets.US_ASCII);
}
