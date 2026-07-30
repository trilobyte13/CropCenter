package com.cropcenter.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * Tests for the Samsung SEFT trailer detector. Every load runs this; every save re- appends whatever this returns. A
 * regression that mis-locates the trailer boundary would either drop SEFT bytes (breaking Gallery's Revert chain on
 * Samsung-edited sources) or include gain-map bytes in the "trailer" (which then get re-appended after an
 * already-present gain-map block and fail Gallery's pre-flight).
 */
public final class SeftExtractorTest
{
	@Test
	public void extractsTrailerAfterSinglePrimaryEoi() throws IOException
	{
		// Layout: SOI + APP1 + SOS+EOI + SEFT data + 4-byte size + "SEFT" magic. The trailer starts at the byte
		// immediately after the primary's EOI.
		byte[] trailerData = { 0x42, 0x42, 0x42, 0x42 };
		byte[] sizeFooter = { 0x00, 0x00, 0x00, 0x04 };       // size value (ignored by extractor)
		byte[] seftMagic = { 'S', 'E', 'F', 'T' };
		byte[] expectedTrailer = JpegFixtures.concat(trailerData, sizeFooter, seftMagic);

		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(), JpegFixtures.appSegment(0xE1, JpegFixtures.exifAppPayload()),
			JpegFixtures.minimalScanAndEoi(), expectedTrailer);

		byte[] result = SeftExtractor.extract(file, true).orElseThrow();
		assertArrayEquals(expectedTrailer, result);
	}

	@Test
	public void findsLastEoiWhenGainMapPresent() throws IOException
	{
		// Layout with both primary and gain map: the trailer starts after the GAIN MAP's EOI (the last FFD9 in
		// the file), not the primary's. The backwards scan from the SEFT magic must find the LATER FFD9, not
		// the first.
		byte[] trailerData = { 0x33, 0x33 };
		byte[] sizeFooter = { 0x00, 0x00, 0x00, 0x02 };
		byte[] seftMagic = { 'S', 'E', 'F', 'T' };
		byte[] expectedTrailer = JpegFixtures.concat(trailerData, sizeFooter, seftMagic);

		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(), JpegFixtures.minimalScanAndEoi(),       // primary EOI
			JpegFixtures.soi(),                     // gain map SOI (FFD8)
			JpegFixtures.minimalScanAndEoi(),       // gain map EOI (FFD9)
			expectedTrailer);

		byte[] result = SeftExtractor.extract(file, true).orElseThrow();
		assertArrayEquals(expectedTrailer, result);
	}

	@Test
	public void hasSeftFooterAcceptsExactMinAndRejectsShorterOrWrongMagic()
	{
		// `hasSeftFooter` is the gate consumed by both `extract` and `GainMapExtractor`'s `file.length -
		// FOOTER_SIZE` slice cap. A regression in its boundary check would silently mis-cap the gain-map walk
		// on Samsung files (dropping HDR) or skip SEFT preservation (breaking Gallery's Revert chain). Pin all
		// four boundary conditions: too-short, exact 12-byte minimum with valid magic, exact 12-byte with wrong
		// magic, and 12-byte with partial magic at the wrong offset.
		assertFalse("11-byte file can't carry SEFT trailer (min is 4 magic + 4 size + 4 EOI = 12)",
			SeftExtractor.hasSeftFooter(new byte[11]));
		byte[] exactMin = new byte[12];
		exactMin[8] = 'S';
		exactMin[9] = 'E';
		exactMin[10] = 'F';
		exactMin[11] = 'T';
		assertTrue("12-byte file with magic at last 4 bytes is exactly the minimum",
			SeftExtractor.hasSeftFooter(exactMin));
		byte[] wrongMagic = exactMin.clone();
		wrongMagic[10] = 'F';
		wrongMagic[11] = 't';   // lowercase t — case-sensitive magic
		assertFalse("magic check is case-sensitive — 'SEFt' must reject",
			SeftExtractor.hasSeftFooter(wrongMagic));
		byte[] offsetMagic = new byte[12];
		offsetMagic[6] = 'S';
		offsetMagic[7] = 'E';
		offsetMagic[8] = 'F';
		offsetMagic[9] = 'T';   // magic at bytes 6..9 (not last 4) — should reject
		assertFalse("magic must be at the LAST 4 bytes, not anywhere in the trailing region",
			SeftExtractor.hasSeftFooter(offsetMagic));
	}

	@Test
	public void returnsEmptyOnFileTooShort()
	{
		// SEFT trailer is at minimum 12 bytes (FFD9 + 4-byte size + "SEFT"). Anything shorter can't carry one
		// and must return empty without indexing OOB.
		assertTrue(SeftExtractor.extract(new byte[]{ }, true).isEmpty());
		assertTrue(SeftExtractor.extract(new byte[]{ 0x01 }, true).isEmpty());
		assertTrue(SeftExtractor.extract(new byte[11], true).isEmpty());
	}

	@Test
	public void returnsEmptyOnMissingSeftMagic() throws IOException
	{
		// File ends with bytes other than "SEFT" — not a Samsung file.
		byte[] file = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.minimalScanAndEoi());
		assertTrue(SeftExtractor.extract(file, true).isEmpty());
	}

	@Test
	public void returnsEmptyOnNullInput()
	{
		assertTrue(SeftExtractor.extract(null, true).isEmpty());
	}

	@Test
	public void returnsEmptyWhenGainMapPresentButSliceUnparseable() throws IOException
	{
		// When primary's EOI is followed by FFD8 (gain-map SOI) but the gain-map walk finds no clean
		// EOI before the SEFT footer, the extractor must fail closed and return empty — the trailer is
		// dropped, never truncated. The size footer is a PLAUSIBLE little-endian value (4) on purpose:
		// a regression that "recovers" the trailer from the footer u32 (which is the SEFH DIRECTORY
		// length only, not the whole-trailer size) would compute an in-bounds start inside the broken
		// gain map, return truncated bytes instead of empty, and fail this assertion.
		byte[] gainMapWithNoEoi = {
			(byte) 0xFF, (byte) 0xD8,                    // SOI
			(byte) 0xFF, (byte) 0xE0, 0x00, 0x06,        // APP0 with 6-byte length
			0x01, 0x02, 0x03, 0x04                       // 4 bytes of body — never reaches EOI
		};
		byte[] sizeFooter = { 0x04, 0x00, 0x00, 0x00 };  // LE u32 = 4
		byte[] seftMagic = { 'S', 'E', 'F', 'T' };
		byte[] file = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.minimalScanAndEoi(),
			gainMapWithNoEoi, sizeFooter, seftMagic);
		assertTrue(SeftExtractor.extract(file, true).isEmpty());
	}

	@Test
	public void sdrFileWithEmbeddedThumbnailInSeftKeepsThumbnailInTrailer() throws IOException
	{
		// An SDR Samsung file's SEFT data block can begin with FF D8 (embedded JPEG thumbnail). With
		// hasGainMap=false the extractor must NOT walk past those bytes as if they were a gain-map EOI — the
		// trailer must include the thumbnail bytes verbatim. An extractor that trusts the FF D8 alone mis-walks
		// to the thumbnail's FF D9, dropping the thumbnail from state.seftTrailer; the next save's re-appended
		// trailer is then missing the thumbnail and breaks Samsung Gallery's Revert flow.
		byte[] embeddedThumb = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.minimalScanAndEoi());
		byte[] sizeFooter = { 0x00, 0x00, 0x00, 0x10 };
		byte[] seftMagic = { 'S', 'E', 'F', 'T' };
		byte[] expectedTrailer = JpegFixtures.concat(embeddedThumb, sizeFooter, seftMagic);

		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(), JpegFixtures.minimalScanAndEoi(), expectedTrailer);

		byte[] result = SeftExtractor.extract(file, false).orElseThrow();
		assertArrayEquals(expectedTrailer, result);
	}

	@Test
	public void trailerSizeMatchesBytesAfterLastEoi() throws IOException
	{
		// Verify the extracted length: file length minus the offset of the byte just past the last FFD9. With
		// minimalScanAndEoi as the only image and a 12-byte trailer, extracted length must be exactly 12.
		byte[] trailer = new byte[12];
		trailer[8] = 'S';
		trailer[9] = 'E';
		trailer[10] = 'F';
		trailer[11] = 'T';
		byte[] file = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.minimalScanAndEoi(), trailer);

		byte[] result = SeftExtractor.extract(file, true).orElseThrow();
		assertEquals(12, result.length);
	}
}
