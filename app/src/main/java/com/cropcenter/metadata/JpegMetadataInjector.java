package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Injects original metadata segments into a re-encoded JPEG. Strips the re-encoder's own APP/COM markers (JFIF, sRGB
 * ICC, etc.) and replaces them with the original segments from the source file.
 */
public final class JpegMetadataInjector
{
	private static final String TAG = "JpegMetadataInjector";
	// Upper bound on the byte[] we read off the head of a file-input JPEG just to find scanStart.
	// JPEG APP / COM markers cluster at the front of the file (per the marker-ordering rules in
	// ITU-T T.81 §B.2.4 — APPn comes before SOF / DHT / SOS); on typical re-encoded outputs the
	// header is under 1 MB even with heavy Samsung metadata. The 2 MB cap covers pathologically
	// large XMP / MakerNote / MPF segments without risking an adversarial input that forces the
	// scan to load the entire 100+ MB primary just to find scanStart.
	private static final int HEAD_READ_LIMIT = 2 * 1024 * 1024;
	// Buffer size for streaming tail-copy operations across the JPEG save pipeline. 64 KB is the
	// FileChannel transferTo / NIO sweet spot on modern Android — large enough to amortise per-syscall
	// overhead, small enough to stay out of the way of any other heap allocations happening
	// concurrently. Public so GainMapComposer.composeFileToFile and CropExporter.appendSeftFileToFile
	// can share one canonical chunk size — when the JPEG streaming primitives move (e.g., a switch to
	// FileChannel.transferTo), this single constant captures the tuning decision.
	public static final int STREAM_CHUNK_SIZE = 64 * 1024;

	private JpegMetadataInjector() {}

	/**
	 * Build a new JPEG: SOI + original metadata segments + image data from re-encoded JPEG.
	 *
	 * @param reencoded  JPEG bytes from Bitmap.compress() (has its own APP markers)
	 * @param segments   original metadata segments to inject
	 * @return new JPEG bytes with original metadata
	 * @throws IOException when reencoded fails JPEG validation (missing SOI, or an APP
	 *                     segment claims a length extending past EOF — Skia bug or
	 *                     byte-stream corruption between encode and inject)
	 */
	public static byte[] inject(byte[] reencoded, List<JpegSegment> segments) throws IOException
	{
		int scanStart = computeScanStart(reencoded);
		Log.d(TAG, "Skipped " + (scanStart - 2) + " bytes of re-encoder APP markers");

		// Compute exact output size up front and allocate a single sized byte[] instead of routing
		// through ByteArrayOutputStream. BAOS's pre-size guess (reencoded.length + 64 KB) is enough
		// for typical metadata but fails for large XMP / Extended XMP / MPF / MakerNote segment lists,
		// causing BAOS to double its internal buffer. On a 108 MB re-encoded primary that doubling
		// allocates a ~216 MB byte[] while the ~108 MB old buffer is still live during the array copy
		// — a peak the Java heap can't satisfy even with android:largeHeap. Single-buffer direct-write
		// skips both the BAOS-grow peak AND the toByteArray copy, cutting peak Java-heap by ~100 MB
		// on 200 MP-class saves. Use long arithmetic for the size sum so a pathological segment list
		// can't wrap int negative; the result must fit in int because byte[] indexing requires it.
		long finalSizeLong = 2L; // SOI marker
		for (JpegSegment seg : segments)
		{
			finalSizeLong += seg.data().length;
		}
		finalSizeLong += (long) reencoded.length - scanStart;
		if (finalSizeLong > Integer.MAX_VALUE)
		{
			throw new IOException("Combined metadata + image data exceeds 2 GB byte[] limit: "
				+ finalSizeLong + " bytes");
		}
		byte[] result = new byte[(int) finalSizeLong];
		int pos = 0;
		result[pos++] = (byte) JpegMarker.PREFIX;
		result[pos++] = (byte) JpegMarker.SOI;
		for (JpegSegment seg : segments)
		{
			byte[] data = seg.data();
			System.arraycopy(data, 0, result, pos, data.length);
			pos += data.length;
		}
		System.arraycopy(reencoded, scanStart, result, pos, reencoded.length - scanStart);
		return result;
	}

	/**
	 * Pure-streaming inject variant: reads the re-encoded JPEG from `inFile` (instead of byte[]) and writes
	 * the metadata-injected output to `outFile`. Eliminates the 100+ MB byte[] readback that the
	 * `byte[] reencoded` variant requires before it can locate scanStart and stream the image data.
	 *
	 * Identifies scanStart by reading only the leading bytes of inFile (JPEG APP/COM markers always sit
	 * before any image data, and typical re-encoded files keep that header under ~1 MB), then opens a
	 * fresh stream positioned at scanStart and copies the tail in chunks to outFile. Peak Java heap
	 * during this entire operation is ~2 MB — the head buffer plus the chunk buffer — independent of
	 * input file size. The 100+ MB primary never materialises as a single byte[].
	 *
	 * @param inFile   re-encoded JPEG bytes on disk (typically the tempfile written by Bitmap.compress)
	 * @param segments original metadata segments to inject
	 * @param outFile  tempfile to write the metadata-injected output to; truncated and overwritten
	 * @throws IOException on invalid JPEG (missing SOI), malformed APP segment, or read/write failure
	 */
	public static void injectFileToFile(File inFile, List<JpegSegment> segments, File outFile)
		throws IOException
	{
		// Read the head into a bounded byte[] just to find scanStart. APP / COM segments cluster at
		// the front; in practice the head is well under 1 MB even on heavily-tagged Samsung captures.
		// HEAD_READ_LIMIT caps the scan so an adversarial input can't force us to load the entire
		// primary just to find scanStart — beyond the cap we bail with the conservative "no APP
		// markers to skip" scanStart of 2 (just past SOI).
		long fileSize = inFile.length();
		int headRead = (int) Math.min(fileSize, HEAD_READ_LIMIT);
		byte[] head = new byte[headRead];
		try (FileInputStream headFis = new FileInputStream(inFile))
		{
			int read = 0;
			while (read < headRead)
			{
				int n = headFis.read(head, read, headRead - read);
				if (n < 0)
				{
					break;
				}
				read += n;
			}
			if (read < headRead)
			{
				// Short read — file shrank mid-call or stream returned EOF early. Use what we got.
				byte[] truncated = new byte[read];
				System.arraycopy(head, 0, truncated, 0, read);
				head = truncated;
			}
		}
		int scanStart = computeScanStart(head);
		// Head-overflow guard. computeScanStart walks APP/COM markers on the bounded `head` buffer;
		// if the markers extend past HEAD_READ_LIMIT, computeScanStart's loop terminates at the head
		// boundary returning a scanStart that points INSIDE an APP segment of the actual file. The tail
		// copy from that offset then concatenates the middle of an APP marker as image data — the
		// segment's `LL LL` length field silently bleeds into the entropy-coded scan, producing a
		// corrupt JPEG that opens to a casual viewer but mis-renders or fails on strict decoders. Treat
		// "scanStart didn't converge inside head" as a hard error so encodePhase routes through
		// "Export failed" rather than shipping silent corruption. inFile size > head.length is the only
		// way to hit this — well-formed re-encoder output has APP segments under a few hundred KB.
		if (head.length == HEAD_READ_LIMIT && scanStart >= head.length - 3)
		{
			throw new IOException("JPEG APP / COM markers exceed " + HEAD_READ_LIMIT
				+ "-byte head budget; scanStart=" + scanStart + " did not converge");
		}
		Log.d(TAG, "Streaming file inject: skipping " + (scanStart - 2)
			+ " bytes of re-encoder APP markers");
		try (FileOutputStream fos = new FileOutputStream(outFile))
		{
			fos.write(JpegMarker.PREFIX);
			fos.write(JpegMarker.SOI);
			for (JpegSegment seg : segments)
			{
				fos.write(seg.data());
			}
			// Stream the image-data tail from inFile starting at scanStart. Position via FileInputStream's
			// skip() — supported by the underlying disk-backed channel — then copy in chunks.
			try (FileInputStream tailFis = new FileInputStream(inFile))
			{
				skipExactly(tailFis, scanStart);
				byte[] buf = new byte[STREAM_CHUNK_SIZE];
				int n;
				while ((n = tailFis.read(buf)) > 0)
				{
					fos.write(buf, 0, n);
				}
			}
			fos.getFD().sync();
		}
	}

	/**
	 * Skip exactly `count` bytes from the stream, treating skip()-returns-0 as "no progress" (Java
	 * idiom: FileInputStream.skip can return 0 even when bytes remain — typically on certain
	 * filesystem states or partial buffering) and falling back to a single read() to force progress.
	 * Only treat read()-returns-negative as true EOF. The naive skip-loop that breaks on `n <= 0`
	 * silently truncates the stream when skip transiently returns 0, leaving the caller's subsequent
	 * read() positioned mid-file — output would be SOI + segments + (some random middle of the file).
	 *
	 * Public so the matching streaming paths in `GainMapComposer.composeFileToFile` and
	 * `CropExporter.injectPngExifFromTiffFileToFile` can share one chokepoint for the skip-then-read
	 * tail-positioning idiom.
	 *
	 * @param fis   stream to advance
	 * @param count number of bytes to skip past
	 * @throws IOException if EOF is hit before `count` bytes have been skipped
	 */
	public static void skipExactly(FileInputStream fis, long count) throws IOException
	{
		long skipped = 0;
		while (skipped < count)
		{
			long n = fis.skip(count - skipped);
			if (n > 0)
			{
				skipped += n;
				continue;
			}
			// skip() returned 0 — force progress with a single-byte read so we either advance or
			// hit a true EOF. Without this nudge, skip() can return 0 indefinitely on some
			// filesystem states (Android scoped-storage FUSE shims observed it intermittently),
			// stranding the caller mid-stream.
			int b = fis.read();
			if (b < 0)
			{
				throw new IOException("Premature EOF skipping to " + count
					+ " (skipped " + skipped + ")");
			}
			skipped++;
		}
	}

	/**
	 * Walk the re-encoded JPEG's leading APP / COM markers and return the offset where image data begins
	 * (the first non-APP / non-COM marker, e.g. DQT, DHT, SOF, SOS). Shared by `inject` and
	 * `injectToFile` so both code paths produce byte-identical output.
	 *
	 * @param reencoded JPEG bytes from Bitmap.compress
	 * @return offset of the first image-data byte (just past the last re-encoder APP / COM segment)
	 * @throws IOException when reencoded is not a valid JPEG (missing SOI, or an APP segment claims a
	 *                     length extending past EOF — Skia bug or byte-stream corruption between encode
	 *                     and inject)
	 */
	private static int computeScanStart(byte[] reencoded) throws IOException
	{
		if (reencoded.length < 4 || (reencoded[0] & 0xFF) != JpegMarker.PREFIX
			|| (reencoded[1] & 0xFF) != JpegMarker.SOI)
		{
			throw new IOException("Not a valid JPEG");
		}
		// Find where re-encoded image data starts (skip its APP/COM markers). JpegMarkerWalker.skipFillBytes
		// handles the `FF FF MARKER` alignment shape some encoders emit.
		int scanStart = 2;
		while (scanStart < reencoded.length - 3)
		{
			if ((reencoded[scanStart] & 0xFF) != 0xFF)
			{
				break;
			}
			int markerByteOff = JpegMarkerWalker.skipFillBytes(reencoded, scanStart, reencoded.length);
			if (markerByteOff < 0)
			{
				break;
			}
			int marker = reencoded[markerByteOff] & 0xFF;
			int afterMarker = markerByteOff + 1;
			// Stop at non-APP/COM markers: DQT(DB), SOF(C0-CF), DHT(C4), SOS(DA), etc.
			if (!((marker >= JpegMarker.APP0 && marker <= JpegMarker.APP_LAST) || marker == JpegMarker.COM))
			{
				break;
			}
			if (afterMarker + 2 > reencoded.length)
			{
				break;
			}
			int segLen = ByteBufferUtils.readU16BE(reencoded, afterMarker);
			if (segLen < 2)
			{
				break; // malformed segment
			}
			// A segLen claiming to extend past EOF means the re-encoded buffer is genuinely malformed
			// (Skia corruption, or damage between encode and injector). Throw so encodePhase surfaces a
			// real "Export failed" toast rather than silently shipping duplicate APP segments (a
			// fallback to scanStart=2 would copy the whole re-encoded image including its own JFIF /
			// sRGB ICC markers).
			long next = (long) afterMarker + segLen;
			if (next > reencoded.length)
			{
				throw new IOException("Re-encoded APP segment at " + scanStart
					+ " claims length " + segLen + " but only " + (reencoded.length - afterMarker)
					+ " bytes remain");
			}
			scanStart = (int) next;
		}
		return scanStart;
	}
}
