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
public final class UltraHdrCompatTest
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
	public void containsHdrgmHandlesMinLengthAllZeros()
	{
		// 5-byte all-zero buffer — exactly at the loop-entry boundary (limit = length - 4 = 1, so the
		// outer loop runs once with i=0 and i=1). Pins that the loop entry doesn't AIOOBE at the
		// minimum-viable length AND that an all-zero needle position doesn't accidentally match.
		assertFalse(UltraHdrCompat.containsHdrgm(new byte[5]));
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
	public void containsHdrgmRejectsShortenedAndCorruptedPatternsButAcceptsLongerPrefixes()
	{
		// Pin both branches of the matcher's near-miss handling. ACCEPT side: "hdrgmm" extends past the
		// 5-byte signature with a trailing 'm' — the matcher correctly treats the first 5 bytes as a
		// successful match because it's a contains-search, not an equals-search. REJECT side: a
		// mid-pattern byte swap ("hdrgxm") plus two truncations ("hdrg", "drgm") must each return false
		// regardless of buffer length. The earlier test name implied reject-only, but the first assertion
		// is an accept — keeping both in one test is intentional because the predicate's contract is a
		// single contains-search whose accept/reject behavior on near-misses needs to be pinned together.
		assertTrue("matcher is a contains-search; trailing bytes don't invalidate the 5-byte hit",
			UltraHdrCompat.containsHdrgm("hdrgmm".getBytes(StandardCharsets.US_ASCII)));
		assertFalse("mid-pattern byte swap must not match",
			UltraHdrCompat.containsHdrgm("hdrgxm".getBytes(StandardCharsets.US_ASCII)));
		assertFalse("truncated prefix must not match",
			UltraHdrCompat.containsHdrgm("hdrg".getBytes(StandardCharsets.US_ASCII)));
		assertFalse("truncated suffix must not match",
			UltraHdrCompat.containsHdrgm("drgm".getBytes(StandardCharsets.US_ASCII)));
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
