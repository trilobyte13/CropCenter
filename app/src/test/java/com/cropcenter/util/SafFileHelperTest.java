package com.cropcenter.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tests for SafFileHelper's pure-string SAF document-ID parsing helpers. These directly cover the bug class fixed when
 * root-level files (e.g., "primary:foo.jpg") were being misclassified as opaque-ID and dropping the crash-safe
 * sibling-replace path. The Context- bound parts of SafFileHelper (createDocument, openInputStream) need an Android
 * runtime and aren't tested here — but the parsing chokepoints that decide whether the path is sibling-derivable in the
 * first place are pure functions and well-suited to unit tests.
 */
public class SafFileHelperTest
{
	@Test
	public void lastSegmentSeparatorEndAtVolumeColonForRoot()
	{
		// Root-level file: the volume colon stands in as the segment separator. Returns the index just after
		// the colon so docId.substring(0, end) + child == sibling.
		assertEquals(8, SafPaths.lastSegmentSeparatorEnd("primary:foo.jpg"));
		// Empty filename after the colon — still parsable.
		assertEquals(8, SafPaths.lastSegmentSeparatorEnd("primary:"));
	}

	@Test
	public void lastSegmentSeparatorEndPrefersSlashOverColon()
	{
		// Nested path: slash takes precedence even though a colon is also present. "primary:Pictures/foo.jpg" →
		// after the slash that ends "primary:Pictures".
		String docId = "primary:Pictures/foo.jpg";
		assertEquals("primary:Pictures/".length(), SafPaths.lastSegmentSeparatorEnd(docId));
		String deep = "primary:DCIM/Camera/IMG_0001.jpg";
		assertEquals("primary:DCIM/Camera/".length(), SafPaths.lastSegmentSeparatorEnd(deep));
	}

	@Test
	public void lastSegmentSeparatorEndReturnsMinusOneForOpaqueId()
	{
		// Opaque IDs (some cloud providers) have neither separator. Caller treats this as "can't derive
		// sibling" and falls back to the in-place overwrite path.
		assertEquals(-1, SafPaths.lastSegmentSeparatorEnd("opaqueDriveDocId123"));
		assertEquals(-1, SafPaths.lastSegmentSeparatorEnd(""));
	}

	@Test
	public void parentDocIdOfNestedFileStripsLastSegment()
	{
		assertEquals("primary:Pictures", SafPaths.parentDocIdOf("primary:Pictures/foo.jpg"));
		assertEquals("primary:DCIM/Camera", SafPaths.parentDocIdOf("primary:DCIM/Camera/IMG_0001.jpg"));
	}

	@Test
	public void parentDocIdOfRootFileFallsBackToVolumePrefix()
	{
		// The bug we just fixed: a file at the provider root must resolve to "primary:" as parent (the volume
		// root document), NOT null. ExternalStorage- Provider accepts "primary:" as a valid root document for
		// createDocument.
		assertEquals("primary:", SafPaths.parentDocIdOf("primary:foo.jpg"));
		assertEquals("home:", SafPaths.parentDocIdOf("home:bar.png"));
	}

	@Test
	public void parentDocIdOfReturnsNullForOpaqueId()
	{
		// No slash AND no colon → caller's signal to skip the sibling-replace path entirely and use the
		// narrower in-place fallback.
		assertNull(SafPaths.parentDocIdOf("opaqueDriveDocId123"));
		assertNull(SafPaths.parentDocIdOf(""));
	}

	@Test
	public void parentDocIdOfHandlesColonPrefixWithNoFilename()
	{
		// "primary:" itself — the volume root with no filename. Treat like "no parent available" since the
		// volume root has no parent to look up. The current impl returns the volume prefix (= same as input)
		// because indexOf(':') = 7 > 0. Worth pinning down so any change is intentional.
		assertEquals("primary:", SafPaths.parentDocIdOf("primary:"));
	}

	// ── readbackByteCountFromStream ── Verify the contract documented on readbackByteCount: full match returns
	// expected.length only after EOF is confirmed clean; mismatch / short / trailing each map to a distinct
	// return-value class so callers using strict equality see the mismatch unambiguously. The helper sits
	// package-private so tests can drive it with ByteArrayInputStream subclasses that throw on close/read,
	// exercising error paths without an Android Context.

	@Test
	public void readbackFromStreamFullMatchReturnsExpectedLength() throws IOException
	{
		byte[] expected = { 0x10, 0x20, 0x30, 0x40 };
		InputStream is = new ByteArrayInputStream(expected.clone());
		assertEquals(expected.length, SafFileHelper.readbackByteCountFromStream(is, expected));
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
	public void readbackFromStreamEofCheckThrowReturnsMinusOne() throws IOException
	{
		// Stream serves a clean expected match in one read, then throws on the post-success EOF-check read.
		// Helper's inner EOF-check try/catch returns -1 — the round-5 inner-try contract.
		byte[] expected = { 0x10, 0x20, 0x30, 0x40 };
		// Custom stream: first read fills with expected bytes; second read throws.
		InputStream is = new EofThrowingStream(expected.clone());
		assertEquals(-1L, SafFileHelper.readbackByteCountFromStream(is, expected));
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
