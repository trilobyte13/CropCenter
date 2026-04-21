package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Injects original metadata segments into a re-encoded JPEG. Strips the re-encoder's own APP/COM
 * markers (JFIF, sRGB ICC, etc.) and replaces them with the original segments from the source file.
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
	 */
	public static byte[] inject(byte[] reencoded, List<JpegSegment> segments) throws IOException
	{
		if (reencoded.length < 4
			|| (reencoded[0] & 0xFF) != 0xFF
			|| (reencoded[1] & 0xFF) != 0xD8)
		{
			throw new IOException("Not a valid JPEG");
		}

		// Find where re-encoded image data starts (skip its APP/COM markers)
		int scanStart = 2;
		while (scanStart < reencoded.length - 3)
		{
			if ((reencoded[scanStart] & 0xFF) != 0xFF)
			{
				break;
			}
			int marker = reencoded[scanStart + 1] & 0xFF;
			// Stop at non-APP/COM markers: DQT(DB), SOF(C0-CF), DHT(C4), SOS(DA), etc.
			if (!((marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE))
			{
				break;
			}
			int segLen = ByteBufferUtils.readU16BE(reencoded, scanStart + 2);
			if (segLen < 2)
			{
				break; // malformed segment
			}
			// Defensive: a lying segLen could drive scanStart past EOF, which would make the
			// final out.write(...) call below throw IndexOutOfBoundsException with negative
			// length. If that happens, fall back to skipping nothing.
			if (scanStart + 2L + segLen > reencoded.length)
			{
				Log.w(TAG, "APP segment length " + segLen + " at " + scanStart + " exceeds file; "
					+ "falling back to scanStart=2");
				scanStart = 2;
				break;
			}
			scanStart += 2 + segLen;
		}

		Log.d(TAG, "Skipped " + (scanStart - 2) + " bytes of re-encoder APP markers");

		ByteArrayOutputStream out = new ByteArrayOutputStream(reencoded.length + 65536);

		// SOI
		out.write(0xFF);
		out.write(0xD8);

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
