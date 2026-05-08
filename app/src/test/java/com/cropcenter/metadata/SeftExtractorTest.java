package com.cropcenter.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

		byte[] result = SeftExtractor.extract(file, true);
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

		byte[] result = SeftExtractor.extract(file, true);
		assertArrayEquals(expectedTrailer, result);
	}

	@Test
	public void returnsNullOnFileTooShort()
	{
		// SEFT trailer is at minimum 12 bytes (FFD9 + 4-byte size + "SEFT"). Anything shorter can't carry one
		// and must return null without indexing OOB.
		assertNull(SeftExtractor.extract(new byte[]{ }, true));
		assertNull(SeftExtractor.extract(new byte[]{ 0x01 }, true));
		assertNull(SeftExtractor.extract(new byte[11], true));
	}

	@Test
	public void returnsNullOnMissingSeftMagic() throws IOException
	{
		// File ends with bytes other than "SEFT" — not a Samsung file.
		byte[] file = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.minimalScanAndEoi());
		assertNull(SeftExtractor.extract(file, true));
	}

	@Test
	public void returnsNullOnNullInput()
	{
		assertNull(SeftExtractor.extract(null, true));
	}

	@Test
	public void returnsNullWhenGainMapPresentButSliceUnparseable() throws IOException
	{
		// Round 8 forward-walk fix: when primary's EOI is followed by FFD8 (gain-map SOI) but the slice
		// has no clean EOI before the SEFT footer, JpegMarkerWalker.findPrimaryEoi(slice, ...) returns -1
		// and the extractor must return null. A regression that fell back to trailerStart=primaryEnd on
		// gmEoiInSlice<0 would mis-locate the trailer start IN THE MIDDLE OF the broken gain map and ship
		// gain-map bytes as the SEFT trailer on save — silent corruption when re-appended downstream.
		byte[] gainMapWithNoEoi = {
			(byte) 0xFF, (byte) 0xD8,                    // SOI
			(byte) 0xFF, (byte) 0xE0, 0x00, 0x06,        // APP0 with 6-byte length
			0x01, 0x02, 0x03, 0x04                       // 4 bytes of body — never reaches EOI
		};
		byte[] sizeFooter = { 0x00, 0x00, 0x00, 0x04 };
		byte[] seftMagic = { 'S', 'E', 'F', 'T' };
		byte[] file = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.minimalScanAndEoi(),
			gainMapWithNoEoi, sizeFooter, seftMagic);
		assertNull(SeftExtractor.extract(file, true));
	}

	@Test
	public void sdrFileWithEmbeddedThumbnailInSeftKeepsThumbnailInTrailer() throws IOException
	{
		// Codex round-18 F1: an SDR Samsung file's SEFT data block can begin with FF D8 (embedded JPEG
		// thumbnail). With hasGainMap=false the extractor must NOT walk past those bytes as if they were
		// a gain-map EOI — the trailer must include the thumbnail bytes verbatim. Pre-fix, the extractor
		// trusted the FF D8 alone and would mis-walk to the thumbnail's FF D9, dropping the thumbnail
		// from state.seftTrailer; the next save's re-appended trailer would be missing the thumbnail
		// and break Samsung Gallery's Revert flow.
		byte[] embeddedThumb = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.minimalScanAndEoi());
		byte[] sizeFooter = { 0x00, 0x00, 0x00, 0x10 };
		byte[] seftMagic = { 'S', 'E', 'F', 'T' };
		byte[] expectedTrailer = JpegFixtures.concat(embeddedThumb, sizeFooter, seftMagic);

		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(), JpegFixtures.minimalScanAndEoi(), expectedTrailer);

		byte[] result = SeftExtractor.extract(file, false);
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

		byte[] result = SeftExtractor.extract(file, true);
		assertEquals(12, result.length);
	}
}
