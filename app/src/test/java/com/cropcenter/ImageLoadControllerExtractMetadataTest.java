package com.cropcenter;

import static com.cropcenter.metadata.JpegFixtures.appSegment;
import static com.cropcenter.metadata.JpegFixtures.concat;
import static com.cropcenter.metadata.JpegFixtures.eoi;
import static com.cropcenter.metadata.JpegFixtures.scanBody;
import static com.cropcenter.metadata.JpegFixtures.soi;
import static com.cropcenter.metadata.PngFixtures.PNG_SIGNATURE;
import static com.cropcenter.metadata.PngFixtures.buildChunk;
import static com.cropcenter.metadata.PngFixtures.buildIhdrChunk;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.model.Format;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tests for ImageLoadController.extractMetadata — the format-routing seam between the SAF byte-load and the per-format
 * metadata extractors. The display string this builds drives the info-bar label the user sees ("EXIF+ICC+XMP+HDR" etc.)
 * and the sourceFormat field gates downstream JPEG vs PNG export decisions, so the routing has to land the right format
 * for every JPEG/PNG path. Pin the JPEG-vs-PNG fork, plus the EXIF-detection edge: a PNG eXIf chunk must surface as
 * "PNG+EXIF" and produce a non-null pngExifTiff payload; a PNG without eXIf surfaces as "PNG" with null pngExifTiff.
 *
 * PNG chunk-builder helpers live in PngFixtures so the same fixture surface is shared with PngMetadataExtractorTest.
 */
public final class ImageLoadControllerExtractMetadataTest
{
	@Test
	public void extractEmitsHdrAndSamsungInDisplayWhenGainMapAndSeftPresent() throws IOException
	{
		// Full Samsung Ultra-HDR shape (MPF segment + XMP carrying hdrgm + primary scan + gain-map JPEG + SEFT
		// footer). The display string must list both HDR and Samsung — UI-honesty contract. A regression that
		// swaps the two appendIf calls or flips a gate surfaces as a missing flag.
		byte[] mpfPayload = concat("MPF\0".getBytes(StandardCharsets.US_ASCII),
			new byte[] { 'I', 'I', '*', 0x00 });
		byte[] mpfSeg = appSegment(0xE2, mpfPayload);
		// XMP segment carrying the hdrgm namespace — HdrSignature.hasHdrgmInXmp scans XMP segment bodies only,
		// so the marker must live INSIDE an XMP APP1 segment to fire the gate. 28 bytes of canonical XMP_HEADER
		// + a body that mentions hdrgm.
		byte[] xmpPayload = concat("http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII),
			"<x:xmpmeta xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"/>"
				.getBytes(StandardCharsets.US_ASCII));
		byte[] xmpSeg = appSegment(0xE1, xmpPayload);
		byte[] primary = concat(soi(), xmpSeg, mpfSeg, scanBody(), eoi());
		byte[] gainMap = concat(soi(), scanBody(), eoi());
		byte[] seftFooter = { 0x00, 0x00, 0x00, 0x04, 'S', 'E', 'F', 'T' };
		byte[] seftBody = { 0x42, 0x42, 0x42, 0x42 };
		byte[] jpeg = concat(primary, gainMap, seftBody, seftFooter);

		var extracted = ImageLoadController.extractMetadata(jpeg);
		assertEquals(Format.JPEG, extracted.sourceFormat());
		assertNotNull("HDR file with hdrgm + MPF + FF D8 after primary must extract a gain map",
			extracted.gainMap());
		assertNotNull("SEFT trailer present after gain map", extracted.seftTrailer());
		String display = extracted.displayString();
		assertTrue("display must contain HDR for an Ultra HDR file: " + display, display.contains("HDR"));
		assertTrue("display must contain Samsung for a SEFT-trailing file: " + display,
			display.contains("Samsung"));
	}

	@Test
	public void extractRoutesBareJpegAsJpegWithEmptyDisplay() throws IOException
	{
		// Bare SOI+EOI — no APP/COM segments, no HDR / SEFT trailer. Format must be JPEG and the display string
		// empty (no "EXIF" / "ICC" / "XMP" / "HDR" / "Samsung" components).
		byte[] jpeg = concat(soi(), eoi());
		var extracted = ImageLoadController.extractMetadata(jpeg);
		assertEquals(Format.JPEG, extracted.sourceFormat());
		assertEquals("", extracted.displayString());
		// pngExifTiff is the PNG-only carrier for the round-trip; must be null on the JPEG branch.
		assertNull("JPEG path must not populate pngExifTiff", extracted.pngExifTiff());
		// jpegMeta is non-null but may be empty — the segment list, not the EXIF-payload byte array.
		assertNotNull(extracted.jpegMeta());
	}

	@Test
	public void extractRoutesJpegWithExifSegmentBuildsExifDisplay() throws IOException
	{
		// JPEG with a real APP1 EXIF segment between SOI and EOI — display string starts with "EXIF". Build the
		// segment manually: FF E1 + segLen + "Exif\0\0" + 8-byte TIFF header.
		byte[] tiff = {
			'I', 'I', 0x2A, 0x00,           // little-endian TIFF magic
			0x08, 0x00, 0x00, 0x00          // IFD0 offset = 8
		};
		ByteArrayOutputStream app1 = new ByteArrayOutputStream();
		app1.write(0xFF);
		app1.write(0xE1);
		int segLen = 2 + 6 + tiff.length; // length bytes + "Exif\0\0" + tiff
		app1.write((segLen >> 8) & 0xFF);
		app1.write(segLen & 0xFF);
		app1.write('E'); app1.write('x'); app1.write('i'); app1.write('f');
		app1.write(0); app1.write(0);
		app1.write(tiff);

		byte[] jpeg = concat(soi(), app1.toByteArray(), eoi());
		var extracted = ImageLoadController.extractMetadata(jpeg);
		assertEquals(Format.JPEG, extracted.sourceFormat());
		// "EXIF" prefix is present (further markers like ICC / XMP would extend it).
		assertTrue("display starts with EXIF for an APP1-EXIF JPEG: " + extracted.displayString(),
			extracted.displayString().startsWith("EXIF"));
	}

	@Test
	public void extractRoutesPngWithExifAsPngPlusExif() throws IOException
	{
		// PNG with a valid eXIf chunk — display string promotes to "PNG+EXIF" and pngExifTiff carries the raw
		// TIFF bytes uncapped. Pin the second contract: the synthetic APP1 segment list also fires (1 segment)
		// so the JPEG-side patcher path can find EXIF when the user saves PNG → JPEG.
		byte[] tiff = {
			'I', 'I', 0x2A, 0x00,           // little-endian TIFF magic
			0x08, 0x00, 0x00, 0x00          // IFD0 offset = 8
		};
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk(), buildChunk("eXIf", tiff));
		var extracted = ImageLoadController.extractMetadata(png);
		assertEquals(Format.PNG, extracted.sourceFormat());
		assertEquals("PNG+EXIF", extracted.displayString());
		assertNotNull("PNG with eXIf must populate pngExifTiff", extracted.pngExifTiff());
		assertEquals("pngExifTiff carries the raw TIFF bytes uncapped",
			tiff.length, extracted.pngExifTiff().length);
		// Synthetic APP1 segment present in jpegMeta so the JPEG-side patcher path can use it on PNG → JPEG
		// export.
		assertEquals(1, extracted.jpegMeta().size());
		JpegSegment seg = extracted.jpegMeta().get(0);
		assertTrue("synthetic APP1 must satisfy isExif()", seg.isExif());
	}

	@Test
	public void extractRoutesPngWithoutExifAsPngOnly() throws IOException
	{
		// PNG with IHDR but no eXIf chunk — display string is "PNG" alone, pngExifTiff is null.
		byte[] png = concat(PNG_SIGNATURE, buildIhdrChunk());
		var extracted = ImageLoadController.extractMetadata(png);
		assertEquals(Format.PNG, extracted.sourceFormat());
		assertEquals("PNG", extracted.displayString());
		assertNull("PNG without eXIf must have null pngExifTiff", extracted.pngExifTiff());
		// HDR / SEFT only apply to JPEG.
		assertNull(extracted.gainMap());
		assertNull(extracted.seftTrailer());
	}

	@Test
	public void extractTreatsJpegWithHdrgmStringButNoMpfAsNonHdr() throws IOException
	{
		// The HDR gate at extractMetadata is `hasMpf && HdrSignature.hasHdrgmInXmp(meta)`. hasMpf is a cheap
		// pre-filter — without it, an SDR file with a coincidental "hdrgm" sequence (vendor MakerNote, COM, XMP
		// fragment in a comment) would be mis-tagged as HDR. A JPEG with "hdrgm" in a COM segment but NO MPF
		// must NOT extract a gain map.
		byte[] commentWithHdrgm = "user comment hdrgm here".getBytes(StandardCharsets.US_ASCII);
		byte[] comSeg = appSegment(0xFE, commentWithHdrgm);
		byte[] jpeg = concat(soi(), comSeg, scanBody(), eoi());

		var extracted = ImageLoadController.extractMetadata(jpeg);
		assertEquals(Format.JPEG, extracted.sourceFormat());
		assertNull("hasMpf=false must short-circuit the hdrgm scan — no gain map", extracted.gainMap());
		assertFalse("display must not advertise HDR without an MPF segment: " + extracted.displayString(),
			extracted.displayString().contains("HDR"));
	}

	@Test
	public void extractTreatsJpegWithMpfButNoHdrgmAsNonHdr() throws IOException
	{
		// Even when MPF is present, the absence of the hdrgm namespace marker means the file is non-HDR (MPF
		// can describe focus-stacked / panorama / ZSL bursts, not just Ultra HDR). Pin: a JPEG with MPF + a
		// post-primary FF D8 thumbnail but no "hdrgm" anywhere must NOT extract that thumbnail as a gain map —
		// the symmetric false-positive on the MPF-but-not-HDR path.
		byte[] mpfPayload = concat("MPF\0".getBytes(StandardCharsets.US_ASCII),
			new byte[] { 'I', 'I', '*', 0x00 });
		byte[] mpfSeg = appSegment(0xE2, mpfPayload);
		byte[] thumbnail = concat(soi(), scanBody(), eoi());
		byte[] jpeg = concat(soi(), mpfSeg, scanBody(), eoi(), thumbnail);

		var extracted = ImageLoadController.extractMetadata(jpeg);
		assertEquals(Format.JPEG, extracted.sourceFormat());
		assertNull("MPF without hdrgm must not extract a gain map", extracted.gainMap());
		assertFalse("display must not advertise HDR without the hdrgm marker: " + extracted.displayString(),
			extracted.displayString().contains("HDR"));
	}

	@Test
	public void extractTreatsJpegWithMpfPlusHdrgmOutsideXmpAsNonHdr() throws IOException
	{
		// Even when MPF AND the literal "hdrgm" sequence are both present, the file is NOT HDR unless the
		// marker lives inside an XMP APP1 segment. A coarser full-file scan false-positives on "hdrgm" in
		// MakerNote / COM / vendor blob / SEFT history / entropy. MPF + COM-with-hdrgm + post-primary FF D8
		// thumbnail must NOT extract a gain map.
		byte[] mpfPayload = concat("MPF\0".getBytes(StandardCharsets.US_ASCII),
			new byte[] { 'I', 'I', '*', 0x00 });
		byte[] mpfSeg = appSegment(0xE2, mpfPayload);
		// "hdrgm" sequence in a COM segment — the bytes are present but not in XMP, so the precise gate must
		// reject.
		byte[] comWithHdrgm = "comment with hdrgm here".getBytes(StandardCharsets.US_ASCII);
		byte[] comSeg = appSegment(0xFE, comWithHdrgm);
		byte[] thumbnail = concat(soi(), scanBody(), eoi());
		byte[] jpeg = concat(soi(), mpfSeg, comSeg, scanBody(), eoi(), thumbnail);

		var extracted = ImageLoadController.extractMetadata(jpeg);
		assertEquals(Format.JPEG, extracted.sourceFormat());
		assertNull("hdrgm OUTSIDE an XMP segment must not trigger gain-map extraction", extracted.gainMap());
		assertFalse("display must not advertise HDR when hdrgm is only in COM: "
			+ extracted.displayString(), extracted.displayString().contains("HDR"));
	}

}
