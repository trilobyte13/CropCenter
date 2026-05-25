package com.cropcenter.crop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Tests for CropExporter.appendSeftFileToFile — the streaming re-append of a Samsung Extended Format Trailer
 * that lives on the JPEG tail. The Revert chain on Gallery-edited originals depends on this trailer surviving
 * every save round-trip byte-for-byte; CropCenter NEVER fabricates fresh trailers (Samsung Gallery only honors
 * trailers pointing at vendor-blessed backup directories that third-party apps can't write) so the only
 * legitimate behavior here is "preserve the exact bytes captured at load, or stream-copy cleanly when none
 * was captured." Pin the byte-perfect concat plus the null/empty short-circuits.
 *
 * Tests write the input JPEG bytes to a tempfile via the JUnit TemporaryFolder rule, invoke
 * appendSeftFileToFile, then read the output tempfile back as a byte array. The earlier byte[]-in-byte[]-out
 * helper this file used was deleted in favour of the streaming variant.
 */
public final class CropExporterSeftTest
{
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void appendSeftAppendsBytesAtJpegTail() throws IOException
	{
		// Happy path: 4-byte JPEG + 3-byte SEFT → 7-byte output with concat order preserved.
		byte[] jpeg = { 0x10, 0x20, 0x30, 0x40 };
		byte[] seft = { (byte) 0xAA, (byte) 0xBB, (byte) 0xCC };
		byte[] result = appendAndReadBack(jpeg, seft);
		assertEquals("output length = jpeg + seft", 7, result.length);
		assertArrayEquals("first 4 bytes are the JPEG verbatim",
			jpeg, java.util.Arrays.copyOfRange(result, 0, 4));
		assertArrayEquals("last 3 bytes are the SEFT verbatim",
			seft, java.util.Arrays.copyOfRange(result, 4, 7));
	}

	@Test
	public void appendSeftConcatsAtMultiMegabyteSize() throws IOException
	{
		// Pin that the happy-path stream-copy works correctly at multi-megabyte sizes. 1MB JPEG + 1MB
		// SEFT → 2MB output exercises the chunk-loop write on a non-trivially-sized input. Real saves
		// hit ~100+ MB; the test is bounded by JVM heap budget for the test runner.
		byte[] jpeg = new byte[1024 * 1024];
		byte[] seft = new byte[1024 * 1024];
		byte[] result = appendAndReadBack(jpeg, seft);
		assertEquals("1MB + 1MB = 2MB output", 2 * 1024 * 1024, result.length);
	}

	@Test
	public void appendSeftPreservesSeftBytesByteForByte() throws IOException
	{
		// Realistic 256-byte SEFT (smaller than typical real trailers but enough to catch a byte-shift /
		// off-by-one in the stream-write length parameter). Verify every byte round-trips.
		byte[] jpeg = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9 };  // SOI + EOI stub
		byte[] seft = new byte[256];
		for (int i = 0; i < seft.length; i++)
		{
			seft[i] = (byte) (i ^ 0x55);  // distinctive non-zero pattern
		}
		byte[] result = appendAndReadBack(jpeg, seft);
		// Verify the tail bytes match the SEFT pattern exactly.
		for (int i = 0; i < seft.length; i++)
		{
			assertEquals("seft byte " + i + " must round-trip",
				seft[i], result[jpeg.length + i]);
		}
	}

	@Test
	public void appendSeftStreamCopiesJpegVerbatimWhenSeftEmpty() throws IOException
	{
		// Empty trailer → outFile is a verbatim copy of inFile. (The byte[]-variant predecessor returned
		// the input reference itself; the streaming variant produces a byte-identical outFile, which is
		// the file-level equivalent.) Pin both branches of the `null || length == 0` short-circuit.
		byte[] jpeg = { 0x10, 0x20, 0x30, 0x40 };
		byte[] result = appendAndReadBack(jpeg, new byte[0]);
		assertArrayEquals("empty SEFT stream-copies inFile verbatim", jpeg, result);
	}

	@Test
	public void appendSeftStreamCopiesJpegVerbatimWhenSeftNull() throws IOException
	{
		// Null trailer → outFile byte-identical to inFile. Same short-circuit branch as empty-trailer
		// above, exercised separately so a regression that distinguished null from empty (e.g., NPE on
		// `seft.length` before the null check) would surface here.
		byte[] jpeg = { 0x10, 0x20, 0x30, 0x40 };
		byte[] result = appendAndReadBack(jpeg, null);
		assertArrayEquals("null SEFT stream-copies inFile verbatim", jpeg, result);
	}

	/**
	 * Helper for every test: write `jpeg` to a fresh tempfile, run appendSeftFileToFile against `seft`,
	 * read the output tempfile back as a byte array, and return it for assertion. Replaces the one-liner
	 * `CropExporter.appendSeft(jpeg, seft)` from the deleted in-memory variant — the additional file I/O
	 * is the cost of pinning the streaming production code path that ships in saves.
	 *
	 * @param jpeg source JPEG bytes (written to inFile verbatim)
	 * @param seft trailer to append (may be null or empty)
	 * @return outFile contents as a byte array
	 * @throws IOException if the tempfile I/O fails
	 */
	private byte[] appendAndReadBack(byte[] jpeg, byte[] seft) throws IOException
	{
		File inFile = tmp.newFile();
		File outFile = tmp.newFile();
		try (FileOutputStream fos = new FileOutputStream(inFile))
		{
			fos.write(jpeg);
		}
		CropExporter.appendSeftFileToFile(inFile, seft, outFile);
		return Files.readAllBytes(outFile.toPath());
	}
}
