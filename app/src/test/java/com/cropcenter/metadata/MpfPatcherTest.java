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
public class MpfPatcherTest
{
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
	public void patchRejectsThreeImageMpfWhenNoMpTypeMatch() throws IOException
	{
		// 3-image MPF whose attr fields are all bogus / non-gain-map MPTypes — patcher must refuse rather than
		// blindly patch entry[1] (which could land the gain-map size into a depth map or thumbnail slot).
		// Falling back to entry[1] is only safe when numImages == 2, where the Samsung Ultra HDR pattern
		// guarantees the gain map lives at index 1 even when its MPType field is malformed.
		int newPrimarySize = 320;
		int gainMapSize = 80;
		long[] attrs = {
			0x20000000L,                // primary
			0x00010003L,                // Large Thumbnail (NOT gain map)
			0x00010003L,                // Large Thumbnail (NOT gain map)
		};
		MpfFixture fx = buildMpfFile(newPrimarySize, gainMapSize, attrs);
		assertFalse(MpfPatcher.patch(fx.bytes, newPrimarySize));
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
	public void patchRejectsSingleImageMpf() throws IOException
	{
		// numImages < 2: no gain-map slot to patch. Patcher must return false rather than silently writing
		// entry[1]-shaped data into adjacent memory.
		MpfFixture fx = buildMpfFile(100, 30, 1);
		assertFalse(MpfPatcher.patch(fx.bytes, 100));
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

	/**
	 * Build a JPEG byte array with a plausible MPF segment carrying `numImages` entries. Returns the bytes plus the
	 * offsets the test needs (mpfStart and entry[0] base).
	 *
	 * MPF layout (little-endian for portability with the MPF "II" form):
	 *   FF E2 [segLen:2] "MPF\0"
	 *   "II*\0" + IFD offset (= 8, points right after the header)
	 *   IFD: numEntries=2, entries: { tag=0xB000 version, tag=0xB002 entries }
	 *   MP Entries: numImages * 16 bytes (attr, size, dataOff, two reserved)
	 */
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
