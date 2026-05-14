package com.cropcenter.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Tests for the SOS-finder bounds guards in GraftWriter.findPrimaryEoi. Two adversarial edit-JPEG shapes are exercised
 * through the public graft() entry point: a truncated SOS header that would IndexOutOfBoundsException without the (off
 * + 4 > file.length) guard, and an SOS-length value of 0xFFFF that would either index past EOF or wrap scanOff to a
 * small-positive int without the (scanOff > file.length) guard. Both must surface as a clean "no recoverable primary
 * scan" IOException so the caller can show a real "graft failed" toast — silently returning a corrupt splice would wipe
 * Samsung Gallery's Revert pre-flight on round-tripped edits.
 */
public final class GraftWriterTest
{
	@Test
	public void graftAcceptsValidEditWithLegalSosAndEoi() throws IOException
	{
		// Sanity counterpart for the two failure tests: confirm the new guards don't false-trip on a
		// well-formed edit. orig = SOI + DQT + minimal SOS scan + EOI; edit = same shape. graft should produce
		// a non-null result that begins with SOI and ends with EOI.
		byte[] valid = JpegFixtures.concat(JpegFixtures.soi(), DQT_STUB, JpegFixtures.minimalScanAndEoi());

		byte[] result = GraftWriter.graft(valid, valid.clone());
		assertNotNull(result);
		assertEquals((byte) 0xFF, result[0]);
		assertEquals((byte) 0xD8, result[1]);
		assertEquals((byte) 0xFF, result[result.length - 2]);
		assertEquals((byte) 0xD9, result[result.length - 1]);
	}

	@Test
	public void graftPreservesOriginalExifSegmentVerbatim() throws IOException
	{
		// Source carries an EXIF APP1 segment whose payload contains a sentinel byte pattern; edit carries a
		// DIFFERENT EXIF (different sentinel). Per the SWAP_EXIF=false design, the output must contain source's
		// EXIF bytes verbatim and must NOT contain the edit's sentinel.
		byte[] origExifPayload = exifPayloadWithSentinel((byte) 0xAA);
		byte[] editExifPayload = exifPayloadWithSentinel((byte) 0xBB);

		byte[] orig = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.appSegment(0xE1, origExifPayload),
			DQT_STUB, JpegFixtures.minimalScanAndEoi());
		byte[] edit = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.appSegment(0xE1, editExifPayload),
			DQT_STUB, JpegFixtures.minimalScanAndEoi());

		byte[] result = GraftWriter.graft(orig, edit);
		assertTrue("result should contain source's EXIF sentinel (0xAA)",
			containsSentinel(result, (byte) 0xAA));
		assertFalse("result must not contain edit's EXIF sentinel (0xBB)",
			containsSentinel(result, (byte) 0xBB));
	}

	@Test
	public void graftReAppendsSourceSeftTrailerVerbatim() throws IOException
	{
		// Source has a SEFT trailer; edit doesn't. Output must end with source's SEFT trailer (Samsung Revert
		// chain preservation depends on this — the SEFT is byte-for-byte re-appended).
		byte[] orig = JpegFixtures.concat(
			JpegFixtures.soi(), DQT_STUB, JpegFixtures.minimalScanAndEoi(), seftTrailer());
		byte[] edit = JpegFixtures.concat(JpegFixtures.soi(), DQT_STUB, JpegFixtures.minimalScanAndEoi());

		byte[] result = GraftWriter.graft(orig, edit);
		// Last 4 bytes of the result must be the SEFT magic (assuming SEFT trailer got appended).
		assertEquals('S', result[result.length - 4]);
		assertEquals('E', result[result.length - 3]);
		assertEquals('F', result[result.length - 2]);
		assertEquals('T', result[result.length - 1]);
	}

	@Test
	public void graftRejectsEditTruncatedMidSosHeader() throws IOException
	{
		// Edit = SOI + DQT + SOS marker truncated (only 1 byte after FF DA, missing the second byte of the
		// 2-byte length field). Without the (off + 4 > file.length) guard, findPrimaryEoi would call
		// ByteBufferUtils.readU16BE on out-of-range bytes and throw AIOOBE, crashing the bg thread silently.
		// With the guard, findPrimaryEoi returns -1 and graft throws IOException so the caller can show a real
		// toast.
		byte[] orig = JpegFixtures.concat(JpegFixtures.soi(), DQT_STUB, JpegFixtures.minimalScanAndEoi());
		byte[] edit = JpegFixtures.concat(
			JpegFixtures.soi(), DQT_STUB, new byte[] { (byte) 0xFF, (byte) 0xDA, 0x00 });

		try
		{
			GraftWriter.graft(orig, edit);
			fail("expected IOException for truncated SOS header");
		}
		catch (IOException e)
		{
			assertTrue("message should mention recoverable primary scan: " + e.getMessage(),
				e.getMessage().contains("primary scan"));
		}
	}

	@Test
	public void graftRejectsEditWithSosLenCausingScanOffPastEof() throws IOException
	{
		// Edit = SOI + DQT + SOS with 0xFFFF length but only a handful of bytes after. scanOff = off + 2 +
		// 0xFFFF lands ~65k past EOF; without the (scanOff > file.length) guard, the inner scan loop would run
		// on a phantom region of bytes outside the array (caught by the loop's own bounds check returning -1
		// silently) — but on overflow paths where sosLen is even larger, scanOff itself wraps to a small
		// positive int that satisfies the inner loop's bounds and reads from arbitrary positions. The clamp
		// makes both cases bail explicitly.
		byte[] orig = JpegFixtures.concat(JpegFixtures.soi(), DQT_STUB, JpegFixtures.minimalScanAndEoi());
		ByteArrayOutputStream editOut = new ByteArrayOutputStream();
		editOut.write(JpegFixtures.soi());
		editOut.write(DQT_STUB);
		editOut.write(0xFF);
		editOut.write(0xDA);
		editOut.write(0xFF);   // sosLen high byte
		editOut.write(0xFF);   // sosLen low byte = 0xFFFF
		editOut.write(new byte[] { 0x01, 0x02, 0x03, 0x04 }); // few padding bytes
		byte[] edit = editOut.toByteArray();

		try
		{
			GraftWriter.graft(orig, edit);
			fail("expected IOException for adversarial SOS length");
		}
		catch (IOException e)
		{
			assertTrue("message should mention recoverable primary scan: " + e.getMessage(),
				e.getMessage().contains("primary scan"));
		}
	}

	@Test
	public void graftSkipsGainMapForSdrSourceWithEmbeddedFfd8PostEoi() throws IOException
	{
		// Pin GraftWriter's HDR per-side AND-gate (`origHasMpf && hasHdrgmInXmp`). Build an SDR original
		// with NO MPF segment and NO hdrgm in XMP, but post-primary-EOI bytes that structurally look
		// like a JPEG (`FF D8 ... FF D9` mimicking a SEFT-block embedded thumbnail). Without the
		// AND-gate, a regression that gated only on hasMpf — or only on hasHdrgmInXmp — would mis-walk
		// those bytes as a synthesised gain map and append them as HDR-claiming output. With the gate
		// intact, GainMapExtractor refuses to inspect post-EOI bytes and the graft output carries no
		// gain map. Mirrors the HdrSignature load-time gate but on the graft path.
		ByteArrayOutputStream origOut = new ByteArrayOutputStream();
		origOut.write(JpegFixtures.soi());
		origOut.write(DQT_STUB);
		origOut.write(JpegFixtures.minimalScanAndEoi());
		// Post-EOI bytes: a structurally-valid mini-JPEG (FF D8 + DQT + SOS + FF D9) that a regressed
		// GainMapExtractor would happily walk. No "SEFT" footer so SeftExtractor refuses to claim it
		// as a trailer either — these bytes should be entirely ignored by graft.
		origOut.write(JpegFixtures.soi());
		origOut.write(DQT_STUB);
		origOut.write(JpegFixtures.minimalScanAndEoi());
		byte[] orig = origOut.toByteArray();

		byte[] edit = JpegFixtures.concat(JpegFixtures.soi(), DQT_STUB, JpegFixtures.minimalScanAndEoi());

		byte[] result = GraftWriter.graft(orig, edit);

		// Find the primary EOI in the result. Anything past it would be a (mis-)appended gain map or
		// a SEFT trailer. Neither should be present for SDR-with-no-SEFT input.
		int primaryEoi = JpegMarkerWalker.findPrimaryEoi(result, result.length);
		assertEquals("graft of SDR sources must produce a clean primary with nothing appended",
			result.length, primaryEoi);
	}

	@Test
	public void graftStripsOrphanMpfWhenSourceHasMpfButNoGainMap() throws IOException
	{
		// Round-45 F2 regression test. Source carries an MPF segment but NO gain map (e.g. Samsung "Best
		// Photo" burst, focus-stacked panorama, ZSL — multi-picture JPEGs that pre-date Ultra HDR and
		// don't carry the hdrgm namespace). The graft preserves source's metadata verbatim into the
		// output, but the spliced primary doesn't carry the secondary images source's MPF describes —
		// strict-MPF decoders (Samsung Gallery's Revert pre-flight) reject the orphan-MPF shape, lenient
		// decoders walk past malformed entries. Output must NOT contain the "MPF\0" signature when
		// gainMapToWrite == null. The dropOrphanMpf branch in GraftWriter.graft fires the strip; this
		// test pins it so a "simplify the segment loop" refactor that removes the branch silently
		// reintroduces the orphan.
		byte[] orig = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE2, mpfPayloadWithSignature()),
			DQT_STUB, JpegFixtures.minimalScanAndEoi());
		byte[] edit = JpegFixtures.concat(JpegFixtures.soi(), DQT_STUB, JpegFixtures.minimalScanAndEoi());

		byte[] result = GraftWriter.graft(orig, edit);
		assertFalse("orphan MPF must be stripped when no gain map anchors it",
			containsMpfSignature(result));
	}

	@Test
	public void graftUsesEditPrimaryScanNotOriginals() throws IOException
	{
		// Source carries a primary scan whose entropy-coded body has byte value 0xAA; edit carries 0xBB. Per
		// the design, the output's primary scan must come from edit (the AI-edited pixels) — verifying this is
		// the whole point of the graft.
		byte[] origScan = scanWithPayload((byte) 0xAA);
		byte[] editScan = scanWithPayload((byte) 0xBB);

		byte[] orig = JpegFixtures.concat(JpegFixtures.soi(), DQT_STUB, origScan);
		byte[] edit = JpegFixtures.concat(JpegFixtures.soi(), DQT_STUB, editScan);

		byte[] result = GraftWriter.graft(orig, edit);
		assertTrue("result should contain edit's scan sentinel (0xBB)", containsSentinel(result, (byte) 0xBB));
		assertFalse("result must not contain source's scan sentinel (0xAA)",
			containsSentinel(result, (byte) 0xAA));
	}

	/**
	 * True when `data` contains the 4-byte ASCII "MPF\0" signature anywhere — the segment-body identifier that
	 * follows the APP2 marker + length in any MPF segment. Used by graftStripsOrphanMpfWhenSourceHasMpfButNoGainMap
	 * to confirm the dropOrphanMpf branch actually removed source's MPF segment from the assembled output.
	 */
	private static boolean containsMpfSignature(byte[] data)
	{
		for (int i = 0; i + 3 < data.length; i++)
		{
			if (data[i] == 'M' && data[i + 1] == 'P' && data[i + 2] == 'F' && data[i + 3] == 0)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * True when `data` contains the single byte `sentinel` anywhere in its range. A crude provenance probe: the
	 * test plants distinct sentinels in source vs edit segments, then asserts which sentinel is or isn't in the
	 * output.
	 */
	private static boolean containsSentinel(byte[] data, byte sentinel)
	{
		for (byte item : data)
		{
			if (item == sentinel)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Build a minimal EXIF APP1 payload ("Exif\0\0" + TIFF header + sentinel byte pattern) where the sentinel sits
	 * inside the TIFF region, not in the marker preamble — so a containsSentinel check on the assembled output
	 * identifies which side of the graft contributed the segment.
	 */
	private static byte[] exifPayloadWithSentinel(byte sentinel)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write('E');
		out.write('x');
		out.write('i');
		out.write('f');
		out.write(0);
		out.write(0);
		// TIFF header (II*\0 + IFD0 offset = 8) + 4-byte sentinel-padded payload.
		out.write('I');
		out.write('I');
		out.write('*');
		out.write(0);
		out.write(8);
		out.write(0);
		out.write(0);
		out.write(0);
		// Sentinel-tagged IFD body — the byte pattern that distinguishes source from edit. 4 bytes is enough to
		// make the containsSentinel walk find it without false positives from arbitrary EXIF bytes (we pick
		// distinct sentinels per caller).
		out.write(sentinel);
		out.write(sentinel);
		out.write(sentinel);
		out.write(sentinel);
		return out.toByteArray();
	}

	/**
	 * Build a minimal MPF APP2 payload — the 4-byte "MPF\0" signature followed by 24 bytes of stubbed MPF body.
	 * Sufficient for GraftWriter's JpegSegment.isMpf check to match; the body bytes don't need to parse as a real
	 * MPF index for the orphan-strip test, which only asserts the signature is absent from the output.
	 */
	private static byte[] mpfPayloadWithSignature()
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write('M');
		out.write('P');
		out.write('F');
		out.write(0);
		for (int i = 0; i < 24; i++)
		{
			out.write(0);
		}
		return out.toByteArray();
	}

	/**
	 * Build a minimal SOS segment + entropy-coded body padded with the sentinel byte + EOI. Used to plant a
	 * side-distinguishing pattern in the primary scan so the test can verify the output's scan came from the right
	 * side.
	 */
	private static byte[] scanWithPayload(byte sentinel) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0xFF);
		out.write(0xDA);
		out.write(0x00);
		out.write(0x06);
		out.write(new byte[]{ 0x01, 0x00, 0x00, 0x00 });
		// Entropy body: a long-ish run of the sentinel so containsSentinel hits even though sentinels could
		// collide with random metadata bytes.
		for (int i = 0; i < 32; i++)
		{
			out.write(sentinel);
		}
		out.write(0xFF);
		out.write(0xD9);
		return out.toByteArray();
	}

	/**
	 * Build a minimal SEFT trailer: 4-byte size field + "SEFT" magic. SeftExtractor scans backward from EOF for the
	 * "SEFT" magic, so this shape is sufficient to make GraftWriter pick up the trailer and re-append it verbatim
	 * in the output.
	 */
	private static byte[] seftTrailer()
	{
		return new byte[]{ 0x10, 0x00, 0x00, 0x00, 'S', 'E', 'F', 'T' };
	}

	private static final byte[] DQT_STUB =
	{
		(byte) 0xFF, (byte) 0xDB, 0x00, 0x04, 0x00, 0x00,
	};
}
