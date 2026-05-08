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
	// Each MPF entry in the MP Entry list is exactly 16 bytes per CIPA DC-007: 4 bytes attr + 4 size + 4
	// dataOffset + 2 dependent-image[0] + 2 dependent-image[1].
	private static final int MPF_ENTRY_BYTES = 16;
	// Sanity cap on MPF entry count. A spec-conformant Samsung Ultra HDR file ships 2 entries (primary +
	// gain map); Apple Portrait composites and other multi-image MPFs rarely exceed 5-6. Anything past 64
	// is almost certainly a corrupt byteCount field — clamping here also keeps the int cast at the
	// numImages assignment below away from negative values.
	private static final int MAX_MPF_ENTRIES = 64;

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
			// Fill bytes (legal per ITU-T T.81 §B.1.1.2) — skip extra 0xFF bytes before reading the marker.
			int markerByteOff = JpegMarkerWalker.skipFillBytes(jpeg, off, jpeg.length);
			if (markerByteOff < 0)
			{
				break;
			}
			int marker = jpeg[markerByteOff] & 0xFF;
			int afterMarker = markerByteOff + 1;

			if (marker == JpegMarker.SOS || marker == JpegMarker.EOI)
			{
				break; // SOS or EOI — stop
			}
			if (marker == JpegMarker.STUFFING || marker == JpegMarker.TEM
				|| (marker >= JpegMarker.RST_FIRST && marker <= JpegMarker.RST_LAST))
			{
				off = afterMarker;
				continue;
			}

			if (afterMarker + 2 > jpeg.length)
			{
				break;
			}
			int segLen = ByteBufferUtils.readU16BE(jpeg, afterMarker);

			// Check for MPF APP2: FF E2 + "MPF\0"
			if (marker == 0xE2 && segLen > 8 && afterMarker + 6 <= jpeg.length
				&& jpeg[afterMarker + 2] == 'M' && jpeg[afterMarker + 3] == 'P'
				&& jpeg[afterMarker + 4] == 'F' && jpeg[afterMarker + 5] == 0)
			{
				int mpfStart = afterMarker + 6; // position of MP Endian field
				// Segment-end bound (afterMarker + segLen is the byte just past the segment). IFD entry
				// walks and MP Entry reads stop here so a malformed MPF with offsets pointing past the
				// segment (into SOS data, another APP segment) doesn't get its "entries" parsed from
				// non-MPF bytes and patched with attacker-controlled writes.
				int mpfSegmentEnd = Math.min(afterMarker + segLen, jpeg.length);
				if (mpfStart + 8 > mpfSegmentEnd)
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

				// IFD offset (relative to mpfStart). Validate the long sum BEFORE the int cast — a u32
				// ifdOffRel near 2^32-1 plus a small mpfStart wraps to a small positive int that would
				// pass the bounds check on the truncated value, letting the entry walk read garbage as
				// entryCount and then call patchMpEntry on attacker-controlled offsets. Same defensive
				// pattern lives in ExifPatcher.scanIfd.
				long ifdOffRel = ByteBufferUtils.readU32(jpeg, mpfStart + 4, isLittleEndian);
				long ifdOffAbs = (long) mpfStart + ifdOffRel;
				if (ifdOffAbs < mpfStart || ifdOffAbs + 2 > mpfSegmentEnd
					|| ifdOffAbs > Integer.MAX_VALUE)
				{
					return false;
				}
				int ifdOff = (int) ifdOffAbs;
				int entryCount = ByteBufferUtils.readU16(jpeg, ifdOff, isLittleEndian);

				for (int i = 0; i < entryCount; i++)
				{
					int entryOffset = ifdOff + 2 + i * 12;
					if (entryOffset + 12 > mpfSegmentEnd)
					{
						break;
					}
					int tag = ByteBufferUtils.readU16(jpeg, entryOffset, isLittleEndian);

					if (tag == TiffTag.MP_ENTRY)
					{
						return patchMpEntry(jpeg, primarySize, mpfStart, mpfSegmentEnd,
							entryOffset, isLittleEndian);
					}
				}
				return false; // MPF found but no MP Entry tag
			}
			// Advance past the segment. afterMarker = byte just after the marker code; add segLen (which
			// includes the 2 length bytes themselves) to land on the next marker's leading 0xFF.
			int next = afterMarker + segLen;
			if (next > jpeg.length || next < off)
			{
				break;
			}
			off = next;
		}
		return false; // no MPF segment
	}

	private static void logEntries(byte[] jpeg, int entryOff, int numImages, boolean isLittleEndian,
		String label)
	{
		for (int img = 0; img < numImages; img++)
		{
			int base = entryOff + img * MPF_ENTRY_BYTES;
			if (base + MPF_ENTRY_BYTES > jpeg.length)
			{
				break;
			}
			long attr = ByteBufferUtils.readU32(jpeg, base, isLittleEndian);
			long size = ByteBufferUtils.readU32(jpeg, base + 4, isLittleEndian);
			long dataOffset = ByteBufferUtils.readU32(jpeg, base + 8, isLittleEndian);
			Log.d(TAG, label + " [" + img + "] attr=0x" + Long.toHexString(attr)
				+ " size=" + size + " offset=" + dataOffset);
		}
	}

	private static boolean patchMpEntry(byte[] jpeg, int primarySize, int mpfStart, int mpfSegmentEnd,
		int entryTagOff, boolean isLittleEndian)
	{
		long byteCount = ByteBufferUtils.readU32(jpeg, entryTagOff + 4, isLittleEndian);
		// Guard against a malformed table claiming thousands of entries. byteCount is an unsigned u32
		// (readU32 returns a long in [0, 0xFFFFFFFF]) so the lower-bound check on negativity was dead code —
		// keep only the upper. The cap also keeps the (int) cast at numImages below away from values that
		// would overflow int.
		if (byteCount > (long) MAX_MPF_ENTRIES * MPF_ENTRY_BYTES)
		{
			Log.w(TAG, "MPF byteCount out of range: " + byteCount);
			return false;
		}
		int numImages = (int) (byteCount / MPF_ENTRY_BYTES);
		// Validate the long sum BEFORE casting — same overflow vector as the IFD-offset check above.
		// A malformed MPF with a huge/negative relative offset would otherwise wrap to a small int that
		// passes the bounds check on the truncated value, letting writeU32 land at attacker-chosen offsets.
		// numImages < 2 means the MPF table can't accommodate a gain-map slot (entry[0] is primary, entry[1]
		// is the gain map); patching only entry[0] in a single-image table would leave the caller's appended
		// gain map desynced from the MPF index.
		long entryOffRel = ByteBufferUtils.readU32(jpeg, entryTagOff + 8, isLittleEndian);
		long entryOffAbs = (long) mpfStart + entryOffRel;
		// MP Entry array must fit inside the MPF segment, not just inside the JPEG file. A malformed MPF
		// claiming the entry array lives in SOS data (or in another APP segment) would otherwise have
		// patchMpEntry walk and PATCH non-MPF bytes — corrupting the JPEG. The segment-end bound ensures
		// every entry-attribute / size / offset write lands inside the MPF segment where it belongs.
		if (entryOffAbs < mpfStart || numImages < 2 || entryOffAbs > Integer.MAX_VALUE
			|| entryOffAbs + (long) numImages * MPF_ENTRY_BYTES > mpfSegmentEnd)
		{
			Log.w(TAG, "MPF entry offset out of bounds or single-image: entryOffAbs=" + entryOffAbs
				+ " numImages=" + numImages + " segEnd=" + mpfSegmentEnd);
			return false;
		}
		int entryOff = (int) entryOffAbs;

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

		// Locate the gain-map entry BEFORE writing any bytes. Per the MPF spec, the entry's lower 24 bits of
		// `attr` carry the MPType (0x010005 = "Original Preservation" / gain map). Samsung Ultra HDR files
		// always place the gain map at index 1, but multi-image MPFs (depth maps, burst frames, Apple Portrait
		// layers) can shuffle it elsewhere — patching entry[1] unconditionally would write the gain-map size
		// into the wrong slot and leave the actual gain-map entry stale, which strict-MPF decoders then reject.
		// Walk all entries counting matches:
		//   - 0 matches + numImages == 2 → fall back to entry[1] (the empirical Samsung Ultra HDR pattern,
		//     which sometimes ships a malformed MPType field but reliably keeps the gain map at index 1).
		//   - 0 matches + numImages != 2 → refuse the patch — writing entry[1] in that case can land the
		//     gain-map size on a depth map, burst frame, or thumbnail entry and leave the real gain-map
		//     entry stale.
		//   - >1 matches → refuse the patch. A spec-legal multi-gain-map MPF (composite depth + Original
		//     Preservation, some Apple Portrait variants) carries multiple 0x010005 entries. We have one
		//     post-edit gain-map size + offset, but no way to assign it across multiple entries without
		//     guessing — a single-entry write would leave the others pointing at pre-edit positions in the
		//     source file, which strict decoders reject. Refusing here lets GainMapComposer drop HDR cleanly
		//     per its design instead of shipping inconsistent metadata.
		//
		// All refusal branches return BEFORE the entry[0] / gain-map writes below so a rejected patch leaves
		// the byte buffer in its pre-call state — the test fixture relies on this invariant for the
		// adversarial multi-match and segment-end-bound cases.
		int gainMapEntryBase = -1;
		int gainMapMatchCount = 0;
		for (int img = 1; img < numImages; img++)
		{
			int base = entryOff + img * MPF_ENTRY_BYTES;
			long attr = ByteBufferUtils.readU32(jpeg, base, isLittleEndian);
			if ((attr & 0x00FFFFFFL) == 0x00010005L)
			{
				if (gainMapMatchCount == 0)
				{
					gainMapEntryBase = base;
				}
				gainMapMatchCount++;
			}
		}
		if (gainMapMatchCount > 1)
		{
			Log.w(TAG, "MPF has " + gainMapMatchCount + " gain-map MPType entries (spec-legal "
				+ "multi-gainmap); refusing to patch — a single post-edit offset can't be assigned "
				+ "across multiple slots");
			return false;
		}
		if (gainMapEntryBase < 0)
		{
			if (numImages != 2)
			{
				Log.w(TAG, "MPF has " + numImages + " entries but no gain-map MPType match;"
					+ " refusing to patch (fallback to entry[1] only valid for 2-image MPF)");
				return false;
			}
			gainMapEntryBase = entryOff + MPF_ENTRY_BYTES;
		}

		// Per-entry diagnostic dump; gated on debug-enabled to skip the readU32 + Long.toHexString work in
		// release builds where Log.d compiles in but is a no-op at runtime.
		if (Log.isLoggable(TAG, Log.DEBUG))
		{
			logEntries(jpeg, entryOff, numImages, isLittleEndian, "BEFORE");
		}

		ByteBufferUtils.writeU32(jpeg, entryOff + 4, primarySize, isLittleEndian);
		Log.d(TAG, "entry[0] size → " + primarySize);

		ByteBufferUtils.writeU32(jpeg, gainMapEntryBase + 4, gainMapSize, isLittleEndian);
		ByteBufferUtils.writeU32(jpeg, gainMapEntryBase + 8, relativeOffset, isLittleEndian);
		Log.d(TAG, "gain-map entry @ +" + (gainMapEntryBase - entryOff) / MPF_ENTRY_BYTES
			+ " offset → " + relativeOffset + " size → " + gainMapSize);

		if (Log.isLoggable(TAG, Log.DEBUG))
		{
			logEntries(jpeg, entryOff, numImages, isLittleEndian, "AFTER");
		}

		return true;
	}
}
