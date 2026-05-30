package com.cropcenter.metadata;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Appends the HDR gain map JPEG after the primary image and updates MPF offsets.
 */
public final class GainMapComposer
{
	/**
	 * Tagged outcome from compose(). Disambiguates the three return shapes that previously had to be inferred
	 * from reference identity on the returned byte[] — a leaky contract that broke once compose started
	 * returning the XMP-patched primary on the MPF-fail path (the new array misled `withGainMap != input`
	 * callers into thinking HDR attached when it hadn't).
	 *
	 * @param bytes        the output JPEG bytes — either the primary verbatim, an XMP-patched primary with
	 *                     no gain map appended, or the full primary + gain map concatenation; callers can't
	 *                     tell the cases apart from the bytes alone
	 * @param hdrAttached  true ONLY when the gain map was successfully appended AND MPF offsets point at it.
	 *                     False on every drop path (no gain map, XmpItemLengthPatcher refused, MpfPatcher
	 *                     failed) regardless of whether `bytes` is the input reference or a fresh allocation
	 */
	public record ComposeResult(byte[] bytes, boolean hdrAttached) {}

	private static final String TAG = "GainMapComposer";
	// Upper bound on the byte[] we load off the primary's head for XMP / MPF patching when streaming
	// compose. JPEG APP segments — including standard XMP (≤ 64 KB), Extended XMP chunks (≤ 64 KB
	// each), MPF (~few hundred bytes), and EXIF with MakerNote (up to a few MB on Samsung captures)
	// — cluster at the front of the file before SOS. 16 MB head comfortably covers every realistic
	// shape while keeping the patch step's peak Java heap to ~32 MB (head + patcher's optional new
	// allocation) rather than ~118 MB on a 200 MP-class primary.
	private static final int HEAD_READ_LIMIT = 16 * 1024 * 1024;

	private GainMapComposer() {}

	/**
	 * Append the gain map to the primary JPEG and fix MPF offsets.
	 *
	 * Three HDR-drop branches return a primary-only JPEG (hdrAttached=false), differing in which primary they
	 * wrap:
	 *  - gainMap null/empty (SDR source that still hit this method) → input `primary` unchanged.
	 *  - XmpItemLengthPatcher.patch returns null (GContainer Item:Length can't be safely rewritten — in
	 *    Extended XMP, straddling a chunk boundary, or an unpatchable standard-XMP segment): shipping the gain
	 *    map with a stale Item:Length silently truncates it in strict decoders (Google's libUltraHdr), so drop
	 *    HDR. bytes = input `primary` unchanged (the patcher's refusal means we can't cleanly rewrite the XMP).
	 *  - MpfPatcher.patch fails (no/malformed MPF, byte-order mismatch, no MPType match, negative offset):
	 *    without this a JPEG ships with gain-map bytes but no MPF entry pointing at them — strict decoders
	 *    (Samsung Revert) reject it, lenient ones render HDR at the wrong offset. bytes = the XMP-patched
	 *    `patched` primary (downstream stripHdrSegments drops the HDR XMP wholesale, but returning `patched`
	 *    keeps the contract self-consistent: the returned XMP matches what's appended — nothing).
	 *
	 * @param primary  the primary JPEG bytes (metadata already injected)
	 * @param gainMap  the gain map JPEG bytes (preserved from original), or null / empty to leave primary as-is
	 * @return tagged result — bytes always set; hdrAttached true ONLY on full success (gain map appended AND MPF
	 *         offsets patched to point at it)
	 */
	public static ComposeResult compose(byte[] primary, byte[] gainMap)
	{
		if (gainMap == null || gainMap.length == 0)
		{
			Log.d(TAG, "Compose skipped: gain map absent");
			return new ComposeResult(primary, false);
		}
		// Patch the GContainer Item:Length attribute in primary's XMP to match the actual gain-map size
		// BEFORE assembly so MpfPatcher's primarySize calculation reflects the post-patch primary length.
		// Without this, strict GContainer-respecting decoders (Google's libUltraHdr is one) slice the gain
		// map by Item:Length and decode a truncated stream — silent HDR-boost drop on a file that's
		// otherwise correct. MpfPatcher's primarySize cascade is preserved
		// because we re-bind primarySize to patched.length below.
		//
		// Null return means Item:Length couldn't be safely rewritten — either it lives in Extended XMP
		// (>64 KB packet form), or it lives in standard XMP but the segment is unpatchable (over-cap
		// segLen, malformed quote, unterminated digit run). The patcher logs the specific reason at
		// the W level before returning null; we drop HDR here rather than ship stale Item:Length.
		byte[] patched = XmpItemLengthPatcher.patch(primary, gainMap.length);
		if (patched == null)
		{
			Log.w(TAG, "Compose dropped gain map: XmpItemLengthPatcher refused the patch. Returning "
				+ "primary verbatim to avoid shipping stale Item:Length.");
			return new ComposeResult(primary, false);
		}
		int primarySize = patched.length;

		byte[] combined = new byte[primarySize + gainMap.length];
		System.arraycopy(patched, 0, combined, 0, primarySize);
		System.arraycopy(gainMap, 0, combined, primarySize, gainMap.length);

		boolean mpfPatched = MpfPatcher.patch(combined, primarySize);
		if (!mpfPatched)
		{
			Log.w(TAG, "Compose dropped gain map: MPF patch failed (no MPF segment, "
				+ "or malformed/unsupported MPF). Returning patched primary verbatim to "
				+ "avoid shipping orphaned HDR bytes.");
			// Return `patched` (the XMP-Item:Length-cleaned primary) rather than `primary` so the
			// HDR-drop output's XMP no longer carries a stale GContainer Item:Length pointing at a
			// gain map that was never appended. hdrAttached=false signals to the caller (CropExporter)
			// to run stripHdrSegments and toast "[HDR dropped]" — the previous reference-inequality
			// detection broke when `patched` was a freshly-allocated array distinct from `primary`,
			// which made `withGainMap != withFullMeta` true on this drop path and silently shipped
			// a JPEG that still claimed HDR but carried no gain map.
			return new ComposeResult(patched, false);
		}

		Log.d(TAG, "Composed: primary=" + primarySize + " + gainMap=" + gainMap.length
			+ " = " + combined.length + " (MPF patched)");
		return new ComposeResult(combined, true);
	}

	/**
	 * Streaming compose: reads the primary JPEG from `inFile`, writes the composed output (patched primary +
	 * gain map appended) to `outFile`, avoiding the ~120 MB `combined` byte[] the byte[] variant needs by
	 * streaming the primary's image-data tail straight from disk. Peak heap ~32 MB + the gainMap reference,
	 * independent of inFile size (the naive byte[] path peaks ~384 MB on a big primary).
	 *
	 * Strategy: load only the first HEAD_READ_LIMIT bytes (JPEG APP segments cluster before SOS) — feeding
	 * XmpItemLengthPatcher / MpfPatcher just the head is byte-identical to the byte[] variant since they
	 * operate only within APP segments. Write the patched head, stream-copy the tail from the original head
	 * offset, then append the gain map. Byte-identical to compose(Files.readAllBytes(inFile), gainMap).
	 *
	 * Drop-path mirrors compose: XmpItemLengthPatcher refusal → verbatim stream-copy, hdrAttached=false;
	 * MpfPatcher failure → patched head + tail (no gain map), hdrAttached=false.
	 *
	 * @param inFile  primary JPEG on disk (typically the injected tempfile); never null
	 * @param gainMap gain map JPEG bytes to append; null / empty → stream-copy with hdrAttached=false
	 * @param outFile destination tempfile; truncated and overwritten on every call
	 * @return true when the gain map was appended AND MPF offsets patched; false on any drop path
	 * @throws IOException on read / write failure
	 */
	public static boolean composeFileToFile(File inFile, byte[] gainMap, File outFile) throws IOException
	{
		if (gainMap == null || gainMap.length == 0)
		{
			Log.d(TAG, "Streaming compose skipped: gain map absent — copying primary unchanged");
			Files.copy(inFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return false;
		}
		long inFileSize = inFile.length();
		int headTarget = (int) Math.min(inFileSize, HEAD_READ_LIMIT);
		byte[] head = new byte[headTarget];
		try (FileInputStream fis = new FileInputStream(inFile))
		{
			int read = 0;
			while (read < headTarget)
			{
				int n = fis.read(head, read, headTarget - read);
				if (n < 0)
				{
					break;
				}
				read += n;
			}
			if (read < headTarget)
			{
				byte[] truncated = new byte[read];
				System.arraycopy(head, 0, truncated, 0, read);
				head = truncated;
			}
		}
		long originalHeadSize = head.length;
		byte[] patchedHead = XmpItemLengthPatcher.patch(head, gainMap.length);
		if (patchedHead == null)
		{
			Log.w(TAG, "Streaming compose: XmpItemLengthPatcher refused — copying primary unchanged");
			Files.copy(inFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return false;
		}
		long tailSize = inFileSize - originalHeadSize;
		long fullPrimarySize = (long) patchedHead.length + tailSize;
		if (fullPrimarySize > Integer.MAX_VALUE)
		{
			throw new IOException("Primary size exceeds int range after XMP patch: " + fullPrimarySize);
		}
		// Pass gainMap.length EXPLICITLY: patchedHead is only the primary's APP-marker head (a few KB to
		// a few MB), so the `jpeg.length - primarySize` derivation used by the 2-arg overload would be
		// negative when patchedHead.length < primarySize and write a garbage u32 into the MPF entry's
		// size field. Lenient decoders fall back to the XMP hdrgm marker so HDR still renders, but the
		// MPF table itself ships corrupt — strict decoders that trust MPF would reject the gain map.
		boolean mpfPatched = MpfPatcher.patch(patchedHead, (int) fullPrimarySize, gainMap.length);
		try (FileOutputStream fos = new FileOutputStream(outFile))
		{
			fos.write(patchedHead);
			if (tailSize > 0)
			{
				try (FileInputStream tailFis = new FileInputStream(inFile))
				{
					// JpegMetadataInjector.skipExactly handles FileInputStream.skip() transiently
					// returning 0 — the previous loop-and-break-on-zero idiom silently truncated
					// the stream and shipped corrupted bytes when skip stalled. See helper Javadoc.
					JpegMetadataInjector.skipExactly(tailFis, originalHeadSize);
					byte[] buf = new byte[JpegMetadataInjector.STREAM_CHUNK_SIZE];
					int n;
					while ((n = tailFis.read(buf)) > 0)
					{
						fos.write(buf, 0, n);
					}
				}
			}
			if (mpfPatched)
			{
				fos.write(gainMap);
			}
			else
			{
				Log.w(TAG, "Streaming compose: MpfPatcher failed — shipping XMP-patched primary "
					+ "without gain map (hdrAttached=false)");
			}
			fos.getFD().sync();
		}
		if (mpfPatched)
		{
			Log.d(TAG, "Streaming composed: primary=" + fullPrimarySize + " + gainMap=" + gainMap.length
				+ " (MPF patched)");
		}
		return mpfPatched;
	}
}
