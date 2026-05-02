package com.cropcenter.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Tests for the byte-array scanners on UltraHdrCompat. containsHdrgm is the only one that runs without a Bitmap and is
 * on the post-encode HDR-success verification path — a regression that always returns true would mask a silent
 * HDR-failed save with a false "HDR OK" toast; one that always returns false would mark every HDR save as failed.
 */
public class UltraHdrCompatTest
{
	@Test
	public void containsHdrgmFindsAtBufferEnd()
	{
		byte[] data = ("garbage prefix bytes... hdrgm").getBytes(StandardCharsets.US_ASCII);
		assertTrue(UltraHdrCompat.containsHdrgm(data));
	}

	@Test
	public void containsHdrgmFindsAtBufferStart()
	{
		byte[] data = "hdrgm and more".getBytes(StandardCharsets.US_ASCII);
		assertTrue(UltraHdrCompat.containsHdrgm(data));
	}

	@Test
	public void containsHdrgmFindsExactly()
	{
		byte[] data = "hdrgm".getBytes(StandardCharsets.US_ASCII);
		assertTrue(UltraHdrCompat.containsHdrgm(data));
	}

	@Test
	public void containsHdrgmHandlesEmptyAndShort()
	{
		// Length checks: limit = length - 4, so any buffer < 5 bytes can't possibly hold the 5-byte pattern.
		// The loop bound has to handle these cleanly without throwing.
		assertFalse(UltraHdrCompat.containsHdrgm(new byte[0]));
		assertFalse(UltraHdrCompat.containsHdrgm(new byte[]{ 'h' }));
		assertFalse(UltraHdrCompat.containsHdrgm(new byte[]{ 'h', 'd', 'r', 'g' }));
	}

	@Test
	public void containsHdrgmHandlesNull()
	{
		assertFalse(UltraHdrCompat.containsHdrgm(null));
	}

	@Test
	public void containsHdrgmIsCaseSensitive()
	{
		// XMP namespace literals are exact-case in the spec — "HDRGM" or "Hdrgm" should NOT match. A regression
		// that case-folded would tag any file with "HDRGM" in EXIF text fields as Ultra HDR.
		byte[] upper = "HDRGM".getBytes(StandardCharsets.US_ASCII);
		assertFalse(UltraHdrCompat.containsHdrgm(upper));

		byte[] mixed = "Hdrgm".getBytes(StandardCharsets.US_ASCII);
		assertFalse(UltraHdrCompat.containsHdrgm(mixed));
	}

	@Test
	public void containsHdrgmRejectsPartialMatches()
	{
		// "hdrgmm" contains "hdrgm" as a prefix and SHOULD match.
		assertTrue(UltraHdrCompat.containsHdrgm("hdrgmm".getBytes(StandardCharsets.US_ASCII)));
		// These are too short or have a wrong byte mid-pattern.
		assertFalse(UltraHdrCompat.containsHdrgm("hdrgxm".getBytes(StandardCharsets.US_ASCII)));
		assertFalse(UltraHdrCompat.containsHdrgm("hdrg".getBytes(StandardCharsets.US_ASCII)));
		assertFalse(UltraHdrCompat.containsHdrgm("drgm".getBytes(StandardCharsets.US_ASCII)));
	}

	@Test
	public void containsHdrgmScansEntireBufferNotJustPrefix()
	{
		// Earlier versions scanned only the first N bytes; a maxed-out EXIF thumbnail can push the XMP segment
		// past any fixed prefix window. Verify a hit deep in a large buffer is still found.
		byte[] data = new byte[200_000];
		byte[] needle = "hdrgm".getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(needle, 0, data, 150_000, needle.length);
		assertTrue(UltraHdrCompat.containsHdrgm(data));
	}
}
