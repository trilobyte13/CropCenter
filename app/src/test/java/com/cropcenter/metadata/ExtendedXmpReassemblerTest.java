package com.cropcenter.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Tests for ExtendedXmpReassembler — the single chokepoint that reassembles Adobe Extended XMP chunks split across
 * multiple APP1 segments. Used transitively by HorizonDetector (roll/tilt scanning across chunk boundaries) and
 * HdrSignature (hdrgm marker scanning), so a regression here silently breaks Auto Rotate horizon detection AND
 * Ultra HDR gating on real-world Samsung Ultra HDR sources whose XMP body exceeds the JPEG APP1 ~64 KB cap.
 *
 * The unsigned-offset sort is the load-bearing invariant — a regression that switches to
 * signed comparison would let a top-bit-set adversarial offset sort BEFORE the legitimate offset 0, producing a
 * reassembled body whose tag-attribute scanners find nothing (Roll/Tilt detection and hdrgm detection both silently
 * fail). Pinned here so a future "simplify the comparator" refactor catches itself.
 */
public final class ExtendedXmpReassemblerTest
{
	private static final String GUID_A = "A1B2C3D4E5F60718293A4B5C6D7E8F90";

	private static final String GUID_B = "B1B2C3D4E5F60718293A4B5C6D7E8F90";

	@Test
	public void chunksReassembleInUnsignedOffsetOrderWithinGuid() throws IOException
	{
		// Out-of-order chunks in the input list must reassemble in ascending unsigned-offset order. Input
		// order: B-body at offset 100, A-body at offset 0. Expected output: A-body before B-body. Confirms
		// the sort actually fires (without it, input order would dominate).
		List<JpegSegment> meta = new ArrayList<>();
		meta.add(extendedXmpSegment(GUID_A, 100, CHUNK_B_BODY));
		meta.add(extendedXmpSegment(GUID_A, 0, CHUNK_A_BODY));

		byte[] reassembled = ExtendedXmpReassembler.reassemble(meta);
		assertArrayEquals(concat(CHUNK_A_BODY, CHUNK_B_BODY), reassembled);
	}

	@Test
	public void emptyMetaReturnsEmptyArray()
	{
		assertEquals(0, ExtendedXmpReassembler.reassemble(null).length);
		assertEquals(0, ExtendedXmpReassembler.reassemble(Collections.emptyList()).length);
	}

	@Test
	public void multipleGuidGroupsReassembleSeparatelyInGuidOrder() throws IOException
	{
		// Two GUID groups (rare but spec-legal). Each GUID's chunks reassemble independently, and GUID groups
		// concatenate in GUID-lexicographic order. GUID_A < GUID_B (first byte 'A' < 'B'), so the assembled
		// body must have ALL of GUID_A's content before ANY of GUID_B's content.
		List<JpegSegment> meta = new ArrayList<>();
		meta.add(extendedXmpSegment(GUID_B, 0, CHUNK_B_BODY));
		meta.add(extendedXmpSegment(GUID_A, 0, CHUNK_A_BODY));

		byte[] reassembled = ExtendedXmpReassembler.reassemble(meta);
		assertArrayEquals(concat(CHUNK_A_BODY, CHUNK_B_BODY), reassembled);
	}

	@Test
	public void noExtendedXmpChunksReturnsEmptyArray() throws IOException
	{
		// Non-extended-XMP segments (a plain EXIF segment) are skipped, so reassembly produces empty output.
		List<JpegSegment> meta = new ArrayList<>();
		meta.add(new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, JpegFixtures.exifAppPayload())));
		assertEquals(0, ExtendedXmpReassembler.reassemble(meta).length);
	}

	@Test
	public void topBitSetOffsetSortsAfterZeroUnderUnsignedComparison() throws IOException
	{
		// Unsigned-offset regression test. Two chunks of the same GUID — one at offset 0 (legit
		// chunk-zero of any well-formed Extended XMP packet), the other at offset 0x80000010 (top-bit set).
		// Under signed int comparison 0x80000010 sorts BEFORE 0 (as the negative int -2_147_483_632), which
		// would corrupt the reassembled body's order — chunk-A's content would appear AFTER chunk-B's
		// content even though chunk-A is at the legitimate file-start offset. Unsigned comparison treats
		// 0x80000010 as the larger value and preserves the chunk-A-then-chunk-B order.
		List<JpegSegment> meta = new ArrayList<>();
		meta.add(extendedXmpSegment(GUID_A, 0, CHUNK_A_BODY));
		meta.add(extendedXmpSegment(GUID_A, 0x80000010L, CHUNK_B_BODY));

		byte[] reassembled = ExtendedXmpReassembler.reassemble(meta);
		assertNotNull(reassembled);
		// Chunk-A's body MUST appear before chunk-B's body in the reassembled output.
		assertArrayEquals(concat(CHUNK_A_BODY, CHUNK_B_BODY), reassembled);
	}

	@Test
	public void truncatedExtendedXmpChunkSkippedWithoutThrow() throws IOException
	{
		// Build a segment whose body passes `isExtendedXmp` (has FF E1 marker + the 35-byte
		// extension namespace prefix at the right offset) but whose total data length is below
		// minHeaderLen = 4 + 35 + 32 + 4 + 4 = 79. The reassembler's `data.length < minHeaderLen`
		// guard must skip the chunk rather than read past end-of-buffer for the GUID / offset
		// fields. A regression that drops the bounds check would AIOOBE on truncated source
		// segments — possible from malformed cloud-provider streams that cut mid-chunk.
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write(JpegSegment.EXTENDED_XMP_HEADER.getBytes(StandardCharsets.US_ASCII));
		// No GUID / totalLen / offset bytes — the segment ends right after the namespace prefix.
		// Total data.length = 4 (FF E1 LL LL) + 35 (prefix) = 39, well under minHeaderLen=79.
		JpegSegment truncated = new JpegSegment(0xE1,
			JpegFixtures.appSegment(0xE1, payload.toByteArray()));

		byte[] reassembled = ExtendedXmpReassembler.reassemble(
			Collections.singletonList(truncated));

		assertNotNull(reassembled);
		assertEquals("truncated chunk skipped, no GUIDs collected → empty reassembly",
			0, reassembled.length);
	}

	/**
	 * Concatenate two byte arrays into a single array. Test-only helper to spell out expected reassembled
	 * payloads without an intermediate ByteArrayOutputStream at every assertion.
	 */
	private static byte[] concat(byte[] first, byte[] second)
	{
		byte[] result = new byte[first.length + second.length];
		System.arraycopy(first, 0, result, 0, first.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}

	/**
	 * Build an Extended XMP APP1 segment carrying `body` as its chunk content, tagged with `guid` and
	 * `offset`. Layout: FF E1 LL LL + JpegSegment.EXTENDED_XMP_HEADER + 32-byte GUID + 4-byte total-length
	 * (big-endian) + 4-byte offset (big-endian) + chunk body. The total-length field is filled with the
	 * body's length here — the reassembler ignores it for the bound calculation (it concatenates whatever
	 * bytes follow the offset field through the end of the segment), so the exact value doesn't matter for
	 * these tests.
	 */
	private static JpegSegment extendedXmpSegment(String guid, long offset, byte[] body) throws IOException
	{
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write(JpegSegment.EXTENDED_XMP_HEADER.getBytes(StandardCharsets.US_ASCII));
		payload.write(guid.getBytes(StandardCharsets.US_ASCII));
		// 4-byte total length (big-endian) — value doesn't matter for these tests but the field must exist.
		payload.write((body.length >> 24) & 0xFF);
		payload.write((body.length >> 16) & 0xFF);
		payload.write((body.length >> 8) & 0xFF);
		payload.write(body.length & 0xFF);
		// 4-byte chunk offset (big-endian, unsigned 32-bit).
		payload.write((int) ((offset >> 24) & 0xFF));
		payload.write((int) ((offset >> 16) & 0xFF));
		payload.write((int) ((offset >> 8) & 0xFF));
		payload.write((int) (offset & 0xFF));
		payload.write(body);
		return new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, payload.toByteArray()));
	}

	private static final byte[] CHUNK_A_BODY = "<chunk-A-content>".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] CHUNK_B_BODY = "<chunk-B-content>".getBytes(StandardCharsets.US_ASCII);
}
