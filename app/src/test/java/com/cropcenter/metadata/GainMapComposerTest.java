package com.cropcenter.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Tests for GainMapComposer.compose covering the four documented outcomes — three HDR-drop paths plus the full-success
 * path — and the ComposeResult.hdrAttached flag that disambiguates them for downstream callers.
 *
 * hdrAttached is the load-bearing signal: reference identity cannot stand in for it, because the MPF-fail drop path
 * returns a freshly-allocated XMP-patched array distinct from the input — a `result != input` test reads that drop as
 * success and reports "[HDR OK]" on a file with no gain map. Each test below asserts both `bytes` content/identity AND
 * the explicit `hdrAttached` flag so the tagged contract can't drift to reference-inequality.
 */
public final class GainMapComposerTest
{
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void composeFileToFileCopiesVerbatimWhenXmpPatcherRefuses() throws IOException
	{
		// Streaming mirror of composeReturnsPrimaryWhenStandardItemLengthIsUnpatchable: when
		// XmpItemLengthPatcher.patch returns empty (standard XMP carries Item:Length but with a malformed
		// empty digit run), composeFileToFile must Files.copy the primary verbatim and return false. A
		// regression returning true ships hdrAttached=true (plus the "[HDR OK]" toast) on a file whose XMP /
		// MPF claim HDR with no gain map appended; removing the fail-closed check instead NPEs the bg save
		// thread when it dereferences the absent patchedHead's length.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=\"\"/>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = buildPrimary(standardXmp);
		byte[] gainMap = new byte[40];
		Arrays.fill(gainMap, (byte) 0x42);
		File inFile = tmp.newFile("refused.jpg");
		Files.write(inFile.toPath(), primary);
		File outFile = tmp.newFile("refused-out.jpg");

		boolean hdrAttached = GainMapComposer.composeFileToFile(inFile, gainMap, outFile);
		assertFalse("XmpItemLengthPatcher refusal must report hdrAttached=false", hdrAttached);
		assertArrayEquals("XmpItemLengthPatcher refusal must copy the primary verbatim (no gain map, "
			+ "no XMP rewrite)", primary, Files.readAllBytes(outFile.toPath()));
	}

	@Test
	public void composeFileToFileHandlesPrimaryLargerThanHeadReadLimit() throws IOException
	{
		// composeFileToFile loads only the first 16 MB (HEAD_READ_LIMIT) of the primary for XMP / MPF patching
		// and streams the rest from disk. Push the file past that limit with entropy and assert the streaming
		// output stays byte-identical to the in-memory compose — pinning the head / tail seam arithmetic
		// (originalHeadSize vs the patched head's length after the Item:Length digit-count delta) that only
		// engages once the head buffer is actually capped.
		byte[] standardXmp = (JpegSegment.XMP_HEADER
			+ "<rdf:Description Item:Length=\"100\" Item:Mime=\"image/jpeg\"/>")
				.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = insertEntropyBeforeEoi(buildPrimary(standardXmp), 17 * 1024 * 1024);
		byte[] gainMap = new byte[40];
		Arrays.fill(gainMap, (byte) 0x42);

		GainMapComposer.ComposeResult inMemory = GainMapComposer.compose(primary, gainMap);
		assertTrue("in-memory compose on the oversized primary must attach HDR", inMemory.hdrAttached());
		File inFile = tmp.newFile("oversized.jpg");
		Files.write(inFile.toPath(), primary);
		File outFile = tmp.newFile("oversized-out.jpg");
		boolean hdrAttached = GainMapComposer.composeFileToFile(inFile, gainMap, outFile);
		assertTrue("streaming compose on the oversized primary must attach HDR", hdrAttached);
		assertArrayEquals("head-capped streaming compose must stay byte-identical to in-memory",
			inMemory.bytes(), Files.readAllBytes(outFile.toPath()));
	}

	@Test
	public void composeFileToFileProducesByteIdenticalOutputToInMemoryCompose() throws IOException
	{
		// Streaming-variant regression: composeFileToFile must produce a byte-for-byte identical output to
		// `compose(primaryBytes, gainMap).bytes()` on the full-success path. Without this contract, a future
		// divergence (different MpfPatcher invocation shape, different chunk boundary, head-truncation
		// off-by-one) could silently ship different bytes for the streaming PNG-save path vs the in-memory
		// JPEG-save path — a divergence that wouldn't surface until a user reported mismatched outputs across
		// formats.
		byte[] primary = buildPrimary();
		byte[] gainMap = new byte[40];
		Arrays.fill(gainMap, (byte) 0x42);
		// In-memory baseline.
		GainMapComposer.ComposeResult inMemory = GainMapComposer.compose(primary, gainMap);
		assertTrue("in-memory full-success path must mark hdrAttached", inMemory.hdrAttached());
		// Streaming variant — same input written to a tempfile, output to another tempfile.
		File inFile = tmp.newFile("primary.jpg");
		File outFile = tmp.newFile("composed.jpg");
		Files.write(inFile.toPath(), primary);
		boolean hdrAttached = GainMapComposer.composeFileToFile(inFile, gainMap, outFile);
		assertTrue("streaming full-success path must mark hdrAttached", hdrAttached);
		byte[] streamingOutput = Files.readAllBytes(outFile.toPath());
		assertArrayEquals("streaming compose must produce byte-identical output to in-memory variant",
			inMemory.bytes(), streamingOutput);
	}

	@Test
	public void composeFileToFileUsesActualBytesWhenFileShorterThanReportedLength() throws IOException
	{
		// The head read sizes its buffer from File.length(), but the stream may return EOF earlier (file shrank
		// between the length() call and the read). The truncation branch must shrink the head to the bytes
		// actually read and carry on. On this SDR-shaped primary (no XMP, no MPF) the patchers pass through and
		// MpfPatcher fails, so the well-defined output is the actual file bytes verbatim with hdrAttached=false
		// — the phantom tail the lying length() promised must contribute nothing.
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(),
			JpegFixtures.minimalScanAndEoi());
		byte[] gainMap = { 0x42, 0x42 };
		File realFile = tmp.newFile("shrunk.jpg");
		Files.write(realFile.toPath(), primary);
		File outFile = tmp.newFile("shrunk-out.jpg");
		boolean hdrAttached = GainMapComposer.composeFileToFile(
			JpegFixtures.fileReportingLength(realFile, primary.length + 64), gainMap, outFile);
		assertFalse("MPF-less primary must not attach HDR", hdrAttached);
		assertArrayEquals("short head read must ship the actual bytes, not a padded head",
			primary, Files.readAllBytes(outFile.toPath()));
	}

	@Test
	public void composeHdrAttachedFalseWhenXmpPatchedButMpfFails() throws IOException
	{
		// Regression for a detection-shape bug: when standard XMP carries a patchable Item:Length AND
		// MpfPatcher subsequently fails (no MPF segment), GainMapComposer returns the XMP-patched primary (a
		// freshly-allocated byte array because Item:Length was rewritten) — DIFFERENT reference from the input.
		// The previous reference-inequality detection in CropExporter (`withGainMap != withFullMeta`) wrongly
		// fired hdrAttached=true on this path, skipped stripHdrSegments, and toasted "[HDR OK]" on a file that
		// ships with no gain map but XMP that still claims one. The ComposeResult.hdrAttached flag must be
		// false here regardless of byte-array identity.
		byte[] standardXmp = (JpegSegment.XMP_HEADER
			+ "<rdf:Description Item:Length=\"100\" Item:Mime=\"image/jpeg\"/>")
				.getBytes(StandardCharsets.US_ASCII);
		// Build a primary with a patchable XMP segment but NO MPF segment. XmpItemLengthPatcher will rewrite
		// "100" to the gain-map size (40), producing a new byte array; MpfPatcher will then fail because no MPF
		// segment exists.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(JpegFixtures.soi());
		out.write(JpegFixtures.appSegment(0xE1, standardXmp));
		out.write(JpegFixtures.minimalScanAndEoi());
		byte[] primary = out.toByteArray();
		byte[] gainMap = new byte[40];
		Arrays.fill(gainMap, (byte) 0x42);

		GainMapComposer.ComposeResult result = GainMapComposer.compose(primary, gainMap);
		assertFalse("MPF-fail-after-XMP-patch must mark hdrAttached=false even when bytes != primary",
			result.hdrAttached());
	}

	@Test
	public void composeReturnsCombinedBytesWhenPatchSucceeds() throws IOException
	{
		// Build a minimal Ultra-HDR-shaped primary (SOI + MPF APP2 + minimal scan + EOI), append a fake gain
		// map, and verify compose returns the combined bytes (length > primary.length) — i.e., the MPF patch
		// succeeded and anchored the gain map at the right offset.
		byte[] primary = buildPrimary();
		byte[] gainMap = new byte[40];
		Arrays.fill(gainMap, (byte) 0x42);

		GainMapComposer.ComposeResult result = GainMapComposer.compose(primary, gainMap);
		assertTrue("full-success path must mark hdrAttached=true", result.hdrAttached());
		byte[] bytes = result.bytes();
		assertEquals("compose should return primary + gainMap concatenated",
			primary.length + gainMap.length, bytes.length);
		// First primary.length bytes should be the primary verbatim (MPF segment inside has been mutated by
		// patch, but the first bytes of the segment header / SOI / etc are unchanged).
		assertEquals((byte) 0xFF, bytes[0]);
		assertEquals((byte) 0xD8, bytes[1]);
		// Last gainMap.length bytes should be our 0x42 padding.
		assertArrayEquals("gain map should be appended verbatim",
			gainMap, Arrays.copyOfRange(bytes, primary.length, primary.length + gainMap.length));
	}

	@Test
	public void composeReturnsPrimaryUnchangedForAbsentGainMap()
	{
		// Single guard: `gainMap == null || gainMap.length == 0` short-circuits both cases through the same
		// early-return. Pin both inputs against the same path so a regression that split the guard (e.g., only
		// null → return, empty → fall through) surfaces here.
		byte[] primary = { 1, 2, 3, 4 };
		GainMapComposer.ComposeResult nullResult = GainMapComposer.compose(primary, null);
		assertFalse("null gain map must mark hdrAttached=false", nullResult.hdrAttached());
		assertSame("null gain map should pass primary through verbatim", primary, nullResult.bytes());
		GainMapComposer.ComposeResult emptyResult = GainMapComposer.compose(primary, new byte[0]);
		assertFalse("empty gain map must mark hdrAttached=false", emptyResult.hdrAttached());
		assertSame("empty gain map should pass primary through verbatim", primary, emptyResult.bytes());
	}

	@Test
	public void composeReturnsPrimaryUnchangedWhenMpfPatchFails()
	{
		// Without a valid MPF segment in primary, MpfPatcher.patch returns false. compose must DROP the gain
		// map — appending orphaned gain-map bytes that no MPF entry points at would either crash strict
		// decoders' Revert pre-flight (Samsung Gallery) or render with the wrong offset in lenient decoders.
		// hdrAttached=false in the ComposeResult is what CropExporter uses to set ExportResult.hdrAttached and
		// toast "[HDR dropped]" instead of "[HDR OK]". On this primary (no XMP, no MPF), the
		// XmpItemLengthPatcher passes through with the input array unchanged, so the returned bytes are also
		// the input reference — pinning that with assertSame protects against an inadvertent re-allocation that
		// would also still be wrong-but-undetectable.
		byte[] primary = { 0x10, 0x20, 0x30 };
		byte[] gainMap = { 0x40, 0x50 };
		GainMapComposer.ComposeResult result = GainMapComposer.compose(primary, gainMap);
		assertFalse("MPF patch failure must mark hdrAttached=false", result.hdrAttached());
		assertSame("MPF patch failure should drop the gain map and return primary", primary, result.bytes());
	}

	@Test
	public void composeReturnsPrimaryWhenItemLengthInExtendedXmp() throws IOException
	{
		// When XmpItemLengthPatcher.patch returns empty (Item:Length lives in Extended XMP, which can't be
		// safely patched in-place across the per-chunk reassembly headers), compose must drop the gain map and
		// ship primary verbatim. Without this fail-closed integration, the composer would either NPE on the
		// absent `patched` array or ship a file with stale Item:Length — silent HDR-boost loss in strict
		// GContainer-respecting decoders. Build a primary with valid MPF (so MPF patching would succeed if
		// reached) and Extended XMP carrying Item:Length, verify the patcher's empty return short-circuits
		// before MpfPatcher runs.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<x:xmpmeta><hdrgm:Version>1.0</hdrgm:Version>"
			+ "</x:xmpmeta>").getBytes(StandardCharsets.US_ASCII);
		// Extended XMP chunk: namespace prefix (35 bytes) + 32-byte GUID + 4-byte total-length + 4-byte offset
		// + body containing Item:Length=. The patcher's reassembly fallback OR the per-chunk scan will detect
		// Item:Length= and return empty.
		byte[] extXmp = buildExtendedXmpChunk(
			"<rdf:Description Item:Length=\"43099\" Item:Mime=\"image/jpeg\"/>");
		byte[] primary = buildPrimary(standardXmp, extXmp);
		byte[] gainMap = new byte[40];
		Arrays.fill(gainMap, (byte) 0x42);

		GainMapComposer.ComposeResult result = GainMapComposer.compose(primary, gainMap);
		assertFalse("Item:Length-in-Extended-XMP must mark hdrAttached=false", result.hdrAttached());
		assertSame("Item:Length-in-Extended-XMP must drop the gain map (patcher null return)",
			primary, result.bytes());
	}

	@Test
	public void composeReturnsPrimaryWhenMpfMissingEvenWithGainMap() throws IOException
	{
		// Variant of the patch-failure test using a real-looking JPEG (SOI + DQT + minimal scan + EOI) that has
		// NO MPF segment. patch returns false; compose must drop the gain map. This pin closes the hardest
		// case: a valid SDR JPEG (not Ultra HDR) being passed to compose — the gain map shouldn't hitch a ride.
		byte[] primary = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.dqtStub(),
			JpegFixtures.minimalScanAndEoi());
		byte[] gainMap = { 0x42, 0x42, 0x42 };
		GainMapComposer.ComposeResult result = GainMapComposer.compose(primary, gainMap);
		assertFalse("SDR primary with no MPF must mark hdrAttached=false", result.hdrAttached());
		assertSame("SDR primary with no MPF should ignore gain map", primary, result.bytes());
	}

	@Test
	public void composeReturnsPrimaryWhenStandardItemLengthIsUnpatchable() throws IOException
	{
		// Pin GainMapComposer's null-return handling for the OTHER trigger:
		// XmpItemLengthPatcher returns null because standard XMP carries Item:Length but the segment
		// is unpatchable (here: malformed empty digit run). Existing
		// composeReturnsPrimaryWhenItemLengthInExtendedXmp covers the Extended XMP trigger; this
		// pins the symmetric standard-XMP-unpatchable path so a regression that only checks the
		// Extended XMP case (or NPEs on null `patched.length`) gets caught.
		byte[] standardXmp = (JpegSegment.XMP_HEADER + "<rdf:Description Item:Length=\"\"/>")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] primary = buildPrimary(standardXmp);
		byte[] gainMap = new byte[40];
		Arrays.fill(gainMap, (byte) 0x42);

		GainMapComposer.ComposeResult result = GainMapComposer.compose(primary, gainMap);
		assertFalse("standard-XMP unpatchable Item:Length must mark hdrAttached=false", result.hdrAttached());
		assertSame("standard-XMP unpatchable Item:Length must drop the gain map (patcher null return)",
			primary, result.bytes());
	}

	private static byte[] buildExtendedXmpChunk(String inner) throws IOException
	{
		return JpegFixtures.extendedXmpChunk("0123456789abcdef0123456789abcdef", 0x1000L, 0L,
			inner.getBytes(StandardCharsets.US_ASCII));
	}

	/**
	 * Build an Ultra-HDR-shaped primary JPEG: SOI + the given APP1 payloads in order + MPF APP2 with the canonical
	 * 2-image MP Entries table + minimal scan + EOI. primarySize passed to MpfPatcher.patch is set to the total
	 * primary length so relativeOffset = primarySize - mpfStart is non-negative and the gain-map entry can be
	 * patched. The standard-XMP-unpatchable tests pass only a standard XMP payload (Extended XMP must NOT be
	 * present so the patcher's empty return is unambiguously triggered by the standard-XMP body).
	 *
	 * @param app1Payloads zero or more APP1 payload bodies (standard XMP, Extended XMP) emitted before the MPF
	 *                     APP2 segment
	 * @return complete primary JPEG bytes with stale MP Entry size/offset fields for the compose pass to rewrite
	 * @throws IOException never from the in-memory streams; declared by the OutputStream write contract
	 */
	private static byte[] buildPrimary(byte[]... app1Payloads) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(JpegFixtures.soi());
		for (byte[] app1Payload : app1Payloads)
		{
			out.write(JpegFixtures.appSegment(0xE1, app1Payload));
		}
		out.write(JpegFixtures.appSegment(0xE2, MpfFixtures.prefixMpfMagic(MpfFixtures.ultraHdrMpfPayload())));
		out.write(JpegFixtures.minimalScanAndEoi());
		return out.toByteArray();
	}

	/**
	 * Grow a JPEG by splicing extra entropy bytes into its scan, immediately before the trailing EOI. The 0x42
	 * filler contains no 0xFF, so no stray markers appear in the entropy walk and the APP-segment head of the input
	 * is untouched — the resulting file differs from the input only in scan length.
	 *
	 * @param jpeg         complete JPEG bytes ending with FF D9
	 * @param entropyBytes number of filler bytes to splice in before the EOI
	 * @return new array of jpeg.length + entropyBytes bytes
	 */
	private static byte[] insertEntropyBeforeEoi(byte[] jpeg, int entropyBytes)
	{
		byte[] result = new byte[jpeg.length + entropyBytes];
		System.arraycopy(jpeg, 0, result, 0, jpeg.length - 2);
		Arrays.fill(result, jpeg.length - 2, jpeg.length - 2 + entropyBytes, (byte) 0x42);
		result[result.length - 2] = jpeg[jpeg.length - 2];
		result[result.length - 1] = jpeg[jpeg.length - 1];
		return result;
	}

}
