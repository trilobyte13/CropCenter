package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

/**
 * Patches MPF (Multi-Picture Format) APP2 offsets in a JPEG byte array. After re-encoding the primary image, the gain
 * map's offset changes.
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

			if (marker == JpegMarker.SOS || marker == JpegMarker.EOI)
			{
				break; // SOS or EOI — stop
			}
			if (marker == JpegMarker.STUFFING || marker == JpegMarker.TEM
				|| (marker >= JpegMarker.RST_FIRST && marker <= JpegMarker.RST_LAST))
			{
				off += 2;
				continue;
			}

			int segLen = ByteBufferUtils.readU16BE(jpeg, off + 2);

			// Check for MPF APP2: FF E2 + "MPF\0"
			if (marker == 0xE2 && segLen > 8 && jpeg[off + 4] == 'M' && jpeg[off + 5] == 'P'
				&& jpeg[off + 6] == 'F' && jpeg[off + 7] == 0)
			{
				int mpfStart = off + 8; // position of MP Endian field
				if (mpfStart + 8 > jpeg.length)
				{
					return false;
				}
				// MP Endian field is 2 bytes per spec — "II" (0x49 0x49) for little-endian or "MM"
				// (0x4D 0x4D) for big-endian. Only checking jpeg[mpfStart] would treat a malformed "IM"
				// / "MI" as little-endian and parse subsequent IFD offsets with the wrong byte order,
				// producing nonsensical bounds-check passes that land writes on arbitrary positions.
				int hi = jpeg[mpfStart] & 0xFF;
				int lo = jpeg[mpfStart + 1] & 0xFF;
				boolean isLittleEndian = hi == 0x49 && lo == 0x49;
				boolean isBigEndian = hi == 0x4D && lo == 0x4D;
				if (!isLittleEndian && !isBigEndian)
				{
					Log.w(TAG, "MPF byte-order field is not II/MM: 0x"
						+ Integer.toHexString(hi) + Integer.toHexString(lo));
					return false;
				}

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
		// Guard against a malformed table claiming thousands of entries. A sane MPF carries a handful of images
		// (primary + gain map + optional burst/portrait layers); anything past 64 is almost certainly corrupt
		// and would also make the int cast below suspect.
		if (byteCount < 0 || byteCount > 64L * 16L)
		{
			Log.w(TAG, "MPF byteCount out of range: " + byteCount);
			return false;
		}
		int numImages = (int) (byteCount / 16);
		long entryOffRel = ByteBufferUtils.readU32(jpeg, entryTagOff + 8, isLittleEndian);
		int entryOff = (int) (mpfStart + entryOffRel);

		// Validate entryOff — a malformed MPF with a huge/negative relative offset would otherwise throw out of
		// writeU32. numImages < 2 means the MPF table can't accommodate a gain-map slot (entry[0] is primary,
		// entry[1] is the gain map); patching only entry[0] in a single-image table would leave the caller's
		// appended gain map desynced from the MPF index.
		if (entryOff < mpfStart || numImages < 2 || (long) entryOff + (long) numImages * 16L > jpeg.length)
		{
			Log.w(TAG, "MPF entry offset out of bounds or single-image: entryOff=" + entryOff
				+ " numImages=" + numImages + " fileLen=" + jpeg.length);
			return false;
		}

		int gainMapSize = jpeg.length - primarySize;
		int relativeOffset = primarySize - mpfStart;
		// On a malformed MPF where the segment is positioned later in the file than the gain map start,
		// relativeOffset is negative and writeU32 reinterprets it as a huge u32 — emitting a corrupt MP entry
		// that decoders treat as an offset past EOF. Refuse to patch rather than write a poison value.
		if (relativeOffset < 0)
		{
			Log.w(TAG, "MPF relativeOffset negative (primarySize=" + primarySize
				+ " < mpfStart=" + mpfStart + "); refusing to patch");
			return false;
		}

		Log.d(TAG, numImages + " images, mpfStart=" + mpfStart);

		for (int img = 0; img < numImages; img++)
		{
			int base = entryOff + img * 16;
			long attr = ByteBufferUtils.readU32(jpeg, base, isLittleEndian);
			long size = ByteBufferUtils.readU32(jpeg, base + 4, isLittleEndian);
			long dataOffset = ByteBufferUtils.readU32(jpeg, base + 8, isLittleEndian);
			Log.d(TAG, "BEFORE [" + img + "] attr=0x" + Long.toHexString(attr)
				+ " size=" + size + " offset=" + dataOffset);
		}

		ByteBufferUtils.writeU32(jpeg, entryOff + 4, primarySize, isLittleEndian);
		Log.d(TAG, "entry[0] size → " + primarySize);

		// Locate the gain-map entry. Per the MPF spec, the entry's lower 24 bits of `attr` carry the MPType
		// (0x010005 = "Original Preservation" / gain map). Samsung Ultra HDR files always place the gain map at
		// index 1, but multi- image MPFs (depth maps, burst frames, Apple Portrait layers) can shuffle it
		// elsewhere — patching entry[1] unconditionally would write the gain-map size into the wrong slot and
		// leave the actual gain-map entry stale, which strict-MPF decoders then reject. Walk all entries; if no
		// MPType match is found, fall back to entry[1] ONLY when numImages == 2 (the empirical Samsung Ultra
		// HDR pattern, which sometimes ships a malformed MPType field but reliably keeps the gain map at index
		// 1). For numImages >= 3 with no MPType match, refuse the patch — writing entry[1] in that case can
		// land the gain-map size on a depth map, burst frame, or thumbnail entry and leave the real gain-map
		// entry stale.
		int gainMapEntryBase = -1;
		for (int img = 1; img < numImages; img++)
		{
			int base = entryOff + img * 16;
			long attr = ByteBufferUtils.readU32(jpeg, base, isLittleEndian);
			if ((attr & 0x00FFFFFFL) == 0x00010005L)
			{
				gainMapEntryBase = base;
				break;
			}
		}
		if (gainMapEntryBase < 0)
		{
			if (numImages != 2)
			{
				Log.w(TAG, "MPF has " + numImages + " entries but no gain-map MPType match;"
					+ " refusing to patch (fallback to entry[1] only valid for 2-image MPF)");
				return false;
			}
			gainMapEntryBase = entryOff + 16;
		}
		ByteBufferUtils.writeU32(jpeg, gainMapEntryBase + 4, gainMapSize, isLittleEndian);
		ByteBufferUtils.writeU32(jpeg, gainMapEntryBase + 8, relativeOffset, isLittleEndian);
		Log.d(TAG, "gain-map entry @ +" + (gainMapEntryBase - entryOff) / 16
			+ " offset → " + relativeOffset + " size → " + gainMapSize);

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
