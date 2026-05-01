package com.cropcenter.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.IOException;

/**
 * Tests for the gain-map extractor that drives HDR detection at load and gain-map
 * preservation through the save pipeline. The extractor walks past the primary's
 * marker structure (handling SOS entropy-coded scans, byte stuffing, and restart
 * markers) and returns the secondary JPEG starting at the byte after the primary's
 * EOI. A regression that misses the EOI inside the SOS stream would either include
 * primary scan bytes in the "gain map" (breaking the secondary JPEG's SOI check) or
 * over-walk and include the gain map's own EOI.
 */
public class GainMapExtractorTest
{
	@Test
	public void extractsGainMapBetweenPrimaryEoiAndSeft() throws IOException
	{
		// Layout: primary + secondary + SEFT trailer. Extractor must locate the END
		// boundary at the gain map's EOI (last FFD9 before SEFT), not the file end.
		byte[] gainMap = JpegFixtures.concat(
			JpegFixtures.soi(),
			JpegFixtures.minimalScanAndEoi());
		byte[] trailer = new byte[]{ 0x77, 0x00, 0x00, 0x00, 0x01, 'S', 'E', 'F', 'T' };
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			JpegFixtures.minimalScanAndEoi(),    // primary
			gainMap,
			trailer);

		byte[] extracted = GainMapExtractor.extract(file);
		assertArrayEquals(gainMap, extracted);
	}

	@Test
	public void extractsGainMapWithoutSeftTrailer() throws IOException
	{
		// Some Samsung HDR files don't carry a SEFT trailer (initial uneditied capture
		// before Gallery has touched it). The extractor must still find the gain map
		// using file length as the end boundary.
		byte[] gainMap = JpegFixtures.concat(
			JpegFixtures.soi(),
			JpegFixtures.minimalScanAndEoi());
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			JpegFixtures.minimalScanAndEoi(),    // primary
			gainMap);

		byte[] extracted = GainMapExtractor.extract(file);
		assertArrayEquals(gainMap, extracted);
	}

	@Test
	public void primaryEntropyByteStuffingNotMisreadAsEoi() throws IOException
	{
		// Inside an entropy-coded scan, FF 00 is byte-stuffed payload (NOT a marker),
		// and FF D0..D7 are restart markers. The walker must treat both as scan body
		// and only stop at FF D9 (EOI) or another marker like FF C0..C2. Construct an
		// entropy stream that contains FF 00 (stuffed) + FF D2 (RST2) before the real
		// EOI; the extractor should still find the correct EOI.
		byte[] primary = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[]{
				(byte) 0xFF, (byte) 0xDA,                 // SOS
				0x00, 0x06,                               // segLen = 6
				0x01, 0x00, 0x00, 0x00,                   // SOS body (4 bytes)
				0x55, (byte) 0xFF, 0x00, 0x66,            // entropy: stuffed FF 00
				(byte) 0xFF, (byte) 0xD2,                 // RST2
				0x77, 0x77,                               // more entropy
				(byte) 0xFF, (byte) 0xD9,                 // EOI
			});
		byte[] gainMap = JpegFixtures.concat(
			JpegFixtures.soi(),
			JpegFixtures.minimalScanAndEoi());
		byte[] file = JpegFixtures.concat(primary, gainMap);

		byte[] extracted = GainMapExtractor.extract(file);
		assertArrayEquals(gainMap, extracted);
	}

	@Test
	public void returnsGainMapStartingWithSoi() throws IOException
	{
		// The extracted gain map MUST start with FF D8 (JPEG SOI). The post-EOI byte
		// of the primary must literally be a new FF D8. If it's not, the extractor
		// rejects (returns null) rather than handing back a JPEG-shaped lie.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			JpegFixtures.minimalScanAndEoi(),
			new byte[]{ 0x12, 0x34, 0x56 });   // not a JPEG SOI

		byte[] extracted = GainMapExtractor.extract(file);
		assertNull(extracted);
	}

	@Test
	public void returnsNullOnFileTooShort()
	{
		assertNull(GainMapExtractor.extract(new byte[0]));
		assertNull(GainMapExtractor.extract(new byte[5]));
		assertNull(GainMapExtractor.extract(null));
	}

	@Test
	public void returnsNullOnNoEoiBeforeEnd() throws IOException
	{
		// Truncated file: SOI but no EOI anywhere. findPrimaryEoi must return -1
		// rather than walking off the end and returning a junk slice.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, new byte[]{ 0x01, 0x02, 0x03 }));

		byte[] extracted = GainMapExtractor.extract(file);
		assertNull(extracted);
	}

	@Test
	public void returnsNullWhenPrimaryHasNoTrailingData() throws IOException
	{
		// Plain JPEG with no gain map — primary EOI is also file end. Extractor
		// must return null (not an empty array) so callers can null-check the
		// "is this HDR" decision uniformly.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			JpegFixtures.minimalScanAndEoi());

		byte[] extracted = GainMapExtractor.extract(file);
		assertNull(extracted);
	}

	@Test
	public void retainedGainMapByteCountIncludesItsOwnEoi() throws IOException
	{
		// The returned gain-map bytes start at the byte AFTER primary's EOI and run
		// through (and including) the gain map's own EOI. Pin down the exact length
		// for a known fixture so a regression in the end-boundary calculation surfaces
		// here (2-byte SOI + 4-byte SOS header + 4-byte body + 3-byte payload + 2-byte
		// EOI = 15 bytes from minimalScanAndEoi + soi).
		byte[] gainMap = JpegFixtures.concat(
			JpegFixtures.soi(),
			JpegFixtures.minimalScanAndEoi());
		assertEquals(15, gainMap.length);

		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			JpegFixtures.minimalScanAndEoi(),
			gainMap);

		byte[] extracted = GainMapExtractor.extract(file);
		assertNotNull(extracted);
		assertEquals(15, extracted.length);
	}
}
