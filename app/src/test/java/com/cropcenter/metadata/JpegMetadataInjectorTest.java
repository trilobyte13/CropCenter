package com.cropcenter.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Tests for both JpegMetadataInjector inject variants. The in-memory inject() coverage pins the malformed-segment guard
 * — an APP segment claiming a segLen past EOF means the re-encoded buffer is structurally broken (Skia bug, byte-stream
 * corruption between encode and inject), and the injector throws so the caller's "Export failed" toast surfaces the
 * real error instead of silently falling back to scanStart = 2 and stacking the re-encoder's APP markers next to the
 * original's — plus the fill-byte marker walk.
 *
 * The streaming injectFileToFile() coverage pins byte-identity with inject() on identical input (the equivalence every
 * disk-backed save relies on), the short-read head truncation, the head-budget converge guard, and skipExactly tail
 * positioning past a STREAM_CHUNK_SIZE boundary.
 */
public final class JpegMetadataInjectorTest
{
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void injectAcceptsValidReencodedJpegAndPreservesSegmentBytes() throws IOException
	{
		// Reencoded = SOI + small APP0 + DQT + SOS + EOI. Original metadata = single EXIF segment. Output
		// should: start with SOI, contain the EXIF bytes verbatim, end with EOI, and NOT contain the reencoded
		// APP0 (it's part of the stripped marker walk).
		byte[] reencoded = JpegFixtures.concat(
			JpegFixtures.soi(), JpegFixtures.appSegment(0xE0, new byte[] { 'J', 'F', 'I', 'F', 0 }),
			JpegFixtures.dqtStub(), JpegFixtures.minimalScanAndEoi());

		byte[] exifPayload = new byte[14];
		exifPayload[0] = 'E';
		exifPayload[1] = 'x';
		exifPayload[2] = 'i';
		exifPayload[3] = 'f';
		// trailing nulls already present
		byte[] exifSegBytes = JpegFixtures.appSegment(0xE1, exifPayload);
		JpegSegment exif = new JpegSegment(0xE1, exifSegBytes);
		List<JpegSegment> segments = Collections.singletonList(exif);

		byte[] result = JpegMetadataInjector.inject(reencoded, segments);
		assertNotNull(result);
		assertEquals((byte) 0xFF, result[0]);
		assertEquals((byte) 0xD8, result[1]);
		assertEquals((byte) 0xFF, result[result.length - 2]);
		assertEquals((byte) 0xD9, result[result.length - 1]);

		// EXIF bytes should appear contiguously after SOI.
		assertTrue("output should contain the original EXIF bytes verbatim",
			JpegFixtures.indexOfSubsequence(result, exifSegBytes) >= 0);
	}

	@Test
	public void injectFileToFileHandlesAppSegmentsPastStreamChunkBoundary() throws IOException
	{
		// Two 40 KB APP1 segments push scanStart past STREAM_CHUNK_SIZE (64 KB), so the tail stream's
		// skipExactly must advance across a chunk boundary before the first copied byte. An off-by-one there
		// ships SOI + segments + (mid-APP-segment bytes) on every save whose re-encoder header outgrows one
		// chunk.
		byte[] bulkyPayload = new byte[40 * 1024];
		Arrays.fill(bulkyPayload, (byte) 0x5A);
		byte[] appMarkers = JpegFixtures.concat(JpegFixtures.appSegment(0xE1, bulkyPayload),
			JpegFixtures.appSegment(0xE1, bulkyPayload));
		byte[] reencoded = buildReencoded(appMarkers, 8 * 1024);
		List<JpegSegment> segments = Collections.singletonList(
			new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, JpegFixtures.exifAppPayload())));
		byte[] expected = JpegMetadataInjector.inject(reencoded, segments);

		File inFile = tmp.newFile("straddle.jpg");
		Files.write(inFile.toPath(), reencoded);
		File outFile = tmp.newFile("straddle-out.jpg");
		JpegMetadataInjector.injectFileToFile(inFile, segments, outFile);
		assertArrayEquals("scanStart past one chunk must not shift the copied tail",
			expected, Files.readAllBytes(outFile.toPath()));
	}

	@Test
	public void injectFileToFileMatchesInMemoryInjectByteForByte() throws IOException
	{
		// The strongest pin on the streaming path: injectFileToFile and inject must produce byte-identical
		// output on identical input, or the disk-backed and in-memory save paths silently diverge. 200 KB of
		// entropy spans multiple STREAM_CHUNK_SIZE chunks so the chunked tail copy is exercised, not just a
		// single-read fast path.
		byte[] reencoded = buildReencoded(
			JpegFixtures.appSegment(0xE0, new byte[] { 'J', 'F', 'I', 'F', 0 }), 200 * 1024);
		List<JpegSegment> segments = List.of(
			new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, JpegFixtures.exifAppPayload())),
			new JpegSegment(0xE2, JpegFixtures.appSegment(0xE2, new byte[] { 0x01, 0x02 })));
		byte[] expected = JpegMetadataInjector.inject(reencoded, segments);

		File inFile = tmp.newFile("identity.jpg");
		Files.write(inFile.toPath(), reencoded);
		File outFile = tmp.newFile("identity-out.jpg");
		JpegMetadataInjector.injectFileToFile(inFile, segments, outFile);
		assertArrayEquals("streaming inject must be byte-identical to in-memory inject",
			expected, Files.readAllBytes(outFile.toPath()));
	}

	@Test
	public void injectFileToFileSucceedsWhenFileExceedsHeadReadLimit() throws IOException
	{
		// Accept side of the head-budget converge guard: a file LARGER than HEAD_READ_LIMIT (2 MB) whose APP
		// markers converge early makes `head.length == HEAD_READ_LIMIT` true while scanStart sits far below
		// `head.length - 3` — the guard must NOT fire and the output must stay byte-identical to inject().
		// Only the throw side and sub-2MB files were pinned before, so mutating the guard's `&&` to `||`
		// passed the whole suite while making every production save over 2 MB throw "head budget"; the
		// inverted mutation silently disables the corruption guard instead.
		byte[] reencoded = buildReencoded(
			JpegFixtures.appSegment(0xE0, new byte[] { 'J', 'F', 'I', 'F', 0 }), 3 * 1024 * 1024);
		List<JpegSegment> segments = Collections.singletonList(
			new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, JpegFixtures.exifAppPayload())));
		byte[] expected = JpegMetadataInjector.inject(reencoded, segments);

		File inFile = tmp.newFile("overbudget.jpg");
		Files.write(inFile.toPath(), reencoded);
		File outFile = tmp.newFile("overbudget-out.jpg");
		JpegMetadataInjector.injectFileToFile(inFile, segments, outFile);
		assertArrayEquals("head-budget guard must not fire when markers converge inside the head",
			expected, Files.readAllBytes(outFile.toPath()));
	}

	@Test
	public void injectFileToFileThrowsWhenAppMarkersFillEntireHeadBudget() throws IOException
	{
		// injectFileToFile scans only the first HEAD_READ_LIMIT (2 MB) bytes for scanStart. When APP segments
		// run all the way to that boundary, the tail copy would start INSIDE an APP segment of the real file —
		// the segment's length field would bleed into the entropy-coded scan — so the injector must throw
		// rather than ship silent corruption. APP1 run sized to end exactly at the boundary: 2 (SOI) + 31 * (2
		// + 65535) + (2 + 65501) = 2_097_152.
		ByteArrayOutputStream reencodedOut = new ByteArrayOutputStream();
		reencodedOut.write(JpegFixtures.soi());
		for (int i = 0; i < 31; i++)
		{
			reencodedOut.write(JpegFixtures.appSegment(0xE1, new byte[65533]));
		}
		reencodedOut.write(JpegFixtures.appSegment(0xE1, new byte[65499]));
		reencodedOut.write(JpegFixtures.minimalScanAndEoi());
		byte[] reencoded = reencodedOut.toByteArray();

		File inFile = tmp.newFile("headbudget.jpg");
		Files.write(inFile.toPath(), reencoded);
		File outFile = tmp.newFile("headbudget-out.jpg");
		IOException ex = assertThrows(IOException.class,
			() -> JpegMetadataInjector.injectFileToFile(inFile, Collections.emptyList(), outFile));
		assertTrue("message should mention the head budget: " + ex.getMessage(),
			ex.getMessage().contains("head budget"));
	}

	@Test
	public void injectFileToFileUsesActualBytesWhenFileShorterThanReportedLength() throws IOException
	{
		// The head read sizes its buffer from File.length(), but the stream may return EOF earlier (file shrank
		// between the length() call and the read). The truncation branch must shrink the head to the bytes
		// actually read and carry on — output byte-identical to in-memory inject of the actual bytes, no
		// exception, no zero-padded phantom head.
		byte[] reencoded = buildReencoded(
			JpegFixtures.appSegment(0xE0, new byte[] { 'J', 'F', 'I', 'F', 0 }), 1024);
		List<JpegSegment> segments = Collections.singletonList(
			new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, JpegFixtures.exifAppPayload())));
		byte[] expected = JpegMetadataInjector.inject(reencoded, segments);

		File realFile = tmp.newFile("shrunk.jpg");
		Files.write(realFile.toPath(), reencoded);
		File outFile = tmp.newFile("shrunk-out.jpg");
		JpegMetadataInjector.injectFileToFile(
			JpegFixtures.fileReportingLength(realFile, reencoded.length + 100), segments, outFile);
		assertArrayEquals("short head read must fall back to the bytes actually read",
			expected, Files.readAllBytes(outFile.toPath()));
	}

	@Test
	public void injectHandlesFillBytesBeforeReencoderApp() throws IOException
	{
		// Per ITU-T T.81 §B.1.1.2, any marker may be preceded by any number of 0xFF fill bytes. Skia's
		// Bitmap.compress could legitimately emit FF FF E0 for APP0 alignment. Without the fill-byte handling
		// added in the recent fix, the injector mis-read the second 0xFF as marker code 0xFF, then parsed
		// garbage as the segment length. This regression test pins the fix.
		byte[] reencoded = JpegFixtures.concat(JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xE0, // FF + fill + APP0 marker
				0x00, 0x07, 'J', 'F', 'I', 'F', 0 },           // segLen=7 + payload "JFIF\0"
			JpegFixtures.dqtStub(), JpegFixtures.minimalScanAndEoi());

		byte[] result = JpegMetadataInjector.inject(reencoded, Collections.emptyList());
		// Output should start with SOI and end with EOI. Crucially, the JFIF payload from the re-encoder should
		// NOT appear (the walker correctly skipped it).
		assertEquals((byte) 0xFF, result[0]);
		assertEquals((byte) 0xD8, result[1]);
		assertEquals((byte) 0xFF, result[result.length - 2]);
		assertEquals((byte) 0xD9, result[result.length - 1]);
		// Confirm "JFIF" wasn't carried through (proves the APP0 was stripped).
		assertFalse("re-encoder JFIF should be stripped from output",
			JpegFixtures.indexOfSubsequence(result, new byte[] { 'J', 'F', 'I', 'F' }) >= 0);
	}

	@Test
	public void injectRejectsNonJpegInput()
	{
		byte[] notJpeg = { 0x12, 0x34, 0x56, 0x78 };
		IOException ex = assertThrows(IOException.class,
			() -> JpegMetadataInjector.inject(notJpeg, Collections.emptyList()));
		assertTrue("message should mention valid JPEG: " + ex.getMessage(),
			ex.getMessage().contains("valid JPEG"));
	}

	@Test
	public void injectRejectsTruncatedReencodedJpeg()
	{
		// Reencoded = just SOI (2 bytes). Length check: < 4, so the up-front "Not a valid JPEG" guard catches
		// it. Pinned to make sure that guard isn't loosened in a future refactor.
		byte[] reencoded = { (byte) 0xFF, (byte) 0xD8 };
		IOException ex = assertThrows(IOException.class,
			() -> JpegMetadataInjector.inject(reencoded, Collections.emptyList()));
		assertTrue("message should mention valid JPEG: " + ex.getMessage(),
			ex.getMessage().contains("valid JPEG"));
	}

	@Test
	public void injectThrowsOnAppSegmentClaimingLengthPastEof() throws IOException
	{
		// Re-encoded JPEG where the APP0 segment claims a 65535-byte length but only 4 bytes follow. The
		// injector must throw — a silent fallback to scanStart = 2 would produce a stitched output with
		// duplicate APP markers; throwing keeps the caller's toast matched to the actual failure mode.
		byte[] reencoded = {
			(byte) 0xFF, (byte) 0xD8,                         // SOI
			(byte) 0xFF, (byte) 0xE0, (byte) 0xFF, (byte) 0xFF, // APP0 with segLen 0xFFFF
			0x00, 0x01, 0x02, 0x03,                           // 4 garbage bytes
		};

		IOException ex = assertThrows(IOException.class,
			() -> JpegMetadataInjector.inject(reencoded, Collections.emptyList()));
		assertTrue("message should mention claims length: " + ex.getMessage(),
			ex.getMessage().contains("claims length"));
	}

	@Test
	public void skipExactlyAdvancesPastCountAndStreamStartsAtCorrectByte() throws IOException
	{
		// Pin the helper's success-path contract: after `skipExactly(fis, count)`, the next read() returns the
		// byte at position `count` in the file. The naive `skip()` loop with a break-on-zero idiom this helper
		// replaces silently truncated streams when FileInputStream.skip() transiently returned 0 — the call
		// returned without advancing, the caller's next read() landed mid-file, and the output bytes were SOI +
		// segments + (some random middle of file).
		byte[] payload = new byte[256];
		for (int i = 0; i < payload.length; i++)
		{
			payload[i] = (byte) (i & 0xFF);
		}
		File file = tmp.newFile("skip.bin");
		Files.write(file.toPath(), payload);
		try (FileInputStream fis = new FileInputStream(file))
		{
			JpegMetadataInjector.skipExactly(fis, 100);
			int next = fis.read();
			assertEquals("next byte after skipExactly(100) must be payload[100]", 100, next);
		}
	}

	@Test
	public void skipExactlyHandlesZeroCountAsNoOp() throws IOException
	{
		// Boundary case: count == 0 should be a no-op; the next read returns the first byte of the file.
		// Defensive check against a future regression that conflates "skip 0" with EOF.
		byte[] payload = { 0x42, 0x43, 0x44 };
		File file = tmp.newFile("zero.bin");
		Files.write(file.toPath(), payload);
		try (FileInputStream fis = new FileInputStream(file))
		{
			JpegMetadataInjector.skipExactly(fis, 0);
			assertEquals("skipExactly(0) advances zero bytes", 0x42, fis.read());
		}
	}

	@Test
	public void skipExactlyThrowsWhenCountExceedsFileSize() throws IOException
	{
		// FileInputStream.skip on a regular file can position past EOF while reporting the full requested count
		// — the skip loop alone never notices, so the "@throws on EOF before count" contract needs the final
		// position-vs-size check. Skipping 100 bytes of a 16-byte file must throw, not return with the stream
		// stranded past EOF (a silent return here would make the streaming tail copy emit zero bytes and ship a
		// truncated JPEG as a successful save).
		byte[] payload = new byte[16];
		File file = tmp.newFile("pasteof.bin");
		Files.write(file.toPath(), payload);
		try (FileInputStream fis = new FileInputStream(file))
		{
			IOException ex = assertThrows(IOException.class,
				() -> JpegMetadataInjector.skipExactly(fis, 100));
			assertTrue("message should mention premature EOF: " + ex.getMessage(),
				ex.getMessage().contains("Premature EOF"));
		}
	}

	/**
	 * Assemble a re-encoder-shaped JPEG: SOI + the given APP / COM marker run + DQT + a scan whose entropy payload
	 * is a 0..250 rolling byte pattern (never 0xFF, so no stray markers) + EOI. The entropy size drives how many
	 * STREAM_CHUNK_SIZE chunks the streaming tail copy spans.
	 *
	 * @param appMarkers   complete APP / COM segment bytes to place between SOI and DQT
	 * @param entropyBytes number of entropy payload bytes in the scan
	 * @return assembled JPEG bytes
	 * @throws IOException never thrown in practice — declared by the ByteArrayOutputStream composition
	 */
	private static byte[] buildReencoded(byte[] appMarkers, int entropyBytes) throws IOException
	{
		byte[] entropy = new byte[entropyBytes];
		for (int i = 0; i < entropy.length; i++)
		{
			entropy[i] = (byte) (i % 251);
		}
		return JpegFixtures.concat(JpegFixtures.soi(), appMarkers, JpegFixtures.dqtStub(),
			new byte[] { (byte) 0xFF, (byte) 0xDA, 0x00, 0x06,                 // SOS, len 6
				0x01, 0x00, 0x00, 0x00 },                                  // scan header body
			entropy, JpegFixtures.eoi());
	}
}
