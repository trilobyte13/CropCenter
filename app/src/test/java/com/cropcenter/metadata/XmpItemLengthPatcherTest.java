package com.cropcenter.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.cropcenter.util.ByteBufferUtils;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Tests for the XMP Container:Item:Length patcher. The Ultra HDR pipeline preserves the source's XMP packet
 * byte-identically (so all hdrgm attributes — Version, GainMapMin / Max, Gamma, OffsetSDR / HDR, HDRCapacity,
 * BaseRenditionIsHDR — round-trip correctly), but the GContainer Item:Length attribute on the gain-map item carries the
 * source's pre-edit gain-map size and goes stale every time we re-encode at a different quality. Strict
 * GContainer-respecting decoders slice the gain map by Item:Length; without this patch they decode a truncated stream
 * and silently drop HDR boost on a file that's otherwise correct.
 *
 * Pinned contract:
 *   - Single Item:Length="N" in the XMP packet is rewritten to gainMapSize, the surrounding bytes are
 *     unchanged, and the APP1 segLen field is updated to match the new body length.
 *   - Same-digit-count replacement (43099 → 82606) is in-place; the byte array length is unchanged.
 *   - Different-digit-count replacement (43099 → 999999) grows the array by the digit-count delta and
 *     splices accordingly.
 *   - When Item:Length already matches gainMapSize, the patcher returns the input array reference
 *     verbatim (allocation-free no-op, lets callers invoke unconditionally without first parsing).
 *   - Missing XMP segment or missing Item:Length attribute falls through to "return input unchanged"
 *     rather than throwing; malformed values (non-quoted, empty / unterminated digit run, mismatched
 *     closing quote) fail closed via empty.
 *   - Both single-quoted ('43099') and double-quoted ("43099") attribute values are recognised, since
 *     XMP serialisers in the wild emit either form.
 *   - Whitespace around the attribute's '=' (XML 1.0 Name S? '=' S? Value) is tolerated by every
 *     Item:Length scan — the patch path, the per-chunk Extended XMP scan, and the reassembled-bytes
 *     scan.
 *   - A packet with several Item:Length attributes (the Google MotionPhoto shape) patches only the
 *     occurrence anchored to an Item:Semantic="GainMap" element; zero or multiple anchored
 *     occurrences fail closed via empty.
 */
public final class XmpItemLengthPatcherTest
{
	@Test
	public void patchAcceptsSegLenAtTheU16FieldMax() throws IOException
	{
		// The cap is the segLen-field cap (65535), not the body cap (65533) — a body-cap regression would
		// unnecessarily reject two valid sizes. Pin that a patch growing the segment to within 65535 (the u16
		// segLen-field max) succeeds. Build a body sized so old segLen sits at 65533; a 2-digit growth (10 →
		// 1000) pushes it to 65535 — exactly the value a body-cap regression would reject and the field cap
		// must accept. Body layout: XMP_HEADER (29 bytes) + filler + the Item:Length attribute.
		String tag = "<rdf:Description Item:Length=\"10\"/>";
		int xmpHeaderLen = JpegSegment.XMP_HEADER.length();
		// segLen = 2 (the length bytes) + body. Want oldSegLen = 65533, so body = 65531. Filler = 65531
		// -xmpHeaderLen - tag.length(). Pad with spaces (whitespace inside an XML element is ignored by
		// parsers, so the Item:Length scan still finds the tag).
		int fillerLen = 65531 - xmpHeaderLen - tag.length();
		String filler = " ".repeat(fillerLen);
		byte[] xmpBody = (JpegSegment.XMP_HEADER + filler + tag).getBytes(StandardCharsets.US_ASCII);
		byte[] primary = primaryWithApp1(xmpBody);
		assertEquals("test fixture pre-condition: old segLen field = 65533", 65533,
			ByteBufferUtils.readU16BE(primary, 4));

		Optional<byte[]> result = XmpItemLengthPatcher.patch(primary, 1000);
		assertTrue("patch must accept a segLen field value of 65535 (the u16 max)", result.isPresent());
		byte[] patched = result.orElseThrow();
		assertEquals("new segLen field = 65535 (old + 2 digit-count delta)", 65535,
			ByteBufferUtils.readU16BE(patched, 4));
	}

	@Test
	public void patchAlreadyCorrectIsAllocationFreeNoOp() throws IOException
	{
		// When the source already has the correct value (e.g. a re-export that didn't change the gain map),
		// patch returns the input array reference unchanged so callers don't pay for a copy.
		byte[] xmpBody = xmpPacketWithLength("82606");
		byte[] primary = primaryWithApp1(xmpBody);

		byte[] result = XmpItemLengthPatcher.patch(primary, 82606).orElseThrow();
		assertSame("return same reference when no patch is needed", primary, result);
	}

	@Test
	public void patchFailClosedOnFirstSegmentSkipsSecondSegment() throws IOException
	{
		// When the first standard XMP segment carries an unpatchable Item:Length (over-cap, malformed, etc),
		// patch() must short-circuit with empty and NOT inspect the second segment. Verifies the contract "any
		// failClosed result short-circuits" against the multi-segment walker. Otherwise a regression that
		// continued the loop would silently patch the second segment and ship a file where segment #1's
		// malformed attribute remains stale.
		byte[] standardXmp1 = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=\"\"/>")
			.getBytes(StandardCharsets.US_ASCII);   // empty digit run → failClosed
		byte[] standardXmp2 = xmpPacketWithLength("43099");
		byte[] primary = primaryWithApp1(standardXmp1, standardXmp2);

		assertTrue("failClosed on segment #1 must short-circuit before segment #2 is reached",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchHonorsFillBytesBeforeExtendedXmpFailClosed() throws IOException
	{
		// Fill-byte handling on the Extended-XMP fail-closed path. Standard XMP has no Item:Length; the
		// Extended XMP chunk is preceded by a fill byte (`FF FF E1 ...`) and carries Item:Length in its body.
		// The walker must route through skipFillBytes so the fail-closed gate fires. A regression to direct
		// `primary[off+1]` reads here would let the stale Item:Length ship and silently truncate HDR boost —
		// the very leak the fail-closed paths exist to prevent. Pins extendedXmpContainsItemLength's walker.
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

		assertTrue("fill-byte-prefixed Extended XMP carrying Item:Length must fail closed",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchHonorsFillBytesBeforeXmpApp1Marker() throws IOException
	{
		// Legal JPEG fill bytes (extra 0xFF before the marker code, ITU-T T.81 §B.1.1.2) must not break the
		// multi-segment walker. Fixture: SOI, then a `FF FF E1 ...` XMP segment carrying GContainer
		// Item:Length. A regression that reads primary[off+1] directly (treating the second 0xFF as a stuck
		// marker) would advance past the segment without reading it, leaving Item:Length unpatched. The fixed
		// walker routes through JpegMarkerWalker.skipFillBytes and patches correctly.
		byte[] xmpBody = xmpPacketWithLength("43099");
		ByteArrayOutputStream withFill = new ByteArrayOutputStream();
		withFill.write(JpegFixtures.soi());
		// Insert a fill byte before the FF E1 marker pair so the segment is `FF FF E1 LL LL ...` (canonical FF
		// + the marker code follows).
		withFill.write(0xFF);
		withFill.write(JpegFixtures.appSegment(0xE1, xmpBody));
		withFill.write(JpegFixtures.minimalScanAndEoi());
		byte[] primary = withFill.toByteArray();

		Optional<byte[]> result = XmpItemLengthPatcher.patch(primary, 82606);
		assertTrue("fill-byte-prefixed XMP must still patch (no walker-stall regression)", result.isPresent());
		String patchedStr = new String(result.orElseThrow(), StandardCharsets.US_ASCII);
		assertNotEquals(-1, patchedStr.indexOf("Item:Length=\"82606\""));
	}

	@Test
	public void patchLongerDigitCountGrowsArrayAndUpdatesSegLen() throws IOException
	{
		// 43099 (5 digits) → 999999 (6 digits) → byte array grows by 1, APP1 segLen grows by 1.
		byte[] xmpBody = xmpPacketWithLength("43099");
		byte[] primary = primaryWithApp1(xmpBody);

		// segLen sits at offset 4 (SOI + FF E1 + segLen u16).
		int oldSegLen = ByteBufferUtils.readU16BE(primary, 4);
		byte[] patched = XmpItemLengthPatcher.patch(primary, 999999).orElseThrow();

		assertEquals("array grew by digit-count delta", primary.length + 1, patched.length);
		int newSegLen = ByteBufferUtils.readU16BE(patched, 4);
		assertEquals("APP1 segLen grew by digit-count delta", oldSegLen + 1, newSegLen);
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		assertNotEquals(-1, patchedStr.indexOf("Item:Length=\"999999\""));
	}

	@Test
	public void patchMissingXmpSegmentReturnsInputUnchanged() throws IOException
	{
		// SOI + EXIF (not XMP) + scan + EOI — no XMP packet to patch, fall through cleanly.
		byte[] primary = primaryWithApp1(JpegFixtures.exifAppPayload());

		assertSame(primary, XmpItemLengthPatcher.patch(primary, 82606).orElseThrow());
	}

	@Test
	public void patchMotionPhotoShapedPacketPatchesGainMapItemOnly() throws IOException
	{
		// Google MotionPhoto-shaped packet: the Primary item declares Item:Length="0" BEFORE the GainMap item's
		// Item:Length. A first-occurrence patch would rewrite the Primary's "0" and ship the gain map's stale
		// length with hdrAttached=true. The patcher must anchor on Item:Semantic="GainMap" and rewrite only
		// that item's length.
		byte[] xmpBody = motionPhotoPacket("Primary", "0", "GainMap", "43099");
		byte[] primary = primaryWithApp1(xmpBody);

		Optional<byte[]> result = XmpItemLengthPatcher.patch(primary, 82606);
		assertTrue("GainMap-anchored occurrence must patch", result.isPresent());
		String patchedStr = new String(result.orElseThrow(), StandardCharsets.US_ASCII);
		assertNotEquals("GainMap item's length rewritten", -1, patchedStr.indexOf(
			"Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\"82606\""));
		assertNotEquals("Primary item's Item:Length=\"0\" untouched", -1, patchedStr.indexOf(
			"Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\" Item:Length=\"0\""));
		assertEquals("stale gain-map length fully replaced", -1, patchedStr.indexOf("43099"));
	}

	@Test
	public void patchOnFirstSegmentSkipsSecondSegment() throws IOException
	{
		// When the first standard XMP segment patches successfully, the loop must return immediately without
		// re-applying patchInSegment to a second standard XMP segment that also carries Item:Length. A
		// regression that didn't short-circuit on success would emit double-patched output (or worse, attempt
		// to patch the SAME byte range twice with stale offsets).
		byte[] standardXmp1 = xmpPacketWithLength("43099");
		byte[] standardXmp2 = xmpPacketWithLength("99999");   // also matchable but should be ignored
		byte[] primary = primaryWithApp1(standardXmp1, standardXmp2);

		Optional<byte[]> result = XmpItemLengthPatcher.patch(primary, 82606);
		assertTrue("first matching segment must patch successfully", result.isPresent());
		String patchedStr = new String(result.orElseThrow(), StandardCharsets.US_ASCII);
		// The first segment's 43099 → 82606. The second segment's 99999 must remain untouched.
		assertNotEquals("first segment patched", -1, patchedStr.indexOf("Item:Length=\"82606\""));
		assertNotEquals("second segment kept its 99999 verbatim", -1,
			patchedStr.indexOf("Item:Length=\"99999\""));
		assertEquals("source 43099 fully replaced", -1, patchedStr.indexOf("43099"));
	}

	@Test
	public void patchRefusesWhenMultipleItemLengthsLackGainMapAnchor() throws IOException
	{
		// Several Item:Length occurrences but no element carries Item:Semantic="GainMap" (e.g. a motion photo
		// whose second item is the video track). There is no safe patch site — a first-match rewrite would
		// corrupt an unrelated item's length. Must fail closed via empty, not fall through to the Extended XMP
		// scan (which would return primary unchanged and ship stale data).
		byte[] xmpBody = motionPhotoPacket("Primary", "0", "MotionPhoto", "43099");
		byte[] primary = primaryWithApp1(xmpBody);

		assertTrue("multi-occurrence packet without a GainMap anchor must fail-closed via empty",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchRefusesWhenOnlySemanticIsGainMapSuperset() throws IOException
	{
		// Multi-Item:Length packet whose only anchor candidate carries the SUPERSET semantic
		// Item:Semantic="GainMapMeta" (with a Primary item's Item:Length="0" as the second occurrence). The
		// anchoring scan requires the closing quote immediately after "GainMap", so "GainMapMeta" must NOT
		// anchor — zero anchored occurrences among several, and patch fails closed via empty. Relaxing the
		// closing-quote check to a prefix match would anchor the GainMapMeta container item and rewrite ITS
		// Item:Length with the gain map's size — a corrupt GContainer directory instead of the safe HDR-drop.
		byte[] xmpBody = motionPhotoPacket("GainMapMeta", "43099", "Primary", "0");
		byte[] primary = primaryWithApp1(xmpBody);

		assertTrue("superset GainMapMeta semantic must not anchor; zero anchors must fail-closed via empty",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchRefusesWhenSegLenFieldWouldExceedU16Max() throws IOException
	{
		// Counterpart to the prior test: pushing segLen to 65536 (one past the u16 max) MUST be rejected — no
		// representation as a u16 length field. Same fixture shape but a 3-digit growth (10 → 10000) would push
		// segLen to 65536, which fails closed: the patcher returns empty (a fall-through-to-input-unchanged
		// behaviour would ship stale Item:Length when Extended XMP didn't carry the pattern; the patcher
		// distinguishes "not present" from "present but unpatchable" and signals the caller to drop HDR).
		String tag = "<rdf:Description Item:Length=\"10\"/>";
		int xmpHeaderLen = JpegSegment.XMP_HEADER.length();
		int fillerLen = 65531 - xmpHeaderLen - tag.length();
		String filler = " ".repeat(fillerLen);
		byte[] xmpBody = (JpegSegment.XMP_HEADER + filler + tag).getBytes(StandardCharsets.US_ASCII);
		byte[] primary = primaryWithApp1(xmpBody);

		assertTrue("over-cap patch must fail-closed via empty so caller drops HDR",
			XmpItemLengthPatcher.patch(primary, 10000).isEmpty());
	}

	@Test
	public void patchRefusesWhenStandardItemLengthEndsAtBodyBoundary() throws IOException
	{
		// Body's final 12 bytes are exactly "Item:Length=" with no following byte. patchInSegment's `valueStart
		// >= xmpBodyEnd` guard must reject; without it the subsequent `primary[valueStart]` read would AIOOBE.
		// Pad the body to land the pattern at the exact tail.
		byte[] xmpBody = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = primaryWithApp1(xmpBody);

		assertTrue("Item:Length= at exact body tail must fail-closed via empty",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchRefusesWhenStandardItemLengthHasMismatchedClosingQuote() throws IOException
	{
		// Body opens with `"` and closes with `'`. The patcher's `primary[digitsEnd] != quote` sub-condition
		// must reject. This pins the third sub-condition of the OR-ed malformed-digits check in patchInSegment.
		byte[] xmpBody = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=\"43099'/>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = primaryWithApp1(xmpBody);

		assertTrue("mismatched-quote close must fail-closed via empty",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchRefusesWhenStandardItemLengthHasUnquotedValue() throws IOException
	{
		// Same fail-closed contract for "Item:Length=" followed by a non-quote character (e.g., a bare number,
		// common in hand-written but spec-violating XMP). The patcher's quote check must reject rather than
		// fall through.
		byte[] xmpBody = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=43099/>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = primaryWithApp1(xmpBody);

		assertTrue("unquoted Item:Length value must fail-closed via empty",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchRefusesWhenStandardItemLengthHasUnterminatedDigitRun() throws IOException
	{
		// Body ends mid-digit-run (no closing quote). The patcher's `digitsEnd >= xmpBodyEnd` sub-condition
		// must reject rather than fall through. This pins one of the OR-ed sub-conditions in patchInSegment
		// that the existing patchRefusesWhenStandardItemLengthIsMalformed test doesn't cover (that one
		// exercises only the empty-digit-run sub-condition).
		byte[] xmpBody = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=\"43099")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = primaryWithApp1(xmpBody);

		assertTrue("unterminated digit run must fail-closed via empty",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchRefusesWhenStandardItemLengthIsMalformed() throws IOException
	{
		// When standard XMP carries Item:Length= followed by something other than a quoted digit run (here: an
		// empty value), the patcher must fail-closed rather than fall through to the Extended XMP scan (which
		// would return primary unchanged when Extended XMP doesn't carry the pattern, shipping stale
		// Item:Length data).
		byte[] xmpBody = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=\"\"/>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = primaryWithApp1(xmpBody);

		assertTrue("malformed Item:Length value (empty digits) must fail-closed via empty",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchRefusesWhenTwoGainMapItemsBothCarryItemLength() throws IOException
	{
		// Two GainMap-semantic items each declaring Item:Length — one gainMapSize, two slots, no way to know
		// which slot describes the appended gain map. The patcher must fail closed via empty (caller drops HDR)
		// rather than guess and ship one stale length; same one-value-many-slots refusal as MpfPatcher's
		// multi-gain-map MPF check.
		byte[] xmpBody = motionPhotoPacket("GainMap", "43099", "GainMap", "51234");
		byte[] primary = primaryWithApp1(xmpBody);

		assertTrue("two GainMap-anchored Item:Length occurrences must fail-closed via empty",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchReturnsEmptyWhenItemLengthLivesInExtendedXmp() throws IOException
	{
		// When GContainer Item:Length lives in an Extended XMP chunk (the >64KB packet form), in-place patching
		// would desync the per-chunk reassembly headers shared across chunks. The patcher signals fail-closed
		// via empty so GainMapComposer can drop HDR rather than ship stale Item:Length data that strict
		// decoders would interpret as a truncated gain map. Setup: standard XMP carries hdrgm declaration only
		// (no Item:Length); Extended XMP chunk carries the Container Item:Length attribute.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<x:xmpmeta><hdrgm:Version>1.0</hdrgm:Version>"
			+ "<xmpNote:HasExtendedXMP>0123456789abcdef0123456789abcdef</xmpNote:HasExtendedXMP>"
			+ "</x:xmpmeta>").getBytes(StandardCharsets.US_ASCII);
		byte[] extXmpBody = extendedXmpChunkBody(
			"<rdf:Description Item:Length=\"43099\" Item:Mime=\"image/jpeg\"/>");
		byte[] primary = primaryWithApp1(standardXmp, extXmpBody);

		assertTrue("Item:Length in Extended XMP must signal fail-closed via empty",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchReturnsEmptyWhenItemLengthStraddlesExtendedXmpChunks() throws IOException
	{
		// When the 12-byte "Item:Length=" pattern straddles an Adobe Extended XMP chunk boundary, the per-chunk
		// substring scan misses it but a reassembly-based scan catches it. Without the fallback, the patcher
		// would return primary unchanged and the file would ship with stale Item:Length, defeating the
		// Extended-XMP fail-closed guard. Build a 2-chunk Extended XMP that splits "Item:Length=" precisely
		// between chunks.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<x:xmpmeta><hdrgm:Version>1.0</hdrgm:Version>"
			+ "<xmpNote:HasExtendedXMP>0123456789abcdef0123456789abcdef</xmpNote:HasExtendedXMP>"
			+ "</x:xmpmeta>").getBytes(StandardCharsets.US_ASCII);
		// Chunk 1 ends with "Item:Le"; chunk 2 starts with "ngth=\"43099\"". Per-chunk scan finds neither the
		// full "Item:Length=" in chunk 1 (truncated) nor in chunk 2 (also truncated), but reassembly
		// concatenates them into "Item:Length=\"43099\"" which the fallback finds.
		byte[] chunk1Body = extendedXmpChunkBody("0123456789abcdef0123456789abcdef", 0,
			"<rdf:Description Item:Le");
		byte[] chunk2Body = extendedXmpChunkBody("0123456789abcdef0123456789abcdef", 24,
			"ngth=\"43099\" Item:Mime=\"image/jpeg\"/>");
		byte[] primary = primaryWithApp1(standardXmp, chunk1Body, chunk2Body);

		assertTrue("Item:Length straddling Extended XMP chunks must signal fail-closed via empty",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchReturnsEmptyWhenWhitespaceItemLengthLivesInExtendedXmp() throws IOException
	{
		// Whitespace-form coherence for the Extended XMP fail-closed gate: `Item:Length = "N"` in an Extended
		// XMP chunk must trip the same fail-closed detection as the byte-literal form. A literal-only detector
		// would miss it, return primary unchanged, and the composer would append the gain map with the stale
		// spaced length — the fail-open this gate exists to prevent.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<x:xmpmeta>"
			+ "<hdrgm:Version>1.0</hdrgm:Version></x:xmpmeta>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] extXmpBody = extendedXmpChunkBody(
			"<rdf:Description Item:Length = \"43099\" Item:Mime=\"image/jpeg\"/>");
		byte[] primary = primaryWithApp1(standardXmp, extXmpBody);

		assertTrue("whitespace-form Item:Length in Extended XMP must fail closed",
			XmpItemLengthPatcher.patch(primary, 82606).isEmpty());
	}

	@Test
	public void patchReturnsInputUnchangedForNegativeGainMapSize() throws IOException
	{
		// Negative gainMapSize is a caller bug guarded by an early return, preserving the input array reference
		// unchanged. Without the guard the patcher would write "-1" or similar into the Item:Length attribute,
		// an invalid u32 length per the GContainer spec.
		byte[] xmpBody = xmpPacketWithLength("43099");
		byte[] primary = primaryWithApp1(xmpBody);

		assertSame("negative gainMapSize returns input verbatim",
			primary, XmpItemLengthPatcher.patch(primary, -1).orElseThrow());
	}

	@Test
	public void patchReturnsPrimaryWhenItemLengthOnlySynthesizesAcrossGuidGroups() throws IOException
	{
		// Cross-GUID twin of patchReturnsEmptyWhenItemLengthStraddlesExtendedXmpChunks: the same "Item:Le" /
		// "ngth=…" split, but the two chunks belong to DIFFERENT GUID groups — two unrelated XMP documents.
		// Per-GUID reassembly scans each group's buffer independently, so the pattern that only exists as a
		// concatenation artifact across the group boundary must NOT trip the fail-closed gate — patch()
		// returns primary unchanged (HDR kept), exactly as if neither document mentioned Item:Length.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<x:xmpmeta><hdrgm:Version>1.0</hdrgm:Version>"
			+ "</x:xmpmeta>").getBytes(StandardCharsets.US_ASCII);
		byte[] groupATail = extendedXmpChunkBody("aaaa456789abcdef0123456789abcdef", 0,
			"<rdf:Description Item:Le");
		byte[] groupBHead = extendedXmpChunkBody("bbbb456789abcdef0123456789abcdef", 0,
			"ngth=\"43099\" Item:Mime=\"image/jpeg\"/>");
		byte[] primary = primaryWithApp1(standardXmp, groupATail, groupBHead);

		assertSame("Item:Length synthesized across GUID groups must NOT trigger fail-closed",
			primary, XmpItemLengthPatcher.patch(primary, 82606).orElseThrow());
	}

	@Test
	public void patchReturnsPrimaryWhenStandardMissesAndExtendedXmpInnocent() throws IOException
	{
		// Reciprocal of patchSucceedsWhenStandardXmpHasItemLengthEvenWithExtendedXmpAlongside. Standard XMP has
		// no Item:Length; Extended XMP exists alongside but also doesn't carry Item:Length. Both Extended-XMP
		// scans (per-chunk + reassembly) must return false and `patch()` must return primary unchanged — not
		// empty. A regression that over-aggressively flags any Extended XMP presence as "Item:Length straddle"
		// would drop HDR for any file with Extended XMP carrying unrelated metadata (Item:Mime, Item:Semantic,
		// xmpNote:HasExtendedXMP, etc.) — common in real Samsung Ultra HDR.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<x:xmpmeta>"
			+ "<hdrgm:Version>1.0</hdrgm:Version></x:xmpmeta>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] extXmpInnocent = extendedXmpChunkBody(
			"<rdf:Description Item:Mime=\"image/jpeg\" Item:Semantic=\"GainMap\"/>");
		byte[] primary = primaryWithApp1(standardXmp, extXmpInnocent);

		assertSame("Extended XMP without Item:Length must NOT trigger fail-closed",
			primary, XmpItemLengthPatcher.patch(primary, 82606).orElseThrow());
	}

	@Test
	public void patchSameDigitCountReplacesInPlace() throws IOException
	{
		// User's reported scenario: source gain map 43099 bytes, re-encoded to 82606 bytes — both 5-digit
		// values, so the byte length is unchanged and the patch is a 5-byte in-place rewrite.
		byte[] xmpBody = xmpPacketWithLength("43099");
		byte[] primary = primaryWithApp1(xmpBody);

		byte[] patched = XmpItemLengthPatcher.patch(primary, 82606).orElseThrow();

		assertEquals("same-digit-count patch must not change byte length", primary.length, patched.length);
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		assertEquals("Item:Length value rewritten to gain-map size", -1, patchedStr.indexOf("43099"));
		assertNotEquals("Item:Length=\"82606\" present in patched bytes", -1,
			patchedStr.indexOf("Item:Length=\"82606\""));
	}

	@Test
	public void patchShorterDigitCountShrinksArrayAndUpdatesSegLen() throws IOException
	{
		// 99999 (5 digits) → 100 (3 digits) — byte array shrinks by 2, APP1 segLen shrinks by 2.
		byte[] xmpBody = xmpPacketWithLength("99999");
		byte[] primary = primaryWithApp1(xmpBody);

		int oldSegLen = ByteBufferUtils.readU16BE(primary, 4);
		byte[] patched = XmpItemLengthPatcher.patch(primary, 100).orElseThrow();

		assertEquals("array shrunk by digit-count delta", primary.length - 2, patched.length);
		assertEquals("APP1 segLen shrunk by digit-count delta", oldSegLen - 2,
			ByteBufferUtils.readU16BE(patched, 4));
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		assertNotEquals(-1, patchedStr.indexOf("Item:Length=\"100\""));
		assertEquals("old digit string fully replaced", -1, patchedStr.indexOf("99999"));
	}

	@Test
	public void patchSingleQuotedValueIsRecognised() throws IOException
	{
		// XMP serialisers emit either '...' or "..." for attribute values. Both forms must patch.
		String packet = JpegSegment.XMP_HEADER + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
			+ "<rdf:Description Item:Length='43099' />" + "</x:xmpmeta>";
		byte[] xmpBody = packet.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = primaryWithApp1(xmpBody);

		byte[] patched = XmpItemLengthPatcher.patch(primary, 82606).orElseThrow();
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		assertNotEquals("single-quoted Item:Length rewritten", -1, patchedStr.indexOf("Item:Length='82606'"));
	}

	@Test
	public void patchSucceedsForItemLengthInLaterStandardXmpSegment() throws IOException
	{
		// Non-conformant source with TWO standard XMP APP1 segments. The first holds hdrgm:Version etc. (no
		// Item:Length); the second holds the GContainer Directory with Item:Length. The patcher must walk ALL
		// standard XMP segments — returning on the first "notPresent" would miss the second segment, fall
		// through to Extended XMP scanning (wrong namespace prefix, no match), and ship stale Item:Length.
		byte[] standardXmp1 = (JpegSegment.XMP_HEADER + "<x:xmpmeta>"
			+ "<hdrgm:Version>1.0</hdrgm:Version></x:xmpmeta>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] standardXmp2 = xmpPacketWithLength("43099");
		byte[] primary = primaryWithApp1(standardXmp1, standardXmp2);

		Optional<byte[]> result = XmpItemLengthPatcher.patch(primary, 82606);
		assertTrue("multi-standard-XMP file must patch the segment that carries Item:Length",
			result.isPresent());
		String patchedStr = new String(result.orElseThrow(), StandardCharsets.US_ASCII);
		assertNotEquals("82606 should appear in the patched output", -1,
			patchedStr.indexOf("Item:Length=\"82606\""));
		assertEquals("source 43099 fully replaced", -1, patchedStr.indexOf("43099"));
	}

	@Test
	public void patchSucceedsWhenStandardXmpHasItemLengthEvenWithExtendedXmpAlongside() throws IOException
	{
		// Mixed scenario: standard XMP has Item:Length AND Extended XMP exists alongside (without Item:Length).
		// The patcher must successfully patch the standard XMP and not be tripped up by the presence of
		// Extended XMP — the fail-closed gate only fires when Extended XMP itself carries Item:Length.
		byte[] standardXmp = xmpPacketWithLength("43099");
		byte[] extXmpBody = extendedXmpChunkBody(
			"<x:xmpmeta><hdrgm:HDRCapacityMin>0</hdrgm:HDRCapacityMin></x:xmpmeta>");
		byte[] primary = primaryWithApp1(standardXmp, extXmpBody);

		Optional<byte[]> result = XmpItemLengthPatcher.patch(primary, 82606);
		assertTrue("standard-XMP patch path must succeed even with Extended XMP alongside", result.isPresent());
		String patchedStr = new String(result.orElseThrow(), StandardCharsets.US_ASCII);
		assertNotEquals(-1, patchedStr.indexOf("Item:Length=\"82606\""));
	}

	@Test
	public void patchSurroundingBytesUnchanged() throws IOException
	{
		// Pin that the patcher only modifies the digit run and the segLen field — everything else (XMP body
		// content before / after the Item:Length, EXIF segment, scan body, EOI) must remain byte-identical so a
		// successful patch can never corrupt unrelated metadata.
		byte[] xmpBody = xmpPacketWithLength("43099");
		byte[] primary = primaryWithApp1(JpegFixtures.exifAppPayload(), xmpBody);

		byte[] patched = XmpItemLengthPatcher.patch(primary, 82606).orElseThrow();
		// EXIF APP1 segment must be byte-identical (same start, same content)
		int exifStart = 2;   // immediately after SOI
		int exifLen = 4 + JpegFixtures.exifAppPayload().length;   // FF E1 + segLen + body
		byte[] exifBefore = Arrays.copyOfRange(primary, exifStart, exifStart + exifLen);
		byte[] exifAfter = Arrays.copyOfRange(patched, exifStart, exifStart + exifLen);
		assertArrayEquals("EXIF segment must not be touched by XMP patch", exifBefore, exifAfter);

		// EOI tail must be byte-identical
		byte[] eoiTail = { (byte) 0xFF, (byte) 0xD9 };
		assertArrayEquals(eoiTail, Arrays.copyOfRange(patched, patched.length - 2, patched.length));
	}

	@Test
	public void patchThrowsOnNullPrimary()
	{
		// Null primary is a programming error — the former null-passthrough is now an explicit non-null
		// precondition (Objects.requireNonNull), so callers can't silently thread a null through the patcher.
		assertThrows(NullPointerException.class, () -> XmpItemLengthPatcher.patch(null, 82606));
	}

	@Test
	public void patchWhitespaceAroundEqualsIsPatchedNotFailOpen() throws IOException
	{
		// XML 1.0 allows whitespace around an attribute's '=' (Name S? '=' S? Value). A byte-literal
		// "Item:Length=" scan misses `Item:Length = "N"`, reports not-present, and GainMapComposer appends the
		// gain map with the stale length — a silent fail-open with hdrAttached=true. The whitespace-tolerant
		// scan must either patch correctly or fail closed; this value is well-formed, so it must patch. The
		// composer-facing fail-open shape is the unchanged input reference — excluded by the presence,
		// not-same, and content assertions below.
		String packet = JpegSegment.XMP_HEADER + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
			+ "<rdf:Description Item:Semantic=\"GainMap\" Item:Length = \"43099\" />" + "</x:xmpmeta>";
		byte[] xmpBody = packet.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = primaryWithApp1(xmpBody);

		Optional<byte[]> result = XmpItemLengthPatcher.patch(primary, 82606);
		assertTrue("whitespace-form Item:Length must not be treated as absent", result.isPresent());
		byte[] patched = result.orElseThrow();
		assertNotSame("fail-open shape is the unchanged input reference — must not occur", primary, patched);
		String patchedStr = new String(patched, StandardCharsets.US_ASCII);
		assertNotEquals("spaced Item:Length rewritten in place (whitespace preserved)", -1,
			patchedStr.indexOf("Item:Length = \"82606\""));
		assertEquals("stale length gone", -1, patchedStr.indexOf("43099"));
	}

	@Test
	public void patchXmpWithoutItemLengthReturnsInputUnchanged() throws IOException
	{
		// XMP packet with no Item:Length attribute — common for non-Ultra-HDR XMP that just carries
		// hdrgm:Version or other metadata. Patcher must not throw or mutate; just bail.
		String packet = JpegSegment.XMP_HEADER
			+ "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><hdrgm:Version>1.0</hdrgm:Version></x:xmpmeta>";
		byte[] xmpBody = packet.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = primaryWithApp1(xmpBody);

		assertSame(primary, XmpItemLengthPatcher.patch(primary, 82606).orElseThrow());
	}

	private static byte[] extendedXmpChunkBody(String innerXml) throws IOException
	{
		// Single-chunk convenience overload — uses a fixed GUID and offset 0. Suitable for tests that only
		// check per-chunk pattern detection without exercising reassembly's GUID grouping or offset sorting.
		return extendedXmpChunkBody("0123456789abcdef0123456789abcdef", 0, innerXml);
	}

	private static byte[] extendedXmpChunkBody(String guid, long offset, String innerXml) throws IOException
	{
		// The total-length field (0x1000 placeholder) isn't validated by ExtendedXmpReassembler.reassemble. The
		// offset field IS honoured for sort order, so callers building multi-chunk fixtures must pass distinct
		// offsets in body-order.
		return JpegFixtures.extendedXmpChunk(guid, 0x1000L, offset,
			innerXml.getBytes(StandardCharsets.US_ASCII));
	}

	private static byte[] motionPhotoPacket(String firstSemantic, String firstLength,
		String secondSemantic, String secondLength)
	{
		// Two-item Container:Directory in the Google MotionPhoto shape: BOTH items declare Item:Length (the
		// Primary item's "0" first in the canonical case), unlike the Samsung shape in xmpPacketWithLength
		// where only the gain-map item carries the attribute.
		String packet = JpegSegment.XMP_HEADER + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF>"
			+ "<rdf:Description xmlns:Container=\"http://ns.google.com/photos/1.0/container/\""
			+ " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\">"
			+ "<Container:Directory><rdf:Seq>" + "<rdf:li rdf:parseType=\"Resource\">"
			+ "<Container:Item Item:Semantic=\"" + firstSemantic + "\" Item:Mime=\"image/jpeg\""
			+ " Item:Length=\"" + firstLength + "\"/></rdf:li>" + "<rdf:li rdf:parseType=\"Resource\">"
			+ "<Container:Item Item:Semantic=\"" + secondSemantic + "\" Item:Mime=\"image/jpeg\""
			+ " Item:Length=\"" + secondLength + "\"/></rdf:li>" + "</rdf:Seq></Container:Directory>"
			+ "</rdf:Description></rdf:RDF></x:xmpmeta>";
		return packet.getBytes(StandardCharsets.US_ASCII);
	}

	/**
	 * Compose the canonical fixture primary: SOI, one APP1 segment wrapping each body in order, then the minimal
	 * scan + EOI. Bodies are usually XMP packets; the metadata-preservation tests pass an EXIF payload too.
	 *
	 * @param app1Bodies APP1 segment bodies emitted in argument order
	 * @return assembled primary JPEG bytes
	 * @throws IOException never from the in-memory stream; declared by the OutputStream write contract
	 */
	private static byte[] primaryWithApp1(byte[]... app1Bodies) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(JpegFixtures.soi());
		for (byte[] app1Body : app1Bodies)
		{
			out.write(JpegFixtures.appSegment(0xE1, app1Body));
		}
		out.write(JpegFixtures.minimalScanAndEoi());
		return out.toByteArray();
	}

	private static byte[] xmpPacketWithLength(String length)
	{
		// Mirrors the Container:Item shape Samsung's Ultra HDR exporter emits, with a single Item:Length
		// attribute on the gain-map item. Per the GContainer schema the Primary item omits Length (its length
		// is implicit), so the packet's lone occurrence patches directly with no GainMap anchoring required.
		String packet = JpegSegment.XMP_HEADER + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF>"
			+ "<rdf:Description xmlns:Container=\"http://ns.google.com/photos/1.0/container/\""
			+ " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""
			+ " xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\">" + "<Container:Directory><rdf:Seq>"
			+ "<rdf:li rdf:parseType=\"Resource\">"
			+ "<Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li>"
			+ "<rdf:li rdf:parseType=\"Resource\">"
			+ "<Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\""
			+ " Item:Length=\"" + length + "\"/></rdf:li>" + "</rdf:Seq></Container:Directory>"
			+ "</rdf:Description></rdf:RDF></x:xmpmeta>";
		return packet.getBytes(StandardCharsets.US_ASCII);
	}
}
