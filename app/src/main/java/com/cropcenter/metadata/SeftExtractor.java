package com.cropcenter.metadata;

import android.util.Log;

// Extracts the Samsung SEFT trailer from a JPEG file. The SEFT trailer is appended after the last
// JPEG EOI (after gain map if present). Layout: [SEFT data blocks][SEFH directory][4-byte size
// LE][4-byte "SEFT" magic]
public final class SeftExtractor
{
	private static final String TAG = "SeftExtractor";

	private SeftExtractor() {}

	/**
	 * Extract raw SEFT trailer bytes from the file.
	 *
	 * @return SEFT trailer bytes, or null if not present.
	 */
	public static byte[] extract(byte[] file)
	{
		if (file == null || file.length < 12)
		{
			return null;
		}
		int len = file.length;

		// Check for "SEFT" magic at end
		if (file[len - 4] != 'S' || file[len - 3] != 'E'
			|| file[len - 2] != 'F' || file[len - 1] != 'T')
		{
			return null;
		}

		// Find the start of the SEFT trailer by scanning backwards for the last FFD9
		// (which is the gain map's or primary's EOI)
		int trailerStart = -1;
		for (int i = len - 9; i >= 2; i--)
		{
			if ((file[i] & 0xFF) == 0xFF && (file[i + 1] & 0xFF) == 0xD9)
			{
				trailerStart = i + 2;
				break;
			}
		}

		if (trailerStart < 0 || trailerStart >= len)
		{
			return null;
		}

		byte[] trailer = new byte[len - trailerStart];
		System.arraycopy(file, trailerStart, trailer, 0, trailer.length);
		Log.d(TAG, "Extracted SEFT trailer: " + trailer.length + " bytes");
		return trailer;
	}
}
