package com.cropcenter.crop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Tests for CropExporter.appendSeft — the byte-concat re-append of a Samsung Extended Format Trailer that lives on
 * the JPEG tail. The Revert chain on Gallery-edited originals depends on this trailer surviving every save
 * round-trip byte-for-byte; CropCenter NEVER fabricates fresh trailers (Samsung Gallery only honors trailers
 * pointing at vendor-blessed backup directories that third-party apps can't write) so the only legitimate
 * behavior here is "preserve the exact bytes captured at load, or pass through cleanly when none was captured."
 * Pin the byte-perfect concat plus the null/empty short-circuits. The int-overflow branch requires near-2GB
 * allocations to actually trigger — beyond what a JVM unit test can do without OOM — so the long-cast guard's
 * correctness is left to the multi-megabyte happy-path test plus a statically obvious source review.
 */
public final class CropExporterSeftTest
{
	@Test
	public void appendSeftAppendsBytesAtJpegTail()
	{
		// Happy path: 4-byte JPEG + 3-byte SEFT → 7-byte output with concat order preserved.
		byte[] jpeg = { 0x10, 0x20, 0x30, 0x40 };
		byte[] seft = { (byte) 0xAA, (byte) 0xBB, (byte) 0xCC };
		byte[] result = CropExporter.appendSeft(jpeg, seft);
		assertEquals("output length = jpeg + seft", 7, result.length);
		assertArrayEquals("first 4 bytes are the JPEG verbatim",
			jpeg, java.util.Arrays.copyOfRange(result, 0, 4));
		assertArrayEquals("last 3 bytes are the SEFT verbatim",
			seft, java.util.Arrays.copyOfRange(result, 4, 7));
	}

	@Test
	public void appendSeftConcatsAtMultiMegabyteSize()
	{
		// Pin that the happy-path arraycopy works correctly at multi-megabyte sizes — the only end-to-end
		// concat case a JVM unit test can reach (the int-overflow branch requires near-2GB allocations that
		// would OOM the test heap; the long-cast guard's correctness is statically obvious from the source).
		// 1MB JPEG + 1MB SEFT → 2MB output exercises the `(int) combinedLong` cast on a non-trivially-sized
		// allocation without hitting the overflow branch.
		byte[] jpeg = new byte[1024 * 1024];
		byte[] seft = new byte[1024 * 1024];
		byte[] result = CropExporter.appendSeft(jpeg, seft);
		assertEquals("1MB + 1MB = 2MB output", 2 * 1024 * 1024, result.length);
	}

	@Test
	public void appendSeftPreservesSeftBytesByteForByte()
	{
		// Realistic 256-byte SEFT (smaller than typical real trailers but enough to catch a byte-shift /
		// off-by-one in the arraycopy length parameter). Verify every byte round-trips.
		byte[] jpeg = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9 };  // SOI + EOI stub
		byte[] seft = new byte[256];
		for (int i = 0; i < seft.length; i++)
		{
			seft[i] = (byte) (i ^ 0x55);  // distinctive non-zero pattern
		}
		byte[] result = CropExporter.appendSeft(jpeg, seft);
		// Verify the tail bytes match the SEFT pattern exactly.
		for (int i = 0; i < seft.length; i++)
		{
			assertEquals("seft byte " + i + " must round-trip",
				seft[i], result[jpeg.length + i]);
		}
	}

	@Test
	public void appendSeftReturnsJpegVerbatimWhenSeftEmpty()
	{
		// Empty trailer → same passthrough as null. Pin both branches of the `null || length == 0` guard.
		byte[] jpeg = { 0x10, 0x20, 0x30, 0x40 };
		byte[] result = CropExporter.appendSeft(jpeg, new byte[0]);
		assertSame("empty SEFT short-circuits to the input reference", jpeg, result);
	}

	@Test
	public void appendSeftReturnsJpegVerbatimWhenSeftNull()
	{
		// Null trailer → reference-equal passthrough (no allocation). Pin the reference equality so a
		// regression that did `Arrays.copyOf(jpeg, jpeg.length)` would surface here — that's wasteful for
		// the common no-SEFT save path.
		byte[] jpeg = { 0x10, 0x20, 0x30, 0x40 };
		byte[] result = CropExporter.appendSeft(jpeg, null);
		assertSame("null SEFT short-circuits to the input reference", jpeg, result);
	}
}
