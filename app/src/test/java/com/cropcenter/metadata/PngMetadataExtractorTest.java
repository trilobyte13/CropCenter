package com.cropcenter.metadata;

import static com.cropcenter.metadata.JpegFixtures.concat;
import static com.cropcenter.metadata.PngFixtures.PNG_SIGNATURE;
import static com.cropcenter.metadata.PngFixtures.buildChunk;
import static com.cropcenter.metadata.PngFixtures.buildIhdrChunk;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for the PNG eXIf chunk parser. The load → save round-trip for PNGs that carry EXIF (orientation, GPS) depends
 * on this extractor — without it, ImageLoadController.extractMetadata leaves state.jpegMeta empty for PNG sources and
 * CropExporter.exportPng has nothing to inject back into the saved file.
 *
 * Pinned contract: a valid PNG with one eXIf chunk → exactly one synthetic APP1 EXIF segment with the canonical `FF E1
 * LL LL "Exif\0\0" [TIFF...]` layout that JpegSegment.isExif accepts and the JPEG-source PNG export path in
 * CropExporter.exportPng can unwrap inline (the 10-byte APP1 prefix is stripped to recover raw TIFF for the eXIf
 * chunk). PNGs without eXIf return an empty list. Malformed inputs (truncated, wrong signature, oversize chunk length)
 * return an empty list rather than throwing.
 *
 * Chunk-builder helpers (PNG signature, length+CRC envelope, minimal IHDR) live in PngFixtures so the same fixture
 * surface is shared with ImageLoadControllerExtractMetadataTest.
 */
public final class PngMetadataExtractorTest
{
	@Test
	public void extractAcceptsExifAtExactlyTheJpegApp1Cap() throws IOException
	{
		// Boundary: TIFF payload of exactly 65527 bytes (the safe cap) produces a segLen of exactly 65535 (= 2
		// + 6 + 65527), which fits in the u16 length field. Verifies the >cap check uses strict "greater than"
		// not "greater than or equal" — otherwise the boundary case would be silently skipped.
		byte[] capTiff = new byte[65527];
		capTiff[0] = 'I';
		capTiff[1] = 'I';
		capTiff[2] = 0x2A;
		capTiff[3] = 0x00;
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", capTiff));
		List<JpegSegment> segs = PngMetadataExtractor.extract(png);
		assertEquals(1, segs.size());
		byte[] data = segs.get(0).data();
		int segLen = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
		assertEquals("segLen at exactly the u16 cap", 65535, segLen);
	}

	@Test
	public void extractFindsExifAfterIdatChunk() throws IOException
	{
		// PNG spec recommends ancillary chunks like eXIf appear before IDAT, but doesn't strictly require it.
		// Some encoders produce eXIf-after-IDAT; the parser walks the entire chunk stream so order doesn't
		// matter. Pinned to rule out a regression where someone adds an "ordered scan" optimisation that breaks
		// early.
		byte[] tiff = { 'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00 };
		byte[] idatStub = buildChunk("IDAT", new byte[]{ 0x78, (byte) 0x9C });
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), idatStub, buildChunk("eXIf", tiff));
		List<JpegSegment> segs = PngMetadataExtractor.extract(png);
		assertEquals(1, segs.size());
		assertTrue(segs.get(0).isExif());
	}

	@Test
	public void extractFindsExifAfterMultiplePrecedingChunks() throws IOException
	{
		// Walk doesn't bail on chunks before eXIf. PNG ordering puts IHDR first, then various ancillary chunks
		// in any order, then IDAT, then IEND. Place eXIf after a stub iCCP-like chunk to verify the loop
		// traverses past unrelated chunks.
		byte[] iccp = buildChunk("iCCP", new byte[]{ 0x12, 0x34, 0x56 });
		byte[] tiff = { 'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08 };
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), iccp, buildChunk("eXIf", tiff));
		List<JpegSegment> segs = PngMetadataExtractor.extract(png);
		assertEquals(1, segs.size());
		assertTrue(segs.get(0).isExif());
	}

	@Test
	public void extractHandlesZeroLengthExifChunk() throws IOException
	{
		// PNG spec doesn't forbid a zero-length eXIf; the parser produces a synthetic APP1 with empty TIFF
		// payload (segLen = 8, just the 2 length + 6 "Exif\\0\\0" overhead). Downstream injectors treat the
		// resulting segment as a no-op EXIF marker rather than throwing on the empty body.
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", new byte[0]));
		List<JpegSegment> segs = PngMetadataExtractor.extract(png);
		assertEquals(1, segs.size());
		byte[] data = segs.get(0).data();
		// Segment must be 10 bytes total: FF E1 00 08 'E' 'x' 'i' 'f' 00 00.
		assertEquals(10, data.length);
		int segLen = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
		assertEquals(8, segLen);
	}

	@Test
	public void extractIgnoresExifChunkAfterIendChunk() throws IOException
	{
		// IEND terminates the PNG datastream per the spec — bytes past it are trailer junk (vendor blobs,
		// appended data), not chunks. An eXIf-shaped byte run planted after IEND must be invisible to all
		// three entry points; walking past IEND would let attacker-controlled trailer bytes masquerade as
		// EXIF metadata (and re-emit verbatim through the >64KB raw-TIFF route).
		byte[] tiff = {
			'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
			0x01, 0x00,                             // entry count = 1
			0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
			0x06, 0x00, 0x00, 0x00                  // orientation = 6
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("IEND", new byte[0]),
			buildChunk("eXIf", tiff));
		assertTrue("extract must not see an eXIf planted after IEND",
			PngMetadataExtractor.extract(png).isEmpty());
		assertTrue("extractRawTiff must not see an eXIf planted after IEND",
			PngMetadataExtractor.extractRawTiff(png).isEmpty());
		assertEquals("extractOrientation must fall back upright for an eXIf planted after IEND",
			1, PngMetadataExtractor.extractOrientation(png));
	}

	// ── extractOrientation ── PNG sources need orientation parsed at load time so applyOrientation rotates pixels
	// before the user crops/edits. Without this, a PNG with eXIf orientation=6 would display sideways while the
	// export normaliser writes orientation=1, baking the wrong rotation into the saved file.

	@Test
	public void extractOrientationReadsBigEndianTiffOrientationTag() throws IOException
	{
		// MM (big-endian) variant: tag value of 8 (rotate 90 CCW) at the same byte position.
		byte[] tiff = {
			'M', 'M',                               // big-endian
			0x00, 0x2A,                             // TIFF magic 42
			0x00, 0x00, 0x00, 0x08,                 // IFD0 offset = 8
			0x00, 0x01,                             // entry count = 1
			0x01, 0x12,                             // tag 0x0112 (Orientation)
			0x00, 0x03,                             // type SHORT
			0x00, 0x00, 0x00, 0x01,                 // count = 1
			0x00, 0x08, 0x00, 0x00                  // value = 8 (high 2 bytes)
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", tiff));
		assertEquals(8, PngMetadataExtractor.extractOrientation(png));
	}

	@Test
	public void extractOrientationReadsLittleEndianTiffOrientationTag() throws IOException
	{
		// II (little-endian) TIFF with one IFD0 entry: tag=0x0112, type=SHORT(3), count=1, value=6 (rotate 90
		// CW). IFD0 starts at offset 8 (right after the 8-byte TIFF header).
		byte[] tiff = {
			'I', 'I',                               // little-endian
			0x2A, 0x00,                             // TIFF magic 42
			0x08, 0x00, 0x00, 0x00,                 // IFD0 offset = 8
			0x01, 0x00,                             // entry count = 1
			0x12, 0x01,                             // tag 0x0112 (Orientation), little-endian
			0x03, 0x00,                             // type SHORT
			0x01, 0x00, 0x00, 0x00,                 // count = 1
			0x06, 0x00, 0x00, 0x00                  // value = 6 (in low 2 bytes)
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", tiff));
		assertEquals(6, PngMetadataExtractor.extractOrientation(png));
	}

	@Test
	public void extractOrientationRejectsOutOfRangeValues() throws IOException
	{
		// EXIF orientation is defined for values 1..8. A value of 9 is malformed and must map to upright (1) —
		// never returned verbatim — because BitmapUtils.applyOrientation treats out-of-range values as identity
		// but the rest of the load path uses the orientation value to pre-compute display dimensions and would
		// mis-classify a 9.
		byte[] tiff = {
			'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
			0x01, 0x00,                             // entry count = 1
			0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
			0x09, 0x00, 0x00, 0x00                  // value = 9 (out of range)
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", tiff));
		assertEquals(1, PngMetadataExtractor.extractOrientation(png));
	}

	@Test
	public void extractOrientationRejectsWrongEntryCount() throws IOException
	{
		// Tag 0x0112 with type SHORT (3) but count != 1 — value field then stores an offset, not a value.
		byte[] tiff = {
			'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
			0x12, 0x01, 0x03, 0x00, 0x02, 0x00, 0x00, 0x00,                 // count = 2 — wrong
			0x06, 0x00, 0x00, 0x00
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", tiff));
		assertEquals(1, PngMetadataExtractor.extractOrientation(png));
	}

	@Test
	public void extractOrientationRejectsWrongEntryType() throws IOException
	{
		// Tag 0x0112 with type LONG (4) instead of SHORT (3). Real EXIF always emits orientation as SHORT/1, so
		// reading a different type means we'd be sampling random bytes from a coincidental same-tag entry in an
		// unrelated IFD.
		byte[] tiff = {
			'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
			0x01, 0x00,                             // entry count = 1
			0x12, 0x01,                             // tag 0x0112
			0x04, 0x00,                             // type LONG (4) — wrong
			0x01, 0x00, 0x00, 0x00,                 // count = 1
			0x06, 0x00, 0x00, 0x00                  // value
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", tiff));
		assertEquals(1, PngMetadataExtractor.extractOrientation(png));
	}

	@Test
	public void extractOrientationRejectsWrongTiffMagic() throws IOException
	{
		// II byte-order field but TIFF magic != 42 — corrupt eXIf payload that happens to start with II would
		// otherwise read entry+8 as orientation. Real TIFF always carries magic 42 at offset 2.
		byte[] tiff = {
			'I', 'I',                               // II byte-order
			(byte) 0xAB, (byte) 0xCD,               // wrong magic (not 42)
			0x08, 0x00, 0x00, 0x00,                 // IFD0 offset = 8
			0x01, 0x00,                             // entry count = 1
			0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
			0x06, 0x00, 0x00, 0x00                  // would-be orientation = 6
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", tiff));
		assertEquals(1, PngMetadataExtractor.extractOrientation(png));
	}

	@Test
	public void extractOrientationReturnsOneForMissingOrientationTag() throws IOException
	{
		// Valid TIFF header + IFD0 entry-count=0 (no entries). No orientation tag → upright fallback.
		byte[] tiff = {
			'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
			0x00, 0x00                              // entry count = 0
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", tiff));
		assertEquals(1, PngMetadataExtractor.extractOrientation(png));
	}

	@Test
	public void extractOrientationReturnsOneForNonPng()
	{
		// Non-PNG inputs return the upright fallback rather than throwing — matches the
		// BitmapUtils.readExifOrientation contract for malformed JPEG.
		assertEquals(1, PngMetadataExtractor.extractOrientation(new byte[]{ 0x12, 0x34, 0x56 }));
		assertEquals(1, PngMetadataExtractor.extractOrientation(null));
	}

	@Test
	public void extractOrientationReturnsOneForPngWithoutExifChunk() throws IOException
	{
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk());
		assertEquals(1, PngMetadataExtractor.extractOrientation(png));
	}

	@Test
	public void extractOrientationReturnsOneOnMalformedByteOrder() throws IOException
	{
		// Neither "II" nor "MM" — defensive fallback rather than guessing endianness.
		byte[] tiff = {
			0x12, 0x34, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
			0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", tiff));
		assertEquals(1, PngMetadataExtractor.extractOrientation(png));
	}

	// ── extractRawTiff ── Used by the PNG → PNG round-trip path. Must return the eXIf chunk's bytes regardless of
	// size — no JPEG APP1 cap applies, and the PNG output's eXIf chunk has a u31 length field.

	@Test
	public void extractRawTiffPreservesOversizedExifChunk() throws IOException
	{
		// The synthetic-APP1 path (extract) drops oversized eXIf to avoid corrupting JPEG output, but the
		// raw-TIFF path is used by PNG → PNG export where no u16 cap applies. The raw bytes must round-trip in
		// full so a PNG with > 64KB EXIF (camera with extensive MakerNote / GPS) keeps its metadata when
		// re-saved as PNG.
		byte[] hugeTiff = new byte[80_000];
		hugeTiff[0] = 'I';
		hugeTiff[1] = 'I';
		hugeTiff[2] = 0x2A;
		hugeTiff[3] = 0x00;
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", hugeTiff));
		byte[] result = PngMetadataExtractor.extractRawTiff(png).orElseThrow();
		assertEquals(80_000, result.length);
		assertEquals('I', result[0]);
	}

	@Test
	public void extractRawTiffReturnsExactlyTheChunkBytesAtJpegApp1Cap() throws IOException
	{
		// At exactly 65527 bytes (the JPEG APP1 cap that extract() honours), extract() succeeds AND
		// extractRawTiff() must return identical bytes — pinning that the parallel readers walk the same chunk
		// to the same end offset, so the JPEG-injection synthetic APP1 and the PNG-injection raw TIFF agree on
		// what "the EXIF" actually is.
		byte[] capTiff = new byte[65527];
		capTiff[0] = 'I';
		capTiff[1] = 'I';
		capTiff[2] = 0x2A;
		capTiff[3] = 0x00;
		capTiff[65526] = (byte) 0xAB; // sentinel at the last byte
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", capTiff));
		byte[] result = PngMetadataExtractor.extractRawTiff(png).orElseThrow();
		assertArrayEquals(capTiff, result);
	}

	@Test
	public void extractRawTiffReturnsExifChunkBytesUnconditionally() throws IOException
	{
		byte[] tiff = { 'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00 };
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", tiff));
		byte[] result = PngMetadataExtractor.extractRawTiff(png).orElseThrow();
		assertArrayEquals(tiff, result);
	}

	@Test
	public void extractRawTiffReturnsEmptyForNonPngBytes()
	{
		// Symmetric with extractReturnsEmptyForNonPngBytes — the raw-TIFF entry point also signature-checks.
		assertTrue(PngMetadataExtractor.extractRawTiff(
			new byte[]{ 0x12, 0x34, 0x56, 0x78, 0x00, 0x00, 0x00, 0x00 }).isEmpty());
	}

	@Test
	public void extractRawTiffReturnsEmptyForNullInput()
	{
		assertTrue(PngMetadataExtractor.extractRawTiff(null).isEmpty());
	}

	@Test
	public void extractRawTiffReturnsEmptyWhenChunkLengthExceedsFile() throws IOException
	{
		// Mirror extractReturnsEmptyWhenChunkLengthExceedsFile — the bounds check in findExifChunk applies
		// uniformly across extract / extractOrientation / extractRawTiff because they share the helper.
		byte[] bogusChunk = {
			0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,   // length
			'e', 'X', 'I', 'f',                              // type
			0x00, 0x00, 0x00, 0x00                            // 4 bytes (much less than claimed)
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), bogusChunk);
		assertTrue(PngMetadataExtractor.extractRawTiff(png).isEmpty());
	}

	@Test
	public void extractRawTiffReturnsEmptyWhenNoExifChunk() throws IOException
	{
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk());
		assertTrue(PngMetadataExtractor.extractRawTiff(png).isEmpty());
	}

	@Test
	public void extractRejectsChunkLengthWithTopBitSet() throws IOException
	{
		// PNG chunk length is u31 per spec (top bit reserved 0). The reader uses long arithmetic specifically
		// to defend against a top-bit-set length value (0x80000000 .. 0xFFFFFFFF) that a signed-int read would
		// wrap negative and slip past the past-EOF guard. Pin the contract: all three entry points reject.
		byte[] bogusChunk = {
			(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,   // length = 0xFFFFFFFF (u31 invalid)
			'e', 'X', 'I', 'f',                                     // type
			0x00, 0x00, 0x00, 0x00                                   // 4 bytes
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), bogusChunk);
		assertTrue("extract must reject u31-invalid length", PngMetadataExtractor.extract(png).isEmpty());
		assertTrue("extractRawTiff must reject u31-invalid length",
			PngMetadataExtractor.extractRawTiff(png).isEmpty());
		assertEquals("extractOrientation must reject u31-invalid length",
			1, PngMetadataExtractor.extractOrientation(png));
	}

	@Test
	public void extractRejectsCrcCorruptExifChunk() throws IOException
	{
		// The eXIf chunk's CRC32 (over type + data, per the PNG spec) must validate before the metadata is
		// trusted — a bit-rotted or tampered chunk drops METADATA ONLY (pixel decode elsewhere is untouched).
		// Corrupt the stored CRC's last byte; the identical chunk with an intact CRC extracts fine (pinned by
		// extractWrapsExifChunkAsApp1Segment), so a failure here isolates the CRC gate.
		byte[] tiff = {
			'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
			0x01, 0x00,                             // entry count = 1
			0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
			0x06, 0x00, 0x00, 0x00                  // orientation = 6
		};
		byte[] chunk = buildChunk("eXIf", tiff);
		chunk[chunk.length - 1] ^= 0x01; // flip one CRC bit
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), chunk);
		assertTrue("extract must reject a CRC-corrupt eXIf chunk", PngMetadataExtractor.extract(png).isEmpty());
		assertTrue("extractRawTiff must reject a CRC-corrupt eXIf chunk",
			PngMetadataExtractor.extractRawTiff(png).isEmpty());
		assertEquals("extractOrientation must fall back upright on a CRC-corrupt eXIf chunk",
			1, PngMetadataExtractor.extractOrientation(png));
	}

	@Test
	public void extractReturnsEmptyForNonPngBytes()
	{
		byte[] notPng = { 0x12, 0x34, 0x56, 0x78, 0x00, 0x00, 0x00, 0x00 };
		assertTrue(PngMetadataExtractor.extract(notPng).isEmpty());
	}

	@Test
	public void extractReturnsEmptyForNullInput()
	{
		assertTrue(PngMetadataExtractor.extract(null).isEmpty());
	}

	@Test
	public void extractReturnsEmptyForPngWithoutExifChunk() throws IOException
	{
		// Valid PNG with just signature + IHDR chunk + no eXIf.
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk());
		assertTrue(PngMetadataExtractor.extract(png).isEmpty());
	}

	@Test
	public void extractReturnsEmptyForSignatureOnlyFile()
	{
		// Just the 8-byte PNG signature, no chunks at all. The walker's `off + 8 <= png.length` check exits the
		// loop immediately without throwing.
		assertTrue(PngMetadataExtractor.extract(PNG_SIGNATURE).isEmpty());
	}

	@Test
	public void extractReturnsEmptyForTooShortInput()
	{
		// Less than the 8-byte signature.
		assertTrue(PngMetadataExtractor.extract(new byte[]{ (byte) 0x89, 'P' }).isEmpty());
	}

	@Test
	public void extractReturnsEmptyWhenChunkLengthExceedsFile() throws IOException
	{
		// Chunk header claims length 0x7FFFFFFF (~2GB) but only 4 actual bytes follow. Bounds check should
		// reject and break the loop.
		byte[] bogusChunk = {
			0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, // length
			'e', 'X', 'I', 'f',                            // type
			0x00, 0x00, 0x00, 0x00                          // 4 bytes (much less than claimed)
		};
		byte[] png = concat(PNG_SIGNATURE, bogusChunk);
		assertTrue(PngMetadataExtractor.extract(png).isEmpty());
	}

	@Test
	public void extractSkipsOversizedExifChunkToAvoidJpegApp1Truncation() throws IOException
	{
		// JPEG APP1 segment length is u16 (max 65535). The extractor wraps a PNG eXIf chunk as a synthetic APP1
		// segment — emitting one whose claimed segLen overflows the u16 cap would corrupt every JPEG produced
		// from PNG → JPEG conversion (the truncated length field misaligns all downstream parsers). Pinned
		// regression: an eXIf with > 65527-byte TIFF payload (the cap minus the 2 length + 6 "Exif\\0\\0"
		// overhead bytes) returns an empty list rather than producing a poison segment.
		byte[] hugeTiff = new byte[65528]; // 1 byte over the safe cap
		hugeTiff[0] = 'I';
		hugeTiff[1] = 'I';
		hugeTiff[2] = 0x2A;
		hugeTiff[3] = 0x00;
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", hugeTiff));
		List<JpegSegment> segs = PngMetadataExtractor.extract(png);
		assertTrue("oversize eXIf must not be wrapped as a malformed APP1 segment", segs.isEmpty());
	}

	@Test
	public void extractTakesFirstExifWhenMultiplePresent() throws IOException
	{
		// PNG spec says SHOULD be at most one eXIf, but defensive: the parser takes the first match and stops,
		// matching the JPEG injector's "break on first EXIF segment" convention.
		byte[] tiff1 = { 'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00 };
		byte[] tiff2 = { 'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08 };
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(),
			buildChunk("eXIf", tiff1), buildChunk("eXIf", tiff2));
		List<JpegSegment> segs = PngMetadataExtractor.extract(png);
		assertEquals(1, segs.size());
		// First eXIf carried tiff1 (II header).
		assertEquals('I', segs.get(0).data()[10]);
	}

	@Test
	public void extractWrapsExifChunkAsApp1Segment() throws IOException
	{
		// Build a PNG with signature + IHDR + eXIf chunk holding minimal TIFF (8-byte header).
		byte[] tiffData = {
			'I', 'I', 0x2A, 0x00,           // little-endian TIFF magic
			0x08, 0x00, 0x00, 0x00          // IFD0 offset = 8
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", tiffData));
		List<JpegSegment> segs = PngMetadataExtractor.extract(png);
		assertEquals(1, segs.size());
		JpegSegment seg = segs.get(0);
		assertEquals(0xE1, seg.marker());
		assertTrue("synthetic APP1 should be recognised by isExif()", seg.isExif());

		// Layout pin: data[0..1] = FF E1, data[2..3] = segLen big-endian, data[4..9] = "Exif\0\0", data[10..] =
		// TIFF bytes verbatim.
		byte[] data = seg.data();
		assertArrayEquals(new byte[] { (byte) 0xFF, (byte) 0xE1 }, Arrays.copyOfRange(data, 0, 2));
		int segLen = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
		assertEquals("segLen = 2 (length bytes) + 6 (Exif\\0\\0) + tiff.length",
			2 + 6 + tiffData.length, segLen);
		assertArrayEquals(new byte[] { 'E', 'x', 'i', 'f', 0, 0 }, Arrays.copyOfRange(data, 4, 10));
		assertArrayEquals(tiffData, Arrays.copyOfRange(data, 10, 10 + tiffData.length));
	}
}
