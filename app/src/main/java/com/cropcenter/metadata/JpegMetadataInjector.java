package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Injects original metadata segments into a re-encoded JPEG. Strips the re-encoder's own APP/COM markers (JFIF, sRGB
 * ICC, etc.) and replaces them with the original segments from the source file.
 */
public final class JpegMetadataInjector
{
	private static final String TAG = "JpegMetadataInjector";

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

		Log.d(TAG, "Skipped " + (scanStart - 2) + " bytes of re-encoder APP markers");

		ByteArrayOutputStream out = new ByteArrayOutputStream(reencoded.length + 65536);

		out.write(JpegMarker.PREFIX);
		out.write(JpegMarker.SOI);

		// Original metadata segments
		for (JpegSegment seg : segments)
		{
			out.write(seg.data());
		}

		// Image data (DQT, SOF, DHT, SOS, entropy, EOI)
		out.write(reencoded, scanStart, reencoded.length - scanStart);

		return out.toByteArray();
	}
}
