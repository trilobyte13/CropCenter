package com.cropcenter.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests for the Bitmap-free surfaces of UltraHdrCompat. containsHdrgm runs on the post-encode HDR-success verification
 * path — a regression that always returns true would mask a silent HDR-failed save with a false "HDR OK" toast; one
 * that always returns false would mark every HDR save as failed. sweepStaleCacheFiles is the startup reclaim for
 * hard-kill temp files — a broadened prefix filter deletes unrelated cache entries on every launch, and a dropped
 * prefix permanently leaks multi-hundred-MB full source-image copies.
 */
public final class UltraHdrCompatTest
{
	// Created lazily by the sweep tests; @After cleanup removes it recursively when set.
	private Path tempDir;

	@After
	public void cleanup() throws IOException
	{
		if (tempDir != null && Files.exists(tempDir))
		{
			File[] residue = tempDir.toFile().listFiles();
			if (residue != null)
			{
				for (File entry : residue)
				{
					entry.delete();
				}
			}
			Files.deleteIfExists(tempDir);
		}
	}

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
		// 5-byte all-zero buffer — exactly at the loop-entry boundary (limit = length - 4 = 1, so the outer
		// loop runs once with i=0 and i=1). Pins that the loop entry doesn't AIOOBE at the minimum-viable
		// length AND that an all-zero needle position doesn't accidentally match.
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
		// Pin both branches of the matcher's near-miss handling. ACCEPT side: "hdrgmm" extends past the 5-byte
		// signature with a trailing 'm' — the matcher correctly treats the first 5 bytes as a successful match
		// because it's a contains-search, not an equals-search. REJECT side: a mid-pattern byte swap ("hdrgxm")
		// plus two truncations ("hdrg", "drgm") must each return false regardless of buffer length. Accept and
		// reject deliberately share one test: the predicate's contract is a single contains-search whose
		// behavior on near-misses needs to be pinned together.
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
		// The scan must cover the whole buffer, not a fixed prefix window — a maxed-out EXIF thumbnail can push
		// the XMP segment past any fixed prefix. Verify a hit deep in a large buffer is found.
		byte[] data = new byte[200_000];
		byte[] needle = "hdrgm".getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(needle, 0, data, 150_000, needle.length);
		assertTrue(UltraHdrCompat.containsHdrgm(data));
	}

	@Test
	public void sweepStaleCacheFilesDeletesOnlyPrefixedEntries() throws IOException
	{
		// The sweep runs at every startup against the shared cache dir. Both temp prefixes (HDR re-decode
		// scratch and the SAF read fallback) must be reclaimed; anything else in the cache dir belongs to
		// other subsystems and must survive. A broadened filter deletes unrelated cache entries on every
		// launch; a dropped prefix permanently leaks full source-image copies after a hard process kill.
		tempDir = Files.createTempDirectory("uhdr-sweep-test");
		File hdrScratch = new File(tempDir.toFile(), UltraHdrCompat.TEMPFILE_PREFIX_HDR_SRC + "1_2.jpg");
		File safFallback = new File(tempDir.toFile(), UltraHdrCompat.TEMPFILE_PREFIX_INPUT_RAW + "x.bin");
		File survivor = new File(tempDir.toFile(), "keep_me.jpg");
		Files.write(hdrScratch.toPath(), new byte[]{ 0x01 });
		Files.write(safFallback.toPath(), new byte[]{ 0x02 });
		Files.write(survivor.toPath(), new byte[]{ 0x03 });
		UltraHdrCompat.sweepStaleCacheFiles(tempDir.toFile());
		assertFalse("hdr_src_* scratch must be reclaimed", hdrScratch.exists());
		assertFalse("input_raw_* fallback must be reclaimed", safFallback.exists());
		assertTrue("non-prefixed cache entry must survive the sweep", survivor.exists());
	}

	@Test
	public void sweepStaleCacheFilesNoOpsOnNullAndNonDirectoryCacheDir() throws IOException
	{
		// Null cacheDir short-circuits without throwing (Context.getCacheDir can return null on storage
		// pressure), and a plain file passed as cacheDir must be left untouched — even when its own name
		// matches a sweep prefix — because the sweep only ever lists a directory.
		UltraHdrCompat.sweepStaleCacheFiles(null);
		tempDir = Files.createTempDirectory("uhdr-sweep-noop-test");
		File plainFile = new File(tempDir.toFile(), UltraHdrCompat.TEMPFILE_PREFIX_HDR_SRC + "leftover.bin");
		Files.write(plainFile.toPath(), new byte[]{ 0x04 });
		UltraHdrCompat.sweepStaleCacheFiles(plainFile);
		assertTrue("plain-file cacheDir must be a no-op, not a delete", plainFile.exists());
	}
}
