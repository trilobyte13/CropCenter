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

		int endBound = findEndBoundary(file);
		int primaryEoiOffset = findPrimaryEoi(file, endBound);
		if (primaryEoiOffset < 0)
		{
			return null;
		}
		return extractBetween(file, primaryEoiOffset, endBound);
	}

	/**
	 * Return the file offset at which to stop the primary-JPEG search — either the
	 * file length, or the byte just past the gain-map's EOI if a Samsung SEFT trailer
	 * is present. The SEFT magic sits at the very end of the file; the SEFH directory
	 * size field only covers the directory, not the data blocks before it, so we scan
	 * backwards from the 8-byte SEFT footer for the last FF D9 instead of relying on
	 * the size field.
	 */
	private static int findEndBoundary(byte[] file)
	{
		int len = file.length;
		if (len < 12
			|| file[len - 4] != 'S' || file[len - 3] != 'E'
			|| file[len - 2] != 'F' || file[len - 1] != 'T')
		{
			return len;
		}

		// Start before the 8-byte footer (4-byte len + 4-byte "SEFT") to skip false matches.
		for (int i = len - 9; i >= 2; i--)
		{
			if ((file[i] & 0xFF) == 0xFF && (file[i + 1] & 0xFF) == 0xD9)
			{
				int endBound = i + 2;
				Log.d(TAG, "SEFT trailer detected, endBound=" + endBound);
				return endBound;
			}
		}
		return len;
	}

	/**
	 * Walk forward through the primary JPEG's markers and return the offset just past
	 * its EOI (FF D9), or -1 if the primary doesn't end cleanly. SOS markers trigger
	 * entropy-coded-data scanning that handles byte stuffing and restart markers, so
	 * progressive JPEGs with multiple SOS segments parse correctly.
	 */
	private static int findPrimaryEoi(byte[] file, int endBound)
	{
		int off = 2; // skip SOI
		while (off < endBound - 1)
		{
			if ((file[off] & 0xFF) != 0xFF)
			{
				off++;
				continue;
			}
			int marker = file[off + 1] & 0xFF;

			if (marker == 0xD9)
			{
				return off + 2;
			}
			if (marker == 0xDA)
			{
				ScanResult scan = scanEntropyCodedData(file, off, endBound);
				if (scan.eoiOffset() >= 0)
				{
					return scan.eoiOffset();
				}
				off = scan.nextMarkerOffset();
				continue;
			}

			// Standalone markers (no length field)
			if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7))
			{
				off += 2;
				continue;
			}

			// Segment with a big-endian u16 length field
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
		return -1;
	}

	/**
	 * Skip past an SOS segment's entropy-coded data, honoring byte-stuff (FF 00) and
	 * restart (FF D0..D7) markers. Returns either the offset of the next real marker
	 * (eoiOffset = -1) or the offset just past the EOI if one is encountered inside
	 * the entropy stream (nextMarkerOffset = -1). Caller checks eoiOffset to
	 * distinguish.
	 */
	private static ScanResult scanEntropyCodedData(byte[] file, int sosOffset, int endBound)
	{
		if (sosOffset + 3 >= endBound)
		{
			return new ScanResult(endBound, -1);
		}
		int sosLen = ((file[sosOffset + 2] & 0xFF) << 8) | (file[sosOffset + 3] & 0xFF);
		int off = sosOffset + 2 + sosLen;

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
				return new ScanResult(-1, off + 2);
			}
			if (next == 0x00 || (next >= 0xD0 && next <= 0xD7))
			{
				off += 2; // byte-stuffed or restart marker
				continue;
			}
			break; // another marker segment — fall through to outer loop
		}
		return new ScanResult(off, -1);
	}

	/**
	 * Outcome of scanning an SOS segment's entropy-coded data. Exactly one field is
	 * meaningful: nextMarkerOffset ≥ 0 with eoiOffset = -1 means "stopped at the next
	 * marker"; eoiOffset ≥ 0 with nextMarkerOffset = -1 means "hit EOI, terminate the
	 * outer walk at this offset". Using a record (instead of the earlier sign-bit
	 * sentinel) makes the two cases explicit.
	 */
	private record ScanResult(int nextMarkerOffset, int eoiOffset)
	{
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
