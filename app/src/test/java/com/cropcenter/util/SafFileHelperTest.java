package com.cropcenter.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tests for SafFileHelper.readbackByteCountFromStream — the post-write read-back checker that distinguishes a clean
 * full match (returns expected.length only after EOF), a divergence offset on mismatch, a short stream return, and the
 * overflow / EOF-check error paths. Pure-string SafPaths parsing chokepoints (parentDocIdOf,
 * lastSegmentSeparatorEnd, hasImageSignature) are covered in SafPathsTest; the Context-bound parts of SafFileHelper
 * (createDocument, openInputStream) need an Android runtime and aren't tested here.
 */
public final class SafFileHelperTest
{
	@Test
	public void readbackFromStreamEofCheckThrowReturnsMinusOne() throws IOException
	{
		// Stream serves a clean expected match in one read, then throws on the post-success EOF-check read.
		// Helper's inner EOF-check try/catch returns -1 — the inner-try contract.
		byte[] expected = { 0x10, 0x20, 0x30, 0x40 };
		// Custom stream: first read fills with expected bytes; second read throws.
		InputStream is = new EofThrowingStream(expected.clone());
		assertEquals(-1L, SafFileHelper.readbackByteCountFromStream(is, expected));
	}

	@Test
	public void readbackFromStreamFullMatchReturnsExpectedLength() throws IOException
	{
		byte[] expected = { 0x10, 0x20, 0x30, 0x40 };
		InputStream is = new ByteArrayInputStream(expected.clone());
		assertEquals(expected.length, SafFileHelper.readbackByteCountFromStream(is, expected));
	}

	@Test
	public void readbackFromStreamMidReadOverflowReturnsTotalPlusN() throws IOException
	{
		// Stream serves a single read returning more bytes than expected.length — the helper's `total + n >
		// expected.length` branch fires before the EOF-check branch ever triggers. Returns total + n.
		byte[] expected = { 0x10, 0x20 };
		byte[] actual   = { 0x10, 0x20, 0x30, 0x40 };  // 4 bytes in one read
		InputStream is = new ByteArrayInputStream(actual);
		// total starts at 0, n returns 4, total + n (4) > expected.length (2) → returns 0 + 4 = 4.
		assertEquals(4L, SafFileHelper.readbackByteCountFromStream(is, expected));
	}

	@Test
	public void readbackFromStreamMismatchReturnsDivergenceOffset() throws IOException
	{
		byte[] expected = { 0x10, 0x20, 0x30, 0x40 };
		byte[] actual   = { 0x10, 0x20, (byte) 0xFF, 0x40 };
		InputStream is = new ByteArrayInputStream(actual);
		// Divergence at byte 2; expected return is 2 (the offset where mismatch was first observed within this
		// read call's `total + i` accounting).
		assertEquals(2, SafFileHelper.readbackByteCountFromStream(is, expected));
	}

	@Test
	public void readbackFromStreamShortStreamReturnsBytesRead() throws IOException
	{
		byte[] expected = { 0x10, 0x20, 0x30, 0x40 };
		byte[] actual   = { 0x10, 0x20 };  // only 2 bytes available
		InputStream is = new ByteArrayInputStream(actual);
		assertEquals(2L, SafFileHelper.readbackByteCountFromStream(is, expected));
	}

	@Test
	public void readbackFromStreamTrailingBytesReturnsOverflow() throws IOException
	{
		// Stream serves expected.length matching bytes plus extras after — covers the EOF-check branch (`if
		// (trailing > 0)`).
		byte[] expected = { 0x10, 0x20, 0x30, 0x40 };
		// 4 expected bytes followed by 3 stale trailing bytes. ByteArrayInputStream's underlying read fills the
		// 8KiB buffer in a single call, but we want the helper's EOF-check branch (post-success-match) to fire
		// — so we craft a stream that returns the expected bytes in chunks small enough that total reaches
		// expected.length BEFORE the trailing bytes are read.
		byte[] withTrailing = { 0x10, 0x20, 0x30, 0x40, 0x55, 0x66, 0x77 };
		InputStream chunked = new ChunkedStream(withTrailing, expected.length);
		// First read returns the 4 matching bytes; total reaches expected.length and the EOF-check
		// `is.read(buf)` returns 3 trailing bytes. Helper returns expected.length + trailing = 4 + 3 = 7.
		assertEquals(7L, SafFileHelper.readbackByteCountFromStream(chunked, expected));
	}

	/**
	 * ByteArrayInputStream subclass that returns at most `chunkSize` bytes per read(byte[]) call. Lets a test force
	 * the helper through the post-success EOF-check branch by ensuring `total` reaches expected.length on a chunked
	 * read rather than on the same read that overflows.
	 */
	private static final class ChunkedStream extends ByteArrayInputStream
	{
		private final int chunkSize;

		ChunkedStream(byte[] buf, int chunkSize)
		{
			super(buf);
			this.chunkSize = chunkSize;
		}

		@Override
		public int read(byte[] b)
		{
			return read(b, 0, Math.min(chunkSize, b.length));
		}
	}

	/**
	 * Stream that returns the buffered bytes on the first read, then throws IOException on every subsequent read.
	 * Used to exercise the helper's EOF-check try/catch branch where a successful comparison is followed by a
	 * provider-side error before EOF can be confirmed.
	 */
	private static final class EofThrowingStream extends ByteArrayInputStream
	{
		EofThrowingStream(byte[] buf)
		{
			super(buf);
		}

		@Override
		public int read(byte[] b) throws IOException
		{
			if (super.available() == 0)
			{
				throw new IOException("simulated EOF-check failure");
			}
			return read(b, 0, b.length);
		}
	}
}
