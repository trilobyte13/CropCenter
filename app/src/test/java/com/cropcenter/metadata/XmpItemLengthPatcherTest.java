package com.cropcenter.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tests for the XMP Container:Item:Length patcher. The Ultra HDR pipeline preserves the source's XMP
 * packet byte-identically (so all hdrgm attributes — Version, GainMapMin / Max, Gamma, OffsetSDR / HDR,
 * HDRCapacity, BaseRenditionIsHDR — round-trip correctly), but the GContainer Item:Length attribute on
 * the gain-map item carries the source's pre-edit gain-map size and goes stale every time we re-encode
 * at a different quality. Strict GContainer-respecting decoders slice the gain map by Item:Length;
 * without this patch they decode a truncated stream and silently drop HDR boost on a file that's
 * otherwise correct.
 *
 * Pinned contract:
 *   - Single Item:Length="N" in the XMP packet is rewritten to gainMapSize, the surrounding bytes are
 *     unchanged, and the APP1 segLen field is updated to match the new body length.
 *   - Same-digit-count replacement (43099 → 82606) is in-place; the byte array length is unchanged.
 *   - Different-digit-count replacement (43099 → 999999) grows the array by the digit-count delta and
 *     splices accordingly.
 *   - When Item:Length already matches gainMapSize, the patcher returns the input array reference
 *     verbatim (allocation-free no-op, lets callers invoke unconditionally without first parsing).
 *   - Missing XMP segment, missing Item:Length attribute, or non-quote character after the pattern —
 *     all fall through to "return input unchanged" rather than throwing, preserving the export's
 *     fail-closed behaviour from MpfPatcher.
 *   - Both single-quoted ('43099') and double-quoted ("43099") attribute values are recognised, since
 *     XMP serialisers in the wild emit either form.
 */
public final class XmpItemLengthPatcherTest
{
	@Test
	public void patchSameDigitCountReplacesInPlace() throws IOException
	{
		// User's reported scenario: source gain map 43099 bytes, re-encoded to 82606 bytes — both
		// 5-digit values, so the byte length is unchanged and the patch is a 5-byte in-place rewrite.
		byte[] xmpBody = xmpPacketWithLength("43099");
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		byte[] patched = XmpItemLengthPatcher.patch(primary, 82606);

		assertEquals("same-digit-count patch must not change byte length",
			primary.length, patched.length);
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		assertEquals("Item:Length value rewritten to gain-map size", -1, patchedStr.indexOf("43099"));
		assertNotEquals("Item:Length=\"82606\" present in patched bytes", -1,
			patchedStr.indexOf("Item:Length=\"82606\""));
	}

	@Test
	public void patchLongerDigitCountGrowsArrayAndUpdatesSegLen() throws IOException
	{
		// 43099 (5 digits) → 999999 (6 digits) → byte array grows by 1, APP1 segLen grows by 1.
		byte[] xmpBody = xmpPacketWithLength("43099");
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		int oldSegLen = readU16Be(primary, 4);   // segLen sits at offset 4 (SOI + FF E1 + segLen u16)
		byte[] patched = XmpItemLengthPatcher.patch(primary, 999999);

		assertEquals("array grew by digit-count delta", primary.length + 1, patched.length);
		int newSegLen = readU16Be(patched, 4);
		assertEquals("APP1 segLen grew by digit-count delta", oldSegLen + 1, newSegLen);
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		assertNotEquals(-1, patchedStr.indexOf("Item:Length=\"999999\""));
	}

	@Test
	public void patchShorterDigitCountShrinksArrayAndUpdatesSegLen() throws IOException
	{
		// 99999 (5 digits) → 100 (3 digits) — byte array shrinks by 2, APP1 segLen shrinks by 2.
		byte[] xmpBody = xmpPacketWithLength("99999");
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		int oldSegLen = readU16Be(primary, 4);
		byte[] patched = XmpItemLengthPatcher.patch(primary, 100);

		assertEquals("array shrunk by digit-count delta", primary.length - 2, patched.length);
		assertEquals("APP1 segLen shrunk by digit-count delta", oldSegLen - 2, readU16Be(patched, 4));
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		assertNotEquals(-1, patchedStr.indexOf("Item:Length=\"100\""));
		assertEquals("old digit string fully replaced", -1, patchedStr.indexOf("99999"));
	}

	@Test
	public void patchAlreadyCorrectIsAllocationFreeNoOp() throws IOException
	{
		// When the source already has the correct value (e.g. a re-export that didn't change the gain
		// map), patch returns the input array reference unchanged so callers don't pay for a copy.
		byte[] xmpBody = xmpPacketWithLength("82606");
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		byte[] result = XmpItemLengthPatcher.patch(primary, 82606);
		assertSame("return same reference when no patch is needed", primary, result);
	}

	@Test
	public void patchSingleQuotedValueIsRecognised() throws IOException
	{
		// XMP serialisers emit either '...' or "..." for attribute values. Both forms must patch.
		String packet = JpegSegment.XMP_HEADER
			+ "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
			+ "<rdf:Description Item:Length='43099' />"
			+ "</x:xmpmeta>";
		byte[] xmpBody = packet.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		byte[] patched = XmpItemLengthPatcher.patch(primary, 82606);
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		assertNotEquals("single-quoted Item:Length rewritten", -1, patchedStr.indexOf("Item:Length='82606'"));
	}

	@Test
	public void patchMissingXmpSegmentReturnsInputUnchanged() throws IOException
	{
		// SOI + EXIF (not XMP) + scan + EOI — no XMP packet to patch, fall through cleanly.
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, JpegFixtures.exifAppPayload()),
			JpegFixtures.minimalScanAndEoi());

		assertSame(primary, XmpItemLengthPatcher.patch(primary, 82606));
	}

	@Test
	public void patchXmpWithoutItemLengthReturnsInputUnchanged() throws IOException
	{
		// XMP packet with no Item:Length attribute — common for non-Ultra-HDR XMP that just carries
		// hdrgm:Version or other metadata. Patcher must not throw or mutate; just bail.
		String packet = JpegSegment.XMP_HEADER
			+ "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><hdrgm:Version>1.0</hdrgm:Version></x:xmpmeta>";
		byte[] xmpBody = packet.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		assertSame(primary, XmpItemLengthPatcher.patch(primary, 82606));
	}

	@Test
	public void patchSurroundingBytesUnchanged() throws IOException
	{
		// Pin that the patcher only modifies the digit run and the segLen field — everything else
		// (XMP body content before / after the Item:Length, EXIF segment, scan body, EOI) must remain
		// byte-identical so a successful patch can never corrupt unrelated metadata.
		byte[] xmpBody = xmpPacketWithLength("43099");
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, JpegFixtures.exifAppPayload()),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		byte[] patched = XmpItemLengthPatcher.patch(primary, 82606);
		// EXIF APP1 segment must be byte-identical (same start, same content)
		int exifStart = 2;   // immediately after SOI
		int exifLen = 4 + JpegFixtures.exifAppPayload().length;   // FF E1 + segLen + body
		byte[] exifBefore = sliceCopy(primary, exifStart, exifLen);
		byte[] exifAfter = sliceCopy(patched, exifStart, exifLen);
		assertArrayEquals("EXIF segment must not be touched by XMP patch", exifBefore, exifAfter);

		// EOI tail must be byte-identical
		byte[] eoiTail = { (byte) 0xFF, (byte) 0xD9 };
		assertArrayEquals(eoiTail, sliceCopy(patched, patched.length - 2, 2));
	}

	private static byte[] sliceCopy(byte[] src, int off, int len)
	{
		byte[] out = new byte[len];
		System.arraycopy(src, off, out, 0, len);
		return out;
	}

	private static int readU16Be(byte[] data, int off)
	{
		return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
	}

	@Test
	public void patchReturnsNullWhenItemLengthLivesInExtendedXmp() throws IOException
	{
		// Codex round-25 F1: when GContainer Item:Length lives in an Extended XMP chunk (the >64KB
		// packet form), in-place patching would desync the per-chunk reassembly headers shared across
		// chunks. The patcher signals fail-closed via null so GainMapComposer can drop HDR rather than
		// ship stale Item:Length data that strict decoders would interpret as a truncated gain map.
		// Setup: standard XMP carries hdrgm declaration only (no Item:Length); Extended XMP chunk
		// carries the Container Item:Length attribute.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<x:xmpmeta><hdrgm:Version>1.0</hdrgm:Version>"
			+ "<xmpNote:HasExtendedXMP>0123456789abcdef0123456789abcdef</xmpNote:HasExtendedXMP>"
			+ "</x:xmpmeta>").getBytes(StandardCharsets.US_ASCII);
		byte[] extXmpBody = extendedXmpChunkBody(
			"<rdf:Description Item:Length=\"43099\" Item:Mime=\"image/jpeg\"/>");
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, standardXmp),
			JpegFixtures.appSegment(0xE1, extXmpBody),
			JpegFixtures.minimalScanAndEoi());

		assertNull("Item:Length in Extended XMP must signal fail-closed via null",
			XmpItemLengthPatcher.patch(primary, 82606));
	}

	@Test
	public void patchReturnsNullWhenItemLengthStraddlesExtendedXmpChunks() throws IOException
	{
		// Codex round-26 F1 — when the 12-byte "Item:Length=" pattern straddles an Adobe Extended XMP
		// chunk boundary, the per-chunk substring scan misses it but a reassembly-based scan catches
		// it. Without the fallback, the patcher returns primary unchanged and the file ships with stale
		// Item:Length, defeating the round-25 fail-closed guard. Build a 2-chunk Extended XMP that
		// splits "Item:Length=" precisely between chunks.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<x:xmpmeta><hdrgm:Version>1.0</hdrgm:Version>"
			+ "<xmpNote:HasExtendedXMP>0123456789abcdef0123456789abcdef</xmpNote:HasExtendedXMP>"
			+ "</x:xmpmeta>").getBytes(StandardCharsets.US_ASCII);
		// Chunk 1 ends with "Item:Le"; chunk 2 starts with "ngth=\"43099\"". Per-chunk scan finds neither
		// the full "Item:Length=" in chunk 1 (truncated) nor in chunk 2 (also truncated), but reassembly
		// concatenates them into "Item:Length=\"43099\"" which the fallback finds.
		byte[] chunk1Body = extendedXmpChunkBody("0123456789abcdef0123456789abcdef", 0,
			"<rdf:Description Item:Le");
		byte[] chunk2Body = extendedXmpChunkBody("0123456789abcdef0123456789abcdef", 24,
			"ngth=\"43099\" Item:Mime=\"image/jpeg\"/>");
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, standardXmp),
			JpegFixtures.appSegment(0xE1, chunk1Body),
			JpegFixtures.appSegment(0xE1, chunk2Body),
			JpegFixtures.minimalScanAndEoi());

		assertNull("Item:Length straddling Extended XMP chunks must signal fail-closed via null",
			XmpItemLengthPatcher.patch(primary, 82606));
	}

	@Test
	public void patchSucceedsWhenStandardXmpHasItemLengthEvenWithExtendedXmpAlongside() throws IOException
	{
		// Mixed scenario: standard XMP has Item:Length AND Extended XMP exists alongside (without
		// Item:Length). The patcher must successfully patch the standard XMP and not be tripped up by
		// the presence of Extended XMP — the fail-closed gate only fires when Extended XMP itself
		// carries Item:Length.
		byte[] standardXmp = xmpPacketWithLength("43099");
		byte[] extXmpBody = extendedXmpChunkBody(
			"<x:xmpmeta><hdrgm:HDRCapacityMin>0</hdrgm:HDRCapacityMin></x:xmpmeta>");
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, standardXmp),
			JpegFixtures.appSegment(0xE1, extXmpBody),
			JpegFixtures.minimalScanAndEoi());

		byte[] patched = XmpItemLengthPatcher.patch(primary, 82606);
		assertNotNull("standard-XMP patch path must succeed even with Extended XMP alongside", patched);
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		assertNotEquals(-1, patchedStr.indexOf("Item:Length=\"82606\""));
	}

	@Test
	public void patchAcceptsSegLenAtTheU16FieldMax() throws IOException
	{
		// Codex round-25 F2: the cap was previously the body cap (65533) instead of the segLen-field
		// cap (65535), unnecessarily rejecting two valid sizes. Pin that a patch growing the segment
		// to within 65535 (the u16 segLen-field max) succeeds. Build a body sized so old segLen sits
		// at 65533 (just under the body cap, same as the old cap value); a 2-digit growth (10 → 1000)
		// pushes it to 65535, which used to reject and now must succeed.
		// Body layout: XMP_HEADER (29 bytes) + filler + the Item:Length attribute.
		String tag = "<rdf:Description Item:Length=\"10\"/>";
		int xmpHeaderLen = JpegSegment.XMP_HEADER.length();
		// segLen = 2 (the length bytes) + body. Want oldSegLen = 65533, so body = 65531. Filler =
		// 65531 - xmpHeaderLen - tag.length(). Pad with spaces (whitespace inside an XML element is
		// ignored by parsers, so the Item:Length scan still finds the tag).
		int fillerLen = 65531 - xmpHeaderLen - tag.length();
		StringBuilder filler = new StringBuilder(fillerLen);
		for (int i = 0; i < fillerLen; i++)
		{
			filler.append(' ');
		}
		byte[] xmpBody = (JpegSegment.XMP_HEADER + filler.toString() + tag)
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());
		assertEquals("test fixture pre-condition: old segLen field = 65533",
			65533, readU16Be(primary, 4));

		byte[] patched = XmpItemLengthPatcher.patch(primary, 1000);
		assertNotNull("patch must accept a segLen field value of 65535 (the u16 max)", patched);
		assertEquals("new segLen field = 65535 (old + 2 digit-count delta)",
			65535, readU16Be(patched, 4));
	}

	@Test
	public void patchRefusesWhenSegLenFieldWouldExceedU16Max() throws IOException
	{
		// Counterpart to the prior test: pushing segLen to 65536 (one past the u16 max) MUST be
		// rejected — no representation as a u16 length field. Same fixture shape but a 3-digit growth
		// (10 → 10000) would push segLen to 65536, which fails closed: the patcher returns null
		// (Codex round-27 F2 — previously "fell through to input-unchanged" and shipped stale
		// Item:Length when Extended XMP didn't carry the pattern; now distinguishes "not present"
		// from "present but unpatchable" and signals the caller to drop HDR).
		String tag = "<rdf:Description Item:Length=\"10\"/>";
		int xmpHeaderLen = JpegSegment.XMP_HEADER.length();
		int fillerLen = 65531 - xmpHeaderLen - tag.length();
		StringBuilder filler = new StringBuilder(fillerLen);
		for (int i = 0; i < fillerLen; i++)
		{
			filler.append(' ');
		}
		byte[] xmpBody = (JpegSegment.XMP_HEADER + filler.toString() + tag)
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		assertNull("over-cap patch must fail-closed via null so caller drops HDR",
			XmpItemLengthPatcher.patch(primary, 10000));
	}

	@Test
	public void patchRefusesWhenStandardItemLengthIsMalformed() throws IOException
	{
		// Codex round-27 F2 — when standard XMP carries Item:Length= followed by something other than
		// a quoted digit run (here: an empty value), the patcher must fail-closed rather than fall
		// through to the Extended XMP scan (which would return primary unchanged when Extended XMP
		// doesn't carry the pattern, shipping stale Item:Length data).
		byte[] xmpBody = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=\"\"/>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		assertNull("malformed Item:Length value (empty digits) must fail-closed via null",
			XmpItemLengthPatcher.patch(primary, 82606));
	}

	@Test
	public void patchRefusesWhenStandardItemLengthHasUnquotedValue() throws IOException
	{
		// Same fail-closed contract for "Item:Length=" followed by a non-quote character (e.g., a bare
		// number, common in hand-written but spec-violating XMP). The patcher's quote check must reject
		// rather than fall through.
		byte[] xmpBody = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=43099/>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		assertNull("unquoted Item:Length value must fail-closed via null",
			XmpItemLengthPatcher.patch(primary, 82606));
	}

	@Test
	public void patchRefusesWhenStandardItemLengthHasUnterminatedDigitRun() throws IOException
	{
		// Codex round-28 A4.2 — body ends mid-digit-run (no closing quote). The patcher's
		// `digitsEnd >= xmpBodyEnd` sub-condition must reject rather than fall through. This
		// pins one of the OR-ed sub-conditions in patchInSegment that the existing
		// patchRefusesWhenStandardItemLengthIsMalformed test doesn't cover (that one exercises
		// only the empty-digit-run sub-condition).
		byte[] xmpBody = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=\"43099")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		assertNull("unterminated digit run must fail-closed via null",
			XmpItemLengthPatcher.patch(primary, 82606));
	}

	@Test
	public void patchRefusesWhenStandardItemLengthHasMismatchedClosingQuote() throws IOException
	{
		// Codex round-28 A4.2 — body opens with `"` and closes with `'`. The patcher's
		// `primary[digitsEnd] != quote` sub-condition must reject. This pins the third sub-condition
		// of the OR-ed malformed-digits check in patchInSegment.
		byte[] xmpBody = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=\"43099'/>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		assertNull("mismatched-quote close must fail-closed via null",
			XmpItemLengthPatcher.patch(primary, 82606));
	}

	@Test
	public void patchRefusesWhenStandardItemLengthEndsAtBodyBoundary() throws IOException
	{
		// Codex round-28 A4.1 — body's final 12 bytes are exactly "Item:Length=" with no following
		// byte. patchInSegment's `valueStart >= xmpBodyEnd` guard must reject; without it the
		// subsequent `primary[valueStart]` read would AIOOBE. Pad the body to land the pattern at
		// the exact tail.
		byte[] xmpBody = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		assertNull("Item:Length= at exact body tail must fail-closed via null",
			XmpItemLengthPatcher.patch(primary, 82606));
	}

	@Test
	public void patchReturnsInputUnchangedForNullPrimary()
	{
		// Codex round-28 A4.3 — null primary is the documented input-validation fall-through.
		// Returns the same null reference so callers can invoke unconditionally.
		assertNull("null primary returns null", XmpItemLengthPatcher.patch(null, 82606));
	}

	@Test
	public void patchReturnsInputUnchangedForNegativeGainMapSize() throws IOException
	{
		// Codex round-28 A4.3 — negative gainMapSize is a caller bug guarded by an early return,
		// preserving the input array reference unchanged. Without the guard the patcher would write
		// "-1" or similar into the Item:Length attribute, an invalid u32 length per the GContainer
		// spec.
		byte[] xmpBody = xmpPacketWithLength("43099");
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, xmpBody),
			JpegFixtures.minimalScanAndEoi());

		assertSame("negative gainMapSize returns input verbatim",
			primary, XmpItemLengthPatcher.patch(primary, -1));
	}

	@Test
	public void patchSucceedsForItemLengthInLaterStandardXmpSegment() throws IOException
	{
		// Codex round-28 F1 — non-conformant source with TWO standard XMP APP1 segments. The first
		// holds hdrgm:Version etc. (no Item:Length); the second holds the GContainer Directory with
		// Item:Length. The patcher must walk ALL standard XMP segments — returning on the first
		// "notPresent" would miss the second segment, fall through to Extended XMP scanning (wrong
		// namespace prefix, no match), and ship stale Item:Length.
		byte[] standardXmp1 = (JpegSegment.XMP_HEADER + "<x:xmpmeta>"
			+ "<hdrgm:Version>1.0</hdrgm:Version></x:xmpmeta>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] standardXmp2 = xmpPacketWithLength("43099");
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, standardXmp1),
			JpegFixtures.appSegment(0xE1, standardXmp2),
			JpegFixtures.minimalScanAndEoi());

		byte[] patched = XmpItemLengthPatcher.patch(primary, 82606);
		assertNotNull("multi-standard-XMP file must patch the segment that carries Item:Length",
			patched);
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		assertNotEquals("82606 should appear in the patched output", -1,
			patchedStr.indexOf("Item:Length=\"82606\""));
		assertEquals("source 43099 fully replaced", -1, patchedStr.indexOf("43099"));
	}

	@Test
	public void patchFailClosedOnFirstSegmentSkipsSecondSegment() throws IOException
	{
		// Codex round-29 A4.1 — when the first standard XMP segment carries an unpatchable Item:Length
		// (over-cap, malformed, etc), patch() must short-circuit with null and NOT inspect the second
		// segment. Verifies the contract "any failClosed result short-circuits" against the multi-
		// segment walker. Otherwise a regression that continued the loop would silently patch the
		// second segment and ship a file where segment #1's malformed attribute remains stale.
		byte[] standardXmp1 = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=\"\"/>")
			.getBytes(StandardCharsets.US_ASCII);   // empty digit run → failClosed
		byte[] standardXmp2 = xmpPacketWithLength("43099");
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, standardXmp1),
			JpegFixtures.appSegment(0xE1, standardXmp2),
			JpegFixtures.minimalScanAndEoi());

		assertNull("failClosed on segment #1 must short-circuit before segment #2 is reached",
			XmpItemLengthPatcher.patch(primary, 82606));
	}

	@Test
	public void patchOnFirstSegmentSkipsSecondSegment() throws IOException
	{
		// Codex round-29 A4.3 — when the first standard XMP segment patches successfully, the loop
		// must return immediately without re-applying patchInSegment to a second standard XMP segment
		// that also carries Item:Length. A regression that didn't short-circuit on success would emit
		// double-patched output (or worse, attempt to patch the SAME byte range twice with stale
		// offsets).
		byte[] standardXmp1 = xmpPacketWithLength("43099");
		byte[] standardXmp2 = xmpPacketWithLength("99999");   // also matchable but should be ignored
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, standardXmp1),
			JpegFixtures.appSegment(0xE1, standardXmp2),
			JpegFixtures.minimalScanAndEoi());

		byte[] patched = XmpItemLengthPatcher.patch(primary, 82606);
		assertNotNull("first matching segment must patch successfully", patched);
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		// The first segment's 43099 → 82606. The second segment's 99999 must remain untouched.
		assertNotEquals("first segment patched", -1, patchedStr.indexOf("Item:Length=\"82606\""));
		assertNotEquals("second segment kept its 99999 verbatim", -1,
			patchedStr.indexOf("Item:Length=\"99999\""));
		assertEquals("source 43099 fully replaced", -1, patchedStr.indexOf("43099"));
	}

	@Test
	public void patchHonorsFillBytesBeforeExtendedXmpFailClosed() throws IOException
	{
		// Codex round-32 T1 — fill-byte handling on the Extended-XMP fail-closed path. Standard XMP
		// has no Item:Length; the Extended XMP chunk is preceded by a fill byte (`FF FF E1 ...`) and
		// carries Item:Length in its body. The walker must route through skipFillBytes so the
		// fail-closed gate fires. A regression to direct `primary[off+1]` reads here would let the
		// stale Item:Length ship and silently truncate HDR boost — the very leak round-25/26/27
		// fail-closed paths exist to prevent. Pins extendedXmpContainsItemLength's walker fix.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<x:xmpmeta>"
			+ "<hdrgm:Version>1.0</hdrgm:Version></x:xmpmeta>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] extXmpBody = extendedXmpChunkBody(
			"<rdf:Description Item:Length=\"43099\" Item:Mime=\"image/jpeg\"/>");
		ByteArrayOutputStream withFill = new ByteArrayOutputStream();
		withFill.write(JpegFixtures.soi());
		withFill.write(JpegFixtures.appSegment(0xE1, standardXmp));
		// Insert a fill byte before the Extended XMP segment's FF E1.
		withFill.write(0xFF);
		withFill.write(JpegFixtures.appSegment(0xE1, extXmpBody));
		withFill.write(JpegFixtures.minimalScanAndEoi());
		byte[] primary = withFill.toByteArray();

		assertNull("fill-byte-prefixed Extended XMP carrying Item:Length must fail closed",
			XmpItemLengthPatcher.patch(primary, 82606));
	}

	@Test
	public void patchHonorsFillBytesBeforeXmpApp1Marker() throws IOException
	{
		// Codex round-31 F2 — legal JPEG fill bytes (extra 0xFF before the marker code, ITU-T T.81
		// §B.1.1.2) must not break the multi-segment walker. Fixture: SOI, then a `FF FF E1 ...` XMP
		// segment carrying GContainer Item:Length. A regression that reads primary[off+1] directly
		// (treating the second 0xFF as a stuck marker) would advance past the segment without
		// reading it, leaving Item:Length unpatched. The fixed walker routes through
		// JpegMarkerWalker.skipFillBytes and patches correctly.
		byte[] xmpBody = xmpPacketWithLength("43099");
		ByteArrayOutputStream withFill = new ByteArrayOutputStream();
		withFill.write(JpegFixtures.soi());
		// Insert a fill byte before the FF E1 marker pair so the segment is `FF FF E1 LL LL ...`
		// (canonical FF + the marker code follows).
		withFill.write(0xFF);
		withFill.write(JpegFixtures.appSegment(0xE1, xmpBody));
		withFill.write(JpegFixtures.minimalScanAndEoi());
		byte[] primary = withFill.toByteArray();

		byte[] patched = XmpItemLengthPatcher.patch(primary, 82606);
		assertNotNull("fill-byte-prefixed XMP must still patch (no walker-stall regression)", patched);
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		assertNotEquals(-1, patchedStr.indexOf("Item:Length=\"82606\""));
	}

	@Test
	public void patchReturnsPrimaryWhenStandardMissesAndExtendedXmpInnocent() throws IOException
	{
		// Codex round-28 A4.5 — reciprocal of patchSucceedsWhenStandardXmpHasItemLengthEvenWith-
		// ExtendedXmpAlongside. Standard XMP has no Item:Length; Extended XMP exists alongside but
		// also doesn't carry Item:Length. Both Extended-XMP scans (per-chunk + reassembly) must
		// return false and `patch()` must return primary unchanged — not null. A regression that
		// over-aggressively flags any Extended XMP presence as "Item:Length straddle" would drop
		// HDR for any file with Extended XMP carrying unrelated metadata (Item:Mime, Item:Semantic,
		// xmpNote:HasExtendedXMP, etc.) — common in real Samsung Ultra HDR.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<x:xmpmeta>"
			+ "<hdrgm:Version>1.0</hdrgm:Version></x:xmpmeta>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] extXmpInnocent = extendedXmpChunkBody(
			"<rdf:Description Item:Mime=\"image/jpeg\" Item:Semantic=\"GainMap\"/>");
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, standardXmp),
			JpegFixtures.appSegment(0xE1, extXmpInnocent),
			JpegFixtures.minimalScanAndEoi());

		assertSame("Extended XMP without Item:Length must NOT trigger fail-closed",
			primary, XmpItemLengthPatcher.patch(primary, 82606));
	}

	private static byte[] extendedXmpChunkBody(String innerXml)
	{
		// Single-chunk convenience overload — uses a fixed GUID and offset 0. Suitable for tests that
		// only check per-chunk pattern detection without exercising reassembly's GUID grouping or
		// offset sorting.
		return extendedXmpChunkBody("0123456789abcdef0123456789abcdef", 0, innerXml);
	}

	private static byte[] extendedXmpChunkBody(String guid, long offset, String innerXml)
	{
		// Adobe Extended XMP chunk shape: namespace prefix (35 bytes incl. NUL) + 32-byte ASCII GUID
		// + 4-byte total-length BE + 4-byte offset BE + chunk payload bytes. The total-length field is
		// a placeholder — ExtendedXmpReassembler.reassemble doesn't validate it. The offset field is
		// honoured for sort order, so callers building multi-chunk fixtures must pass distinct offsets
		// in body-order.
		String prefix = "http://ns.adobe.com/xmp/extension/\0";
		byte[] inner = innerXml.getBytes(StandardCharsets.US_ASCII);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try
		{
			out.write(prefix.getBytes(StandardCharsets.US_ASCII));
			out.write(guid.getBytes(StandardCharsets.US_ASCII));
			out.write(new byte[] { 0, 0, 0x10, 0 });
			out.write(new byte[] {
				(byte) ((offset >> 24) & 0xFF),
				(byte) ((offset >> 16) & 0xFF),
				(byte) ((offset >> 8) & 0xFF),
				(byte) (offset & 0xFF),
			});
			out.write(inner);
		}
		catch (IOException e)
		{
			// ByteArrayOutputStream.write doesn't actually throw IOException; the catch keeps the test
			// helper signature clean and the build is configured to flag genuinely-unreachable swallows.
			throw new AssertionError(e);
		}
		return out.toByteArray();
	}

	private static byte[] xmpPacketWithLength(String length)
	{
		// Mirrors the Container:Item shape Samsung's Ultra HDR exporter emits, with a single Item:Length
		// attribute on the gain-map item. Per the GContainer schema the Primary item omits Length (its
		// length is implicit), so the patcher's first-match strategy lands on the gain-map item.
		String packet = JpegSegment.XMP_HEADER
			+ "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF>"
			+ "<rdf:Description xmlns:Container=\"http://ns.google.com/photos/1.0/container/\""
			+ " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""
			+ " xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\">"
			+ "<Container:Directory><rdf:Seq>"
			+ "<rdf:li rdf:parseType=\"Resource\">"
			+ "<Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li>"
			+ "<rdf:li rdf:parseType=\"Resource\">"
			+ "<Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\""
			+ " Item:Length=\"" + length + "\"/></rdf:li>"
			+ "</rdf:Seq></Container:Directory>"
			+ "</rdf:Description></rdf:RDF></x:xmpmeta>";
		return packet.getBytes(StandardCharsets.US_ASCII);
	}
}
