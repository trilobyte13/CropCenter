package com.cropcenter.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Tests for the JpegSegment record's content-type predicates. Each predicate runs on every
 * loaded JPEG to drive the "format string" the info bar shows and to gate downstream
 * processing (HDR detection, ICC color-space awareness). A regression that mis-classifies
 * a segment would either (a) drop a real EXIF/XMP/ICC/MPF segment from the categorization
 * — surfacing as a missing format tag in the UI — or (b) over-claim a non-conforming
 * APP segment as a known type, which downstream parsers then crash on.
 */
public class JpegSegmentTest
{
	@Test
	public void componentAccessorsExposeMarkerAndData()
	{
		// Records auto-generate accessors. Pin them down so a renamed component
		// would surface as a compile error here.
		byte[] data = { (byte) 0xFF, (byte) 0xE1, 0x00, 0x06, 0x01, 0x02, 0x03, 0x04 };
		JpegSegment seg = new JpegSegment(0xE1, data);
		assertEquals(0xE1, seg.marker());
		assertArrayEquals(data, seg.data());
	}

	@Test
	public void isExifFalseOnTooShortPayload()
	{
		// EXIF requires at least 10 bytes (FF E1 + 2-byte len + "Exif\0\0"). Anything
		// shorter is malformed; predicate returns false rather than throwing on the
		// out-of-bounds data[4..9] reads.
		byte[] tooShort = { (byte) 0xFF, (byte) 0xE1, 0x00, 0x05, 'E', 'x', 'i' };
		assertFalse(new JpegSegment(0xE1, tooShort).isExif());
	}

	@Test
	public void isExifFalseOnWrongMarker()
	{
		// Marker mismatch is a fast no — even if data starts with "Exif\0\0".
		byte[] data = exifLike();
		assertFalse(new JpegSegment(0xE0, data).isExif());
	}

	@Test
	public void isExifFalseOnWrongSignature()
	{
		// Marker matches APP1 but payload signature isn't "Exif\0\0" (this could be XMP
		// or some vendor APP1). Predicate must not match.
		byte[] xmp = xmpLike();
		assertFalse(new JpegSegment(0xE1, xmp).isExif());
	}

	@Test
	public void isExifTrueOnValidExif()
	{
		// "FF E1 [len] Exif\0\0 [tiff data...]" — the canonical EXIF APP1 signature.
		byte[] data = exifLike();
		assertTrue(new JpegSegment(0xE1, data).isExif());
	}

	@Test
	public void isIccFalseOnNonApp2()
	{
		// "ICC_PROFILE\0" payload but APP1 marker — predicate should reject.
		byte[] icc = iccLike();
		assertFalse(new JpegSegment(0xE1, icc).isIcc());
	}

	@Test
	public void isIccFalseOnTooShortPayload()
	{
		// Need at least 18 bytes (4 prefix + 12-byte "ICC_PROFILE\0" + 2 chunk bytes).
		byte[] tooShort = { (byte) 0xFF, (byte) 0xE2, 0x00, 0x10,
			'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L' };
		assertFalse(new JpegSegment(0xE2, tooShort).isIcc());
	}

	@Test
	public void isIccTrueOnValidIcc()
	{
		byte[] data = iccLike();
		assertTrue(new JpegSegment(0xE2, data).isIcc());
	}

	@Test
	public void isMpfFalseOnNonApp2()
	{
		byte[] data = mpfLike();
		assertFalse(new JpegSegment(0xE1, data).isMpf());
	}

	@Test
	public void isMpfTrueOnValidMpf()
	{
		// MPF APP2 starts with "MPF\0".
		byte[] data = mpfLike();
		assertTrue(new JpegSegment(0xE2, data).isMpf());
	}

	@Test
	public void isXmpFalseOnNonApp1()
	{
		byte[] data = xmpLike();
		assertFalse(new JpegSegment(0xE2, data).isXmp());
	}

	@Test
	public void isXmpFalseOnTooShortPayload()
	{
		// XMP signature is "http://ns.adobe.com/xap/1.0/\0" — 29 bytes plus 4-byte
		// header = 33 minimum. Anything shorter must reject without indexing OOB.
		byte[] tooShort = { (byte) 0xFF, (byte) 0xE1, 0x00, 0x10,
			'h', 't', 't', 'p', ':', '/', '/' };
		assertFalse(new JpegSegment(0xE1, tooShort).isXmp());
	}

	@Test
	public void isXmpTrueOnValidXmp()
	{
		byte[] data = xmpLike();
		assertTrue(new JpegSegment(0xE1, data).isXmp());
	}

	@Test
	public void predicatesAreMutuallyExclusiveOnRealSegments()
	{
		// A JPEG segment is exactly one type — sanity-check that exact-type matchers
		// don't false-positive on a sibling type's payload.
		JpegSegment exif = new JpegSegment(0xE1, exifLike());
		assertTrue(exif.isExif());
		assertFalse(exif.isXmp());
		assertFalse(exif.isIcc());
		assertFalse(exif.isMpf());

		JpegSegment xmp = new JpegSegment(0xE1, xmpLike());
		assertFalse(xmp.isExif());
		assertTrue(xmp.isXmp());
		assertFalse(xmp.isIcc());
		assertFalse(xmp.isMpf());

		JpegSegment icc = new JpegSegment(0xE2, iccLike());
		assertFalse(icc.isExif());
		assertFalse(icc.isXmp());
		assertTrue(icc.isIcc());
		assertFalse(icc.isMpf());

		JpegSegment mpf = new JpegSegment(0xE2, mpfLike());
		assertFalse(mpf.isExif());
		assertFalse(mpf.isXmp());
		assertFalse(mpf.isIcc());
		assertTrue(mpf.isMpf());
	}

	private static byte[] exifLike()
	{
		// FF E1 [len:2] Exif\0\0 [TIFF "II*\0" + minimal IFD]
		byte[] header = { (byte) 0xFF, (byte) 0xE1, 0x00, 0x10 };
		byte[] sig = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
		byte[] tiff = { 'I', 'I', '*', 0x00, 0x08, 0x00, 0x00, 0x00 };
		return concat(header, sig, tiff);
	}

	private static byte[] iccLike()
	{
		// FF E2 [len:2] ICC_PROFILE\0 [chunk# 1/N] [body]
		byte[] header = { (byte) 0xFF, (byte) 0xE2, 0x00, 0x10 };
		byte[] sig = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);
		byte[] body = { 0x01, 0x01, (byte) 0xAA };
		return concat(header, sig, body);
	}

	private static byte[] mpfLike()
	{
		// FF E2 [len:2] MPF\0 [body]
		byte[] header = { (byte) 0xFF, (byte) 0xE2, 0x00, 0x0C };
		byte[] sig = "MPF\0".getBytes(StandardCharsets.US_ASCII);
		byte[] body = { 'I', 'I', '*', 0x00 };
		return concat(header, sig, body);
	}

	private static byte[] xmpLike()
	{
		// FF E1 [len:2] http://ns.adobe.com/xap/1.0/\0 [body]
		byte[] header = { (byte) 0xFF, (byte) 0xE1, 0x00, 0x21 };
		byte[] sig = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
		byte[] body = { '<', '?', 'x' };
		return concat(header, sig, body);
	}

	private static byte[] concat(byte[]... arrays)
	{
		int total = 0;
		for (byte[] array : arrays)
		{
			total += array.length;
		}
		byte[] out = new byte[total];
		int off = 0;
		for (byte[] array : arrays)
		{
			System.arraycopy(array, 0, out, off, array.length);
			off += array.length;
		}
		return out;
	}
}
