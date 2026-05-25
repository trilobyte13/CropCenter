package com.cropcenter.crop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.CRC32;

/**
 * Tests for the package-private CropExporter.injectPngExifFromTiffFileToFile helper that writes a PNG eXIf
 * chunk carrying raw TIFF bytes immediately after IHDR. Pin the byte-layout (chunk-type "eXIf", big-endian
 * u32 length, CRC32 over chunk type + data) plus the IHDR-only PNG rejection and the long-cast IHDR-length
 * overflow guard (against sign-flipped u32 inputs). The complementary chunkTotal + inSize output-size
 * overflow guard cannot be exercised on a JVM unit test — driving it would require a near-2 GB tiffData
 * array (heap OOM); the guard's correctness is statically obvious from the source. A regression in the
 * covered paths silently produces unreadable PNGs (wrong CRC) or stream-truncates on adversarial inputs —
 * every PNG save with EXIF flows through this function so a bug there surfaces on every saved image.
 *
 * Tests write the input PNG bytes to a tempfile via the JUnit TemporaryFolder rule, invoke
 * injectPngExifFromTiffFileToFile, then read outFile back as a byte array and assert against expected
 * bytes. The earlier byte[]-in-byte[]-out helper this file used was deleted in favour of the streaming
 * variant; each test was migrated to the file-based contract with the same intent-revealing assertions.
 */
public final class CropExporterPngExifInjectionTest
{
	// Standard 8-byte PNG signature.
	private static final byte[] PNG_SIGNATURE = {
		(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
	};

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void chunkLengthFieldIsBigEndianU32() throws IOException
	{
		// Build a PNG with a 256-byte TIFF — length field {0, 0, 1, 0} verifies byte order. A 0x12345678-byte
		// TIFF would require allocating 305MB in a test heap; the smaller fixture exercises the same writer.
		byte[] tiff = new byte[256];
		byte[] result = injectAndReadBack(buildMinimalPng(), tiff);
		int eXIfStart = 33;
		assertEquals("MSB byte of length", (byte) 0x00, result[eXIfStart]);
		assertEquals("second byte of length", (byte) 0x00, result[eXIfStart + 1]);
		assertEquals("third byte of length", (byte) 0x01, result[eXIfStart + 2]);
		assertEquals("LSB byte of length", (byte) 0x00, result[eXIfStart + 3]);
	}

	@Test
	public void crcMatchesZlibCrc32OverChunkTypeAndOneByteTiff() throws IOException
	{
		// 1-byte TIFF — minimal non-empty input (empty tiff goes through the early-return short-circuit and
		// never reaches the CRC computation). CRC32 over "eXIf" + {0x42} must match java.util.zip.CRC32's
		// output exactly.
		byte[] tiff = { 0x42 };
		byte[] result = injectAndReadBack(buildMinimalPng(), tiff);
		int eXIfStart = 33;
		int crcOffset = eXIfStart + 8 + tiff.length;
		CRC32 expected = new CRC32();
		expected.update(new byte[] { 'e', 'X', 'I', 'f', 0x42 });
		long expectedCrc = expected.getValue();
		byte[] actualCrcBytes = {
			result[crcOffset], result[crcOffset + 1], result[crcOffset + 2], result[crcOffset + 3],
		};
		byte[] expectedCrcBytes = {
			(byte) (expectedCrc >> 24), (byte) (expectedCrc >> 16),
			(byte) (expectedCrc >> 8), (byte) expectedCrc,
		};
		assertArrayEquals(expectedCrcBytes, actualCrcBytes);
	}

	@Test
	public void injectsExifChunkAfterIhdrWithCorrectCrc() throws IOException
	{
		byte[] tiff = { 'I', 'I', '*', 0x00, 0x08, 0x00, 0x00, 0x00 };  // tiny TIFF stub
		byte[] result = injectAndReadBack(buildMinimalPng(), tiff);

		// IHDR ends at offset 8 + 4 + 4 + 13 + 4 = 33. The eXIf chunk starts there.
		int eXIfStart = 33;
		// 4-byte big-endian length field.
		int storedLen = ((result[eXIfStart] & 0xFF) << 24)
			| ((result[eXIfStart + 1] & 0xFF) << 16)
			| ((result[eXIfStart + 2] & 0xFF) << 8)
			| (result[eXIfStart + 3] & 0xFF);
		assertEquals("u32 length field carries the TIFF size", tiff.length, storedLen);
		// Chunk type "eXIf".
		assertEquals('e', result[eXIfStart + 4]);
		assertEquals('X', result[eXIfStart + 5]);
		assertEquals('I', result[eXIfStart + 6]);
		assertEquals('f', result[eXIfStart + 7]);
		// TIFF body sits at offset eXIfStart + 8..+8+tiffLen.
		for (int i = 0; i < tiff.length; i++)
		{
			assertEquals("tiff byte " + i, tiff[i], result[eXIfStart + 8 + i]);
		}
		// CRC32 over "eXIf" + tiffData.
		CRC32 expected = new CRC32();
		expected.update(new byte[] { 'e', 'X', 'I', 'f' });
		expected.update(tiff);
		long expectedCrc = expected.getValue();
		int crcOffset = eXIfStart + 8 + tiff.length;
		long actualCrc = ((result[crcOffset] & 0xFFL) << 24)
			| ((result[crcOffset + 1] & 0xFFL) << 16)
			| ((result[crcOffset + 2] & 0xFFL) << 8)
			| (result[crcOffset + 3] & 0xFFL);
		assertEquals("CRC32 over chunk type + data must match zlib CRC", expectedCrc, actualCrc);
	}

	@Test
	public void preservesOriginalPngBytesAroundInsertPos() throws IOException
	{
		byte[] png = buildMinimalPng();
		byte[] tiff = { 'I', 'I', '*', 0x00, 0x08, 0x00, 0x00, 0x00 };
		int insertPos = 33;  // post-IHDR
		byte[] result = injectAndReadBack(png, tiff);
		// Bytes [0..insertPos) come straight from the source PNG.
		for (int i = 0; i < insertPos; i++)
		{
			assertEquals("pre-insert byte " + i, png[i], result[i]);
		}
		// Bytes after the eXIf chunk also come from the source — the inserted block is 8 + tiff.length + 4
		// = 20 bytes, so source[insertPos..] sits at result[insertPos + 20..].
		int chunkLen = 8 + tiff.length + 4;
		for (int i = insertPos; i < png.length; i++)
		{
			assertEquals("post-insert byte " + i + " (source offset)", png[i], result[i + chunkLen]);
		}
	}

	@Test
	public void rejectsAdversarialIhdrLengthThatWrapsNegative() throws IOException
	{
		// Craft an IHDR with length 0x80000001 (high bit set). Without the long-cast read, this would
		// underflow to Integer.MIN_VALUE + 1 = -2147483647, slip past the `insertPos >= inSize` guard
		// (since insertPos becomes a small POSITIVE value after int wrap), then truncate the stream copy.
		// The long-arithmetic read keeps insertPosLong as a real positive ~2.15e9, which trips the
		// `>= inSize` guard cleanly and bails to verbatim copy.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(PNG_SIGNATURE);
		// IHDR with adversarial length 0x80000001.
		out.write(new byte[] { (byte) 0x80, 0, 0, 1 });
		out.write(new byte[] { 'I', 'H', 'D', 'R' });
		// We can't actually emit 2GB of data; the guard fires on the length field alone.
		out.write(new byte[20]);  // any tail; the guard rejects before reaching here
		byte[] adversarial = out.toByteArray();
		byte[] tiff = { 'I', 'I' };
		byte[] result = injectAndReadBack(adversarial, tiff);
		assertArrayEquals("adversarial IHDR length must reject; outFile is verbatim copy of input",
			adversarial, result);
	}

	@Test
	public void resultLengthEqualsSourcePlusChunkTotal() throws IOException
	{
		// Output size = png.length + 4 (length) + 4 (type) + tiffLen + 4 (CRC).
		byte[] png = buildMinimalPng();
		byte[] tiff = new byte[100];
		byte[] result = injectAndReadBack(png, tiff);
		assertEquals(png.length + 4 + 4 + 100 + 4, result.length);
	}

	@Test
	public void returnsPngUnchangedForEmptyTiff() throws IOException
	{
		// tiffLen == 0 short-circuits without inserting anything — outFile is a verbatim copy of inFile.
		// (The byte[]-variant predecessor returned the input reference itself; the streaming variant
		// produces an outFile byte-identical to inFile, which is the file-level equivalent.)
		byte[] png = buildMinimalPng();
		byte[] result = injectAndReadBack(png, new byte[0]);
		assertArrayEquals("empty TIFF stream-copies inFile verbatim", png, result);
	}

	@Test
	public void returnsPngUnchangedForIhdrOnlyPng() throws IOException
	{
		// A PNG whose only chunk is IHDR (no IEND following) — insertPos == inSize triggers the
		// `insertPosLong >= inSize` reject. Inserting eXIf at the very tail would produce a PNG that
		// ends with eXIf and still no IEND (no better than the source).
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(PNG_SIGNATURE);
		// IHDR: length=13, "IHDR", 13-byte data, 4-byte CRC.
		out.write(new byte[] { 0, 0, 0, 13 });
		out.write(new byte[] { 'I', 'H', 'D', 'R' });
		out.write(new byte[13]);
		out.write(new byte[] { 0, 0, 0, 0 });  // dummy CRC
		byte[] ihdrOnly = out.toByteArray();
		byte[] tiff = { 'I', 'I' };
		byte[] result = injectAndReadBack(ihdrOnly, tiff);
		assertArrayEquals("IHDR-only PNG stream-copies verbatim", ihdrOnly, result);
	}

	@Test
	public void returnsPngUnchangedForTooShortPng() throws IOException
	{
		// PNG < 20 bytes can't even hold signature + an IHDR header. The early-bail at `inSize < 8 + 12`
		// stream-copies the input to outFile without touching it.
		byte[] tooShort = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A };
		byte[] tiff = { 'I', 'I' };
		byte[] result = injectAndReadBack(tooShort, tiff);
		assertArrayEquals("too-short PNG stream-copies verbatim", tooShort, result);
	}

	/**
	 * Build a minimal valid PNG: 8-byte signature + 13-byte IHDR chunk + 0-byte IEND chunk. Total bytes:
	 * 8 + (4+4+13+4) + (4+4+0+4) = 8 + 25 + 12 = 45. eXIf insertion point sits at byte 33 (after IHDR's CRC,
	 * before IEND's length field).
	 *
	 * @return 45-byte PNG ready for injectPngExifFromTiffFileToFile to chunk-walk
	 * @throws IOException never; ByteArrayOutputStream doesn't throw
	 */
	private static byte[] buildMinimalPng() throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(PNG_SIGNATURE);
		// IHDR: length=13, "IHDR", 13-byte data, 4-byte CRC.
		out.write(new byte[] { 0, 0, 0, 13 });
		out.write(new byte[] { 'I', 'H', 'D', 'R' });
		out.write(new byte[13]);                       // width / height / etc. — unused by injection
		out.write(new byte[] { 0, 0, 0, 0 });          // dummy CRC; the injector doesn't verify
		// IEND: length=0, "IEND", no data, 4-byte CRC.
		out.write(new byte[] { 0, 0, 0, 0 });
		out.write(new byte[] { 'I', 'E', 'N', 'D' });
		out.write(new byte[] { 0, 0, 0, 0 });
		assertTrue("minimal PNG is at least 45 bytes", out.size() >= 45);
		return out.toByteArray();
	}

	/**
	 * Helper for every test: write `png` to a fresh tempfile, run injectPngExifFromTiffFileToFile against
	 * `tiff`, read the output tempfile back as a byte array, and return it for assertion. Replaces the
	 * one-liner `CropExporter.injectPngExifFromTiff(png, tiff)` from the deleted in-memory variant — the
	 * additional file I/O is the cost of pinning the streaming production code path that ships in saves.
	 *
	 * @param png  source PNG bytes
	 * @param tiff raw TIFF bytes to inject (may be empty)
	 * @return outFile contents as a byte array
	 * @throws IOException if the tempfile I/O fails
	 */
	private byte[] injectAndReadBack(byte[] png, byte[] tiff) throws IOException
	{
		File inFile = tmp.newFile();
		File outFile = tmp.newFile();
		try (FileOutputStream fos = new FileOutputStream(inFile))
		{
			fos.write(png);
		}
		CropExporter.injectPngExifFromTiffFileToFile(inFile, tiff, outFile);
		return Files.readAllBytes(outFile.toPath());
	}
}
