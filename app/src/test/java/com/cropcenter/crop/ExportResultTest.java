package com.cropcenter.crop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Tests for the ExportResult record's compact-constructor invariants. The record's XOR
 * (bytes-vs-tempfile) and Integer.MAX_VALUE size guards are load-bearing — `ExportPipeline`'s
 * `writePayloadToStream` dispatches on `bytes() != null` and `ReplaceStrategy`'s OOM-fallback
 * casts `size()` to int. A regression that relaxed either invariant would silently mis-dispatch
 * or wrap to negative int respectively; structural tests here keep the record's contract honest.
 */
public final class ExportResultTest
{
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void bytesAndTempfileBothNonNullThrows() throws IOException
	{
		// XOR invariant: rejecting `(bytes, tempfile, _)` blocks the future caller from populating
		// both fields with potentially-different content (e.g., a stale tempfile reference plus
		// fresh bytes). writePayloadToStream branches on bytes() != null, so the bytes-mode wins
		// and the tempfile silently leaks AND ships unknown content.
		File tempFile = tmp.newFile("x.png");
		try (FileOutputStream fos = new FileOutputStream(tempFile))
		{
			fos.write(new byte[] { 1, 2, 3 });
		}
		byte[] bytes = { 4, 5, 6 };
		assertThrows(IllegalArgumentException.class,
			() -> new ExportResult(bytes, tempFile, false));
	}

	@Test
	public void bytesAndTempfileBothNullThrows()
	{
		// XOR invariant: rejecting `(null, null, _)` blocks a future caller from silently returning
		// nothing through ExportResult while the dispatch sites in writePayloadToStream assume one
		// side is populated. Without this guard the bytes-mode branch would attempt
		// `os.write(null)` → NPE deep in the write phase.
		assertThrows(IllegalArgumentException.class,
			() -> new ExportResult(null, null, false));
	}

	@Test
	public void bytesModeSizeReturnsByteLength()
	{
		// Bytes-mode size() is straightforward: byte[] length. Pin so a future refactor that
		// introduces lazy initialization or caching can't drift the answer.
		byte[] payload = new byte[1234];
		ExportResult result = new ExportResult(payload, false);
		assertEquals("size() returns bytes.length in bytes-mode", 1234L, result.size());
		assertEquals(payload, result.bytes());
		assertNull("tempfile is null in bytes-mode", result.tempfile());
		assertFalse(result.hdrAttached());
	}

	@Test
	public void tempfileExceedingIntMaxThrows()
	{
		// Size cap invariant: tempfile.length() > Integer.MAX_VALUE must throw at construction time
		// so ReplaceStrategy.writeReplacementPayload's `(int) encoded.size()` cast in the OOM
		// fallback can't silently truncate. The encode pipelines validate size before
		// constructing the record, so the only way to violate this is via a future caller that
		// skips that check — this test pins the defensive guard.
		File hugeStub = new File("nonexistent-placeholder")
		{
			@Override
			public long length()
			{
				return (long) Integer.MAX_VALUE + 1L;
			}
		};
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> new ExportResult(null, hugeStub, false));
		assertTrue("exception names the offending invariant",
			ex.getMessage().contains("Integer.MAX_VALUE"));
	}

	@Test
	public void tempfileModeSizeReturnsFileLength() throws IOException
	{
		// Tempfile-mode size() reads the file's current length. The cheap stat call avoids loading
		// the file into heap (which is the whole point of tempfile mode for PNG saves).
		File tempFile = tmp.newFile("x.png");
		byte[] payload = new byte[5678];
		try (FileOutputStream fos = new FileOutputStream(tempFile))
		{
			fos.write(payload);
		}
		ExportResult result = new ExportResult(null, tempFile, false);
		assertEquals("size() returns tempfile.length() in tempfile-mode", 5678L, result.size());
		assertNull("bytes is null in tempfile-mode", result.bytes());
		assertEquals(tempFile, result.tempfile());
	}

	@Test
	public void twoArgConstructorNullBytesThrows()
	{
		// 2-arg overload delegates to the compact constructor with tempfile=null. A null bytes
		// then violates the XOR invariant (both null), and the compact ctor throws. Pin that
		// behaviour so the 2-arg shape stays guarded.
		assertThrows(IllegalArgumentException.class,
			() -> new ExportResult(null, false));
	}

	@Test
	public void twoArgConstructorProducesBytesMode()
	{
		// The 2-arg overload `(bytes, hdrAttached)` is the backward-compat shape every JPEG-side
		// and bypass-encode caller still uses. Pin that it produces a bytes-mode result with
		// tempfile=null and the hdrAttached flag correctly propagated.
		byte[] payload = { 1, 2, 3 };
		ExportResult result = new ExportResult(payload, true);
		assertArrayEquals(payload, result.bytes());
		assertNull("2-arg ctor produces tempfile==null", result.tempfile());
		assertTrue("hdrAttached flag propagated", result.hdrAttached());
	}
}
