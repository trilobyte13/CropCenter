package com.cropcenter;

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
	private static final byte[] JPEG_SOI = { (byte) 0xFF, (byte) 0xD8 };
	private static final byte[] JPEG_EOI = { (byte) 0xFF, (byte) 0xD9 };

	@Test
	public void extractEmitsHdrAndSamsungInDisplayWhenGainMapAndSeftPresent() throws IOException
	{
		// Round-19 F-B: full Samsung Ultra-HDR shape (MPF segment + XMP carrying the hdrgm namespace
		// marker + primary scan + gain-map JPEG + SEFT footer). The display string must list both HDR
		// and Samsung — UI honesty contract per feedback_ui_honesty.md. A regression that swapped the
		// two appendIf calls or that flipped one of the gates would surface as a missing flag here.
		byte[] mpfPayload = concat(
			"MPF\0".getBytes(StandardCharsets.US_ASCII),
			new byte[] { 'I', 'I', '*', 0x00 });
		byte[] mpfSeg = appSegment(0xE2, mpfPayload);
		// XMP segment carrying the hdrgm namespace — HdrSignature.hasHdrgmInXmp scans XMP segment
		// bodies only, so the marker must live INSIDE an XMP APP1 segment to fire the gate. 28 bytes
		// of canonical XMP_HEADER + a body that mentions hdrgm.
		byte[] xmpPayload = concat(
			"http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII),
			"<x:xmpmeta xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"/>"
				.getBytes(StandardCharsets.US_ASCII));
		byte[] xmpSeg = appSegment(0xE1, xmpPayload);
		byte[] primary = concat(JPEG_SOI, xmpSeg, mpfSeg, scanBody(), JPEG_EOI);
		byte[] gainMap = concat(JPEG_SOI, scanBody(), JPEG_EOI);
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
		byte[] jpeg = concat(JPEG_SOI, JPEG_EOI);
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

		byte[] jpeg = concat(JPEG_SOI, app1.toByteArray(), JPEG_EOI);
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
		// Round-19 F-A: the HDR gate at extractMetadata is `hasMpf && HdrSignature.hasHdrgmInXmp(meta)`.
		// hasMpf is the deliberate cheap pre-filter — without it, an SDR file with a coincidental
		// "hdrgm" 5-byte sequence (XMP namespace fragment in a comment, vendor MakerNote blob, etc.)
		// would be mis-tagged as HDR. Pin: a JPEG with "hdrgm" in a COM segment but NO MPF must NOT
		// extract a gain map and must NOT advertise HDR in its display string.
		byte[] commentWithHdrgm = "user comment hdrgm here".getBytes(StandardCharsets.US_ASCII);
		byte[] comSeg = appSegment(0xFE, commentWithHdrgm);
		byte[] jpeg = concat(JPEG_SOI, comSeg, scanBody(), JPEG_EOI);

		var extracted = ImageLoadController.extractMetadata(jpeg);
		assertEquals(Format.JPEG, extracted.sourceFormat());
		assertNull("hasMpf=false must short-circuit the hdrgm scan — no gain map", extracted.gainMap());
		assertFalse("display must not advertise HDR without an MPF segment: " + extracted.displayString(),
			extracted.displayString().contains("HDR"));
	}

	@Test
	public void extractTreatsJpegWithMpfButNoHdrgmAsNonHdr() throws IOException
	{
		// Even when MPF is present, the absence of the hdrgm namespace marker means the file is non-HDR
		// (MPF can describe focus-stacked / panorama / ZSL bursts, not just Ultra HDR). Pin: a JPEG
		// with MPF + a post-primary FF D8 thumbnail but no "hdrgm" anywhere must NOT extract that
		// thumbnail as a gain map — the symmetric false-positive on the MPF-but-not-HDR path.
		byte[] mpfPayload = concat(
			"MPF\0".getBytes(StandardCharsets.US_ASCII),
			new byte[] { 'I', 'I', '*', 0x00 });
		byte[] mpfSeg = appSegment(0xE2, mpfPayload);
		byte[] thumbnail = concat(JPEG_SOI, scanBody(), JPEG_EOI);
		byte[] jpeg = concat(JPEG_SOI, mpfSeg, scanBody(), JPEG_EOI, thumbnail);

		var extracted = ImageLoadController.extractMetadata(jpeg);
		assertEquals(Format.JPEG, extracted.sourceFormat());
		assertNull("MPF without hdrgm must not extract a gain map", extracted.gainMap());
		assertFalse("display must not advertise HDR without the hdrgm marker: " + extracted.displayString(),
			extracted.displayString().contains("HDR"));
	}

	@Test
	public void extractTreatsJpegWithMpfPlusHdrgmOutsideXmpAsNonHdr() throws IOException
	{
		// Even when MPF AND the literal "hdrgm" 5-byte sequence are both present,
		// the file is NOT HDR unless the marker lives inside an XMP APP1 segment. A pre-fix full-file
		// scan would false-positive on "hdrgm" in MakerNote / COM / vendor blob / SEFT history /
		// entropy. Pin the precise XMP-only contract: MPF + COM-with-hdrgm + post-primary FF D8
		// thumbnail must NOT extract a gain map.
		byte[] mpfPayload = concat(
			"MPF\0".getBytes(StandardCharsets.US_ASCII),
			new byte[] { 'I', 'I', '*', 0x00 });
		byte[] mpfSeg = appSegment(0xE2, mpfPayload);
		// "hdrgm" sequence in a COM segment — the bytes are present but not in XMP, so the precise
		// gate must reject.
		byte[] comWithHdrgm = "comment with hdrgm here".getBytes(StandardCharsets.US_ASCII);
		byte[] comSeg = appSegment(0xFE, comWithHdrgm);
		byte[] thumbnail = concat(JPEG_SOI, scanBody(), JPEG_EOI);
		byte[] jpeg = concat(JPEG_SOI, mpfSeg, comSeg, scanBody(), JPEG_EOI, thumbnail);

		var extracted = ImageLoadController.extractMetadata(jpeg);
		assertEquals(Format.JPEG, extracted.sourceFormat());
		assertNull("hdrgm OUTSIDE an XMP segment must not trigger gain-map extraction",
			extracted.gainMap());
		assertFalse("display must not advertise HDR when hdrgm is only in COM: "
			+ extracted.displayString(), extracted.displayString().contains("HDR"));
	}

	private static byte[] appSegment(int marker, byte[] payload) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0xFF);
		out.write(marker);
		// segLen INCLUDES the 2 length bytes themselves per JPEG spec.
		int segLen = 2 + payload.length;
		out.write((segLen >> 8) & 0xFF);
		out.write(segLen & 0xFF);
		out.write(payload);
		return out.toByteArray();
	}

	private static byte[] concat(byte[]... parts) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts)
		{
			out.write(p);
		}
		return out.toByteArray();
	}

	private static byte[] scanBody() throws IOException
	{
		// Minimal SOS segment + entropy-coded scan body. The walker stops at the next FF D9 (EOI) the
		// caller appends — keep that EOI separate so test compositions can append a gain map / trailer
		// directly after.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0xFF);
		out.write(0xDA);                // SOS
		out.write(0x00);
		out.write(0x06);                // length = 6
		out.write(new byte[] { 0x01, 0x00, 0x00, 0x00 });   // scan header body
		out.write(new byte[] { 0x77, 0x77, 0x77 });          // entropy-coded payload
		return out.toByteArray();
	}
}
