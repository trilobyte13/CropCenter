package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

/**
 * Patches MPF (Multi-Picture Format) APP2 offsets in a JPEG byte array. After re-encoding the
 * primary image, the gain map's offset changes.
 *
 * MPF APP2 structure:
 *   FF E2 [len] "MPF\0" [MP Endian II/MM] [TIFF IFD] [MP Entries]
 *
 * All offsets in MP Entries are relative to the MP Endian field position.
 */
public final class MpfPatcher
{
	private static final String TAG = "MpfPatcher";

	private MpfPatcher() {}

	/**
	 * Patch MPF offsets in the assembled JPEG (primary + gain map concatenated).
	 *
	 * @param jpeg         the complete byte array: [primary JPEG][gain map JPEG]
	 * @param primarySize  byte offset where the gain map starts (= primary JPEG size)
	 * @return true if MPF was found and patched, false otherwise.
	 */
	public static boolean patch(byte[] jpeg, int primarySize)
	{
		int off = 2; // skip SOI
		while (off < jpeg.length - 8)
		{
			if ((jpeg[off] & 0xFF) != 0xFF)
			{
				break;
			}
			int marker = jpeg[off + 1] & 0xFF;

			if (marker == 0xDA || marker == 0xD9)
			{
				break; // SOS or EOI — stop
			}
			if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7))
			{
				off += 2;
				continue;
			}

			int segLen = ByteBufferUtils.readU16BE(jpeg, off + 2);

			// Check for MPF APP2: FF E2 + "MPF\0"
			if (marker == 0xE2 && segLen > 8
				&& jpeg[off + 4] == 'M' && jpeg[off + 5] == 'P'
				&& jpeg[off + 6] == 'F' && jpeg[off + 7] == 0)
			{
				int mpfStart = off + 8; // position of MP Endian field
				if (mpfStart + 8 > jpeg.length)
				{
					return false;
				}
				boolean isLittleEndian = jpeg[mpfStart] == 0x49; // 'I' = little-endian

				// IFD offset (relative to mpfStart)
				long ifdOffRel = ByteBufferUtils.readU32(jpeg, mpfStart + 4, isLittleEndian);
				int ifdOff = (int) (mpfStart + ifdOffRel);
				if (ifdOff < mpfStart || ifdOff + 2 > jpeg.length)
				{
					return false;
				}
				int entryCount = ByteBufferUtils.readU16(jpeg, ifdOff, isLittleEndian);

				for (int i = 0; i < entryCount; i++)
				{
					int entryOffset = ifdOff + 2 + i * 12;
					if (entryOffset + 12 > jpeg.length)
					{
						break;
					}
					int tag = ByteBufferUtils.readU16(jpeg, entryOffset, isLittleEndian);

					// Tag 0xB002 = MP Entry
					if (tag == 0xB002)
					{
						return patchMpEntry(jpeg, primarySize, mpfStart,
							entryOffset, isLittleEndian);
					}
				}
				return false; // MPF found but no MP Entry tag
			}
			off += 2 + segLen;
		}
		return false; // no MPF segment
	}

	private static boolean patchMpEntry(byte[] jpeg, int primarySize, int mpfStart,
		int entryTagOff, boolean isLittleEndian)
	{
		long byteCount = ByteBufferUtils.readU32(jpeg, entryTagOff + 4, isLittleEndian);
		// Guard against a malformed table claiming thousands of entries. A sane MPF carries
		// a handful of images (primary + gain map + optional burst/portrait layers); anything
		// past 64 is almost certainly corrupt and would also make the int cast below suspect.
		if (byteCount < 0 || byteCount > 64L * 16L)
		{
			Log.w(TAG, "MPF byteCount out of range: " + byteCount);
			return false;
		}
		int numImages = (int) (byteCount / 16);
		long entryOffRel = ByteBufferUtils.readU32(jpeg, entryTagOff + 8, isLittleEndian);
		int entryOff = (int) (mpfStart + entryOffRel);

		// Validate entryOff — a malformed MPF with a huge/negative
		// relative offset would otherwise throw out of writeU32.
		// numImages < 2 means the MPF table can't accommodate a gain-map
		// slot (entry[0] is primary, entry[1] is the gain map); patching
		// only entry[0] in a single-image table would leave the caller's
		// appended gain map desynced from the MPF index.
		if (entryOff < mpfStart || numImages < 2
			|| (long) entryOff + (long) numImages * 16L > jpeg.length)
		{
			Log.w(TAG, "MPF entry offset out of bounds or single-image: entryOff=" + entryOff
				+ " numImages=" + numImages + " fileLen=" + jpeg.length);
			return false;
		}

		int gainMapSize = jpeg.length - primarySize;
		int relativeOffset = primarySize - mpfStart;

		Log.d(TAG, numImages + " images, mpfStart=" + mpfStart);

		// Log before
		for (int img = 0; img < numImages; img++)
		{
			int base = entryOff + img * 16;
			long attr = ByteBufferUtils.readU32(jpeg, base, isLittleEndian);
			long size = ByteBufferUtils.readU32(jpeg, base + 4, isLittleEndian);
			long dataOffset = ByteBufferUtils.readU32(jpeg, base + 8, isLittleEndian);
			Log.d(TAG, "BEFORE [" + img + "] attr=0x" + Long.toHexString(attr)
				+ " size=" + size + " offset=" + dataOffset);
		}

		// Update entry[0] (primary): update size
		ByteBufferUtils.writeU32(jpeg, entryOff + 4, primarySize, isLittleEndian);
		Log.d(TAG, "entry[0] size → " + primarySize);

		// Update ONLY entry[1] — the gain map slot in Ultra HDR.
		// Files with additional MP entries (depth maps, burst frames,
		// Apple Portrait layers) must leave those untouched; previously
		// this loop wrote the gain-map size/offset into every secondary
		// entry, silently corrupting the MPF index for multi-image files.
		// numImages >= 2 is already guaranteed by the guard above.
		int gainMapEntryBase = entryOff + 16;
		ByteBufferUtils.writeU32(jpeg, gainMapEntryBase + 4, gainMapSize, isLittleEndian);
		ByteBufferUtils.writeU32(jpeg, gainMapEntryBase + 8, relativeOffset, isLittleEndian);
		Log.d(TAG, "entry[1] offset → " + relativeOffset
			+ " size → " + gainMapSize);

		// Log after
		for (int img = 0; img < numImages; img++)
		{
			int base = entryOff + img * 16;
			if (base + 16 > jpeg.length)
			{
				break;
			}
			long attr = ByteBufferUtils.readU32(jpeg, base, isLittleEndian);
			long size = ByteBufferUtils.readU32(jpeg, base + 4, isLittleEndian);
			long dataOffset = ByteBufferUtils.readU32(jpeg, base + 8, isLittleEndian);
			Log.d(TAG, "AFTER [" + img + "] attr=0x" + Long.toHexString(attr)
				+ " size=" + size + " offset=" + dataOffset);
		}

		return true;
	}
}
