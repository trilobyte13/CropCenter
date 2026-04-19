package com.cropcenter.metadata;

import android.util.Log;

/**
 * Extracts the HDR gain map from a Samsung Ultra HDR JPEG file.
 * The gain map is a secondary JPEG stored between the primary image's EOI
 * and any trailing data (e.g., Samsung SEFT trailer).
 *
 * File layout:
 *   [primary JPEG FFD8...FFD9][gain map JPEG FFD8...FFD9][SEFT data blocks][SEFH][len][SEFT]
 */
public final class GainMapExtractor
{
	private static final String TAG = "GainMapExtractor";

	private GainMapExtractor() {}

	/**
	 * Extract the gain map JPEG from the raw file bytes.
	 *
	 * @return gain map JPEG bytes (starting with FFD8), or null if not found.
	 */
	public static byte[] extract(byte[] file)
	{
		if (file == null || file.length < 10)
		{
			return null;
		}

		int len = file.length;

		// Find end boundary: skip Samsung SEFT trailer if present.
		// SEFT layout: [...][SEFH directory][4-byte SEFH-size LE][4-byte "SEFT" magic]
		// The SEFH-size field only covers the SEFH directory, NOT the data blocks before it.
		// So we scan backwards for the last FF D9 (gain map's EOI) instead.
		int endBound = len;
		if (len >= 12
			&& file[len - 4] == 'S' && file[len - 3] == 'E'
			&& file[len - 2] == 'F' && file[len - 1] == 'T')
		{
			// Start before the 8-byte footer (4-byte len + 4-byte "SEFT") to skip false matches
			for (int i = len - 9; i >= 2; i--)
			{
				if ((file[i] & 0xFF) == 0xFF && (file[i + 1] & 0xFF) == 0xD9)
				{
					endBound = i + 2;
					break;
				}
			}
			Log.d(TAG, "SEFT trailer detected, endBound=" + endBound);
		}

		// Walk forward through primary JPEG markers to find its EOI
		int off = 2; // skip SOI
		while (off < endBound - 1)
		{
			if ((file[off] & 0xFF) != 0xFF)
			{
				off++;
				continue;
			}
			int marker = file[off + 1] & 0xFF;

			// EOI — this is the primary's end
			if (marker == 0xD9)
			{
				return extractBetween(file, off + 2, endBound);
			}

			// SOS — scan entropy-coded data (handles progressive JPEGs with multiple SOS)
			if (marker == 0xDA)
			{
				if (off + 3 >= endBound)
				{
					break;
				}
				int sosLen = ((file[off + 2] & 0xFF) << 8) | (file[off + 3] & 0xFF);
				off += 2 + sosLen;

				while (off < endBound - 1)
				{
					if ((file[off] & 0xFF) != 0xFF)
					{
						off++;
						continue;
					}
					int next = file[off + 1] & 0xFF;
					if (next == 0xD9)
					{
						return extractBetween(file, off + 2, endBound);
					}
					if (next == 0x00)
					{
						off += 2; // byte-stuffed
						continue;
					}
					if (next >= 0xD0 && next <= 0xD7)
					{
						off += 2; // restart marker
						continue;
					}
					break; // another marker segment — fall through to outer loop
				}
				continue; // outer loop handles the next marker (DHT, SOS for progressive, etc.)
			}

			// Standalone markers
			if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7))
			{
				off += 2;
				continue;
			}

			// Skip segment by length
			if (off + 3 < endBound)
			{
				int segLen = ((file[off + 2] & 0xFF) << 8) | (file[off + 3] & 0xFF);
				off += 2 + segLen;
			}
			else
			{
				off += 2;
			}
		}
		return null;
	}

	private static byte[] extractBetween(byte[] file, int primaryEnd, int endBound)
	{
		if (primaryEnd >= endBound || primaryEnd + 1 >= file.length)
		{
			return null;
		}
		int gmLen = endBound - primaryEnd;
		if (gmLen < 4)
		{
			return null;
		}
		// Verify it starts with JPEG SOI
		if ((file[primaryEnd] & 0xFF) != 0xFF || (file[primaryEnd + 1] & 0xFF) != 0xD8)
		{
			return null;
		}
		byte[] gainMap = new byte[gmLen];
		System.arraycopy(file, primaryEnd, gainMap, 0, gmLen);
		Log.d(TAG, "Extracted gain map: " + gmLen + " bytes after primary EOI");
		return gainMap;
	}
}
