package com.cropcenter.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Tests for the MPF (Multi-Picture Format) APP2 offset patcher. Every HDR save runs this after the canvas re-encode
 * resizes the primary — without correct patching, Gallery's Revert pre-flight (which validates the MP entries against
 * actual JPEG offsets) rejects the file and the Revert button disappears. The patcher mutates the assembled
 * primary+gainmap bytes in place to point the gain-map entry at the new gain-map offset and update entry[0]'s size to
 * the new primary size.
 *
 * Key behaviours covered:
 *   - Patching the standard 2-image MPF (primary + gain map) — the common case.
 *   - Locating the gain-map entry by its MPType field (0x010005, "Original Preservation")
 *     rather than hardcoding entry[1]. Some multi-image producers shuffle the order; the
 *     patcher must walk entries to find the MPType match.
 *   - 2-image fallback: when numImages == 2 and no entry has the gain-map MPType, the
 *     Samsung Ultra HDR pattern guarantees the gain map sits at entry[1] regardless, so
 *     fallback to entry[1] is safe in this specific case.
 *   - REJECTING 3+ image MPFs without an MPType match — the "burst frame / Apple
 *     Portrait layer / depth map" pattern. Blindly patching entry[1] would land the
 *     gain-map size into a non-gain-map slot and leave the actual gain-map entry stale.
 *   - REJECTING single-image MPFs (no gain-map slot to patch).
 */
public final class MpfPatcherTest
{
	@Test
	public void patchAcceptsExactlyMaxMpfEntriesAndRejectsOneOver() throws IOException
	{
		// Pin the cap boundary at MAX_MPF_ENTRIES = 64. 64 entries with the gain
		// map at index 1 must succeed; 65 entries must be refused as overflow protection for the int cast
		// at `numImages = (int)(byteCount / MPF_ENTRY_BYTES)`. A regression that swapped `>` to `>=` would
		// silently drop the cap by one and slip 65-entry MPFs through — caught here.
		long[] attrsAtCap = new long[64];
		attrsAtCap[1] = 0x00010005L;
		MpfFixture okFx = buildMpfFile(2_000, 60, attrsAtCap);
		assertTrue("64 entries (cap) must patch successfully",
			MpfPatcher.patch(okFx.bytes, 2_000));

		long[] attrsOverCap = new long[65];
		attrsOverCap[1] = 0x00010005L;
		MpfFixture badFx = buildMpfFile(3_000, 60, attrsOverCap);
		assertFalse("65 entries (one over MAX_MPF_ENTRIES) must be rejected",
			MpfPatcher.patch(badFx.bytes, 3_000));
	}

	@Test
	public void patchFallsBackToEntry1ForTwoImageMpfWithMissingMpType() throws IOException
	{
		// numImages == 2 with no MPType match: the empirical Samsung Ultra HDR pattern ships gain map at index
		// 1 even when MPType is malformed. Patcher should fall back to entry[1] in this specific case so
		// existing Samsung captures keep round-tripping correctly.
		int newPrimarySize = 220;
		int gainMapSize = 60;
		long[] attrs = { 0x20000000L, 0x12345678L };   // bogus attr on entry[1]
		MpfFixture fx = buildMpfFile(newPrimarySize, gainMapSize, attrs);
		assertTrue(MpfPatcher.patch(fx.bytes, newPrimarySize));
		assertEquals(gainMapSize, readU32Le(fx.bytes, fx.entry0Off + 16 + 4));
	}

	@Test
	public void patchLeavesEntry2OffsetUntouchedForThreeImageMpf() throws IOException
	{
		// numImages = 3 with the gain-map MPType correctly placed at entry[1] (the Samsung-style layout where
		// the third entry is some other sub-image — depth map / burst frame / Apple Portrait alternate).
		// Patcher must update entry[0] and entry[1] but explicitly NOT touch entry[2]. Pin down that the
		// entry[2] bytes are byte-identical pre/post patch — guards against a regression that loops over ALL
		// secondary entries and overwrites every one with the gain-map offset.
		int newPrimarySize = 300;
		int gainMapSize = 80;
		long[] attrs = {
			0x20000000L,                // entry[0] primary
			0x00010005L,                // entry[1] gain map (Original Preservation)
			0x00010003L,                // entry[2] something else (Large Thumbnail)
		};
		MpfFixture fx = buildMpfFile(newPrimarySize, gainMapSize, attrs);

		// Snapshot entry[2] bytes BEFORE patch.
		int entry2Off = fx.entry0Off + 32;
		byte[] entry2Before = new byte[16];
		System.arraycopy(fx.bytes, entry2Off, entry2Before, 0, 16);

		assertTrue(MpfPatcher.patch(fx.bytes, newPrimarySize));

		// entry[2] bytes after patch must match the snapshot.
		byte[] entry2After = new byte[16];
		System.arraycopy(fx.bytes, entry2Off, entry2After, 0, 16);
		for (int i = 0; i < 16; i++)
		{
			assertEquals("entry[2] byte " + i + " mutated by patch", entry2Before[i], entry2After[i]);
		}
	}

	@Test
	public void patchLocatesGainMapByMpTypeWhenAtIndexTwo() throws IOException
	{
		// 3-image MPF where the gain-map MPType (0x010005) is at index 2 — Samsung Ultra HDR conventionally
		// places it at index 1, but the spec doesn't require that, and some multi-image producers shuffle the
		// order. The patcher must walk entries to find the MPType match instead of hardcoding entry[1] —
		// otherwise the gain-map size lands in a non-gain-map slot and the actual gain-map entry stays stale.
		int newPrimarySize = 320;
		int gainMapSize = 75;
		long[] attrs = {
			0x20000000L,                // entry[0] primary (top-bit / "representative image")
			0x00010003L,                // entry[1] Large Thumbnail — NOT the gain map
			0x00010005L,                // entry[2] Original Preservation == gain map
		};
		MpfFixture fx = buildMpfFile(newPrimarySize, gainMapSize, attrs);
		assertTrue(MpfPatcher.patch(fx.bytes, newPrimarySize));

		// Entry[2] (the gain-map slot per MPType) should hold the new size + offset.
		int entry2Off = fx.entry0Off + 32;
		assertEquals(gainMapSize, readU32Le(fx.bytes, entry2Off + 4));
		assertEquals(newPrimarySize - fx.mpfStart, readU32Le(fx.bytes, entry2Off + 8));

		// Entry[1] (the non-gain-map "Large Thumbnail" slot) must be untouched. Capture its post-patch state
		// and verify it still holds the original fixture values that buildMpfFile wrote (size 51 = 50 + i for
		// i=1, dataOffset 10099 = 9999 + 100).
		int entry1Off = fx.entry0Off + 16;
		assertEquals("entry[1] size unexpectedly mutated", 51L, readU32Le(fx.bytes, entry1Off + 4));
		assertEquals("entry[1] dataOffset unexpectedly mutated", 10099L, readU32Le(fx.bytes, entry1Off + 8));
	}

	@Test
	public void patchPassesByteOrderCheckOnBigEndianHeaderButFailsLaterOnMisencodedIfd() throws IOException
	{
		// "MM" (big-endian) MPF is legal per spec. Confirm the byte-order validation accepts it (i.e. doesn't
		// fast-bail at the II/MM check the way it would for "IM" / "MI"). Our fixture's subsequent IFD fields
		// are still LE-encoded, so the patcher reads garbage offsets when interpreting them as BE: with
		// LE-encoded ifdOff=8 read as BE, ifdOff=0x08000000 — well past data.length — and patch returns false
		// from the out-of-bounds check. The pin here is that we reach that downstream check at all (vs. early
		// rejection); the input bytes must also be unmutated by the rejected patch so a regression that wrote
		// past the byte-order guard then bailed mid-write would corrupt the file.
		int newPrimarySize = 200;
		int gainMapSize = 60;
		MpfFixture fx = buildMpfFile(newPrimarySize, gainMapSize, 2);
		fx.bytes[fx.mpfStart] = 'M';
		fx.bytes[fx.mpfStart + 1] = 'M';
		byte[] snapshot = fx.bytes.clone();
		assertFalse("patch should fail on out-of-bounds IFD offset, not byte-order rejection",
			MpfPatcher.patch(fx.bytes, newPrimarySize));
		// Pin: a rejected patch must not have touched the bytes after the byte-order field.
		for (int i = fx.mpfStart + 2; i < fx.bytes.length; i++)
		{
			assertEquals("byte " + i + " must be unchanged when patch rejected mid-flow",
				snapshot[i], fx.bytes[i]);
		}
	}

	@Test
	public void patchRejectsByteCountNotDivisibleBySixteenViaFloorToOne() throws IOException
	{
		// The MPEntries IFD entry's byteCount is a u32 — a malformed encoder could write any value,
		// not just exact multiples of 16. patchMpEntry floors via integer division:
		//   `numImages = (int)(byteCount / MPF_ENTRY_BYTES)`.
		// At byteCount=17, numImages floors to 1 (< 2), which the next guard rejects. Pin this so
		// a regression that "fixes" the floor (e.g. round-up, or admits trailing-slop entries) is
		// caught here — admitting a half-entry would write past the MPF segment boundary.
		int newPrimarySize = 200;
		int gainMapSize = 60;
		MpfFixture fx = buildMpfFile(newPrimarySize, gainMapSize, 2);

		// MPEntries IFD entry: tag(2) + type(2) + byteCount(4) + dataOffset(4) = 12 bytes total.
		// Sibling test `patchRejectsEntryArrayPointingPastMpfSegmentIntoSosData` documents
		// `dataOffset at mpfStart + 30`, so `byteCount` sits at mpfStart + 26 (4 bytes earlier).
		// Write byteCount = 17 in little-endian.
		fx.bytes[fx.mpfStart + 26] = 17;
		fx.bytes[fx.mpfStart + 27] = 0;
		fx.bytes[fx.mpfStart + 28] = 0;
		fx.bytes[fx.mpfStart + 29] = 0;

		byte[] snapshot = fx.bytes.clone();
		assertFalse("byteCount=17 floors to numImages=1 (<2) and must reject",
			MpfPatcher.patch(fx.bytes, newPrimarySize));
		// Confirm no bytes were mutated before the reject — same no-mutation contract as the
		// sister out-of-bounds-offset test.
		for (int i = 0; i < fx.bytes.length; i++)
		{
			assertEquals("byte " + i + " must be unchanged when patch rejected on numImages<2",
				snapshot[i], fx.bytes[i]);
		}
	}

	@Test
	public void patchRejectsEntryArrayPointingPastMpfSegmentIntoSosData() throws IOException
	{
		// Adversarial MPF: dataOffset of the MPEntries IFD entry points past the MPF segment into SOS data.
		// Without the segment-end bound on the entry array, the patcher would have walked the SOS bytes as
		// "MP entries" and rewritten 12 bytes of compressed scan data with attacker-controlled values,
		// corrupting the JPEG. With the bound, patch returns false and bytes are untouched. Pin both: the
		// rejection AND the no-mutation invariant.
		int newPrimarySize = 200;
		int gainMapSize = 60;
		MpfFixture fx = buildMpfFile(newPrimarySize, gainMapSize, 2);

		// The MPEntries IFD entry's dataOffset field sits at mpfStart + 30 (LE u32). Point it past the MPF
		// segment end (mpfSegmentEnd - mpfStart ≈ 70) but still within the JPEG file, so a vulnerability that
		// only checked jpeg.length would have written into the scan body.
		int corruptedRelativeOff = 100;
		fx.bytes[fx.mpfStart + 30] = (byte) (corruptedRelativeOff & 0xFF);
		fx.bytes[fx.mpfStart + 31] = (byte) ((corruptedRelativeOff >> 8) & 0xFF);
		fx.bytes[fx.mpfStart + 32] = 0;
		fx.bytes[fx.mpfStart + 33] = 0;

		byte[] snapshot = fx.bytes.clone();
		assertFalse("patch must reject an entry-array offset that points outside the MPF segment",
			MpfPatcher.patch(fx.bytes, newPrimarySize));

		// The rejected patch must not have mutated any byte. A regression that wrote entry[0].size before the
		// out-of-bounds check would corrupt the scan body silently.
		for (int i = 0; i < fx.bytes.length; i++)
		{
			assertEquals("byte " + i + " must be unchanged when patch rejected on segment-end bound",
				snapshot[i], fx.bytes[i]);
		}
	}

	@Test
	public void patchRejectsMismatchedByteOrderField() throws IOException
	{
		// MPF MP Endian field must be "II" (0x49 0x49) or "MM" (0x4D 0x4D). A malformed half-pair like "IM" /
		// "MI" would slip through a single-byte check and parse subsequent IFD offsets with wrong byte order,
		// producing nonsense bounds-check passes that land writes on arbitrary positions.
		int newPrimarySize = 200;
		int gainMapSize = 60;
		MpfFixture fx = buildMpfFile(newPrimarySize, gainMapSize, 2);
		// Corrupt the byte-order field from "II" to "IM".
		fx.bytes[fx.mpfStart + 1] = 'M';
		assertFalse(MpfPatcher.patch(fx.bytes, newPrimarySize));
	}

	@Test
	public void patchRejectsMultipleGainMapMpTypeMatches() throws IOException
	{
		// A spec-legal multi-gain-map MPF (composite depth + Original Preservation, some Apple Portrait
		// variants) carries multiple 0x010005 entries. We have one post-edit gain-map size + offset, but no
		// way to assign it across multiple entries without guessing — a single-entry write would leave the
		// others pointing at pre-edit positions in the source file, which strict decoders reject. Refusing
		// here lets GainMapComposer drop HDR cleanly per its design.
		int newPrimarySize = 280;
		int gainMapSize = 70;
		long[] attrs = {
			0x20000000L,                // entry[0] primary (representative-image bit)
			0x00010005L,                // entry[1] Original Preservation #1 (gain map)
			0x00010005L,                // entry[2] Original Preservation #2 (second gain map)
		};
		MpfFixture fx = buildMpfFile(newPrimarySize, gainMapSize, attrs);
		byte[] snapshot = fx.bytes.clone();
		assertFalse("multi-gain-map MPF must be refused, not silently single-patched",
			MpfPatcher.patch(fx.bytes, newPrimarySize));

		// No bytes mutated — a regression that wrote into entry[1] or entry[2] before checking the count
		// would trip this assertion.
		for (int i = 0; i < fx.bytes.length; i++)
		{
			assertEquals("byte " + i + " must be unchanged when patch refused on multi-match",
				snapshot[i], fx.bytes[i]);
		}
	}

	@Test
	public void patchRejectsPrimarySizeSmallerThanMpfStart() throws IOException
	{
		// primarySize < mpfStart produces a negative relativeOffset = primarySize
		// - mpfStart, which writeU32 reinterprets as a huge u32 — emitting a
		// corrupt MP entry that decoders treat as an offset past EOF. Refuse to
		// patch rather than write the poison value.
		int gainMapSize = 60;
		MpfFixture fx = buildMpfFile(200, gainMapSize, 2);
		// mpfStart in the fixture sits around offset 10; passing primarySize = 5 drives relativeOffset
		// negative.
		assertFalse(MpfPatcher.patch(fx.bytes, 5));
	}

	@Test
	public void patchRejectsSingleImageMpf() throws IOException
	{
		// numImages < 2: no gain-map slot to patch. Patcher must return false rather than silently writing
		// entry[1]-shaped data into adjacent memory.
		MpfFixture fx = buildMpfFile(100, 30, 1);
		assertFalse(MpfPatcher.patch(fx.bytes, 100));
	}

	@Test
	public void patchRejectsThreeImageMpfWhenNoMpTypeMatch() throws IOException
	{
		// 3-image MPF whose attr fields are all bogus / non-gain-map MPTypes — patcher must refuse rather than
		// blindly patch entry[1] (which could land the gain-map size into a depth map or thumbnail slot).
		// Falling back to entry[1] is only safe when numImages == 2, where the Samsung Ultra HDR pattern
		// guarantees the gain map lives at index 1 even when its MPType field is malformed.
		//
		// Pin BOTH the false return AND the no-byte-mutation invariant. The patch function reorders the
		// gain-map walk before the entry[0] / gain-map writes specifically so a refused patch leaves the
		// JPEG byte buffer in its pre-call state — a regression that wrote entry[0].size before the refusal
		// check would silently corrupt the file while still returning false. Sister test
		// patchRejectsMultipleGainMapMpTypeMatches pins the same invariant on the multi-match branch; this
		// test pins it on the no-match-with-numImages-not-2 branch.
		int newPrimarySize = 320;
		int gainMapSize = 80;
		long[] attrs = {
			0x20000000L,                // primary
			0x00010003L,                // Large Thumbnail (NOT gain map)
			0x00010003L,                // Large Thumbnail (NOT gain map)
		};
		MpfFixture fx = buildMpfFile(newPrimarySize, gainMapSize, attrs);
		byte[] snapshot = fx.bytes.clone();
		assertFalse(MpfPatcher.patch(fx.bytes, newPrimarySize));
		for (int i = 0; i < fx.bytes.length; i++)
		{
			assertEquals("byte " + i + " mutated on rejected patch (no-MPType-match branch)",
				snapshot[i], fx.bytes[i]);
		}
	}

	@Test
	public void patchReturnsFalseWhenNoMpfPresent() throws IOException
	{
		// File has only a non-MPF APP2 (e.g. ICC). Walker should reach SOS and return false without claiming a
		// successful patch.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(JpegFixtures.soi());
		byte[] iccPayload = "ICC_PROFILE\0body".getBytes();
		out.write(JpegFixtures.appSegment(0xE2, iccPayload));
		out.write(JpegFixtures.minimalScanAndEoi());
		assertFalse(MpfPatcher.patch(out.toByteArray(), out.size()));
	}

	@Test
	public void patchSucceedsOnTwoImageMpf() throws IOException
	{
		// Standard Ultra HDR layout: primary + gain map, MPF carries 2 entries.
		int newPrimarySize = 200;
		int gainMapSize = 60;
		MpfFixture fx = buildMpfFile(newPrimarySize, gainMapSize, 2);
		assertTrue(MpfPatcher.patch(fx.bytes, newPrimarySize));
		// entry[0] size patched to newPrimarySize.
		assertEquals(newPrimarySize, readU32Le(fx.bytes, fx.entry0Off + 4));
		// entry[1] size = gainMapSize, offset = newPrimarySize - mpfStart.
		assertEquals(gainMapSize, readU32Le(fx.bytes, fx.entry0Off + 16 + 4));
		assertEquals(newPrimarySize - fx.mpfStart, readU32Le(fx.bytes, fx.entry0Off + 16 + 8));
	}

	@Test
	public void patchThreeArgUsesExplicitGainMapSizeRatherThanBufferLength() throws IOException
	{
		// Streaming-pipeline regression. `GainMapComposer.composeFileToFile` calls the 3-arg overload
		// with the primary's APP-marker HEAD (a few hundred bytes for MPF/XMP) as `jpeg`, while
		// `primarySize` is the FULL primary size (which can be > head.length on any non-trivial save).
		// The 2-arg overload would compute `gainMapSize = jpeg.length - primarySize` and write a
		// negative u32 into the MPF size field. The 3-arg overload takes gainMapSize explicitly so
		// the patched bytes are byte-identical to what the in-memory `compose` produces.
		int primarySize = 1000;      // claimed full primary size (post-XMP-patch + tail concatenation)
		int gainMapSize = 60;
		MpfFixture fx = buildMpfFile(primarySize, gainMapSize, 2);
		// Truncate the bytes to the HEAD ONLY: keep just enough to cover the MPF segment, drop the
		// scan body and gain-map tail that the in-memory variant would also see. Verifies the patcher
		// no longer relies on `jpeg.length` past the MPF segment.
		int headLen = fx.mpfStart + 38 + 2 * 16; // mpfStart + IFD + 2 entries
		byte[] headOnly = new byte[headLen];
		System.arraycopy(fx.bytes, 0, headOnly, 0, headLen);
		assertTrue("3-arg patch should succeed on head-only buffer when gainMapSize given explicitly",
			MpfPatcher.patch(headOnly, primarySize, gainMapSize));
		// entry[0] size patched to primarySize.
		assertEquals(primarySize, readU32Le(headOnly, fx.entry0Off + 4));
		// entry[1] size = gainMapSize (the explicit arg, NOT jpeg.length - primarySize which would be
		// negative for headLen < primarySize). Without the 3-arg overload + GainMapComposer's switch
		// to it, this assertion fails with the negative-as-u32 value ~0xFFFFFx (e.g. 4 294 966 296
		// when primarySize=1000 and headLen=300, instead of the expected 60).
		assertEquals("gain-map entry size must come from the explicit arg, not buffer length",
			gainMapSize, readU32Le(headOnly, fx.entry0Off + 16 + 4));
		// Offset still derived from primarySize - mpfStart (no buffer-length dependency).
		assertEquals(primarySize - fx.mpfStart, readU32Le(headOnly, fx.entry0Off + 16 + 8));
	}

	@Test
	public void patchTwoArgBackwardCompatStillUsesBufferLengthDerivation() throws IOException
	{
		// Backward-compatible 2-arg entry point — the in-memory `compose` and existing test fixtures
		// pass the full `[primary][gainMap]` buffer, so gainMapSize derived from `jpeg.length -
		// primarySize` is correct. Pin the contract that the 2-arg overload still derives the size
		// from the buffer (no behavior change for the byte[] callers).
		int primarySize = 200;
		int gainMapSize = 60;
		MpfFixture fx = buildMpfFile(primarySize, gainMapSize, 2);
		assertTrue(MpfPatcher.patch(fx.bytes, primarySize));
		assertEquals("2-arg derives gainMapSize from buffer length", gainMapSize,
			readU32Le(fx.bytes, fx.entry0Off + 16 + 4));
	}

	/**
	 * Convenience overload that gives every entry the same placeholder `attr` value. Used by tests that don't care
	 * which entry carries the gain-map MPType (the patcher's fall-through to entry[1] handles that case).
	 */
	private static MpfFixture buildMpfFile(int primarySize, int gainMapSize, int numImages)
		throws IOException
	{
		long[] attrs = new long[numImages];
		for (int i = 0; i < numImages; i++)
		{
			attrs[i] = 0x12345678L;
		}
		return buildMpfFile(primarySize, gainMapSize, attrs);
	}

	/**
	 * Build a JPEG byte array with a plausible MPF segment carrying `attrs.length` entries. Returns the bytes plus
	 * the offsets the test needs (mpfStart and entry[0] base).
	 *
	 * MPF layout (little-endian for portability with the MPF "II" form):
	 *   FF E2 [segLen:2] "MPF\0"
	 *   "II*\0" + IFD offset (= 8, points right after the header)
	 *   IFD: numEntries=2, entries: { tag=0xB000 version, tag=0xB002 entries }
	 *   MP Entries: numImages * 16 bytes (attr, size, dataOff, two reserved)
	 */
	private static MpfFixture buildMpfFile(int primarySize, int gainMapSize, long[] attrs)
		throws IOException
	{
		int numImages = attrs.length;
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		// MP Endian header (8 bytes from "MPF\0" — the patcher's mpfStart):
		// "II*\0" + IFD offset 8.
		payload.write('I');
		payload.write('I');
		payload.write('*');
		payload.write(0);
		writeU32Le(payload, 8);

		// IFD: 2 entries (Version + MPEntries), then 4-byte next-IFD-offset = 0.
		writeU16Le(payload, 2);

		// Entry 1: tag 0xB000 Version (4 bytes data inline)
		writeU16Le(payload, 0xB000);
		writeU16Le(payload, 7);                  // type UNDEFINED
		writeU32Le(payload, 4);                  // count
		payload.write(new byte[]{ '0', '1', '0', '0' });   // value "0100"

		// Entry 2: tag 0xB002 MPEntries (numImages * 16 bytes referenced via offset)
		writeU16Le(payload, 0xB002);
		writeU16Le(payload, 7);                  // UNDEFINED
		writeU32Le(payload, numImages * 16L);    // byte count
		// dataOffset = relative to mpfStart. Header is 8 bytes; IFD entries follow:
		// 2-byte count + 12-byte version entry + 12-byte mpentries entry + 4-byte
		// next-IFD = 8 + 30 = 38 bytes. MP Entries table starts at offset 38.
		writeU32Le(payload, 38);

		// next-IFD offset = 0
		writeU32Le(payload, 0);

		// MP Entries table: numImages * 16 bytes (attr, size, dataOff, dependents:2). First entry stub values
		// are immaterial — the patcher overwrites them. Use recognisable junk for the second entry's pre-patch
		// bytes so we can verify they got rewritten, and for the third entry use values that should NOT be
		// touched.
		for (int i = 0; i < numImages; i++)
		{
			writeU32Le(payload, attrs[i]);                      // attr (caller-controlled)
			writeU32Le(payload, i == 0 ? 999 : 50 + i);         // size
			writeU32Le(payload, i == 0 ? 0 : 9999 + i * 100);   // dataOffset
			writeU32Le(payload, 0);                             // dependents
		}

		byte[] mpfPayload = payload.toByteArray();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(JpegFixtures.soi());

		// Track the offset where the MPF APP2 segment starts.
		int mpfApp2Off = out.size();
		out.write(JpegFixtures.appSegment(0xE2, prefixMpfMagic(mpfPayload)));

		// mpfStart = (FF E2) + (length:2) + (MPF\0) = mpfApp2Off + 4 + 4 = mpfApp2Off + 8.
		int mpfStart = mpfApp2Off + 8;

		// Pad up to the requested primary size with scan body.
		out.write(JpegFixtures.minimalScanAndEoi());
		while (out.size() < primarySize)
		{
			out.write(0);
		}

		// Append a gain-map-shaped block.
		for (int i = 0; i < gainMapSize; i++)
		{
			out.write(0x42);
		}

		byte[] bytes = out.toByteArray();

		// entry[0] base = mpfStart + 38 (the dataOffset value we wrote above).
		int entry0Off = mpfStart + 38;

		return new MpfFixture(bytes, mpfStart, entry0Off);
	}

	private static byte[] prefixMpfMagic(byte[] body)
	{
		// String literal "MPF\0" through getBytes drops the trailing null, so build the 4-byte signature
		// explicitly. The function is hardcoded to MPF magic because every caller in this test file builds an
		// MPF segment.
		byte[] sig = { 'M', 'P', 'F', 0 };
		byte[] out = new byte[sig.length + body.length];
		System.arraycopy(sig, 0, out, 0, sig.length);
		System.arraycopy(body, 0, out, sig.length, body.length);
		return out;
	}

	private static long readU32Le(byte[] bytes, int off)
	{
		return ((bytes[off] & 0xFFL)) | ((bytes[off + 1] & 0xFFL) << 8)
			| ((bytes[off + 2] & 0xFFL) << 16) | ((bytes[off + 3] & 0xFFL) << 24);
	}

	private static void writeU16Le(ByteArrayOutputStream out, int value)
	{
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
	}

	private static void writeU32Le(ByteArrayOutputStream out, long value)
	{
		out.write((int) (value & 0xFF));
		out.write((int) ((value >> 8) & 0xFF));
		out.write((int) ((value >> 16) & 0xFF));
		out.write((int) ((value >> 24) & 0xFF));
	}

	private record MpfFixture(byte[] bytes, int mpfStart, int entry0Off) {}
}
