package com.cropcenter;

import static org.junit.Assert.assertEquals;

import com.cropcenter.metadata.JpegFixtures;
import com.cropcenter.metadata.JpegMarker;
import com.cropcenter.metadata.PngFixtures;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Tests for ImageLoadController.chooseOrientationByFormat — the format-dispatch chokepoint that decides whether
 * EXIF orientation is read via JPEG APP1 walk or PNG eXIf chunk walk. A regression here silently loads sources in
 * stored-pixel orientation while the export side normalises to 1, baking a permanent rotation into every saved
 * image of the mis-routed format.
 */
public final class ImageLoadControllerOrientationTest
{
	@Test
	public void chooseOrientationDoesNotRouteJpegBytesThroughPngExtractor() throws IOException
	{
		// Adversarial fixture: a real JPEG with EXIF orientation=6 PLUS a COM segment whose body contains
		// the literal ASCII bytes 'e','X','I','f' (the PNG eXIf chunk-type marker). The dispatch must
		// route on the JPEG SOI signature, NOT the body content — a regression that scanned the byte body
		// looking for "eXIf" would pick the COM segment as a fake PNG chunk and either crash on its
		// missing chunk length or return a wrong orientation. Pin: the result is 6 (from the JPEG EXIF
		// reader), not 1 (PNG reader's no-chunk default).
		byte[] jpeg = buildJpegWithExifAndComContainingExifBytes(6);
		assertEquals("JPEG signature wins over body content; routed to APP1 reader",
			6, ImageLoadController.chooseOrientationByFormat(jpeg));
	}

	@Test
	public void chooseOrientationForEmptyBytesReturnsOne()
	{
		// Zero-length input — must not AIOOBE on the signature checks. Both isJpegSignature and
		// isPngSignature reject zero-length, so the function falls through to the 1 default.
		assertEquals(1, ImageLoadController.chooseOrientationByFormat(new byte[0]));
	}

	@Test
	public void chooseOrientationForJpegReadsExifApp1() throws IOException
	{
		// JPEG with EXIF APP1 carrying orientation=6 (rotate 90° CW). The dispatch must route to
		// BitmapUtils.readExifOrientation, which walks the APP1 segment and returns 6.
		byte[] jpeg = buildJpegWithExifOrientation(6);
		assertEquals(6, ImageLoadController.chooseOrientationByFormat(jpeg));
	}

	@Test
	public void chooseOrientationForJpegReturnsOneWhenExifAbsent() throws IOException
	{
		// Plain JPEG (SOI + EOI) with no EXIF APP1 segment. The reader must return 1 (upright default).
		byte[] jpeg = JpegFixtures.concat(JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xD9 });  // SOI + EOI
		assertEquals(1, ImageLoadController.chooseOrientationByFormat(jpeg));
	}

	@Test
	public void chooseOrientationForPngReadsExifChunk() throws IOException
	{
		// PNG with eXIf chunk carrying orientation=8 (rotate 270° CW). The dispatch must route to
		// PngMetadataExtractor.extractOrientation, which walks the eXIf chunk and returns 8.
		byte[] png = buildPngWithExifOrientation(8);
		assertEquals(8, ImageLoadController.chooseOrientationByFormat(png));
	}

	@Test
	public void chooseOrientationForPngReturnsOneWhenExifAbsent() throws IOException
	{
		// PNG signature + IHDR + IEND, no eXIf chunk. The reader must return 1 (upright default).
		byte[] png = buildMinimalPng();
		assertEquals(1, ImageLoadController.chooseOrientationByFormat(png));
	}

	@Test
	public void chooseOrientationForUnrecognisedBytesReturnsOne()
	{
		// Random bytes that are neither JPEG nor PNG signatures. The function must NOT throw or return a
		// non-1 value — production callers gate on isJpeg/isPng before reaching this function, but the
		// defense-in-depth default keeps the helper composable.
		byte[] garbage = { 0x12, 0x34, 0x56, 0x78 };
		assertEquals(1, ImageLoadController.chooseOrientationByFormat(garbage));
	}

	/**
	 * Build an adversarial JPEG: same EXIF APP1 as buildJpegWithExifOrientation, PLUS a COM segment whose body
	 * literally contains the ASCII bytes 'e','X','I','f' (matching the PNG eXIf chunk-type marker). Used by
	 * chooseOrientationDoesNotRouteJpegBytesThroughPngExtractor to verify the dispatch honors the JPEG SOI
	 * signature rather than scanning the body for PNG chunk-type bytes.
	 *
	 * @param orientation EXIF orientation value 1..8
	 * @return JPEG bytes: SOI + COM("eXIf" payload) + APP1(EXIF + IFD0 with orientation) + EOI
	 * @throws IOException never; ByteArrayOutputStream doesn't throw
	 */
	private static byte[] buildJpegWithExifAndComContainingExifBytes(int orientation) throws IOException
	{
		byte[] base = buildJpegWithExifOrientation(orientation);
		// Insert a COM segment right after SOI (offset 2). Layout: FF FE + segLen + 'e','X','I','f'.
		// segLen counts itself + the 4-byte body = 6 bytes (u16 BE).
		byte[] comSegment = {
			(byte) JpegMarker.PREFIX, (byte) 0xFE,
			0x00, 0x06,
			'e', 'X', 'I', 'f',
		};
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(base, 0, 2);  // SOI
		out.write(comSegment);
		out.write(base, 2, base.length - 2);  // APP1 + EOI
		return out.toByteArray();
	}

	/**
	 * Build a minimal JPEG containing exactly one EXIF APP1 segment with the given orientation. Uses a tiny TIFF
	 * IFD0 with a single Orientation entry (tag 0x0112, type SHORT, count 1).
	 *
	 * @param orientation EXIF orientation value 1..8
	 * @return JPEG bytes: SOI + APP1(EXIF + IFD0 with orientation) + EOI
	 * @throws IOException never; ByteArrayOutputStream doesn't throw
	 */
	private static byte[] buildJpegWithExifOrientation(int orientation) throws IOException
	{
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		// "Exif\0\0" identifier
		payload.write(new byte[] { 'E', 'x', 'i', 'f', 0, 0 });
		// TIFF header: II (little-endian) + magic 42 + IFD0 offset 8
		payload.write(new byte[] { 'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00 });
		// IFD0: count=1, entry { tag=0x0112, type=3 (SHORT), count=1, value=orientation (u16 in low bytes) }
		payload.write(new byte[] { 0x01, 0x00 });  // entry count = 1
		// Tag 0x0112 (orientation), type 3 (SHORT), count 1, value = orientation as u16 + padding to u32
		payload.write(new byte[] {
			0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
			(byte) orientation, 0x00, 0x00, 0x00,
		});
		// next-IFD pointer = 0
		payload.write(new byte[] { 0x00, 0x00, 0x00, 0x00 });
		byte[] payloadBytes = payload.toByteArray();
		// APP1 segment: FF E1 + length (u16 BE) + payload
		ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
		jpeg.write((byte) JpegMarker.PREFIX);
		jpeg.write((byte) JpegMarker.SOI);
		jpeg.write((byte) JpegMarker.PREFIX);
		jpeg.write((byte) JpegMarker.APP1);
		int segLen = payloadBytes.length + 2;
		jpeg.write((segLen >> 8) & 0xFF);
		jpeg.write(segLen & 0xFF);
		jpeg.write(payloadBytes);
		jpeg.write((byte) JpegMarker.PREFIX);
		jpeg.write((byte) 0xD9);  // EOI
		return jpeg.toByteArray();
	}

	/**
	 * Build a minimal valid PNG: signature + IHDR (1x1, 8-bit RGB) + IEND. No eXIf chunk so the orientation
	 * reader falls through to its 1-default.
	 *
	 * @return minimal-valid PNG bytes
	 * @throws IOException never; ByteArrayOutputStream doesn't throw
	 */
	private static byte[] buildMinimalPng() throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(PngFixtures.PNG_SIGNATURE);
		out.write(PngFixtures.buildIhdrChunk());
		out.write(PngFixtures.buildChunk("IEND", new byte[0]));
		return out.toByteArray();
	}

	/**
	 * Build a PNG with a single eXIf chunk carrying a tiny TIFF that encodes just the given orientation. The
	 * eXIf chunk sits between IHDR and IEND (any position after IHDR is spec-legal). Mirror the TIFF layout
	 * used by buildJpegWithExifOrientation so the underlying PNG path's TIFF walker reads the same byte
	 * sequence.
	 *
	 * @param orientation EXIF orientation 1..8
	 * @return PNG bytes carrying the orientation in its eXIf chunk
	 * @throws IOException never; ByteArrayOutputStream doesn't throw
	 */
	private static byte[] buildPngWithExifOrientation(int orientation) throws IOException
	{
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		// TIFF header: II + magic 42 + IFD0 offset 8
		tiff.write(new byte[] { 'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00 });
		// IFD0 entry count = 1
		tiff.write(new byte[] { 0x01, 0x00 });
		// Tag 0x0112 (Orientation), type 3 (SHORT), count 1, value = orientation u16 + padding
		tiff.write(new byte[] {
			0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
			(byte) orientation, 0x00, 0x00, 0x00,
		});
		// next-IFD pointer = 0
		tiff.write(new byte[] { 0x00, 0x00, 0x00, 0x00 });
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(PngFixtures.PNG_SIGNATURE);
		out.write(PngFixtures.buildIhdrChunk());
		out.write(PngFixtures.buildChunk("eXIf", tiff.toByteArray()));
		out.write(PngFixtures.buildChunk("IEND", new byte[0]));
		return out.toByteArray();
	}
}
