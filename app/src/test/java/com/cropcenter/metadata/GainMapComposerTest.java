package com.cropcenter.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tests for GainMapComposer.compose covering the three documented return classes:
 * primary-unchanged on null/empty gain map, primary-unchanged on MPF-patch failure
 * (the round-7 P2 fix that prevents shipping orphaned HDR bytes), and combined
 * bytes when patch succeeds. The MPF-patch-failure case is the key safety
 * invariant — without it, decoders that scan for the hdrgm signature would
 * render HDR with the wrong offset on a file whose metadata still claimed an
 * attached gain map. The save-toast wiring through ExportResult.hdrAttached
 * separately ensures the [HDR OK] / [HDR dropped] suffix reflects what the
 * encoder actually appended (Codex round-20 F2 replaced an earlier full-file
 * substring scan).
 */
public final class GainMapComposerTest
{
	@Test
	public void composeReturnsPrimaryUnchangedForNullGainMap()
	{
		byte[] primary = { 1, 2, 3, 4 };
		byte[] result = GainMapComposer.compose(primary, null);
		assertSame("null gain map should pass primary through verbatim", primary, result);
	}

	@Test
	public void composeReturnsPrimaryUnchangedForEmptyGainMap()
	{
		byte[] primary = { 1, 2, 3, 4 };
		byte[] empty = new byte[0];
		byte[] result = GainMapComposer.compose(primary, empty);
		assertSame("empty gain map should pass primary through verbatim", primary, result);
	}

	@Test
	public void composeReturnsPrimaryUnchangedWhenMpfPatchFails()
	{
		// Without a valid MPF segment in primary, MpfPatcher.patch returns false. compose must DROP the gain
		// map and ship primary verbatim — appending orphaned gain-map bytes that no MPF entry points at would
		// either crash strict decoders' Revert pre-flight (Samsung Gallery) or render with the wrong offset in
		// lenient decoders (which scan for the hdrgm signature). Reference equality on the compose result is
		// what CropExporter uses to set ExportResult.hdrAttached=false on this drop path so the save toast
		// reads "[HDR dropped]" instead of "[HDR OK]" (Codex round-20 F2 replaced an earlier full-file scan).
		byte[] primary = { 0x10, 0x20, 0x30 };
		byte[] gainMap = { 0x40, 0x50 };
		byte[] result = GainMapComposer.compose(primary, gainMap);
		assertSame("MPF patch failure should drop the gain map and return primary", primary, result);
	}

	@Test
	public void composeReturnsCombinedBytesWhenPatchSucceeds() throws IOException
	{
		// Build a minimal Ultra-HDR-shaped primary (SOI + MPF APP2 + minimal scan + EOI), append a fake gain
		// map, and verify compose returns the combined bytes (length > primary.length) — i.e., the MPF patch
		// succeeded and anchored the gain map at the right offset.
		byte[] primary = buildPrimaryWithMpf();
		byte[] gainMap = new byte[40];
		for (int i = 0; i < gainMap.length; i++)
		{
			gainMap[i] = 0x42;
		}

		byte[] result = GainMapComposer.compose(primary, gainMap);
		assertEquals("compose should return primary + gainMap concatenated",
			primary.length + gainMap.length, result.length);
		// First primary.length bytes should be the primary verbatim (MPF segment inside has been mutated by
		// patch, but the first bytes of the segment header / SOI / etc are unchanged).
		assertEquals((byte) 0xFF, result[0]);
		assertEquals((byte) 0xD8, result[1]);
		// Last gainMap.length bytes should be our 0x42 padding.
		for (int i = 0; i < gainMap.length; i++)
		{
			assertEquals("gain map byte " + i + " should be appended verbatim",
				(byte) 0x42, result[primary.length + i]);
		}
	}

	@Test
	public void composeReturnsPrimaryWhenItemLengthInExtendedXmp() throws IOException
	{
		// Codex round-26 T1 — when XmpItemLengthPatcher.patch returns null (Item:Length lives in Extended
		// XMP, which can't be safely patched in-place across the per-chunk reassembly headers), compose
		// must drop the gain map and ship primary verbatim. Without this null-handling integration, the
		// composer would either NPE on the null `patched` array or ship a file with stale Item:Length —
		// silent HDR-boost loss in strict GContainer-respecting decoders. Build a primary with valid MPF
		// (so MPF patching would succeed if reached) and Extended XMP carrying Item:Length, verify the
		// patcher's null short-circuits before MpfPatcher runs.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<x:xmpmeta><hdrgm:Version>1.0</hdrgm:Version>"
			+ "</x:xmpmeta>").getBytes(StandardCharsets.US_ASCII);
		// Extended XMP chunk: namespace prefix (35 bytes) + 32-byte GUID + 4-byte total-length + 4-byte
		// offset + body containing Item:Length=. The patcher's reassembly fallback OR the per-chunk scan
		// will detect Item:Length= and return null.
		byte[] extXmp = buildExtendedXmpChunk(
			"<rdf:Description Item:Length=\"43099\" Item:Mime=\"image/jpeg\"/>");
		byte[] primary = buildPrimaryWithMpfAndExtraXmp(standardXmp, extXmp);
		byte[] gainMap = new byte[40];
		for (int i = 0; i < gainMap.length; i++)
		{
			gainMap[i] = 0x42;
		}

		byte[] result = GainMapComposer.compose(primary, gainMap);
		assertSame("Item:Length-in-Extended-XMP must drop the gain map (patcher null return)",
			primary, result);
	}

	@Test
	public void composeReturnsPrimaryWhenStandardItemLengthIsUnpatchable() throws IOException
	{
		// Codex round-29 A4.2 — pin GainMapComposer's null-return handling for the OTHER trigger:
		// XmpItemLengthPatcher returns null because standard XMP carries Item:Length but the segment
		// is unpatchable (here: malformed empty digit run). Existing
		// composeReturnsPrimaryWhenItemLengthInExtendedXmp covers the Extended XMP trigger; this
		// pins the symmetric standard-XMP-unpatchable path so a regression that only checks the
		// Extended XMP case (or NPEs on null `patched.length`) gets caught.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=\"\"/>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = buildPrimaryWithStandardXmpAndMpf(standardXmp);
		byte[] gainMap = new byte[40];
		for (int i = 0; i < gainMap.length; i++)
		{
			gainMap[i] = 0x42;
		}

		byte[] result = GainMapComposer.compose(primary, gainMap);
		assertSame("standard-XMP unpatchable Item:Length must drop the gain map (patcher null return)",
			primary, result);
	}

	@Test
	public void composeReturnsPrimaryWhenMpfMissingEvenWithGainMap() throws IOException
	{
		// Variant of the patch-failure test using a real-looking JPEG (SOI + DQT + minimal scan + EOI) that has
		// NO MPF segment. patch returns false; compose must drop the gain map. This pin closes the hardest
		// case: a valid SDR JPEG (not Ultra HDR) being passed to compose — the gain map shouldn't hitch a ride.
		byte[] primary = JpegFixtures.concat(
			JpegFixtures.soi(), new byte[] { (byte) 0xFF, (byte) 0xDB, 0x00, 0x04, 0x00, 0x00 },
			JpegFixtures.minimalScanAndEoi());
		byte[] gainMap = { 0x42, 0x42, 0x42 };
		byte[] result = GainMapComposer.compose(primary, gainMap);
		assertSame("SDR primary with no MPF should ignore gain map", primary, result);
	}

	private static byte[] buildExtendedXmpChunk(String inner) throws IOException
	{
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write("http://ns.adobe.com/xmp/extension/\0".getBytes(StandardCharsets.US_ASCII));
		body.write("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
		body.write(new byte[] { 0, 0, 0x10, 0 });
		body.write(new byte[] { 0, 0, 0, 0 });
		body.write(inner.getBytes(StandardCharsets.US_ASCII));
		return body.toByteArray();
	}

	/**
	 * Build a Ultra-HDR-shaped primary JPEG: SOI + MPF APP2 with a 2-image MP Entries table + minimal scan + EOI.
	 * The MP Entries table sits inside the APP2 segment; primarySize passed to MpfPatcher.patch is set to the total
	 * primary length so relativeOffset = primarySize - mpfStart is non-negative and the gain-map entry can be
	 * patched. Mirrors the layout produced by MpfPatcherTest.buildMpfFile but condensed to just what compose needs.
	 */
	private static byte[] buildPrimaryWithMpf() throws IOException
	{
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		// MP Endian header: "II*\0" + IFD offset = 8.
		payload.write('I');
		payload.write('I');
		payload.write('*');
		payload.write(0);
		writeU32Le(payload, 8L);

		// IFD: 2 entries (Version, MPEntries) + 4-byte next-IFD = 0.
		writeU16Le(payload, 2);
		// Entry: tag 0xB000 Version, type UNDEFINED, count 4, value "0100".
		writeU16Le(payload, 0xB000);
		writeU16Le(payload, 7);
		writeU32Le(payload, 4L);
		payload.write(new byte[] { '0', '1', '0', '0' });
		// Entry: tag 0xB002 MPEntries, type UNDEFINED, count = numImages * 16, dataOffset = 38 (after IFD).
		writeU16Le(payload, 0xB002);
		writeU16Le(payload, 7);
		writeU32Le(payload, 2L * 16L);
		writeU32Le(payload, 38L);
		writeU32Le(payload, 0L); // next IFD

		// MP Entries: entry[0] primary (attr 0x20000000 / size placeholder / offset 0), entry[1] gain map (attr
		// 0x010005 = Original Preservation).
		writeU32Le(payload, 0x20000000L);
		writeU32Le(payload, 999L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0x00010005L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0L);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(JpegFixtures.soi());
		out.write(JpegFixtures.appSegment(0xE2, prefixMpfMagic(payload.toByteArray())));
		out.write(JpegFixtures.minimalScanAndEoi());
		return out.toByteArray();
	}

	private static byte[] buildPrimaryWithMpfAndExtraXmp(byte[] standardXmp, byte[] extXmp)
		throws IOException
	{
		// Same shape as buildPrimaryWithMpf but interleaves a standard XMP APP1 + Extended XMP APP1
		// before the MPF segment, so the patcher exercises its standard-XMP-miss → Extended-XMP path
		// and returns null.
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write('I'); payload.write('I'); payload.write('*'); payload.write(0);
		writeU32Le(payload, 8L);
		writeU16Le(payload, 2);
		writeU16Le(payload, 0xB000);
		writeU16Le(payload, 7);
		writeU32Le(payload, 4L);
		payload.write(new byte[] { '0', '1', '0', '0' });
		writeU16Le(payload, 0xB002);
		writeU16Le(payload, 7);
		writeU32Le(payload, 2L * 16L);
		writeU32Le(payload, 38L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0x20000000L);
		writeU32Le(payload, 999L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0x00010005L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0L);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(JpegFixtures.soi());
		out.write(JpegFixtures.appSegment(0xE1, standardXmp));
		out.write(JpegFixtures.appSegment(0xE1, extXmp));
		out.write(JpegFixtures.appSegment(0xE2, prefixMpfMagic(payload.toByteArray())));
		out.write(JpegFixtures.minimalScanAndEoi());
		return out.toByteArray();
	}

	private static byte[] buildPrimaryWithStandardXmpAndMpf(byte[] standardXmp) throws IOException
	{
		// Variant of buildPrimaryWithMpfAndExtraXmp that adds only a single standard XMP segment +
		// the MPF segment. Used by the standard-XMP-unpatchable test where Extended XMP must NOT be
		// present so the patcher's null return is unambiguously triggered by the standard-XMP body.
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write('I'); payload.write('I'); payload.write('*'); payload.write(0);
		writeU32Le(payload, 8L);
		writeU16Le(payload, 2);
		writeU16Le(payload, 0xB000);
		writeU16Le(payload, 7);
		writeU32Le(payload, 4L);
		payload.write(new byte[] { '0', '1', '0', '0' });
		writeU16Le(payload, 0xB002);
		writeU16Le(payload, 7);
		writeU32Le(payload, 2L * 16L);
		writeU32Le(payload, 38L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0x20000000L);
		writeU32Le(payload, 999L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0x00010005L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0L);
		writeU32Le(payload, 0L);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(JpegFixtures.soi());
		out.write(JpegFixtures.appSegment(0xE1, standardXmp));
		out.write(JpegFixtures.appSegment(0xE2, prefixMpfMagic(payload.toByteArray())));
		out.write(JpegFixtures.minimalScanAndEoi());
		return out.toByteArray();
	}

	private static byte[] prefixMpfMagic(byte[] body)
	{
		// 4-byte MPF magic: 'M','P','F',NUL. String literal getBytes drops the trailing null, so build the
		// signature byte-by-byte.
		byte[] sig = { 'M', 'P', 'F', 0 };
		byte[] result = new byte[sig.length + body.length];
		System.arraycopy(sig, 0, result, 0, sig.length);
		System.arraycopy(body, 0, result, sig.length, body.length);
		return result;
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
}
