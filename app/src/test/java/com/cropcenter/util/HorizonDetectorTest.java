package com.cropcenter.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cropcenter.metadata.JpegFixtures;
import com.cropcenter.metadata.JpegSegment;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

/**
 * Tests the pure-Java precision refinements inside HorizonDetector. The bitmap edge pipeline itself depends on
 * android.graphics, but once edge coordinates have been gathered the Hough / line-fit math is ordinary geometry and can
 * be pinned down on the host JVM.
 */
public final class HorizonDetectorTest
{
	private static final int HEIGHT = 600;
	private static final float TOL = 1e-3f;
	private static final int WIDTH = 1200;
	private static final String XMP_ID = "http://ns.adobe.com/xap/1.0/\0";

	// ── detectFromMetadata ──

	@Test
	public void detectFromMetadataReturnsNaNForNullList()
	{
		// Null guard — caller passes null when JpegMetadataExtractor failed entirely.
		assertTrue(Float.isNaN(HorizonDetector.detectFromMetadata(null)));
	}

	@Test
	public void detectFromMetadataReturnsNaNForEmptyList()
	{
		assertTrue(Float.isNaN(HorizonDetector.detectFromMetadata(Collections.emptyList())));
	}

	@Test
	public void detectFromMetadataReturnsNaNWhenNoXmpSegmentPresent() throws IOException
	{
		// Single non-XMP APP1 segment (EXIF-shaped). Function must not crash, returns NaN.
		byte[] payload = JpegFixtures.exifAppPayload();
		JpegSegment exif = new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, payload));
		assertTrue(Float.isNaN(HorizonDetector.detectFromMetadata(Collections.singletonList(exif))));
	}

	@Test
	public void detectFromMetadataFindsRollAndInvertsSign() throws IOException
	{
		// A positive Roll in metadata represents CW tilt from the device's perspective; the UI corrects by
		// rotating CCW, so the function returns -roll. Pin the sign convention.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:Camera='c' Camera:Roll=\"1.50\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertEquals(-1.50f, result, TOL);
	}

	@Test
	public void detectFromMetadataFindsTiltWhenRollAbsent() throws IOException
	{
		// Roll missing, Tilt present — function falls through to the Tilt lookup and applies the same
		// sign-inversion contract.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:Camera='c' Camera:Tilt=\"2.30\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertEquals(-2.30f, result, TOL);
	}

	@Test
	public void detectFromMetadataPrefersRollOverTilt() throws IOException
	{
		// Both attributes present — Roll wins by lookup order. Pin this so a regression that re-orders the
		// searches doesn't silently change which axis correction is applied.
		JpegSegment xmp = buildXmpSegment(
			"<rdf:Description xmlns:Camera='c' Camera:Roll=\"1.00\" Camera:Tilt=\"2.00\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertEquals(-1.00f, result, TOL);
	}

	@Test
	public void detectFromMetadataSnapsSubEpsilonToZero() throws IOException
	{
		// 0.003° is below the 0.005° snap threshold — return 0f (not -0.003 rounded). A regression that removed
		// the snap would return tiny rotations that round-trip through the ruler as visible jitter.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:Camera='c' Camera:Roll=\"0.003\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertEquals(0f, result, 0f);
	}

	@Test
	public void detectFromMetadataRejectsImplausibleTiltAsNan() throws IOException
	{
		// > MAX_HORIZON_TILT_DEGREES (30°) is treated as bad sensor data — return NaN so the auto-rotate UI
		// doesn't apply a 35°-correction that's clearly wrong. (A 35° device tilt would normally trigger
		// orientation, not roll-correction.) The 30° threshold is shared with the painted-region path; bumping
		// from the old 25° aligns the two so the same image gets the same auto-rotate verdict regardless of
		// whether XMP roll happens to be present.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:Camera='c' Camera:Roll=\"35.0\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertTrue(Float.isNaN(result));
	}

	@Test
	public void detectFromMetadataAcceptsBoundaryAtThirtyDegrees() throws IOException
	{
		// Predicate is `Math.abs(deg) > MAX_HORIZON_TILT_DEGREES` (strict greater-than) — 30.0 exactly is
		// accepted. Pin both edges of the boundary so a refactor that swaps `>` for `>=` is caught.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:Camera='c' Camera:Roll=\"30.0\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertEquals(-30.0f, result, TOL);
	}

	@Test
	public void detectFromMetadataAcceptsAngleBetweenOldAndNewThreshold() throws IOException
	{
		// 28° fell inside the painted-region path's window but was rejected by the metadata path before the
		// thresholds were unified. Pin that the metadata path now accepts it so the two code paths can't
		// disagree about the same image again.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:Camera='c' Camera:Roll=\"28.0\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertEquals(-28.0f, result, TOL);
	}

	@Test
	public void detectFromMetadataRoundsToTwoDecimalPlaces() throws IOException
	{
		// 1.234 → -1.23 (round to 2 decimal places). Pin this because the ruler ticks at 0.01° and we want the
		// metadata read to land on a tick exactly.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:Camera='c' Camera:Roll=\"1.234\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertEquals(-1.23f, result, TOL);
	}

	@Test
	public void detectFromMetadataFindsRollInAdobeExtendedXmpChunkPastFirst() throws IOException
	{
		// A > 64 KB XMP packet split across multiple Adobe Extended XMP
		// chunks (different namespace prefix from standard XMP, plus 32-byte GUID + 4-byte total length
		// + 4-byte offset header) must be reassembled by GUID + offset before Roll/Tilt scanning.
		// Pre-fix the per-segment substring scan would have missed Roll attributes split across chunk
		// boundaries OR landing past the 65000-byte cap; even a clean Roll in the second chunk would
		// have been found by the per-segment fallback only if the chunk contained the literal
		// "Roll"/"Tilt" bytes. Here we deliberately put Roll across chunks (start in chunk0, end in
		// chunk1) — only correct reassembly recovers the value.
		String guid = "0123456789ABCDEF0123456789ABCDEF"; // 32 ASCII hex
		String chunk0Body = "<rdf:Description xmlns:Camera='c' Camera:Ro";
		String chunk1Body = "ll=\"4.20\"/>";
		JpegSegment seg0 = buildExtendedXmpChunk(guid, 0, chunk0Body);
		JpegSegment seg1 = buildExtendedXmpChunk(guid, chunk0Body.length(), chunk1Body);

		float result = HorizonDetector.detectFromMetadata(Arrays.asList(seg0, seg1));
		assertEquals(-4.20f, result, TOL);
	}

	@Test
	public void detectFromMetadataFindsTiltInReassembledExtendedXmp() throws IOException
	{
		// Pin the Tilt branch within the Extended XMP reassembly pass. The existing
		// detectFromMetadataFindsTiltInExtendedXmp test exercises the pass-3 non-canonical APP1 fallback
		// (its segment has no Adobe extension namespace prefix), NOT the reassembly path. A regression
		// that dropped the Tilt scan from the reassembled-XMP block would still pass that test plus the
		// Roll-only reassembly tests. Force the reassembly path by splitting a Tilt attribute across
		// two extension chunks so the per-segment scan in pass-3 can't recover it.
		String guid = "ABCDEF0123456789ABCDEF0123456789";
		String chunk0Body = "<rdf:Description xmlns:c='c' c:Ti";
		String chunk1Body = "lt=\"2.50\"/>";
		JpegSegment seg0 = buildExtendedXmpChunk(guid, 0, chunk0Body);
		JpegSegment seg1 = buildExtendedXmpChunk(guid, chunk0Body.length(), chunk1Body);

		float result = HorizonDetector.detectFromMetadata(Arrays.asList(seg0, seg1));
		assertEquals(-2.50f, result, TOL);
	}

	@Test
	public void detectFromMetadataPrefersStandardXmpOverExtendedXmp() throws IOException
	{
		// Round-22 test coverage B: pin the three-pass priority chain. Standard XMP must return its
		// Roll value WITHOUT invoking Extended XMP reassembly, even when both segments are present and
		// disagree. A refactor that moved the reassemble call before the primary loop would change the
		// returned value here without any other failing assertion.
		String guid = "1111222233334444AAAABBBBCCCCDDDD";
		String chunk0 = "<rdf:Description xmlns:c='c' c:Ro";
		String chunk1 = "ll=\"9.99\"/>";
		JpegSegment standard = buildXmpSegment(
			"<rdf:Description xmlns:c='c' c:Roll=\"1.50\"/>");
		JpegSegment ext0 = buildExtendedXmpChunk(guid, 0, chunk0);
		JpegSegment ext1 = buildExtendedXmpChunk(guid, chunk0.length(), chunk1);

		float result = HorizonDetector.detectFromMetadata(Arrays.asList(standard, ext0, ext1));
		assertEquals("standard XMP must win without invoking Extended XMP reassembly", -1.50f, result,
			TOL);
	}

	@Test
	public void detectFromMetadataReassemblesAdobeExtendedXmpAcrossDistinctGuidGroups() throws IOException
	{
		// Round-22 test coverage C: spec-legal multiple GUID groups in one file. The first group's
		// chunks carry no Roll/Tilt; the SECOND alphabetic group carries a Roll attribute split across
		// its two chunks. A speed-tweak that broke after the first GUID group, or that sorted only by
		// offset and lost the GUID primary key, would mis-reassemble and miss the Roll value.
		String guidA = "AAAA0000000000000000000000000000";
		String guidB = "BBBB0000000000000000000000000000";
		String aChunk0 = "<rdf:Description xmlns:dc='dc' dc:tit";
		String aChunk1 = "le=\"x\"/>";
		String bChunk0 = "<rdf:Description xmlns:c='c' c:Ro";
		String bChunk1 = "ll=\"1.75\"/>";
		JpegSegment a0 = buildExtendedXmpChunk(guidA, 0, aChunk0);
		JpegSegment a1 = buildExtendedXmpChunk(guidA, aChunk0.length(), aChunk1);
		JpegSegment b0 = buildExtendedXmpChunk(guidB, 0, bChunk0);
		JpegSegment b1 = buildExtendedXmpChunk(guidB, bChunk0.length(), bChunk1);

		// Pass in arbitrary segment-list order — sort-by-GUID-then-offset must restore correct grouping.
		float result = HorizonDetector.detectFromMetadata(Arrays.asList(b1, a0, b0, a1));
		assertEquals(-1.75f, result, TOL);
	}

	@Test
	public void detectFromMetadataReassemblesAdobeExtendedXmpInOffsetOrder() throws IOException
	{
		// Out-of-file-order chunks must still reassemble correctly: file order chunk1, chunk0 — the
		// reassembler sorts by GUID then offset so the resulting buffer is chunk0 + chunk1 regardless
		// of segment-list order. Without offset-aware reassembly, the concatenation would read
		// "ll=\"3.30\"/>" + "<rdf:Description ... Camera:Ro" and miss the "Roll" attribute entirely.
		String guid = "FEDCBA9876543210FEDCBA9876543210";
		String chunk0Body = "<rdf:Description xmlns:Camera='c' Camera:Ro";
		String chunk1Body = "ll=\"3.30\"/>";
		JpegSegment seg0 = buildExtendedXmpChunk(guid, 0, chunk0Body);
		JpegSegment seg1 = buildExtendedXmpChunk(guid, chunk0Body.length(), chunk1Body);

		float result = HorizonDetector.detectFromMetadata(Arrays.asList(seg1, seg0));
		assertEquals(-3.30f, result, TOL);
	}

	@Test
	public void detectFromMetadataFindsTiltInExtendedXmp() throws IOException
	{
		// Tilt-only segment in the extended-XMP fallback loop. Earlier the second loop only searched for "Roll"
		// so a Tilt-only extended-XMP segment slipped through and returned NaN. Pin the fix. The extended-XMP
		// path requires marker == 0xE1, length >= 50, and that the body contain "Roll" / "roll" / "Tilt" as a
		// pre-filter. We give it a non-canonical APP1 (no XMP_ID prefix) carrying a Tilt attribute.
		String xmpishBody = "<rdf:Description xmlns:Camera='c' Camera:Tilt=\"3.00\"/>"
			+ pad(60); // ensure segment length >= 50 after the 4-byte APP1 header
		byte[] payload = xmpishBody.getBytes(StandardCharsets.UTF_8);
		JpegSegment seg = new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, payload));
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(seg));
		assertEquals(-3.00f, result, TOL);
	}

	@Test
	public void detectFromMetadataTriesNextSegmentWhenFirstHasNoRollOrTilt() throws IOException
	{
		// Two XMP segments — first has neither Roll nor Tilt, second has Roll. Loop must continue to the
		// second.
		JpegSegment empty = buildXmpSegment("<rdf:Description xmlns:dc='dc' dc:title=\"photo\"/>");
		JpegSegment withRoll = buildXmpSegment("<rdf:Description xmlns:Camera='c' Camera:Roll=\"1.50\"/>");
		float result = HorizonDetector.detectFromMetadata(Arrays.asList(empty, withRoll));
		assertEquals(-1.50f, result, TOL);
	}

	@Test
	public void detectFromMetadataRejectsCameraRollGreedyMatch() throws IOException
	{
		// "CameraRoll" is NOT the same attribute as "Camera:Roll" — but a sloppy regex like `\\w*:?Roll`
		// would greedy-match the suffix and return CameraRoll's value as the horizon angle. The fix-comment
		// in HorizonDetector.findXmpFloat (line 471) explicitly calls out this regression. Pin it: a
		// CameraRoll attribute with no Roll attribute returns NaN.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:x='c' x:CameraRoll=\"9.0\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertTrue("CameraRoll must NOT match the Roll suffix: " + result, Float.isNaN(result));
	}

	@Test
	public void detectFromMetadataRejectsGyroRollGreedyMatch() throws IOException
	{
		// Same regression class as CameraRoll — a "GyroRoll" attribute (any custom namespace ending in
		// "Roll") must not match. Mirror test for completeness.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:y='c' y:GyroRoll=\"7.5\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertTrue("GyroRoll must NOT match the Roll suffix: " + result, Float.isNaN(result));
	}

	@Test
	public void detectFromMetadataSkipsUnparseableValueAndContinuesSearch() throws IOException
	{
		// Two Roll attributes in the SAME XMP — first is unparseable ("invalid"), second is "2.50". The
		// findXmpFloat loop catches NumberFormatException and continues with matcher.find(), so it should
		// land on 2.50. Without the loop-on-parse-fail behaviour the regex would return NaN on the first
		// match and never see the second.
		String body = "<rdf:Description xmlns:a='a' xmlns:b='b' "
			+ "a:Roll=\"invalid\" b:Roll=\"2.50\"/>";
		JpegSegment xmp = buildXmpSegment(body);
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertEquals(-2.50f, result, TOL);
	}

	@Test
	public void detectFromMetadataIgnoresMalformedQuotedValue() throws IOException
	{
		// Roll attribute with no closing quote — the regex `Roll\\s*=\\s*"([^"]+)"` won't match at all
		// (the value capture requires a closing quote), so this falls through to NaN rather than parsing
		// up to end-of-segment.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:c='c' c:Roll=\"1.5/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertTrue("malformed unclosed quote must not parse: " + result, Float.isNaN(result));
	}

	@Test
	public void detectFromMetadataParsesRollWithLeadingAndTrailingWhitespace() throws IOException
	{
		// XMP serializers in the wild sometimes pad attribute values with whitespace ("  1.50  ") for
		// formatting. The .trim() call on the captured regex group at HorizonDetector.findXmpFloat is the
		// only thing that lets these parse — without it, Float.parseFloat throws NumberFormatException
		// (which the catch silently swallows) and the user's "auto-rotate stops working" for cameras that
		// pad their XMP.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:c='c' c:Roll=\"  1.50  \"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertEquals(-1.50f, result, TOL);
	}

	// ── refineLineFitAngle (existing) ──

	@Test
	public void lineFitRefinesFractionalHorizonSlope()
	{
		float trueTilt = 1.23f;
		int[][] line = syntheticLine(trueTilt);

		float refined = HorizonDetector.refineLineFitAngle(line[0], line[1],
			line[0].length, WIDTH, HEIGHT, 90f + 1.20f);

		assertEquals(90f + trueTilt, refined, 0.02f);
	}

	@Test
	public void lineFitRejectsLargeDisagreementWithHoughSeed()
	{
		int[][] line = syntheticLine(1.23f);

		float refined = HorizonDetector.refineLineFitAngle(line[0], line[1],
			line[0].length, WIDTH, HEIGHT, 91.70f);

		assertTrue(Float.isNaN(refined));
	}

	/**
	 * Build a synthetic Adobe Extended XMP APP1 chunk: namespace prefix + 32-byte GUID + 4-byte total
	 * length (placeholder zero — extractor doesn't validate it) + 4-byte big-endian offset + chunk body.
	 * Used by the reassembly tests to compose multi-chunk payloads that ExtendedXmpReassembler must
	 * merge by GUID + offset before HorizonDetector scans for Roll/Tilt.
	 */
	private static JpegSegment buildExtendedXmpChunk(String guid, int offset, String body)
		throws IOException
	{
		byte[] prefix = "http://ns.adobe.com/xmp/extension/\0".getBytes(StandardCharsets.UTF_8);
		byte[] guidBytes = guid.getBytes(StandardCharsets.US_ASCII);
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
		byte[] payload = new byte[prefix.length + guidBytes.length + 4 + 4 + bodyBytes.length];
		int pos = 0;
		System.arraycopy(prefix, 0, payload, pos, prefix.length);
		pos += prefix.length;
		System.arraycopy(guidBytes, 0, payload, pos, guidBytes.length);
		pos += guidBytes.length;
		// 4-byte total length placeholder (extractor reads but doesn't validate).
		pos += 4;
		// 4-byte big-endian offset.
		payload[pos] = (byte) ((offset >> 24) & 0xFF);
		payload[pos + 1] = (byte) ((offset >> 16) & 0xFF);
		payload[pos + 2] = (byte) ((offset >> 8) & 0xFF);
		payload[pos + 3] = (byte) (offset & 0xFF);
		pos += 4;
		System.arraycopy(bodyBytes, 0, payload, pos, bodyBytes.length);
		return new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, payload));
	}

	/**
	 * Build a synthetic XMP APP1 segment with the given XML body wrapped in the canonical
	 * "http://ns.adobe.com/xap/1.0/\0" identifier, suitable for HorizonDetector.detectFromMetadata's primary-loop
	 * entry.
	 */
	private static JpegSegment buildXmpSegment(String xml) throws IOException
	{
		byte[] xmpIdBytes = XMP_ID.getBytes(StandardCharsets.UTF_8);
		byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
		byte[] payload = new byte[xmpIdBytes.length + xmlBytes.length];
		System.arraycopy(xmpIdBytes, 0, payload, 0, xmpIdBytes.length);
		System.arraycopy(xmlBytes, 0, payload, xmpIdBytes.length, xmlBytes.length);
		return new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, payload));
	}

	/**
	 * Build a string of `length` ASCII filler chars — used to stretch synthetic APP1 payloads past the 50-byte
	 * minimum that detectFromMetadata's extended-XMP fallback loop requires.
	 */
	private static String pad(int length)
	{
		char[] buf = new char[length];
		Arrays.fill(buf, ' ');
		return new String(buf);
	}

	/**
	 * Build integer edge coordinates for a single near-horizontal line at the requested tilt.
	 *
	 * @param tiltDegrees line tilt in screen/image coordinates
	 * @return two arrays packed as {edgeX, edgeY}
	 */
	private static int[][] syntheticLine(float tiltDegrees)
	{
		int[] edgeX = new int[WIDTH];
		int[] edgeY = new int[WIDTH];
		double slope = Math.tan(Math.toRadians(tiltDegrees));
		for (int x = 0; x < WIDTH; x++)
		{
			edgeX[x] = x;
			edgeY[x] = Math.round((float) (180 + slope * x));
		}
		return new int[][] { edgeX, edgeY };
	}
}
