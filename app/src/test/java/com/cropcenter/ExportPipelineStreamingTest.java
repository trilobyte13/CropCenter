package com.cropcenter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.cropcenter.crop.ExportResult;
import com.cropcenter.metadata.JpegMetadataInjector;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Tests for ExportPipeline's streaming chokepoints. writePayloadToStream is the dual-mode payload writer every
 * disk-backed save AND ReplaceStrategy's strategy-A temp write stream through: bytes-mode writes the in-heap array
 * verbatim, tempfile-mode chunk-copies the file byte-identically across multiple STREAM_CHUNK_SIZE chunks plus a short
 * tail, and both modes report the byte count written. streamCompare is the verify chokepoint for every tempfile-mode
 * (large PNG) save and ReplaceStrategy's strategy-B readback: its sentinel classification (exact match, first-mismatch
 * offset, URI-longer-than-tempfile, short EOF, trailing-past-expected) decides whether ReplaceStrategy deletes the
 * placeholder — the user's only verified copy — so a compare-loop regression that reports a corrupted target as
 * verified is a silent data-loss bug.
 */
public final class ExportPipelineStreamingTest
{
	private File tempfile;

	@Test
	public void bytesModeWritesArrayVerbatim() throws IOException
	{
		byte[] payload = patternedBytes(4321);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		long written = ExportPipeline.writePayloadToStream(new ExportResult(payload, false), out);
		assertEquals(payload.length, written);
		assertArrayEquals(payload, out.toByteArray());
	}

	@After
	public void cleanup()
	{
		if (tempfile != null && tempfile.exists())
		{
			tempfile.delete();
		}
	}

	@Test
	public void streamCompareExactMatchReturnsExpected() throws IOException
	{
		// Clean verify: both streams byte-identical across a chunk boundary plus a short tail, EOF
		// together, expected == length. The ONLY classification callers treat as verified is the return
		// value equalling `expected` exactly.
		byte[] payload = patternedBytes(JpegMetadataInjector.STREAM_CHUNK_SIZE + 4321);
		long verified = ExportPipeline.streamCompare(new ByteArrayInputStream(payload),
			new ByteArrayInputStream(payload.clone()), payload.length);
		assertEquals(payload.length, verified);
	}

	@Test
	public void streamCompareMatchingBytesPastExpectedReportTrailing() throws IOException
	{
		// Trailing-past-expected guard: both streams keep matching BEYOND the expected byte count (a
		// stale longer document the provider never truncated, mirrored by a stale tempfile read). The
		// return must exceed `expected` so the equality check fails — returning `expected` here would
		// verify a save whose on-disk length is wrong.
		byte[] payload = patternedBytes(10);
		long verified = ExportPipeline.streamCompare(new ByteArrayInputStream(payload),
			new ByteArrayInputStream(payload.clone()), 6);
		assertEquals(10L, verified);
	}

	@Test
	public void streamCompareReportsFirstMismatchOffset() throws IOException
	{
		// First divergence must be reported at its ABSOLUTE offset (total + i) — the corrupt byte sits in
		// the SECOND 64 KB chunk so the accumulated `total` from chunk 1 is load-bearing. Dropping `total`
		// from the return (or comparing chunk-2 bytes against the tempfile head) would pass any
		// first-chunk fixture while mis-verifying every multi-chunk save.
		byte[] payload = patternedBytes(JpegMetadataInjector.STREAM_CHUNK_SIZE + 100);
		int corruptOffset = JpegMetadataInjector.STREAM_CHUNK_SIZE + 5;
		byte[] corrupted = payload.clone();
		corrupted[corruptOffset] ^= (byte) 0xFF;
		long verified = ExportPipeline.streamCompare(new ByteArrayInputStream(corrupted),
			new ByteArrayInputStream(payload), payload.length);
		assertEquals(corruptOffset, verified);
	}

	@Test
	public void streamCompareShortUriStreamReturnsTotalBelowExpected() throws IOException
	{
		// Short EOF: the saved document EOFs before the tempfile (truncated write). The return is the
		// matched byte count, strictly below `expected`, so the caller's equality check reports the save
		// as unverified.
		byte[] payload = patternedBytes(10);
		byte[] truncated = new byte[6];
		System.arraycopy(payload, 0, truncated, 0, 6);
		long verified = ExportPipeline.streamCompare(new ByteArrayInputStream(truncated),
			new ByteArrayInputStream(payload), payload.length);
		assertEquals(6L, verified);
	}

	@Test
	public void streamCompareUriStreamLongerThanTempfileReportsTrailing() throws IOException
	{
		// The fileN < uriN branch: the saved document serves MORE bytes than the tempfile holds. Return is
		// total + uriN (strictly past the tempfile's length), never `expected` — the save has trailing
		// foreign bytes.
		byte[] tempfilePayload = patternedBytes(6);
		byte[] longerUriPayload = patternedBytes(10);
		long verified = ExportPipeline.streamCompare(new ByteArrayInputStream(longerUriPayload),
			new ByteArrayInputStream(tempfilePayload), tempfilePayload.length);
		assertEquals(10L, verified);
	}

	@Test
	public void tempfileModeChunkCopyIsByteIdentical() throws IOException
	{
		// Two full 64 KB chunks plus a non-aligned tail so the loop's chunk boundaries AND the final short read
		// are both exercised — an off-by-one at either would corrupt the copy.
		byte[] payload = patternedBytes(JpegMetadataInjector.STREAM_CHUNK_SIZE * 2 + 4321);
		tempfile = File.createTempFile("stream-test", ".bin");
		Files.write(tempfile.toPath(), payload);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		long written = ExportPipeline.writePayloadToStream(new ExportResult(null, tempfile, false), out);
		assertEquals(payload.length, written);
		assertArrayEquals(payload, out.toByteArray());
	}

	/**
	 * Non-repeating-per-offset fill so a chunk transposition or overlap produces a detectable mismatch — a constant
	 * fill would let a mis-ordered copy pass byte equality.
	 *
	 * @param length payload size in bytes
	 * @return array where each byte derives from its offset
	 */
	private static byte[] patternedBytes(int length)
	{
		byte[] payload = new byte[length];
		for (int i = 0; i < length; i++)
		{
			payload[i] = (byte) (i * 31 + (i >> 11));
		}
		return payload;
	}
}
