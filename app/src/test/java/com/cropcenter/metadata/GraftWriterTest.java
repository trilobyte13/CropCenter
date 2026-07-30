package com.cropcenter.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.cropcenter.util.ByteBufferUtils;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Tests for GraftWriter.graft — the splice that pairs the original's metadata with the edit's primary scan. All
 * cases run through the public graft() entry point:
 *
 * Splice provenance: the output's primary scan comes from the edit, the EXIF segment comes from the original verbatim,
 * and a source SEFT trailer is re-appended byte-for-byte (Samsung Revert chain preservation).
 *
 * HDR gating: the per-side AND-gate (origHasMpf && hasHdrgmInXmp) keeps SDR sources from having post-EOI bytes
 * mis-walked as a gain map, and an orphan MPF segment is stripped when no gain map anchors it.
 *
 * Input rejection: null / non-JPEG inputs, plus two adversarial edit shapes routed into JpegMarkerWalker.findPrimaryEoi
 * — a truncated SOS header (AIOOBE without the off + 4 > file.length guard) and an SOS length of 0xFFFF (indexes past
 * EOF or wraps scanOff to a small-positive int without the scanOff > file.length guard). All must surface as a clean
 * IOException so the caller can show a real "graft failed" toast — silently returning a corrupt splice would wipe
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
		byte[] valid = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(),
			JpegFixtures.minimalScanAndEoi());

		byte[] result = GraftWriter.graft(valid, valid.clone());
		assertNotNull(result);
		assertEquals((byte) 0xFF, result[0]);
		assertEquals((byte) 0xD8, result[1]);
		assertEquals((byte) 0xFF, result[result.length - 2]);
		assertEquals((byte) 0xD9, result[result.length - 1]);
	}

	@Test
	public void graftPatchesMpfOffsetsForHdrOriginal() throws IOException
	{
		// HDR graft happy path: original carries hdrgm XMP + a valid 2-image MPF APP2 + a gain-map JPEG after
		// the primary EOI. The graft must append source's gain map verbatim right after the new primary's EOI
		// and MpfPatcher must rewrite entry[0].size to the post-graft primarySize and the gain-map entry's
		// dataOffset to primarySize - mpfStart. primarySize is computed BEFORE the scan is written — an
		// off-by-N there (moving the computation, SEFT reorder) corrupts the MPF index on every HDR "Apply
		// External Edit": Samsung Gallery's Revert pre-flight rejects the file or renders HDR at the wrong
		// offset.
		byte[] gainMap = gainMapJpeg();
		byte[] orig = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, hdrgmXmpPayload()),
			JpegFixtures.appSegment(0xE2, validMpfPayload()),
			JpegFixtures.dqtStub(), JpegFixtures.minimalScanAndEoi(), gainMap);
		byte[] edit = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(),
			JpegFixtures.minimalScanAndEoi());

		byte[] result = GraftWriter.graft(orig, edit);

		// The gain map must sit immediately after the grafted primary's EOI and run to end-of-file (no SEFT
		// on this fixture).
		int primaryEoi = JpegMarkerWalker.findPrimaryEoi(result, result.length);
		assertTrue("grafted primary must parse to a positive EOI offset", primaryEoi > 0);
		assertEquals("gain map must be the only bytes after the primary EOI",
			primaryEoi + gainMap.length, result.length);
		assertArrayEquals("gain map must be appended verbatim after the primary EOI",
			gainMap, Arrays.copyOfRange(result, primaryEoi, primaryEoi + gainMap.length));

		// Parse the patched MPF: mpfStart is the MP Endian field right after the 4-byte "MPF\0" signature;
		// the MP Entry array sits at mpfStart + 38 per the fixture's IFD layout (endian header 8 + IFD 30).
		int mpfStart = mpfSignatureIndex(result) + 4;
		int entryOff = mpfStart + 38;
		assertEquals("MPF entry[0].size must record the post-graft primary size",
			primaryEoi, ByteBufferUtils.readU32LE(result, entryOff + 4));
		assertEquals("gain-map entry size must record the appended gain map's byte count",
			gainMap.length, ByteBufferUtils.readU32LE(result, entryOff + 16 + 4));
		assertEquals("gain-map entry dataOffset must be primarySize - mpfStart",
			primaryEoi - mpfStart, ByteBufferUtils.readU32LE(result, entryOff + 16 + 8));
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
			JpegFixtures.dqtStub(), JpegFixtures.minimalScanAndEoi());
		byte[] edit = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.appSegment(0xE1, editExifPayload),
			JpegFixtures.dqtStub(), JpegFixtures.minimalScanAndEoi());

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
			JpegFixtures.soi(), JpegFixtures.dqtStub(), JpegFixtures.minimalScanAndEoi(), seftTrailer());
		byte[] edit = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(),
			JpegFixtures.minimalScanAndEoi());

		byte[] result = GraftWriter.graft(orig, edit);
		// Last 4 bytes of the result must be the SEFT magic (assuming SEFT trailer got appended).
		assertArrayEquals(new byte[] { 'S', 'E', 'F', 'T' },
			Arrays.copyOfRange(result, result.length - 4, result.length));
	}

	@Test
	public void graftRejectsEditTruncatedMidSosHeader() throws IOException
	{
		// Edit = SOI + DQT + SOS marker truncated (only 1 byte after FF DA, missing the second byte of the
		// 2-byte length field). Without the (off + 4 > file.length) guard, findPrimaryEoi would call
		// ByteBufferUtils.readU16BE on out-of-range bytes and throw AIOOBE, crashing the bg thread silently.
		// With the guard, findPrimaryEoi returns -1 and graft throws IOException so the caller can show a real
		// toast.
		byte[] orig = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(),
			JpegFixtures.minimalScanAndEoi());
		byte[] edit = JpegFixtures.concat(
			JpegFixtures.soi(), JpegFixtures.dqtStub(), new byte[] { (byte) 0xFF, (byte) 0xDA, 0x00 });

		IOException ex = assertThrows(IOException.class, () -> GraftWriter.graft(orig, edit));
		assertTrue("message should mention recoverable primary scan: " + ex.getMessage(),
			ex.getMessage().contains("primary scan"));
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
		byte[] orig = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(),
			JpegFixtures.minimalScanAndEoi());
		ByteArrayOutputStream editOut = new ByteArrayOutputStream();
		editOut.write(JpegFixtures.soi());
		editOut.write(JpegFixtures.dqtStub());
		editOut.write(0xFF);
		editOut.write(0xDA);
		editOut.write(0xFF);   // sosLen high byte
		editOut.write(0xFF);   // sosLen low byte = 0xFFFF
		editOut.write(new byte[] { 0x01, 0x02, 0x03, 0x04 }); // few padding bytes
		byte[] edit = editOut.toByteArray();

		IOException ex = assertThrows(IOException.class, () -> GraftWriter.graft(orig, edit));
		assertTrue("message should mention recoverable primary scan: " + ex.getMessage(),
			ex.getMessage().contains("primary scan"));
	}

	@Test
	public void graftRejectsNullAndNonJpegInputs() throws IOException
	{
		// Pin the three up-front guards (null original, null edit, non-JPEG signature). These are the first
		// line of defense before any byte-walking; removing any guard would NPE on the bg save thread (null) or
		// scan random bytes as JPEG markers (non-JPEG). The existing tests cover happy-path and malformed-SOS
		// rejections but never the input-shape gates.
		byte[] validJpeg = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.minimalScanAndEoi());
		byte[] notJpeg = { 0x12, 0x34, 0x56, 0x78 };

		IOException nullOriginalEx = assertThrows(IOException.class, () -> GraftWriter.graft(null, validJpeg));
		assertTrue("message should mention null: " + nullOriginalEx.getMessage(),
			nullOriginalEx.getMessage().contains("null"));

		IOException nullEditEx = assertThrows(IOException.class, () -> GraftWriter.graft(validJpeg, null));
		assertTrue("message should mention null: " + nullEditEx.getMessage(),
			nullEditEx.getMessage().contains("null"));

		IOException nonJpegOriginalEx = assertThrows(IOException.class,
			() -> GraftWriter.graft(notJpeg, validJpeg));
		assertTrue("message should mention Original / JPEG: " + nonJpegOriginalEx.getMessage(),
			nonJpegOriginalEx.getMessage().contains("Original")
				|| nonJpegOriginalEx.getMessage().contains("JPEG"));

		IOException nonJpegEditEx = assertThrows(IOException.class,
			() -> GraftWriter.graft(validJpeg, notJpeg));
		assertTrue("message should mention Edit / JPEG: " + nonJpegEditEx.getMessage(),
			nonJpegEditEx.getMessage().contains("Edit") || nonJpegEditEx.getMessage().contains("JPEG"));
	}

	@Test
	public void graftSkipsGainMapForSdrSourceWithEmbeddedFfd8PostEoi() throws IOException
	{
		// Pin GraftWriter's HDR per-side AND-gate (`origHasMpf && hasHdrgmInXmp`). Build an SDR original with
		// NO MPF segment and NO hdrgm in XMP, but post-primary-EOI bytes that structurally look like a JPEG
		// (`FF D8 ... FF D9` mimicking a SEFT-block embedded thumbnail). Without the AND-gate, a regression
		// that gated only on hasMpf — or only on hasHdrgmInXmp — would mis-walk those bytes as a synthesised
		// gain map and append them as HDR-claiming output. With the gate intact, GainMapExtractor refuses to
		// inspect post-EOI bytes and the graft output carries no gain map. Mirrors the HdrSignature load-time
		// gate but on the graft path.
		ByteArrayOutputStream origOut = new ByteArrayOutputStream();
		origOut.write(JpegFixtures.soi());
		origOut.write(JpegFixtures.dqtStub());
		origOut.write(JpegFixtures.minimalScanAndEoi());
		// Post-EOI bytes: a structurally-valid mini-JPEG (FF D8 + DQT + SOS + FF D9) that a regressed
		// GainMapExtractor would happily walk. No "SEFT" footer so SeftExtractor refuses to claim it as a
		// trailer either — these bytes should be entirely ignored by graft.
		origOut.write(JpegFixtures.soi());
		origOut.write(JpegFixtures.dqtStub());
		origOut.write(JpegFixtures.minimalScanAndEoi());
		byte[] orig = origOut.toByteArray();

		byte[] edit = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(),
			JpegFixtures.minimalScanAndEoi());

		byte[] result = GraftWriter.graft(orig, edit);

		// Find the primary EOI in the result. Anything past it would be a (mis-)appended gain map or a SEFT
		// trailer. Neither should be present for SDR-with-no-SEFT input.
		int primaryEoi = JpegMarkerWalker.findPrimaryEoi(result, result.length);
		assertEquals("graft of SDR sources must produce a clean primary with nothing appended",
			result.length, primaryEoi);
	}

	@Test
	public void graftStripsOrphanMpfWhenSourceHasMpfButNoGainMap() throws IOException
	{
		// Source carries an MPF segment but NO gain map (Samsung "Best Photo" burst, focus-stacked panorama,
		// ZSL — multi-picture JPEGs pre-dating Ultra HDR). The graft preserves source's metadata verbatim, but
		// the spliced primary doesn't carry the secondary images source's MPF describes — strict-MPF decoders
		// reject the orphan, lenient ones walk past malformed entries. Output must NOT contain "MPF\0" when
		// gainMapToWrite == null. Pins the dropOrphanMpf branch against a "simplify the segment loop" refactor
		// that removes it.
		byte[] orig = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE2, mpfPayloadWithSignature()),
			JpegFixtures.dqtStub(), JpegFixtures.minimalScanAndEoi());
		byte[] edit = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(),
			JpegFixtures.minimalScanAndEoi());

		byte[] result = GraftWriter.graft(orig, edit);
		assertFalse("orphan MPF must be stripped when no gain map anchors it", containsMpfSignature(result));
	}

	@Test
	public void graftThrowsWhenHdrOriginalMpfIsMalformed() throws IOException
	{
		// HDR original whose MPF passes the signature gate ("MPF\0" present, so isMpf() and the graft's HDR
		// gating both open) but whose MP Endian byte-order field is neither II nor MM —
		// mpfPayloadWithSignature's all-zero body models exactly that. MpfPatcher.patch refuses, and graft
		// MUST surface the refusal as IOException("MPF patch failed ..."). A regression that logs instead of
		// throwing ships gain-map bytes with stale MPF offsets pointing into the old primary's scan — silent
		// HDR corruption that Samsung Gallery's Revert pre-flight either rejects or renders at the wrong
		// offset.
		byte[] orig = JpegFixtures.concat(JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE1, hdrgmXmpPayload()),
			JpegFixtures.appSegment(0xE2, mpfPayloadWithSignature()),
			JpegFixtures.dqtStub(), JpegFixtures.minimalScanAndEoi(), gainMapJpeg());
		byte[] edit = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(),
			JpegFixtures.minimalScanAndEoi());

		IOException ex = assertThrows(IOException.class, () -> GraftWriter.graft(orig, edit));
		assertTrue("message should mention the MPF patch failure: " + ex.getMessage(),
			ex.getMessage().contains("MPF patch failed"));
	}

	@Test
	public void graftUsesEditPrimaryScanNotOriginals() throws IOException
	{
		// Source carries a primary scan whose entropy-coded body has byte value 0xAA; edit carries 0xBB. Per
		// the design, the output's primary scan must come from edit (the AI-edited pixels) — verifying this is
		// the whole point of the graft.
		byte[] origScan = scanWithPayload((byte) 0xAA);
		byte[] editScan = scanWithPayload((byte) 0xBB);

		byte[] orig = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(), origScan);
		byte[] edit = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(), editScan);

		byte[] result = GraftWriter.graft(orig, edit);
		assertTrue("result should contain edit's scan sentinel (0xBB)", containsSentinel(result, (byte) 0xBB));
		assertFalse("result must not contain source's scan sentinel (0xAA)",
			containsSentinel(result, (byte) 0xAA));
	}

	/**
	 * Probe for the 4-byte ASCII "MPF\0" signature — the segment-body identifier that follows the APP2 marker +
	 * length in any MPF segment. Used by graftStripsOrphanMpfWhenSourceHasMpfButNoGainMap to confirm the
	 * dropOrphanMpf branch actually removed source's MPF segment from the assembled output.
	 *
	 * @param data assembled JPEG bytes to scan end to end
	 * @return true when the signature occurs anywhere in data
	 */
	private static boolean containsMpfSignature(byte[] data)
	{
		return mpfSignatureIndex(data) != -1;
	}

	/**
	 * Crude provenance probe: the test plants distinct sentinels in source vs edit segments, then asserts which
	 * sentinel is or isn't in the output.
	 *
	 * @param data     assembled JPEG bytes to scan end to end
	 * @param sentinel byte value planted by exactly one side of the graft
	 * @return true when the sentinel byte occurs anywhere in data
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
	 * identifies which side of the graft contributed the segment. 4 sentinel bytes are enough to make the
	 * containsSentinel walk find the pattern; callers pick distinct sentinels per side.
	 *
	 * @param sentinel byte written 4 times as the IFD body; pick a distinct value per side of the graft
	 * @return EXIF payload bytes ending in the 4-byte sentinel run
	 * @throws IOException never from the in-memory composition; declared by the OutputStream write contract
	 */
	private static byte[] exifPayloadWithSentinel(byte sentinel) throws IOException
	{
		return JpegFixtures.concat(JpegFixtures.exifAppPayload(),
			new byte[] { sentinel, sentinel, sentinel, sentinel });
	}

	/**
	 * Build a self-contained gain-map JPEG (SOI + DQT stub + minimal scan + EOI). Appended after the primary EOI
	 * in the HDR-original fixtures so GainMapExtractor finds a walkable secondary image there.
	 *
	 * @return complete secondary-image JPEG bytes, SOI through EOI
	 * @throws IOException never from the in-memory composition; declared by the OutputStream write contract
	 */
	private static byte[] gainMapJpeg() throws IOException
	{
		return JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(),
			JpegFixtures.minimalScanAndEoi());
	}

	/**
	 * Build a standard XMP APP1 payload carrying the hdrgm namespace declaration — the Ultra HDR signature
	 * HdrSignature.hasHdrgmInXmp gates gain-map extraction on. The body only needs the 5-byte "hdrgm" sequence
	 * inside an XMP-headed APP1; a realistic xmlns declaration keeps the fixture honest.
	 *
	 * @return US-ASCII payload: XMP namespace header + hdrgm-declaring xmpmeta body
	 */
	private static byte[] hdrgmXmpPayload()
	{
		return (JpegSegment.XMP_HEADER + "<x:xmpmeta xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\">"
			+ "<hdrgm:Version>1.0</hdrgm:Version></x:xmpmeta>").getBytes(StandardCharsets.US_ASCII);
	}

	/**
	 * Build a minimal MPF APP2 payload — the 4-byte "MPF\0" signature followed by 24 bytes of stubbed MPF body.
	 * Sufficient for GraftWriter's JpegSegment.isMpf check to match; the body bytes don't need to parse as a real
	 * MPF index for the orphan-strip test, which only asserts the signature is absent from the output.
	 *
	 * @return 28-byte payload: "MPF\0" + zero-filled stub body
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
	 * Index counterpart to containsMpfSignature — the HDR happy-path test derives mpfStart (the MP Endian field,
	 * signature index + 4) from it to read the patched MP Entry fields.
	 *
	 * @param data assembled JPEG bytes to scan end to end
	 * @return index of the first 4-byte ASCII "MPF\0" signature, or -1 when absent
	 */
	private static int mpfSignatureIndex(byte[] data)
	{
		for (int i = 0; i + 3 < data.length; i++)
		{
			if (data[i] == 'M' && data[i + 1] == 'P' && data[i + 2] == 'F' && data[i + 3] == 0)
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Build a minimal SOS segment + entropy-coded body padded with the sentinel byte + EOI. Used to plant a
	 * side-distinguishing pattern in the primary scan so the test can verify the output's scan came from the right
	 * side.
	 *
	 * @param sentinel byte repeated 32 times as the entropy body — long enough that containsSentinel can't be
	 *                 satisfied by a stray metadata byte
	 * @return SOS marker + scan header + sentinel run + EOI
	 * @throws IOException never from the in-memory stream; declared by the OutputStream write contract
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
	 * Build a minimal SEFT trailer: 4-byte size field + "SEFT" magic. SeftExtractor checks for the "SEFT" magic in
	 * the file's last 4 bytes and then walks the marker chain forward to locate the trailer start, so this shape is
	 * sufficient to make GraftWriter pick up the trailer and re-append it verbatim in the output.
	 *
	 * @return 8 trailer bytes: little-endian size field, then "SEFT"
	 */
	private static byte[] seftTrailer()
	{
		return new byte[]{ 0x10, 0x00, 0x00, 0x00, 'S', 'E', 'F', 'T' };
	}

	/**
	 * Build a valid Ultra-HDR-shaped MPF APP2 payload: "MPF\0" signature + MpfFixtures' canonical 2-image table
	 * whose second entry carries the 0x010005 gain-map MPType, so MpfPatcher.patch accepts and rewrites it.
	 *
	 * @return complete MPF payload with stale size/offset fields for MpfPatcher to rewrite
	 */
	private static byte[] validMpfPayload()
	{
		return MpfFixtures.prefixMpfMagic(MpfFixtures.ultraHdrMpfPayload());
	}
}
