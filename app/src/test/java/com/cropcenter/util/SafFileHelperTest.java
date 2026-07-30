package com.cropcenter.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tests for SafFileHelper.readbackByteCountFromStream — the post-write read-back checker that distinguishes a clean
 * full match (returns expected.length only after EOF), a divergence offset on mismatch, a short stream return, and the
 * overflow / EOF-check error paths — plus readExactSnapshot (the direct-read torn-file guard: refuse a stream that
 * disagrees with the File.length() snapshot in either direction) and the isUnderStorageRoot / canonicalUnderStorage
 * trust gates that anchor provider-supplied _data paths under /storage/ before a direct MES-backed read/write.
 * Pure-string SafPaths parsing chokepoints (parentDocIdOf, lastSegmentSeparatorEnd, hasImageSignature) are covered in
 * SafPathsTest; the Context-bound parts of SafFileHelper (createDocument, openInputStream) need an Android runtime and
 * aren't tested here.
 */
public final class SafFileHelperTest
{
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

	@Test
	public void buildExternalStorageDocIdRejectsTraversalSegment()
	{
		// SafPaths.hasParentTraversalSegment must reject ".." anywhere in the relative path; a caller passing
		// new File(root, "subdir/../../escape.jpg") could otherwise produce a docId that resolves outside the
		// volume.
		assertTrue(SafFileHelper.buildExternalStorageDocId(
			"/storage/emulated/0/subdir/../../escape.jpg", "/storage/emulated/0").isEmpty());
	}

	@Test
	public void buildExternalStorageDocIdReturnsEmptyForNullPath()
	{
		assertTrue(SafFileHelper.buildExternalStorageDocId(null, "/storage/emulated/0").isEmpty());
	}

	@Test
	public void buildExternalStorageDocIdReturnsEmptyForNullRoot()
	{
		assertTrue(SafFileHelper.buildExternalStorageDocId("/storage/emulated/0/Pictures/foo.jpg", null)
			.isEmpty());
	}

	@Test
	public void buildExternalStorageDocIdReturnsEmptyForPathOutsideRoot()
	{
		// Secondary volume / SD card path — must reject so the merged save dialog falls back to SAF.
		assertTrue(SafFileHelper.buildExternalStorageDocId(
			"/storage/AAAA-BBBB/Pictures/foo.jpg", "/storage/emulated/0").isEmpty());
	}

	@Test
	public void buildExternalStorageDocIdReturnsEmptyForRootItself()
	{
		// rootPath alone doesn't end in "/" so startsWith(rootPath + "/") fails — rejection is correct (the
		// root volume isn't a valid save target; the picker UI shouldn't allow it but the helper must reject
		// defensively).
		assertTrue(SafFileHelper.buildExternalStorageDocId("/storage/emulated/0", "/storage/emulated/0")
			.isEmpty());
	}

	@Test
	public void buildExternalStorageDocIdReturnsPrimaryDocIdForNestedFile()
	{
		// Happy path — path /storage/emulated/0/Pictures/foo.jpg → "primary:Pictures/foo.jpg".
		assertEquals("primary:Pictures/foo.jpg", SafFileHelper.buildExternalStorageDocId(
				"/storage/emulated/0/Pictures/foo.jpg", "/storage/emulated/0").orElseThrow());
	}

	@Test
	public void canonicalUnderStorageAcceptsCleanMediaPath()
	{
		// Real media path — must pass so the direct (GPS-preserving) read/write still runs.
		File f = new File("/storage/emulated/0/DCIM/Camera/20240101_120000.jpg");
		assertEquals(f, SafFileHelper.canonicalUnderStorage(f).orElseThrow());
	}

	@Test
	public void canonicalUnderStorageAcceptsRemovableVolumePath()
	{
		// Removable-volume _data (SD card / USB) also lives under /storage/ — must pass so the media.documents
		// image:NNN branch keeps its direct read on secondary volumes.
		File f = new File("/storage/AAAA-BBBB/DCIM/Camera/20240101_120000.jpg");
		assertEquals(f, SafFileHelper.canonicalUnderStorage(f).orElseThrow());
	}

	@Test
	public void canonicalUnderStorageRejectsNull()
	{
		assertTrue(SafFileHelper.canonicalUnderStorage(null).isEmpty());
	}

	@Test
	public void canonicalUnderStorageRejectsOutsideStorage()
	{
		// Path that anchors elsewhere entirely — must be rejected so the caller uses the SAF stream.
		assertTrue(SafFileHelper.canonicalUnderStorage(new File("/data/data/com.victim/db.sqlite")).isEmpty());
	}

	@Test
	public void canonicalUnderStorageRejectsStorageRootTraversal()
	{
		// The attack shape a crafted MediaStore _data row would take through getFilePathAndId's media.documents
		// image:NNN branch: prefix-anchored at /storage/ but escaping immediately via a root-level ".."
		// segment. The gate must reject it before the direct FileInputStream.
		assertTrue(SafFileHelper.canonicalUnderStorage(new File("/storage/../data/local/tmp/evil.jpg"))
			.isEmpty());
	}

	@Test
	public void canonicalUnderStorageRejectsTraversalEscape()
	{
		// The injection this gate exists for: a _data / raw path that prefix-matches /storage/ but walks out
		// via ".." segments. Lexical segment check rejects it before any direct I/O.
		assertTrue(SafFileHelper.canonicalUnderStorage(
			new File("/storage/emulated/0/../../data/data/com.victim/db.sqlite")).isEmpty());
	}

	@Test
	public void isTrustedFileAuthorityAcceptsPlatformProviders()
	{
		// MediaStore + the three SAF document providers are the only authorities whose _data is trusted for
		// direct File I/O.
		assertTrue(SafFileHelper.isTrustedFileAuthority("media"));
		assertTrue(SafFileHelper.isTrustedFileAuthority("com.android.externalstorage.documents"));
		assertTrue(SafFileHelper.isTrustedFileAuthority("com.android.providers.downloads.documents"));
		assertTrue(SafFileHelper.isTrustedFileAuthority("com.android.providers.media.documents"));
	}

	@Test
	public void isTrustedFileAuthorityRejectsNull()
	{
		assertFalse(SafFileHelper.isTrustedFileAuthority(null));
	}

	@Test
	public void isTrustedFileAuthorityRejectsThirdPartyProvider()
	{
		// A hostile / unknown DocumentsProvider must NOT have its _data trusted for direct I/O — this is the
		// gate that forces the access-checked SAF stream fallback for it.
		assertFalse(SafFileHelper.isTrustedFileAuthority("com.evil.app.documents"));
		assertFalse(SafFileHelper.isTrustedFileAuthority("com.android.providers.media"));
	}

	@Test
	public void isUnderStorageRootAcceptsPrimaryMediaPath()
	{
		// Real camera _data — must pass so the direct read/write path (GPS-preserving) still runs.
		assertTrue(SafFileHelper.isUnderStorageRoot("/storage/emulated/0/DCIM/Camera/20240101_120000.jpg"));
	}

	@Test
	public void isUnderStorageRootAcceptsRemovableVolumePath()
	{
		// SD-card / USB volume media also lives under /storage/ — must pass.
		assertTrue(SafFileHelper.isUnderStorageRoot("/storage/AAAA-BBBB/Pictures/foo.jpg"));
	}

	@Test
	public void isUnderStorageRootRejectsAppPrivatePath()
	{
		// The attack this gate exists for: a malicious Share/VIEW provider returning a _data column pointing
		// into another app's private dir. With MANAGE_EXTERNAL_STORAGE a direct read/write would otherwise
		// reach it; rejection forces the access-checked SAF stream fallback.
		assertFalse(SafFileHelper.isUnderStorageRoot("/data/data/com.victim/databases/secrets.db"));
	}

	@Test
	public void isUnderStorageRootRejectsNull()
	{
		assertFalse(SafFileHelper.isUnderStorageRoot(null));
	}

	@Test
	public void isUnderStorageRootRejectsSneakyPrefix()
	{
		// "/storageX/..." shares the literal prefix "/storage" but isn't under the "/storage/" root — the
		// trailing slash in the constant prevents a sibling-dir bypass.
		assertFalse(SafFileHelper.isUnderStorageRoot("/storageX/evil/foo.jpg"));
	}

	@Test
	public void readExactSnapshotRefusesShortStream() throws IOException
	{
		// One direction of snapshot disagreement: the stream ends before the snapshot length (the file
		// shrank, or the snapshot was stale) — null refusal, caller falls back to the SAF stream.
		byte[] shrunk = { 0x10, 0x20 };
		assertNull("short stream must refuse the snapshot read",
			SafFileHelper.readExactSnapshot(new ByteArrayInputStream(shrunk), 4, "pin"));
	}

	@Test
	public void readExactSnapshotRefusesStreamWithTrailingByte() throws IOException
	{
		// The torn direct-read guard: readNBytes returning exactly the snapshot length proves nothing about
		// growth, so a stream still yielding data after the exact-length read (a concurrent writer appended
		// past the File.length() snapshot — Samsung post-processing writing the gain-map / SEFT appendix)
		// must refuse the result. A null return sends the caller to the SAF stream fallback, which reads to
		// EOF at its own read time instead of installing a valid-prefix JPEG minus its appendix.
		byte[] grown = { 0x10, 0x20, 0x30, 0x40, 0x50 }; // one byte past the 4-byte snapshot
		assertNull("grown stream must refuse the snapshot read",
			SafFileHelper.readExactSnapshot(new ByteArrayInputStream(grown), 4, "pin"));
	}

	@Test
	public void readExactSnapshotReturnsBytesOnExactLength() throws IOException
	{
		// Clean case: the stream holds exactly the snapshot's byte count — the one-byte EOF probe sees EOF
		// and the read returns the full payload.
		byte[] exact = { 0x10, 0x20, 0x30, 0x40 };
		byte[] result = SafFileHelper.readExactSnapshot(new ByteArrayInputStream(exact), 4, "pin");
		assertArrayEquals("exact-length stream must return the full payload", exact, result);
	}

	@Test
	public void readbackFromStreamCrossChunkMismatchReturnsAbsoluteOffset() throws IOException
	{
		// Chunked reads are the norm in production (8 KiB buffer vs multi-MB JPEGs), so the mismatch
		// accounting must be ABSOLUTE — `expected[(int) total + i]` and `return total + i` both depend on
		// the accumulated `total` from earlier chunks. Every other mismatch fixture diverges inside the
		// first read (total == 0), so dropping `total` from either expression would still pass them while
		// comparing chunk-2 bytes against the file head — the exact regression that could verify a corrupt
		// repeated-content save as clean. Chunk size 4 over 8 bytes with the corrupt byte at offset 5 puts
		// the divergence in the SECOND chunk at in-chunk index 1: return must be total (4) + i (1) = 5.
		byte[] expected = { 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, (byte) 0x80 };
		byte[] actual = expected.clone();
		actual[5] = (byte) 0xFF;
		InputStream chunked = new ChunkedStream(actual, 4);
		assertEquals(5L, SafFileHelper.readbackByteCountFromStream(chunked, expected));
	}

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
}
