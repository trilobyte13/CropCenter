package com.cropcenter.metadata;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;

/**
 * Tests for JpegMarkerWalker — the canonical FF D9 walker shared by GainMapExtractor, GraftWriter, CropExporter,
 * and BitmapUtils.readExifOrientation. Bug classes fixed here apply to every consumer by construction.
 *
 * Major regression coverage: the walker must accept marker fill bytes (any number of leading 0xFF before the
 * marker code, per ITU-T T.81 §B.1.1.2). Without that, JPEGs that legally pad markers for 16-bit alignment fail
 * gain-map extraction, splice, and EXIF readback — silent HDR drop, failed graft, lost orientation.
 */
public final class JpegMarkerWalkerTest
{
	@Test
	public void findEoiHonoursTighterEndBound() throws IOException
	{
		// When the gain-map walker passes a SEFT-aware endBound (file.length - SEFT_FOOTER_SIZE), the
		// walker must NOT scan into the SEFT bytes looking for EOI. Pass an endBound that cuts off
		// AFTER the gain-map EOI but before the trailing SEFT bytes — walker must find the EOI in the
		// allowed range. Then pass an endBound that cuts off BEFORE the gain-map EOI — walker must
		// return -1 because there's no clean EOI within bounds.
		byte[] embedded = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xE0, 0x00, 0x04, 0x01, 0x02 },
			JpegFixtures.minimalScanAndEoi());
		byte[] junkPrefix = new byte[] { 0x00, 0x11 };
		byte[] trailing = new byte[] { 0x44, 0x53, 0x45, 0x46, 0x54 };  // arbitrary post-EOI bytes
		byte[] file = JpegFixtures.concat(junkPrefix, embedded, trailing);

		int embeddedEoi = junkPrefix.length + embedded.length;
		assertEquals("endBound at embedded EOI: walker returns absolute EOI offset",
			embeddedEoi, JpegMarkerWalker.findEoi(file, junkPrefix.length, embeddedEoi));
		assertEquals("endBound cutting off before EOI: walker returns -1",
			-1, JpegMarkerWalker.findEoi(file, junkPrefix.length, embeddedEoi - 4));
	}

	@Test
	public void findEoiRejectsStartOffNearMaxIntWithoutOverflowing() throws IOException
	{
		// Adversarial input: caller passes startOff = Integer.MAX_VALUE - 1 with a file of length 4. The
		// walker computes `off = startOff + 2` internally to skip SOI; without the long-arithmetic
		// guard, that addition would wrap negative and silently pass the `off < endBound` check. Result
		// would either be a wraparound array read (AIOOBE) or — worse — a returned offset pointing into
		// the wrapped negative range that downstream consumers treat as a valid index. Pin: the walker
		// must return -1 (not found) rather than throw or return a wrapped index.
		byte[] tiny = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9 };
		int eoi = JpegMarkerWalker.findEoi(tiny, Integer.MAX_VALUE - 1, tiny.length);
		assertEquals("MAX_VALUE-1 startOff must return -1 (overflow guard)", -1, eoi);
	}

	@Test
	public void findEoiWalksFromNonZeroStartOff() throws IOException
	{
		// The 3-parameter findEoi(file, startOff, endBound) overload is what GainMapExtractor +
		// SeftExtractor use to walk an embedded gain-map sub-JPEG that starts at a non-zero file offset.
		// Construct [junk-prefix][SOI][APP0-stub][minimal-SOS-EOI] and verify findEoi returns an
		// offset measured from file byte 0 (absolute) — NOT relative to startOff — and that the
		// returned offset points one byte past the embedded EOI. A regression in the `off = startOff +
		// 2` initialisation (forgetting the +2 to skip SOI, or making startOff 1-indexed) would
		// shift every gain-map walk by one byte and corrupt every HDR re-save.
		byte[] junkPrefix = new byte[] { 0x00, 0x11, 0x22, 0x33, 0x44, 0x55 };
		byte[] embedded = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xE0, 0x00, 0x04, 0x01, 0x02 },
			JpegFixtures.minimalScanAndEoi());
		byte[] file = JpegFixtures.concat(junkPrefix, embedded);

		int eoi = JpegMarkerWalker.findEoi(file, junkPrefix.length, file.length);
		assertEquals("findEoi returns absolute offset (relative to file byte 0), pointing one byte past EOI",
			file.length, eoi);
	}

	@Test
	public void findPrimaryEoiAcceptsFillBytesBeforeApp1() throws IOException
	{
		// FF FF E1 ... — APP1 with one leading fill byte. Without fill-byte handling the walker treats
		// marker code as 0xFF, parses garbage segLen from the next two bytes, and returns -1. With it, the
		// walker correctly skips fill, parses APP1, advances, and returns the EOI position.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xE1, 0x00, 0x06, 0x01, 0x02, 0x03, 0x04 },
			JpegFixtures.minimalScanAndEoi());
		int eoi = JpegMarkerWalker.findPrimaryEoi(file, file.length);
		assertEquals(file.length, eoi);
	}

	@Test
	public void findPrimaryEoiAcceptsFillBytesBeforeEoi() throws IOException
	{
		// FF FF D9 — EOI with leading fill bytes. Real-world some encoders pad here for alignment.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xE0, 0x00, 0x04, 0x01, 0x02 }, // APP0 stub
			new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xD9 }); // 2 fill + EOI
		int eoi = JpegMarkerWalker.findPrimaryEoi(file, file.length);
		assertEquals(file.length, eoi);
	}

	@Test
	public void findPrimaryEoiAcceptsMinimalSosLenOfTwo() throws IOException
	{
		// sosLen = 2 = the 2 length bytes themselves, no body. Legal per ITU-T T.81 §B.1.1.4. The
		// `sosLen < 2` reject guard exists; pin the accept side of the inclusive boundary so a regression that
		// flipped `< 2` to `<= 2` (or `< 3`) — which would reject every legal minimal SOS — surfaces here.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xDA, 0x00, 0x02,
				(byte) 0xFF, (byte) 0xD9 });
		assertEquals(file.length, JpegMarkerWalker.findPrimaryEoi(file, file.length));
	}

	@Test
	public void findPrimaryEoiHandlesFillBytesBeforeSos() throws IOException
	{
		// FF FF DA — SOS with one fill byte. The walker must locate the marker, scan SOS entropy, and find
		// the trailing EOI.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] {
				(byte) 0xFF, (byte) 0xFF, (byte) 0xDA, // FF + fill + SOS marker
				0x00, 0x06,                            // length = 6
				0x01, 0x00, 0x00, 0x00,                // scan header body
				0x77, 0x77, 0x77,                      // entropy
				(byte) 0xFF, (byte) 0xD9               // EOI
			});
		int eoi = JpegMarkerWalker.findPrimaryEoi(file, file.length);
		assertEquals(file.length, eoi);
	}

	@Test
	public void findPrimaryEoiHandlesMultipleFillBytes() throws IOException
	{
		// 4 fill bytes before APP0. Defensive — some pathological encoders pad heavily.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] {
				(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xE0,
				0x00, 0x04, 0x01, 0x02
			},
			JpegFixtures.minimalScanAndEoi());
		int eoi = JpegMarkerWalker.findPrimaryEoi(file, file.length);
		assertEquals(file.length, eoi);
	}

	@Test
	public void findPrimaryEoiHandlesNoFillBytesUnchanged() throws IOException
	{
		// Sanity — the no-fill path still works after the refactor.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE0, new byte[] { 0x01, 0x02 }),
			JpegFixtures.minimalScanAndEoi());
		int eoi = JpegMarkerWalker.findPrimaryEoi(file, file.length);
		assertEquals(file.length, eoi);
	}

	@Test
	public void findPrimaryEoiReturnsMinusOneOnSegLenBelowTwo() throws IOException
	{
		// Per ITU-T T.81, segment length includes the 2 length bytes themselves — segLen<2 is malformed.
		// The walker must bail with -1 rather than getting stuck mid-segment (advancing by 2 or 3 instead
		// of the real segment size). Currently exercised indirectly via JpegMetadataExtractor tests; this
		// test pins the canonical helper directly so a regression in the chokepoint surfaces here
		// regardless of which downstream caller consumed it.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xE1, 0x00, 0x01 });   // segLen=1 — malformed
		assertEquals(-1, JpegMarkerWalker.findPrimaryEoi(file, file.length));
	}

	@Test
	public void findPrimaryEoiReturnsMinusOneOnSegLenClaimingPastEndBound() throws IOException
	{
		// Wrap-overflow guard at line 105: `next < off || next > endBound`. A malformed APP1 with segLen
		// = 0xFFFE in a 6-byte file would normally walk off into an AIOOBE — the walker must clamp and
		// return -1 instead. Reachable via real-world truncated APP1 segments.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] {
				(byte) 0xFF, (byte) 0xE1,                    // APP1 marker
				(byte) 0xFF, (byte) 0xFE,                    // segLen = 0xFFFE
				0x00, 0x00                                    // 2 bytes after (way under 0xFFFE)
			});
		assertEquals(-1, JpegMarkerWalker.findPrimaryEoi(file, file.length));
	}

	@Test
	public void findPrimaryEoiReturnsMinusOneOnSosLenBelowTwo() throws IOException
	{
		// SOS segments share the segLen<2 invariant with normal segments — sosLen=0 lands scanOff inside
		// the length field, sosLen=1 lands it inside the SOS header body. Without the guard the entropy
		// walk treats those header bytes as scan content and accepts a coincidental FF D9 there as a
		// valid EOI. Pin both boundary values: sosLen=0 and sosLen=1 must reject.
		byte[] zero = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xDA, 0x00, 0x00,
				(byte) 0xFF, (byte) 0xD9 });
		assertEquals(-1, JpegMarkerWalker.findPrimaryEoi(zero, zero.length));

		byte[] one = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xDA, 0x00, 0x01,
				(byte) 0xFF, (byte) 0xD9 });
		assertEquals(-1, JpegMarkerWalker.findPrimaryEoi(one, one.length));
	}

	@Test
	public void findPrimaryEoiReturnsMinusOneOnSosLenClaimingPastEndBound() throws IOException
	{
		// SOS-side wrap-overflow guard at scanSosEntropy line 182 (`scanOff < off || scanOff > endBound`).
		// Currently exercised only indirectly via GraftWriterTest; this pins the canonical helper directly
		// so a regression in just this guard isn't masked by the GraftWriter test continuing to pass via a
		// different code path.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] {
				(byte) 0xFF, (byte) 0xDA,                    // SOS marker
				(byte) 0xFF, (byte) 0xFF,                    // sosLen = 0xFFFF
				0x01, 0x02, 0x03, 0x04                       // 4 bytes — way under 0xFFFF
			});
		assertEquals(-1, JpegMarkerWalker.findPrimaryEoi(file, file.length));
	}

	@Test
	public void findPrimaryEoiReturnsMinusOneWhenFileEndsExactlyOnLeadingFf() throws IOException
	{
		// File ends with a lone 0xFF — no marker code follows. The outer loop's `off < endBound - 1` guard
		// must reject this without indexing OOB. A regression that flipped the predicate to `off < endBound`
		// would let `markerByteOff` slide past file end and read garbage as a marker. Pin to catch.
		byte[] file = JpegFixtures.concat(JpegFixtures.soi(), new byte[] { (byte) 0xFF });
		assertEquals(-1, JpegMarkerWalker.findPrimaryEoi(file, file.length));
	}

	@Test
	public void findPrimaryEoiReturnsMinusOneWhenFillBytesRunOffEndBound() throws IOException
	{
		// Pathological: a stream of FF bytes that never terminates in a real marker before endBound.
		byte[] file = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF });
		int eoi = JpegMarkerWalker.findPrimaryEoi(file, file.length);
		assertEquals(-1, eoi);
	}

	@Test
	public void skipFillBytesAdvancesPastSingleFillByte()
	{
		byte[] file = { (byte) 0xFF, (byte) 0xFF, (byte) 0xE1, 0x00 };
		int markerByte = JpegMarkerWalker.skipFillBytes(file, 0, file.length);
		assertEquals(2, markerByte); // points at 0xE1
	}

	@Test
	public void skipFillBytesNoFillReturnsImmediateNext()
	{
		byte[] file = { (byte) 0xFF, (byte) 0xE1, 0x00, 0x04 };
		int markerByte = JpegMarkerWalker.skipFillBytes(file, 0, file.length);
		assertEquals(1, markerByte);
	}

	@Test
	public void skipFillBytesRespectsTighterEndBound()
	{
		byte[] file = { (byte) 0xFF, (byte) 0xFF, (byte) 0xE1 };
		// endBound = 2 forces the search to stop before reaching the 0xE1 at index 2.
		int markerByte = JpegMarkerWalker.skipFillBytes(file, 0, 2);
		assertEquals(-1, markerByte);
	}

	@Test
	public void skipFillBytesReturnsMinusOneWhenRunOffEndBound()
	{
		// All FFs to end of bound — no marker byte found.
		byte[] file = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
		int markerByte = JpegMarkerWalker.skipFillBytes(file, 0, file.length);
		assertEquals(-1, markerByte);
	}
}
