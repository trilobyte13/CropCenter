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

	// Sanity cap on MPF entry count. A spec-conformant Samsung Ultra HDR file ships 2 entries (primary + gain map);
	// Apple Portrait composites and other multi-image MPFs rarely exceed 5-6. Anything past 64 is almost certainly
	// a corrupt byteCount field — clamping here also keeps the int cast at the numImages assignment below away from
	// negative values.
	private static final int MAX_MPF_ENTRIES = 64;
	// Each MPF entry in the MP Entry list is exactly 16 bytes per CIPA DC-007: 4 bytes attr + 4 size + 4 dataOffset
	// + 2 dependent-image[0] + 2 dependent-image[1].
	private static final int MPF_ENTRY_BYTES = 16;

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
		// Backward-compatible entry point for callers (in-memory `compose`, tests) that pass the full
		// `[primary][gainMap]` buffer. The buffer's length precisely encodes the gain-map size, so we can
		// derive it here. The streaming entry point below takes gainMapSize explicitly because its `jpeg`
		// buffer is only the primary's APP-marker HEAD — `jpeg.length - primarySize` would be negative when
		// patchedHead.length < primarySize, writing a garbage u32 into the MPF size slot.
		return patch(jpeg, primarySize, jpeg.length - primarySize);
	}

	/**
	 * Patch MPF offsets when the caller knows the gain-map size independently of `jpeg.length`. Used by
	 * `GainMapComposer.composeFileToFile` where `jpeg` is the primary's APP-marker head (not the full primary +
	 * gain map) and `gainMapSize` is the gain-map JPEG's byte count.
	 *
	 * @param jpeg         a byte array containing at least the MPF APP2 segment; need not be the full
	 *                     primary, but must include all APP segments before SOS so the segment walk
	 *                     finds the MPF marker
	 * @param primarySize  byte offset where the gain map will start after the primary (= full primary
	 *                     JPEG size); written into the MPF entry[0] size field and into the gain-map
	 *                     entry's relative-offset field as `primarySize - mpfStart`
	 * @param gainMapSize  byte count of the gain-map JPEG to be appended after the primary; written
	 *                     into the gain-map entry's size field
	 * @return true if MPF was found and patched, false otherwise
	 */
	public static boolean patch(byte[] jpeg, int primarySize, int gainMapSize)
	{
		int off = 2; // skip SOI
		while (off < jpeg.length - 8)
		{
			JpegMarkerWalker.HeadSegment step = JpegMarkerWalker.nextHeadSegment(jpeg, off, jpeg.length);
			if (step.kind() == JpegMarkerWalker.HeadKind.NO_MARKER)
			{
				break;
			}
			int marker = step.marker();

			if (marker == JpegMarker.SOS || marker == JpegMarker.EOI)
			{
				break;
			}
			if (step.kind() == JpegMarkerWalker.HeadKind.STANDALONE)
			{
				off = step.next();
				continue;
			}

			if (step.kind() == JpegMarkerWalker.HeadKind.BAD_LENGTH)
			{
				break;
			}
			// SEGMENT or OVERRUN — the MPF match below runs for both because the walk's own segment-end
			// clamp (mpfSegmentEnd) tolerates a claimed length past EOF; only a non-MPF OVERRUN stops the
			// walk (after the match check, matching the pre-cursor guard order).
			int afterMarker = step.markerByteOff() + 1;
			int segLen = step.segLen();

			if (marker == JpegMarker.APP2 && segLen > 8 && afterMarker + 6 <= jpeg.length
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
				// ifdOffRel near 2^32-1 plus a small mpfStart wraps to a small positive int that passes
				// the bounds check on the truncated value, letting the entry walk read garbage as
				// entryCount and call patchMpEntry on attacker-controlled offsets.
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
					long entryOffsetLong = (long) ifdOff + 2 + (long) i * 12;
					if (entryOffsetLong + 12 > mpfSegmentEnd)
					{
						break;
					}
					int entryOffset = (int) entryOffsetLong;
					int tag = ByteBufferUtils.readU16(jpeg, entryOffset, isLittleEndian);

					if (tag == TiffTag.MP_ENTRY)
					{
						return patchMpEntry(jpeg, primarySize, gainMapSize, mpfStart,
							mpfSegmentEnd, entryOffset, isLittleEndian);
					}
				}
				return false;
			}
			if (step.kind() == JpegMarkerWalker.HeadKind.OVERRUN)
			{
				break;
			}
			off = step.next();
		}
		return false;
	}

	/**
	 * Dump every MP Entry's attribute / size / offset fields to the debug log. Read-only diagnostic used by
	 * patchMpEntry before and after its writes; entries running past the file are skipped silently.
	 *
	 * @param jpeg           full JPEG bytes; read only
	 * @param entryOff       absolute offset of the MP Entry array
	 * @param numImages      entry count to dump
	 * @param isLittleEndian MPF byte order
	 * @param label          log prefix distinguishing the BEFORE / AFTER dumps
	 */
	private static void logEntries(byte[] jpeg, int entryOff, int numImages, boolean isLittleEndian, String label)
	{
		for (int img = 0; img < numImages; img++)
		{
			long baseLong = (long) entryOff + (long) img * MPF_ENTRY_BYTES;
			if (baseLong + MPF_ENTRY_BYTES > jpeg.length)
			{
				break;
			}
			int base = (int) baseLong;
			long attr = ByteBufferUtils.readU32(jpeg, base, isLittleEndian);
			long size = ByteBufferUtils.readU32(jpeg, base + 4, isLittleEndian);
			long dataOffset = ByteBufferUtils.readU32(jpeg, base + 8, isLittleEndian);
			Log.d(TAG, label + " [" + img + "] attr=0x" + Long.toHexString(attr)
				+ " size=" + size + " offset=" + dataOffset);
		}
	}

	/**
	 * Rewrite the MP Entry array IN PLACE with the post-edit primary size and gain-map size / offset. Every
	 * validation runs BEFORE the first write, so a refusal never leaves a half-patched table. Refusal classes:
	 * malformed table geometry (entry count over cap, entry array outside the MPF segment, negative gain-map
	 * offset), ambiguous gain-map slot (no 0x010005 MPType match on a 3+ image table, or multiple matches — one
	 * post-edit offset can't be assigned across several slots), and an unrecognised entry[0] MPType on a 3+ image
	 * table (entry[0] might not be the primary). A no-MPType-match 2-image table falls back to entry[1] (Samsung
	 * Ultra HDR ships malformed MPType but keeps the gain map at index 1).
	 *
	 * @param jpeg           full JPEG bytes; mutated in place only when every validation passes
	 * @param primarySize    post-edit primary-image byte count, written into entry[0].size; the gain map starts
	 *                       right after the primary, so primarySize - mpfStart is written as the gain-map
	 *                       entry's data offset
	 * @param gainMapSize    post-edit gain-map byte count, written into the gain-map entry's size field
	 * @param mpfStart       absolute offset of the MP Endian field (base for all MPF-relative offsets)
	 * @param mpfSegmentEnd  byte just past the MPF segment; every read and write is bounded by it so a malformed
	 *                       table can't land writes in SOS data or another segment
	 * @param entryTagOff    absolute offset of the MP_ENTRY IFD entry (count + offset fields are read from it)
	 * @param isLittleEndian MPF byte order
	 * @return true when both entries were patched; false on any refusal — the caller drops HDR rather than ship
	 *         a stale or corrupt MPF index
	 */
	private static boolean patchMpEntry(byte[] jpeg, int primarySize, int gainMapSize, int mpfStart,
		int mpfSegmentEnd, int entryTagOff, boolean isLittleEndian)
	{
		long byteCount = ByteBufferUtils.readU32(jpeg, entryTagOff + 4, isLittleEndian);
		// Guard against a malformed table claiming thousands of entries. byteCount is an unsigned u32 (readU32
		// returns a long in [0, 0xFFFFFFFF]) so the lower-bound check on negativity was dead code — keep only
		// the upper. The cap also keeps the (int) cast at numImages below away from values that would overflow
		// int.
		if (byteCount > (long) MAX_MPF_ENTRIES * MPF_ENTRY_BYTES)
		{
			Log.w(TAG, "MPF byteCount out of range: " + byteCount);
			return false;
		}
		int numImages = (int) (byteCount / MPF_ENTRY_BYTES);
		// Validate the long sum BEFORE casting — same overflow vector as the IFD-offset check above. A
		// malformed MPF with a huge/negative relative offset would otherwise wrap to a small int that passes
		// the bounds check on the truncated value, letting writeU32 land at attacker-chosen offsets. numImages
		// < 2 means the MPF table can't accommodate a gain-map slot (entry[0] is primary, entry[1] is the gain
		// map); patching only entry[0] in a single-image table would leave the caller's appended gain map
		// desynced from the MPF index.
		long entryOffRel = ByteBufferUtils.readU32(jpeg, entryTagOff + 8, isLittleEndian);
		long entryOffAbs = (long) mpfStart + entryOffRel;
		// MP Entry array must fit inside the MPF segment, not just inside the JPEG file. A malformed MPF
		// claiming the entry array lives in SOS data (or in another APP segment) would otherwise have
		// patchMpEntry walk and PATCH non-MPF bytes — corrupting the JPEG. The segment-end bound ensures every
		// entry-attribute / size / offset write lands inside the MPF segment where it belongs.
		if (entryOffAbs < mpfStart || numImages < 2 || entryOffAbs > Integer.MAX_VALUE
			|| entryOffAbs + (long) numImages * MPF_ENTRY_BYTES > mpfSegmentEnd)
		{
			Log.w(TAG, "MPF entry offset out of bounds or single-image: entryOffAbs=" + entryOffAbs
				+ " numImages=" + numImages + " segEnd=" + mpfSegmentEnd);
			return false;
		}
		int entryOff = (int) entryOffAbs;

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

		// Locate the gain-map entry before writing any bytes. The entry's lower 24 bits of `attr` carry
		// the MPType (0x010005 = "Original Preservation" / gain map). Multi-image MPFs (depth maps,
		// burst frames, Apple Portrait layers) can shuffle the gain map off index 1, so walk all
		// entries counting matches:
		//   - 0 matches + numImages == 2 → fall back to entry[1] (Samsung Ultra HDR sometimes ships a
		//     malformed MPType field but reliably keeps the gain map at index 1).
		//   - 0 matches + numImages != 2 → refuse: writing entry[1] could land on a depth map / burst
		//     frame / thumbnail and leave the real gain-map entry stale.
		//   - >1 matches → refuse: spec-legal multi-gain-map MPF (composite depth + Original
		//     Preservation) carries multiple 0x010005 entries; we have one post-edit size + offset.
		// Refusal branches return BEFORE the entry writes below.
		int gainMapEntryBase = -1;
		int gainMapMatchCount = 0;
		for (int img = 1; img < numImages; img++)
		{
			long baseLong = (long) entryOff + (long) img * MPF_ENTRY_BYTES;
			if (baseLong + MPF_ENTRY_BYTES > jpeg.length)
			{
				break;
			}
			int base = (int) baseLong;
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

		// Validate entry[0] IS the primary representative image on 3+ image MPFs before rewriting its size. MPF
		// spec section 4.5 says entry[0] is "FirstIndividualImage" with MPType 0x030000 ("Representative Image"
		// / "Baseline MP Primary"). On 2-image MPFs (the dominant case) the patcher already assumes the
		// standard primary+gain-map layout, and existing test fixtures use placeholder MPType values that we
		// don't want to reject. On 3+ images (Apple Portrait layers, burst frames, depth+primary+gain-map
		// composites) the entry[0]-might-not-be-primary risk is real — a non-standard ordering would let the
		// rewrite corrupt the wrong slot. We accept 0x030000 (canonical primary marker) AND the all-zero MPType
		// (some firmware writers omit the field on the primary). Anything else on a 3+ image MPF refuses the
		// patch.
		if (numImages >= 3)
		{
			long primaryAttr = ByteBufferUtils.readU32(jpeg, entryOff, isLittleEndian);
			long primaryMpType = primaryAttr & 0x00FFFFFFL;
			if (primaryMpType != 0x00030000L && primaryMpType != 0L)
			{
				Log.w(TAG, "MPF entry[0] MPType=0x" + Long.toHexString(primaryMpType)
					+ " on " + numImages + "-image MPF is neither Representative (0x030000) nor "
					+ "empty; refusing to patch entry[0].size");
				return false;
			}
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
