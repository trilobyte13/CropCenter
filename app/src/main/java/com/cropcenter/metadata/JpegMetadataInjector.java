package com.cropcenter.metadata;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.List;

/**
 * Injects original metadata segments into a re-encoded JPEG. Strips the re-encoder's own APP/COM markers (JFIF, sRGB
 * ICC, etc.) and replaces them with the original segments from the source file.
 */
public final class JpegMetadataInjector
{
	private static final String TAG = "JpegMetadataInjector";
	// Upper bound on the byte[] we read off the head of a file-input JPEG just to find scanStart. JPEG APP / COM
	// markers cluster at the front of the file (per the marker-ordering rules in ITU-T T.81 §B.2.4 — APPn comes
	// before SOF / DHT / SOS); on typical re-encoded outputs the header is under 1 MB even with heavy Samsung
	// metadata. The 2 MB cap covers pathologically large XMP / MakerNote / MPF segments without risking an
	// adversarial input that forces the scan to load the entire 100+ MB primary just to find scanStart.
	private static final int HEAD_READ_LIMIT = 2 * 1024 * 1024;
	// Buffer size for streaming copy operations across the JPEG save pipeline. 64 KB is the FileChannel
	// transferTo / NIO sweet spot on modern Android — large enough to amortise per-syscall overhead, small enough
	// to stay out of the way of any other heap allocations happening concurrently. The copy loop itself is the
	// copyRemaining chokepoint below; the constant stays public for SafFileHelper.streamCompare, whose
	// two-stream readback comparison needs the chunk size without the copy.
	public static final int STREAM_CHUNK_SIZE = 64 * 1024;

	private JpegMetadataInjector() {}

	/**
	 * Copy every remaining byte from `in` to `os` in STREAM_CHUNK_SIZE chunks, returning the byte count copied.
	 * The single chunked-copy body for the streaming save pipeline (paired with skipExactly for tail copies) so
	 * the `> 0` loop-condition subtlety and any future FileChannel.transferTo migration live in one place.
	 * Consumed by injectFileToFile, GainMapComposer.composeFileToFile, CropExporter.appendSeftFileToFile,
	 * CropExporter.injectPngExifFromTiffFileToFile, and ExportPipeline.writePayloadToStream.
	 *
	 * @param in stream to drain; not closed here — caller owns it
	 * @param os destination; not flushed or synced here — caller owns durability
	 * @return total bytes copied (0 when `in` is already at EOF)
	 * @throws IOException on read or write failure
	 */
	public static long copyRemaining(InputStream in, OutputStream os) throws IOException
	{
		byte[] buf = new byte[STREAM_CHUNK_SIZE];
		long total = 0;
		int n;
		while ((n = in.read(buf)) > 0)
		{
			os.write(buf, 0, n);
			total += n;
		}
		return total;
	}

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

		// Compute exact output size up front and allocate a single sized byte[] instead of routing through
		// ByteArrayOutputStream. BAOS's pre-size guess (reencoded.length + 64 KB) is enough for typical
		// metadata but fails for large XMP / Extended XMP / MPF / MakerNote segment lists, causing BAOS to
		// double its internal buffer. On a 108 MB re-encoded primary that doubling allocates a ~216 MB byte[]
		// while the ~108 MB old buffer is still live during the array copy — a peak the Java heap can't satisfy
		// even with android:largeHeap. Single-buffer direct-write skips both the BAOS-grow peak AND the
		// toByteArray copy, cutting peak Java-heap by ~100 MB on 200 MP-class saves. Use long arithmetic for
		// the size sum so a pathological segment list can't wrap int negative; the result must fit in int
		// because byte[] indexing requires it.
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
	 * Pure-streaming inject variant: reads the re-encoded JPEG from `inFile` (instead of byte[]) and writes the
	 * metadata-injected output to `outFile`. Eliminates the 100+ MB byte[] readback that the `byte[] reencoded`
	 * variant requires before it can locate scanStart and stream the image data.
	 *
	 * Identifies scanStart by reading only the leading bytes of inFile (JPEG APP/COM markers always sit before any
	 * image data, and typical re-encoded files keep that header under ~1 MB), then opens a fresh stream positioned
	 * at scanStart and copies the tail in chunks to outFile. Peak Java heap during this entire operation is ~2 MB —
	 * the head buffer plus the chunk buffer — independent of input file size. The 100+ MB primary never
	 * materialises as a single byte[].
	 *
	 * @param inFile   re-encoded JPEG bytes on disk (typically the tempfile written by Bitmap.compress)
	 * @param segments original metadata segments to inject
	 * @param outFile  tempfile to write the metadata-injected output to; truncated and overwritten
	 * @throws IOException on invalid JPEG (missing SOI), malformed APP segment, or read/write failure
	 */
	public static void injectFileToFile(File inFile, List<JpegSegment> segments, File outFile)
		throws IOException
	{
		// Read the head into a bounded byte[] just to find scanStart. APP / COM segments cluster at the front;
		// in practice the head is well under 1 MB even on heavily-tagged Samsung captures. HEAD_READ_LIMIT caps
		// the scan so an adversarial input can't force us to load the entire primary just to find scanStart —
		// beyond the cap we bail with the conservative "no APP markers to skip" scanStart of 2 (just past SOI).
		long fileSize = inFile.length();
		int headRead = (int) Math.min(fileSize, HEAD_READ_LIMIT);
		byte[] head;
		try (FileInputStream headFis = new FileInputStream(inFile))
		{
			// readNBytes returns exactly the bytes read — shorter than headRead only when the file shrank
			// mid-call or the stream hit EOF early, in which case we use what we got.
			head = headFis.readNBytes(headRead);
		}
		int scanStart = computeScanStart(head);
		// Head-overflow guard. computeScanStart walks APP/COM markers on the bounded `head` buffer; if the
		// markers extend past HEAD_READ_LIMIT, computeScanStart's loop terminates at the head boundary
		// returning a scanStart that points INSIDE an APP segment of the actual file. The tail copy from that
		// offset then concatenates the middle of an APP marker as image data — the segment's `LL LL` length
		// field silently bleeds into the entropy-coded scan, producing a corrupt JPEG that opens to a casual
		// viewer but mis-renders or fails on strict decoders. Treat "scanStart didn't converge inside head" as
		// a hard error so encodePhase routes through "Export failed" rather than shipping silent corruption.
		// inFile size > head.length is the only way to hit this — well-formed re-encoder output has APP
		// segments under a few hundred KB.
		if (head.length == HEAD_READ_LIMIT && scanStart >= head.length - 3)
		{
			throw new IOException("JPEG APP / COM markers exceed " + HEAD_READ_LIMIT
				+ "-byte head budget; scanStart=" + scanStart + " did not converge");
		}
		Log.d(TAG, "Streaming file inject: skipping " + (scanStart - 2) + " bytes of re-encoder APP markers");
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
				copyRemaining(tailFis, fos);
			}
			fos.getFD().sync();
		}
	}

	/**
	 * Skip exactly `count` bytes from the stream, treating skip()-returns-0 as "no progress" (Java idiom:
	 * FileInputStream.skip can return 0 even when bytes remain — typically on certain filesystem states or partial
	 * buffering) and falling back to a single read() to force progress. Only treat read()-returns-negative as true
	 * EOF. The naive skip-loop that breaks on `n <= 0` silently truncates the stream when skip transiently returns
	 * 0, leaving the caller's subsequent read() positioned mid-file — output would be SOI + segments + (some random
	 * middle of the file). skip() may also position PAST EOF while reporting the full requested count (legal for
	 * FileInputStream), so a final position-vs-size check on the underlying channel enforces the EOF contract.
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
			// skip() returned 0 — force progress with a single-byte read so we either advance or hit a true
			// EOF. Without this nudge, skip() can return 0 indefinitely on some filesystem states (Android
			// scoped-storage FUSE shims observed it intermittently), stranding the caller mid-stream.
			int b = fis.read();
			if (b < 0)
			{
				throw new IOException("Premature EOF skipping to " + count
					+ " (skipped " + skipped + ")");
			}
			skipped++;
		}
		// FileInputStream.skip may position past EOF while still reporting the full requested count, so the
		// loop alone can't enforce the EOF contract. Verify the actual file position against the file size:
		// position > size means fewer than `count` real bytes existed to skip past.
		FileChannel channel = fis.getChannel();
		if (channel.position() > channel.size())
		{
			throw new IOException("Premature EOF skipping to " + count + " (position "
				+ channel.position() + " past size " + channel.size() + ")");
		}
	}

	/**
	 * Walk the re-encoded JPEG's leading APP / COM markers and return the offset where image data begins (the first
	 * non-APP / non-COM marker, e.g. DQT, DHT, SOF, SOS). Shared by `inject` and `injectFileToFile` so both code
	 * paths produce byte-identical output.
	 *
	 * @param reencoded JPEG bytes from Bitmap.compress
	 * @return offset of the first image-data byte (just past the last re-encoder APP / COM segment)
	 * @throws IOException when reencoded is not a valid JPEG (missing SOI, or an APP segment claims a
	 *                     length extending past EOF — Skia bug or byte-stream corruption between encode and inject)
	 */
	private static int computeScanStart(byte[] reencoded) throws IOException
	{
		if (reencoded.length < 4 || (reencoded[0] & 0xFF) != JpegMarker.PREFIX
			|| (reencoded[1] & 0xFF) != JpegMarker.SOI)
		{
			throw new IOException("Not a valid JPEG");
		}
		// Find where re-encoded image data starts (skip its APP/COM markers). JpegMarkerWalker.nextHeadSegment
		// handles the `FF FF MARKER` alignment shape some encoders emit, the length read, and the overrun
		// classification.
		int scanStart = 2;
		while (scanStart < reencoded.length - 3)
		{
			JpegMarkerWalker.HeadSegment step = JpegMarkerWalker.nextHeadSegment(reencoded, scanStart,
				reencoded.length);
			if (step.kind() == JpegMarkerWalker.HeadKind.NO_MARKER)
			{
				break;
			}
			int marker = step.marker();
			// Stop at non-APP/COM markers: DQT(DB), SOF(C0-CF), DHT(C4), SOS(DA), standalone markers, etc.
			if (!((marker >= JpegMarker.APP0 && marker <= JpegMarker.APP_LAST) || marker == JpegMarker.COM))
			{
				break;
			}
			if (step.kind() == JpegMarkerWalker.HeadKind.BAD_LENGTH)
			{
				break; // truncated length field or malformed sub-2 segLen
			}
			// A segLen claiming to extend past EOF means the re-encoded buffer is genuinely malformed (Skia
			// corruption, or damage between encode and injector). Throw so encodePhase surfaces a real
			// "Export failed" toast rather than silently shipping duplicate APP segments (a fallback to
			// scanStart=2 would copy the whole re-encoded image including its own JFIF / sRGB ICC markers).
			if (step.kind() == JpegMarkerWalker.HeadKind.OVERRUN)
			{
				throw new IOException("Re-encoded APP segment at " + scanStart
					+ " claims length " + step.segLen() + " but only "
					+ (reencoded.length - (step.markerByteOff() + 1)) + " bytes remain");
			}
			scanStart = step.next();
		}
		return scanStart;
	}
}
