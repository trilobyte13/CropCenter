package com.cropcenter.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * Tests for the JPEG marker walker that drives identity-metadata preservation. Every load runs this against the source
 * bytes; the resulting JpegSegment list feeds CropExporter's post-encode injection step (which re-attaches original's
 * APPs onto the canvas-encoded output). A regression that drops a real APP segment, walks past EOF, or accepts a
 * malformed segment length would manifest as missing EXIF on saved files or hard crashes on truncated inputs.
 */
public final class JpegMetadataExtractorTest
{
	@Test
	public void emptyInputReturnsEmptyList() throws IOException
	{
		assertTrue(JpegMetadataExtractor.extract(new byte[0]).isEmpty());
		assertTrue(JpegMetadataExtractor.extract(new byte[]{ 0x00 }).isEmpty());
	}

	@Test
	public void extractsApp1AndApp2Segments() throws IOException
	{
		byte[] exif = JpegFixtures.exifAppPayload();
		byte[] iccPayload = "ICC_PROFILE\0body".getBytes();
		byte[] jpeg = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.appSegment(0xE1, exif),
			JpegFixtures.appSegment(0xE2, iccPayload), JpegFixtures.minimalScanAndEoi());

		List<JpegSegment> segs = JpegMetadataExtractor.extract(jpeg);
		assertEquals(2, segs.size());
		assertEquals(0xE1, segs.get(0).marker());
		assertEquals(0xE2, segs.get(1).marker());
		assertTrue(segs.get(0).isExif());
		assertTrue(segs.get(1).isIcc());
	}

	@Test
	public void extractsCommentSegment() throws IOException
	{
		// COM (FFFE) is the one non-APPn marker the extractor keeps.
		byte[] commentPayload = "comment text".getBytes();
		byte[] jpeg = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.appSegment(0xFE, commentPayload),
			JpegFixtures.minimalScanAndEoi());

		List<JpegSegment> segs = JpegMetadataExtractor.extract(jpeg);
		assertEquals(1, segs.size());
		assertEquals(0xFE, segs.get(0).marker());
	}

	@Test
	public void malformedSegmentLengthHaltsParsing() throws IOException
	{
		// Segment length of 1 is corrupt (must be ≥ 2 to include the length bytes). Parser should bail rather
		// than treat as a bogus 2-byte segment.
		byte[] jpeg = JpegFixtures.concat(JpegFixtures.soi(),
			new byte[]{ (byte) 0xFF, (byte) 0xE1, 0x00, 0x01 });   // segLen = 1, malformed
		List<JpegSegment> segs = JpegMetadataExtractor.extract(jpeg);
		assertTrue("malformed segment should not be added", segs.isEmpty());
	}

	@Test
	public void malformedZeroSegmentLengthHaltsParsing() throws IOException
	{
		// Boundary case: segLen=0 must also bail. The guard is `segLen < 2`, so segLen=0 is rejected. Pin this
		// distinct from the segLen=1 test so a regression to `segLen < 1` (which would still pass the segLen=1
		// test but loop forever at next == off when segLen=0) surfaces here.
		byte[] jpeg = JpegFixtures.concat(JpegFixtures.soi(),
			new byte[]{ (byte) 0xFF, (byte) 0xE1, 0x00, 0x00 });   // segLen = 0, malformed
		List<JpegSegment> segs = JpegMetadataExtractor.extract(jpeg);
		assertTrue("zero-length segment should not be added", segs.isEmpty());
	}

	@Test
	public void markerFillBytesBetweenSegmentsArePreserved() throws IOException
	{
		// JPEG spec ITU-T T.81 §B.1.1.2: any marker may be preceded by any number of 0xFF fill bytes. Without
		// fill-byte handling the walker would mis-read the second 0xFF as marker code 0xFF and fail to extract
		// the APP segment that follows. This test pins the regression fix.
		byte[] exif = JpegFixtures.exifAppPayload();
		byte[] jpeg = JpegFixtures.concat(JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xE1 }, // 1 fill byte before APP1
			new byte[] { 0x00, (byte) (2 + exif.length) },         // segLen
			exif, JpegFixtures.minimalScanAndEoi());
		List<JpegSegment> segs = JpegMetadataExtractor.extract(jpeg);
		assertEquals(1, segs.size());
		assertEquals(0xE1, segs.get(0).marker());
		assertTrue(segs.get(0).isExif());
	}

	@Test
	public void noSoiReturnsEmptyList()
	{
		// Anything not starting with FF D8 isn't a JPEG — return empty rather than trying to parse from byte 0.
		// (Some callers feed PNG bytes here when the image format is uncertain at the call site.)
		byte[] notJpegBytes = { 0x12, 0x34, 0x56, 0x78, (byte) 0xFF, (byte) 0xD8 };
		assertTrue(JpegMetadataExtractor.extract(notJpegBytes).isEmpty());
	}

	@Test
	public void preservesRawSegmentBytesIncludingMarkerAndLength() throws IOException
	{
		// The "data" field of a returned JpegSegment includes the FF marker and the 2-byte length field, not
		// just the payload — JpegMetadataInjector needs the segment verbatim to write it back into the output.
		// Verify the full 4-byte header is preserved.
		byte[] body = { 0x10, 0x20, 0x30 };
		byte[] jpeg = JpegFixtures.concat(
			JpegFixtures.soi(), JpegFixtures.appSegment(0xE3, body), JpegFixtures.minimalScanAndEoi());
		List<JpegSegment> segs = JpegMetadataExtractor.extract(jpeg);
		assertEquals(1, segs.size());
		// FF E3 marker + length 0x0005 (2 length bytes + 3 payload bytes) + payload verbatim.
		assertArrayEquals(new byte[] { (byte) 0xFF, (byte) 0xE3, 0x00, 0x05, 0x10, 0x20, 0x30 },
			segs.get(0).data());
	}

	@Test
	public void rstMarkersAreSkippedNotCollected() throws IOException
	{
		// Restart markers (FF D0..D7) are standalone and don't carry segment data. Walker should advance past
		// them without trying to read a length.
		byte[] jpeg = JpegFixtures.concat(JpegFixtures.soi(),
			new byte[]{ (byte) 0xFF, (byte) 0xD0 },                  // RST0 — should skip
			JpegFixtures.appSegment(0xE1, JpegFixtures.exifAppPayload()), JpegFixtures.minimalScanAndEoi());
		List<JpegSegment> segs = JpegMetadataExtractor.extract(jpeg);
		assertEquals(1, segs.size());
		assertTrue(segs.get(0).isExif());
	}

	@Test
	public void stopsAtSosMarker() throws IOException
	{
		// SOS terminates segment-collection. APP segments after SOS (rare, but theoretically present in some
		// embedded thumbnails) are not collected by the primary-image extractor.
		byte[] jpeg = JpegFixtures.concat(
			JpegFixtures.soi(), JpegFixtures.appSegment(0xE1, JpegFixtures.exifAppPayload()),
			JpegFixtures.minimalScanAndEoi());
		List<JpegSegment> segs = JpegMetadataExtractor.extract(jpeg);
		assertEquals(1, segs.size());
		assertEquals(0xE1, segs.get(0).marker());
	}

	@Test
	public void truncatedSegmentIsSkippedNotIncluded() throws IOException
	{
		// Segment claims length = 100 but file ends after 10 bytes of segment body. The extractor's bounds
		// check (off + totalLen <= jpeg.length) rejects this entry; nothing should be added for it.
		byte[] jpeg = JpegFixtures.concat(
			JpegFixtures.soi(), new byte[]{ (byte) 0xFF, (byte) 0xE1, 0x00, 0x64,        // segLen = 100
				0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 });   // only 8 body bytes
		List<JpegSegment> segs = JpegMetadataExtractor.extract(jpeg);
		assertTrue("truncated segment should not be added", segs.isEmpty());
	}
}
