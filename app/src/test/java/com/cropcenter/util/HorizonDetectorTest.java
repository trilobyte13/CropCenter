package com.cropcenter.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cropcenter.metadata.JpegFixtures;
import com.cropcenter.metadata.JpegSegment;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
	public void detectFromMetadataSkipsXmpSegmentWithEmptyBody() throws IOException
	{
		// An XMP APP1 segment carrying ONLY the 29-byte XMP_HEADER and no XML body. isXmp() returns true
		// (data.length == 4 + 29 = 33 satisfies the >= guard), but the primary-loop's `segData.length <=
		// xmlStart` continue fires so detectFromMetadata moves on without trying to parse XML. Pin that the
		// empty-body path doesn't crash and ultimately returns NaN. Without this guard a regression that
		// swapped `<=` for `<` would feed an empty string into findXmpFloat (harmless today, but the guard is
		// the documented contract).
		byte[] xmpIdBytes = XMP_ID.getBytes(StandardCharsets.UTF_8);
		JpegSegment emptyBodyXmp = new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, xmpIdBytes));
		assertTrue(Float.isNaN(HorizonDetector.detectFromMetadata(Collections.singletonList(emptyBodyXmp))));
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
		// orientation, not roll-correction.) The 30° threshold is shared with the painted-region path so the
		// same image gets the same auto-rotate verdict regardless of whether XMP roll happens to be present.
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
	public void detectFromMetadataAcceptsAngleInsidePaintedRegionWindow() throws IOException
	{
		// Both detection paths share one acceptance window: 28° sits inside the painted-region path's range, so
		// the metadata path must accept it too. Pins that the two paths can't disagree about the same image.
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
		// A > 64 KB XMP packet split across multiple Adobe Extended XMP chunks (different namespace prefix from
		// standard XMP, plus 32-byte GUID + 4-byte total length + 4-byte offset header) must be reassembled by
		// GUID + offset before Roll/Tilt scanning. Reassembly is load-bearing: a per-segment substring scan
		// misses Roll attributes split across chunk boundaries, and the per-segment fallback only finds a clean
		// Roll in a later chunk when that chunk happens to contain the literal "Roll"/"Tilt" bytes. Here we
		// deliberately put Roll across chunks (start in chunk0, end in chunk1) — only correct reassembly
		// recovers the value.
		String guid = "0123456789ABCDEF0123456789ABCDEF"; // 32 ASCII hex
		String chunk0Body = "<rdf:Description xmlns:Camera='c' Camera:Ro";
		String chunk1Body = "ll=\"4.20\"/>";
		JpegSegment seg0 = buildExtendedXmpChunk(guid, 0, chunk0Body);
		JpegSegment seg1 = buildExtendedXmpChunk(guid, chunk0Body.length(), chunk1Body);

		float result = HorizonDetector.detectFromMetadata(Arrays.asList(seg0, seg1));
		assertEquals(-4.20f, result, TOL);
	}

	@Test
	public void detectFromMetadataDoesNotSynthesizeRollAcrossGuidGroups() throws IOException
	{
		// Cross-group-synthesis guard, the rejection twin of the straddle test above: group A's document ends
		// with "…Camera:Ro" and group B's begins with "ll=\"9.99\"/>" — two unrelated XMP documents whose
		// boundary bytes would spell a full Roll attribute ONLY in a buffer concatenated across the GUID
		// groups. Per-GUID reassembly scans each group independently, so no Roll may be detected — a value
		// here would auto-rotate the photo by an angle no document actually declares.
		String guidA = "AAAA1111000000000000000000000000";
		String guidB = "BBBB1111000000000000000000000000";
		JpegSegment groupATail = buildExtendedXmpChunk(guidA, 0, "<rdf:Description xmlns:Camera='c' Camera:Ro");
		JpegSegment groupBHead = buildExtendedXmpChunk(guidB, 0, "ll=\"9.99\"/>");

		float result = HorizonDetector.detectFromMetadata(Arrays.asList(groupATail, groupBHead));
		assertTrue("Roll synthesized across GUID groups must NOT be detected: " + result, Float.isNaN(result));
	}

	@Test
	public void detectFromMetadataFindsTiltInReassembledExtendedXmp() throws IOException
	{
		// Pin the Tilt branch within the Extended XMP reassembly pass. The existing
		// detectFromMetadataFindsTiltInExtendedXmp test exercises the pass-3 non-canonical APP1 fallback (its
		// segment has no Adobe extension namespace prefix), NOT the reassembly path. A regression that dropped
		// the Tilt scan from the reassembled-XMP block would still pass that test plus the Roll-only reassembly
		// tests. Force the reassembly path by splitting a Tilt attribute across two extension chunks so the
		// per-segment scan in pass-3 can't recover it.
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
		// Pin the three-pass priority chain. Standard XMP must return its Roll value WITHOUT invoking Extended
		// XMP reassembly, even when both segments are present and disagree. A refactor moving the reassemble
		// call before the primary loop would silently change the returned value.
		String guid = "1111222233334444AAAABBBBCCCCDDDD";
		String chunk0 = "<rdf:Description xmlns:c='c' c:Ro";
		String chunk1 = "ll=\"9.99\"/>";
		JpegSegment standard = buildXmpSegment("<rdf:Description xmlns:c='c' c:Roll=\"1.50\"/>");
		JpegSegment ext0 = buildExtendedXmpChunk(guid, 0, chunk0);
		JpegSegment ext1 = buildExtendedXmpChunk(guid, chunk0.length(), chunk1);

		float result = HorizonDetector.detectFromMetadata(Arrays.asList(standard, ext0, ext1));
		assertEquals("standard XMP must win without invoking Extended XMP reassembly", -1.50f, result, TOL);
	}

	@Test
	public void detectFromMetadataReassemblesAdobeExtendedXmpAcrossDistinctGuidGroups() throws IOException
	{
		// Spec-legal multiple GUID groups in one file. The first group's chunks carry no Roll/Tilt; the SECOND
		// alphabetic group carries a Roll attribute split across its two chunks. A speed-tweak that breaks
		// after the first GUID group, or sorts only by offset and loses the GUID primary key, would
		// mis-reassemble and miss the Roll value.
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
		// Out-of-file-order chunks must still reassemble correctly: file order chunk1, chunk0 — the reassembler
		// sorts by GUID then offset so the resulting buffer is chunk0 + chunk1 regardless of segment-list
		// order. Without offset-aware reassembly, the concatenation would read "ll=\"3.30\"/>" +
		// "<rdf:Description ... Camera:Ro" and miss the "Roll" attribute entirely.
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
		// Tilt-only segment in the extended-XMP fallback loop. The fallback must look up Tilt as well as Roll —
		// a Roll-only lookup lets a Tilt-only extended-XMP segment slip through and returns NaN. The
		// extended-XMP path requires marker == 0xE1, length >= 50, and that the body contain "Roll" / "roll" /
		// "Tilt" as a pre-filter. We give it a non-canonical APP1 (no XMP_ID prefix) carrying a Tilt attribute.
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
		// "CameraRoll" is NOT the same attribute as "Camera:Roll" — but a sloppy regex like `\\w*:?Roll` would
		// greedy-match the suffix and return CameraRoll's value as the horizon angle. The fix-comment in
		// HorizonDetector.findXmpFloat (line 471) explicitly calls out this regression. Pin it: a CameraRoll
		// attribute with no Roll attribute returns NaN.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:x='c' x:CameraRoll=\"9.0\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertTrue("CameraRoll must NOT match the Roll suffix: " + result, Float.isNaN(result));
	}

	@Test
	public void detectFromMetadataRejectsGyroRollGreedyMatch() throws IOException
	{
		// Same regression class as CameraRoll — a "GyroRoll" attribute (any custom namespace ending in "Roll")
		// must not match. Mirror test for completeness.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:y='c' y:GyroRoll=\"7.5\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertTrue("GyroRoll must NOT match the Roll suffix: " + result, Float.isNaN(result));
	}

	@Test
	public void detectFromMetadataSkipsUnparseableValueAndContinuesSearch() throws IOException
	{
		// Two Roll attributes in the SAME XMP — first is unparseable ("invalid"), second is "2.50". The
		// findXmpFloat loop catches NumberFormatException and continues with matcher.find(), so it should land
		// on 2.50. Without the loop-on-parse-fail behaviour the regex would return NaN on the first match and
		// never see the second.
		String body = "<rdf:Description xmlns:a='a' xmlns:b='b' " + "a:Roll=\"invalid\" b:Roll=\"2.50\"/>";
		JpegSegment xmp = buildXmpSegment(body);
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertEquals(-2.50f, result, TOL);
	}

	@Test
	public void detectFromMetadataIgnoresMalformedQuotedValue() throws IOException
	{
		// Roll attribute with no closing quote — the regex `Roll\\s*=\\s*"([^"]+)"` won't match at all (the
		// value capture requires a closing quote), so this falls through to NaN rather than parsing up to
		// end-of-segment.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:c='c' c:Roll=\"1.5/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertTrue("malformed unclosed quote must not parse: " + result, Float.isNaN(result));
	}

	@Test
	public void detectFromMetadataParsesRollWithLeadingAndTrailingWhitespace() throws IOException
	{
		// XMP serializers in the wild sometimes pad attribute values with whitespace (" 1.50 ") for formatting.
		// The .trim() call on the captured regex group at HorizonDetector.findXmpFloat is the only thing that
		// lets these parse — without it, Float.parseFloat throws NumberFormatException (which the catch
		// silently swallows) and the user's "auto-rotate stops working" for cameras that pad their XMP.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:c='c' c:Roll=\"  1.50  \"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertEquals(-1.50f, result, TOL);
	}

	// ── refineLineFitAngle (existing) ──

	@Test
	public void lineFitClampsRefinedAngleToStructuralTiltCap()
	{
		// Final Math.clamp(pass2, 80f, 100f) — the last enforcement of the documented ±10° structural tilt
		// cap. An LSQ angle just OUTSIDE [80, 100] but within MAX_LINE_FIT_DELTA_DEGREES (0.5°) of the
		// Hough seed passes the disagreement gate and must clamp to the range edge; returning bare pass2
		// would ship an out-of-cap tilt into the auto-rotate path. Line at -10.1° tilt → LSQ ≈ 79.9°; seed
		// 80.3° puts the delta at ~0.4°, inside the gate. Baseline 400 (not syntheticLine's 180) keeps y
		// positive across the full width at this steep tilt.
		int[] edgeX = new int[WIDTH];
		int[] edgeY = new int[WIDTH];
		double slope = Math.tan(Math.toRadians(-10.1f));
		for (int x = 0; x < WIDTH; x++)
		{
			edgeX[x] = x;
			edgeY[x] = Math.round((float) (400 + slope * x));
		}
		float refined = HorizonDetector.refineLineFitAngle(edgeX, edgeY, WIDTH, WIDTH, HEIGHT, 80.3f);
		assertEquals("out-of-cap LSQ angle must clamp to the structural edge", 80.0f, refined, 0f);
	}

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
		// MAX_LINE_FIT_DELTA_DEGREES = 0.5 — a seed past 0.5° from the LSQ slope is rejected. Synthetic line is
		// at tilt 1.23 (true LSQ angle ≈ 91.23°); a Hough seed at 92.10° puts the delta at ~0.87°, comfortably
		// past the rejection threshold.
		int[][] line = syntheticLine(1.23f);

		float refined = HorizonDetector.refineLineFitAngle(line[0], line[1],
			line[0].length, WIDTH, HEIGHT, 92.10f);

		assertTrue(Float.isNaN(refined));
	}

	@Test
	public void lineFitReturnsNaNForDegenerateVerticalLine()
	{
		// All edge points share the same X coordinate — sumXX - sumX^2/n collapses below the 1e-6 denom guard
		// and the slope-from-X regression is degenerate. With a Hough seed near 0° the rho computation
		// collapses to rho = x*cos(0°) + y*sin(0°) ≈ x, so all 200 same-x edges land in the same histogram bin
		// and pass the bestCount + inliers thresholds; the next pass then trips the explicit `denom <= 1e-6`
		// guard rather than dividing by zero. Pin the guard so a refactor that removes the check surfaces here
		// instead of producing Inf/NaN at the call site.
		int[] edgeX = new int[200];
		int[] edgeY = new int[200];
		for (int i = 0; i < 200; i++)
		{
			edgeX[i] = WIDTH / 2;
			edgeY[i] = 100 + i;
		}
		float refined = HorizonDetector.refineLineFitAngle(edgeX, edgeY, 200, WIDTH, HEIGHT, 0f);
		assertTrue("degenerate vertical line must return NaN: " + refined, Float.isNaN(refined));
	}

	@Test
	public void lineFitReturnsNaNWhenBestBinHasTooFewInliers()
	{
		// minInliers = max(30, width * 3 / 100). For WIDTH=1200 that's max(30, 36) = 36. A 20-pixel synthetic
		// line packs all 20 votes into one bin but the count is still below the threshold, so the early
		// `bestCount < minInliers` guard returns NaN before the slope fit runs. Pin the threshold so a
		// regression that drops it would start accepting sparse-edge inputs that aren't trustworthy.
		int[] edgeX = new int[20];
		int[] edgeY = new int[20];
		double slope = Math.tan(Math.toRadians(1.23f));
		for (int i = 0; i < 20; i++)
		{
			edgeX[i] = i;
			edgeY[i] = Math.round((float) (180 + slope * i));
		}
		float refined = HorizonDetector.refineLineFitAngle(edgeX, edgeY, 20, WIDTH, HEIGHT, 91.23f);
		assertTrue("too few inliers must return NaN: " + refined, Float.isNaN(refined));
	}

	// ── computeThreshold ──

	@Test
	public void computeThresholdReturnsMaxValueForAllZeroInput()
	{
		// No non-zero edges → no signal at all. Returns Float.MAX_VALUE so the threshold comparison `edges[i]
		// >= threshold` rejects every pixel, leaving the masked-edges count at 0 (downstream gatherMaskedEdges
		// then returns null). Pin so a regression that returned 0 would let every zero-edge pixel through and
		// crash the Hough pass on noise.
		float[] edges = new float[100];
		assertEquals(Float.MAX_VALUE, HorizonDetector.computeThreshold(edges, 0.15f), 0f);
	}

	@Test
	public void computeThresholdSelectsTopFractionOfNonZeroEdges()
	{
		// Uniform-strength field with one outlier — topFraction 0.05 targets the 95th percentile, excluding the
		// lower 95%. Pin that the histogram-based percentile lands in the upper portion rather than returning
		// the absolute max (which would reject the outlier too).
		float[] edges = new float[100];
		for (int i = 0; i < 99; i++)
		{
			edges[i] = 10f;
		}
		edges[99] = 100f;
		float threshold = HorizonDetector.computeThreshold(edges, 0.05f);
		// The 99 values at 10 quantize into bin 25 of 255 (threshold ≈ 9.8 after bin-edge rounding); the single
		// 100 sits alone in the top bin. Bound the result from BOTH sides: it must clear the low-value plateau
		// AND stay far below the outlier — a regression that collapsed the percentile to max() would return 100
		// and reject the plateau entirely.
		assertTrue("threshold should clear the low-value plateau: " + threshold, threshold > 9f);
		assertTrue("threshold must stay below the outlier (a max() collapse returns 100): " + threshold,
			threshold < 20f);
	}

	// ── gatherMaskedEdges ──

	@Test
	public void gatherMaskedEdgesReturnsEmptyWhenFewerThanThirtyQualify()
	{
		// A 40x40 source with a 10x10 mask where only the top-left 4×4 mask cells are flagged. Each mask cell
		// covers a 4×4 block of source pixels — so the maximum possible edge count is 4*4*4*4 = 256 pixels IF
		// every pixel in those blocks crosses threshold. We give exactly 20 strong pixels and expect the < 30
		// guard to return empty.
		int width = 40;
		int height = 40;
		int maskWidth = 10;
		int maskHeight = 10;
		float[] edges = new float[width * height];
		boolean[] mask = new boolean[maskWidth * maskHeight];
		// Mask the upper-left 4×4 mask cells (covers source rows 0..15, cols 0..15).
		for (int my = 0; my < 4; my++)
		{
			for (int mx = 0; mx < 4; mx++)
			{
				mask[my * maskWidth + mx] = true;
			}
		}
		// Plant exactly 20 strong edges, all inside the masked area.
		for (int i = 0; i < 20; i++)
		{
			edges[i] = 100f;
		}
		assertTrue("fewer than 30 edges → empty",
			HorizonDetector.gatherMaskedEdges(edges, mask, width, height, maskWidth, maskHeight).isEmpty());
	}

	@Test
	public void gatherMaskedEdgesCollectsCoordinatesAboveThreshold()
	{
		// A 40x40 source with the entire mask flagged and 50 strong pixels. The function returns {edgeX[],
		// edgeY[]} packed as a 2-element array. Pin: every edge above threshold inside the mask is collected,
		// and the X/Y coordinates round-trip.
		int width = 40;
		int height = 40;
		int maskWidth = 10;
		int maskHeight = 10;
		float[] edges = new float[width * height];
		boolean[] mask = new boolean[maskWidth * maskHeight];
		for (int i = 0; i < mask.length; i++)
		{
			mask[i] = true;
		}
		// Plant 50 strong pixels along row 5 (cols 0..49 → wraps once; cols 0..39 in row 5, then 10 in row 6).
		for (int i = 0; i < 50; i++)
		{
			int x = i % width;
			int y = 5 + (i / width);
			edges[y * width + x] = 100f;
		}
		int[][] result = HorizonDetector.gatherMaskedEdges(edges, mask, width, height, maskWidth, maskHeight)
			.orElseThrow(() -> new AssertionError("present when count >= 30"));
		assertEquals("returns {edgeX, edgeY} as 2-element array", 2, result.length);
		assertEquals(50, result[0].length);
		assertEquals(50, result[1].length);
	}

	// ── gaussianBlur5x5 ──

	@Test
	public void gaussianBlur5x5LeavesUniformFieldAtSameValue()
	{
		// A uniform-100 5x5 field with the kernel normalized to sum=1 must round-trip the center pixel to 100
		// (or within float rounding tolerance). The border-2 ring is zero because the blur skips the 2-pixel
		// border. Pin both: center unchanged, border untouched.
		float[] src = new float[25];
		for (int i = 0; i < 25; i++)
		{
			src[i] = 100f;
		}
		float[] dst = HorizonDetector.gaussianBlur5x5(src, 5, 5);
		assertEquals("center pixel preserves uniform value", 100f, dst[12], 0.01f);
		assertEquals("top-left border-2 stays zero", 0f, dst[0], 0f);
		assertEquals("bottom-right border-2 stays zero", 0f, dst[24], 0f);
	}

	@Test
	public void gaussianBlur5x5SpreadsImpulseToNeighborhood()
	{
		// Single bright pixel at the center of a 7x7 field. Post-blur the center should drop (some weight
		// distributes to neighbors) and the immediate 4-neighbours should pick up positive weight. Pin the
		// spread direction so a regression to a center-only kernel surfaces here.
		float[] src = new float[49];
		src[24] = 255f; // center of 7x7 grid (3, 3)
		float[] dst = HorizonDetector.gaussianBlur5x5(src, 7, 7);
		// Kernel center coefficient is 41/273 ≈ 0.150. So dst[center] ≈ 0.150 * 255 ≈ 38.3.
		assertTrue("center is reduced after spread: " + dst[24], dst[24] < 255f);
		assertTrue("center retains some weight: " + dst[24], dst[24] > 20f);
		// Immediate up/down/left/right neighbours pick up the 26/273 weight ≈ 0.0953 → ~24.3.
		assertTrue("right neighbour gains weight: " + dst[25], dst[25] > 10f);
		assertTrue("left neighbour gains weight: " + dst[23], dst[23] > 10f);
	}

	// ── sobelGradient ──

	@Test
	public void sobelGradientDetectsHorizontalIntensityStep()
	{
		// A 7x7 field with the left half dark (0) and the right half bright (255). The Sobel operator should
		// produce a strong horizontal gradient (high gradX, near-zero gradY) along the column boundary, with
		// direction ≈ 0 (pointing along +x). Pin the direction convention.
		int width = 7;
		int height = 7;
		float[] src = new float[width * height];
		for (int y = 0; y < height; y++)
		{
			for (int x = 0; x < width; x++)
			{
				src[y * width + x] = x >= width / 2 ? 255f : 0f;
			}
		}
		float[] mag = new float[width * height];
		float[] dir = new float[width * height];
		HorizonDetector.sobelGradient(src, width, height, mag, dir);
		// At the boundary column (x = width/2 = 3, interior y = 3), the gradient is purely horizontal.
		int idx = 3 * width + 3;
		assertTrue("magnitude on the step boundary is non-trivial: " + mag[idx], mag[idx] > 100f);
		assertEquals("direction along +x is near zero radians", 0f, dir[idx], 0.1f);
	}

	@Test
	public void sobelGradientDetectsVerticalIntensityStepAsPiOverTwo()
	{
		// Top half dark, bottom half bright — gradient direction should be ≈ +π/2 (Math.atan2(positive,
		// 0) = π/2). Pin the orthogonal direction convention as a symmetry check on the horizontal-step
		// test above.
		int width = 7;
		int height = 7;
		float[] src = new float[width * height];
		for (int y = 0; y < height; y++)
		{
			for (int x = 0; x < width; x++)
			{
				src[y * width + x] = y >= height / 2 ? 255f : 0f;
			}
		}
		float[] mag = new float[width * height];
		float[] dir = new float[width * height];
		HorizonDetector.sobelGradient(src, width, height, mag, dir);
		int idx = 3 * width + 3;
		assertTrue("magnitude on the step boundary is non-trivial: " + mag[idx], mag[idx] > 100f);
		assertEquals("direction is +π/2 radians for a vertical step", (float) (Math.PI / 2), dir[idx], 0.1f);
	}

	// ── nonMaxSuppression ──

	@Test
	public void nonMaxSuppressionKeepsCenterPeakAndRidgeEdgesAtVerticalGradient()
	{
		// 5x5 magnitude field with a 3-wide horizontal ridge at row 2 — center pixel has the highest magnitude,
		// flanked by smaller values. With direction = π/2 (vertical gradient), the suppression compares against
		// the up/down neighbours which are zero in this fixture — so the ridge EDGES also survive (each is a
		// local max along its own vertical axis). Pin both outcomes: a regression that flipped vertical-axis
		// sampling to horizontal-axis sampling would suppress the edges (because the center is bigger
		// horizontally) and break the test.
		int width = 5;
		int height = 5;
		float[] mag = new float[width * height];
		float[] dir = new float[width * height];
		// Plant a horizontal ridge with the peak at the center.
		mag[2 * width + 1] = 50f;
		mag[2 * width + 2] = 100f;
		mag[2 * width + 3] = 50f;
		// Direction = π/2 (gradient pointing down) — comparison goes against (y-1, x) and (y+1, x).
		for (int i = 0; i < dir.length; i++)
		{
			dir[i] = (float) (Math.PI / 2);
		}
		float[] out = HorizonDetector.nonMaxSuppression(mag, dir, width, height);
		assertEquals("ridge peak survives (vertical neighbours are zero)", 100f, out[2 * width + 2], 0f);
		assertEquals("ridge edge at x=1 survives — local max along vertical axis", 50f, out[2 * width + 1], 0f);
		assertEquals("ridge edge at x=3 survives — local max along vertical axis", 50f, out[2 * width + 3], 0f);
	}

	@Test
	public void nonMaxSuppressionHorizontalDirectionComparesAgainstLeftAndRightNeighbors()
	{
		// Direction near 0 (or near π) — horizontal gradient. Suppression compares against (y, x-1) and (y,
		// x+1). Plant a vertical ridge: at (2, 2) center magnitude 100, with (2, 1) = 40 and (2, 3) = 60 as
		// horizontal neighbours. Center MUST survive (100 >= max(40, 60)). The non-max neighbours (50 each) at
		// (1, 2) and (3, 2) survive too because they're local-max along their own row (left/right neighbours
		// are zero in that row).
		int width = 5;
		int height = 5;
		float[] mag = new float[width * height];
		float[] dir = new float[width * height];
		mag[2 * width + 1] = 40f;
		mag[2 * width + 2] = 100f;
		mag[2 * width + 3] = 60f;
		// Direction = 0 → angle < π/8 branch, compares (y, x-1) vs (y, x+1).
		for (int i = 0; i < dir.length; i++)
		{
			dir[i] = 0f;
		}
		float[] out = HorizonDetector.nonMaxSuppression(mag, dir, width, height);
		assertEquals("center peak survives", 100f, out[2 * width + 2], 0f);
		assertEquals("(2, 1) magnitude 40 fails to beat right-neighbour 100 → suppressed",
			0f, out[2 * width + 1], 0f);
		assertEquals("(2, 3) magnitude 60 fails to beat left-neighbour 100 → suppressed",
			0f, out[2 * width + 3], 0f);
	}

	@Test
	public void nonMaxSuppressionDiagonalUpDirectionComparesAgainstTopRightAndBottomLeft()
	{
		// Direction in [π/8, 3π/8) — diagonal-up gradient. Suppression compares (y-1, x+1) vs (y+1, x-1). Plant
		// a peak at (2, 2) with magnitude 100, flanked by 30 at the comparison axis (1, 3) and (3, 1). Pin:
		// center MUST survive (100 >= 30). A regression that swapped to (y-1, x-1) / (y+1, x+1) would compare
		// against 0 zeros — peak still survives — so we also plant 50 at (1, 1) and (3, 3) which only matters
		// under the diagonal-DOWN branch. If diagonal-up's branch is correct, those don't influence the center;
		// if a regression mis-routed to diagonal-down's branch, the center would compare against 50 (still pass
		// since 100 > 50), but the (1, 1)/(3, 3) cells would behave differently — they're isolated points whose
		// own diagonal-up comparisons are 0 and 0, so they survive. Use a sharper distinguisher: plant a
		// STRONGER value at the diagonal-up comparison axis so a routing bug surfaces as a SURVIVING center
		// instead of a SUPPRESSED one.
		int width = 5;
		int height = 5;
		float[] mag = new float[width * height];
		float[] dir = new float[width * height];
		mag[2 * width + 2] = 100f;            // center
		mag[1 * width + 3] = 150f;            // (y-1, x+1) — diagonal-up neighbour, stronger than center
		mag[3 * width + 1] = 150f;            // (y+1, x-1) — diagonal-up neighbour, stronger than center
		// Direction = π/4 (45°) → falls in [π/8, 3π/8) → diagonal-up branch.
		for (int i = 0; i < dir.length; i++)
		{
			dir[i] = (float) (Math.PI / 4);
		}
		float[] out = HorizonDetector.nonMaxSuppression(mag, dir, width, height);
		assertEquals("center 100 < diagonal-up neighbours 150 → suppressed", 0f, out[2 * width + 2], 0f);
	}

	@Test
	public void nonMaxSuppressionDiagonalDownDirectionComparesAgainstTopLeftAndBottomRight()
	{
		// Direction in [5π/8, 7π/8) — diagonal-down gradient. Suppression compares (y-1, x-1) vs (y+1, x+1).
		// Same distinguishing technique as the diagonal-up test: plant stronger neighbours on the diagonal-down
		// axis so a routing regression surfaces as a center that survives when it shouldn't.
		int width = 5;
		int height = 5;
		float[] mag = new float[width * height];
		float[] dir = new float[width * height];
		mag[2 * width + 2] = 100f;
		mag[1 * width + 1] = 150f;            // (y-1, x-1)
		mag[3 * width + 3] = 150f;            // (y+1, x+1)
		// Direction = 3π/4 (135°) falls in [5π/8, 7π/8) → diagonal-down branch.
		for (int i = 0; i < dir.length; i++)
		{
			dir[i] = (float) (3 * Math.PI / 4);
		}
		float[] out = HorizonDetector.nonMaxSuppression(mag, dir, width, height);
		assertEquals("center 100 < diagonal-down neighbours 150 → suppressed", 0f, out[2 * width + 2], 0f);
	}

	@Test
	public void nonMaxSuppressionWrapsNegativeDirectionToPositive()
	{
		// The production code adds π to negative directions so the bucket-classification only sees [0, π). A
		// direction of -π/2 should behave identically to +π/2. Plant the same fixture as the vertical-direction
		// "peak survives" test but with direction = -π/2; the result must match.
		int width = 5;
		int height = 5;
		float[] mag = new float[width * height];
		float[] dir = new float[width * height];
		mag[2 * width + 2] = 100f;
		mag[1 * width + 2] = 40f;             // (y-1, x) — vertical-axis neighbour
		mag[3 * width + 2] = 40f;             // (y+1, x) — vertical-axis neighbour
		for (int i = 0; i < dir.length; i++)
		{
			dir[i] = (float) (-Math.PI / 2);  // negative; wraps to +π/2 inside the function
		}
		float[] out = HorizonDetector.nonMaxSuppression(mag, dir, width, height);
		assertEquals("negative direction wraps; center peak survives", 100f, out[2 * width + 2], 0f);
		assertEquals("vertical-axis neighbour 40 < 100 → suppressed", 0f, out[1 * width + 2], 0f);
	}

	@Test
	public void nonMaxSuppressionZeroMagnitudeCenterDoesNotSuppressNonZeroNeighbour()
	{
		// Pin the `if (center == 0) continue` short-circuit: when the center pixel's magnitude is 0, the loop
		// skips it entirely and the surrounding non-zero magnitudes are evaluated against each other as usual.
		// Plant a non-zero peak at (2, 2) and zero magnitude at (2, 3); verify the peak survives. A regression
		// that removed the short-circuit would still produce the same outcome here (0 < neighbour fails the
		// survival check and writes 0 — but 0 is already the default), so the real contract this test pins is
		// that the function DOESN'T crash on a zero-magnitude pixel when other interior pixels exist. The
		// peak's survival is the observable outcome.
		int width = 5;
		int height = 5;
		float[] mag = new float[width * height];
		float[] dir = new float[width * height];
		// Plant a vertical-direction peak (π/2) at (2, 2) with non-zero neighbours along the gradient axis that
		// are weaker. Zero magnitude at (2, 3) means that pixel hits the short-circuit.
		mag[2 * width + 2] = 100f;
		mag[1 * width + 2] = 40f;
		mag[3 * width + 2] = 40f;
		mag[2 * width + 3] = 0f;
		for (int i = 0; i < dir.length; i++)
		{
			dir[i] = (float) (Math.PI / 2);
		}
		float[] out = HorizonDetector.nonMaxSuppression(mag, dir, width, height);
		assertEquals("center peak survives, undisturbed by zero-mag neighbour", 100f, out[2 * width + 2], 0f);
		assertEquals("zero-mag pixel stays zero in output (default; short-circuit wrote nothing)",
			0f, out[2 * width + 3], 0f);
	}

	@Test
	public void nonMaxSuppressionSkipsBorderPixelsWithoutAioobeOnNeighbourReads()
	{
		// Pin the loop bound `(1, height-1) / (1, width-1)`: a regression that widened to `(0, height) / (0,
		// width)` would AIOOBE on the neighbour reads at border pixels (e.g. at y=0 the diagonal branches
		// sample `(y-1, x±1)` = row -1). The test plants non-zero magnitudes at ALL FOUR borders so a widened
		// loop would either crash on the negative-row read or write non-zero values to out[] at border indices.
		// The current contract: function returns without exception, and border indices stay at the default
		// zero.
		int width = 5;
		int height = 5;
		float[] mag = new float[width * height];
		float[] dir = new float[width * height];
		// Non-zero magnitudes along every border pixel.
		for (int x = 0; x < width; x++)
		{
			mag[x] = 100f;                                 // top row
			mag[(height - 1) * width + x] = 100f;          // bottom row
		}
		for (int y = 0; y < height; y++)
		{
			mag[y * width] = 100f;                         // left column
			mag[y * width + width - 1] = 100f;             // right column
		}
		// Use direction = π/4 (diagonal-up branch) so a widened loop would sample (y-1, x+1) / (y+1, x-1) —
		// those reads would AIOOBE at border indices.
		for (int i = 0; i < dir.length; i++)
		{
			dir[i] = (float) (Math.PI / 4);
		}
		// The contract: no exception thrown, output array sized correctly, borders stay zero.
		float[] out = HorizonDetector.nonMaxSuppression(mag, dir, width, height);
		assertEquals(width * height, out.length);
		for (int x = 0; x < width; x++)
		{
			assertEquals("top-row x=" + x + " border stays zero", 0f, out[x], 0f);
			assertEquals("bottom-row x=" + x + " border stays zero", 0f, out[(height - 1) * width + x], 0f);
		}
		for (int y = 0; y < height; y++)
		{
			assertEquals("left-col y=" + y + " border stays zero", 0f, out[y * width], 0f);
			assertEquals("right-col y=" + y + " border stays zero", 0f, out[y * width + width - 1], 0f);
		}
	}

	// ── houghPass ──

	@Test
	public void houghPassFindsAngleNearNinetyForHorizontalLine()
	{
		// A perfectly horizontal line in screen coordinates has its Hough normal at 90° (since the normal
		// points up/down). Generate WIDTH samples along Y = 100 and verify the coarse 80–100° scan returns
		// ~90°. Pin the convention so a regression that flipped sin/cos would surface as the angle landing near
		// 0° instead.
		int[] edgeX = new int[WIDTH];
		int[] edgeY = new int[WIDTH];
		for (int x = 0; x < WIDTH; x++)
		{
			edgeX[x] = x;
			edgeY[x] = 100;
		}
		float angle = HorizonDetector.houghPass(edgeX, edgeY, WIDTH, WIDTH, HEIGHT, 80f, 100f, 0.1f);
		assertEquals("horizontal line → Hough normal at 90°", 90f, angle, 0.5f);
	}

	@Test
	public void houghPassReturnsNaNWhenBestBinUnderThreeWidthPercent()
	{
		// houghPass requires bestPeak >= max(15, width * 3 / 100) to commit. For WIDTH=1200 that's max(15, 36)
		// = 36. A 30-pixel line is below the gate → returns NaN. Pin the spec'd "line must span ≥ 3% of image
		// width" rule.
		int[] edgeX = new int[30];
		int[] edgeY = new int[30];
		for (int i = 0; i < 30; i++)
		{
			edgeX[i] = i;
			edgeY[i] = 100;
		}
		float angle = HorizonDetector.houghPass(edgeX, edgeY, 30, WIDTH, HEIGHT, 80f, 100f, 0.1f);
		assertTrue("short line must return NaN: " + angle, Float.isNaN(angle));
	}

	// ── rasterizePaintMask ──

	@Test
	public void rasterizePaintMaskMarksCirclesAroundPoints()
	{
		// One paint point at the mask center, brush radius 8 source pixels. The function operates at 1/4 source
		// resolution so the mask circle radius is 8/4 = 2. The center pixel and its 4-neighbours should be
		// flagged; pixels at distance > 2 (in mask coords) should not.
		List<float[]> points = Collections.singletonList(new float[] { 40f, 40f }); // source coords
		boolean[] mask = HorizonDetector.rasterizePaintMask(points, 20, 20, 8f);
		int centerX = 40 / 4; // = 10
		int centerY = 40 / 4; // = 10
		assertTrue("center mask cell flagged", mask[centerY * 20 + centerX]);
		assertTrue("up neighbour flagged (dist=1)", mask[(centerY - 1) * 20 + centerX]);
		assertTrue("left neighbour flagged", mask[centerY * 20 + (centerX - 1)]);
		// (centerX + 3, centerY + 3) is dist = sqrt(18) ≈ 4.24 > 2 → not flagged.
		assertFalse("far cell beyond radius is not flagged", mask[(centerY + 3) * 20 + (centerX + 3)]);
	}

	@Test
	public void rasterizePaintMaskClampsToImageBounds()
	{
		// Paint point near the image edge — the radius would write outside [0, maskWidth) × [0, maskHeight) but
		// the bounds checks must clip. Test that no IOOBE is thrown and the masked pixels are contained within
		// the mask.
		List<float[]> points = Collections.singletonList(new float[] { 0f, 0f });
		boolean[] mask = HorizonDetector.rasterizePaintMask(points, 10, 10, 8f);
		// Point at (0,0) source coords = (0,0) in mask coords. With radius 2 in mask coords, the kernel writes
		// dx,dy ∈ [-2, +2]. The negative offsets are clipped; (0,0) and (1,0), (0,1), (1,1) all get flagged
		// (dist ≤ 2).
		assertTrue("top-left corner flagged", mask[0]);
		assertTrue("right of corner flagged", mask[1]);
	}

	// ── runHoughAndConvertToRotation ──

	@Test
	public void runHoughAndConvertToRotationReturnsZeroForLevelHorizontalLine()
	{
		// End-to-end: a perfectly horizontal line should produce a 0° rotation correction. The wrapper runs the
		// coarse + fine Hough scan, optionally refines via line-fit, then returns -(tilt) where tilt =
		// fineAngle - 90°. For a level line the tilt is below the sub-epsilon threshold → 0.
		int[] edgeX = new int[WIDTH];
		int[] edgeY = new int[WIDTH];
		for (int x = 0; x < WIDTH; x++)
		{
			edgeX[x] = x;
			edgeY[x] = 100;
		}
		float rotation = HorizonDetector.runHoughAndConvertToRotation(edgeX, edgeY, WIDTH, WIDTH, HEIGHT);
		assertEquals("level horizontal line → 0° rotation", 0f, rotation, 0f);
	}

	@Test
	public void runHoughAndConvertToRotationProducesCorrectionForTiltedLine()
	{
		// 1.5° tilted line. The wrapper returns -(tilt) since the correction is the inverse rotation.
		int[][] line = syntheticLine(1.5f);
		float rotation = HorizonDetector.runHoughAndConvertToRotation(line[0], line[1],
			line[0].length, WIDTH, HEIGHT);
		// Result must be ≈ -1.5° (rounded to 0.01°), within ~0.05° to allow Hough + line-fit precision.
		assertEquals("1.5° tilt → -1.5° correction", -1.5f, rotation, 0.05f);
	}

	/**
	 * Build a synthetic Adobe Extended XMP APP1 chunk: namespace prefix + 32-byte GUID + 4-byte total length
	 * (placeholder zero — extractor doesn't validate it) + 4-byte big-endian offset + chunk body. Used by the
	 * reassembly tests to compose multi-chunk payloads that ExtendedXmpReassembler must merge by GUID + offset
	 * before HorizonDetector scans for Roll/Tilt.
	 *
	 * @param guid   32-character ASCII GUID grouping chunks of one logical XMP document
	 * @param offset chunk position within the reassembled document, written big-endian
	 * @param body   chunk content, encoded as UTF-8 after the offset field
	 * @return Extended XMP APP1 segment ready for the reassembler
	 * @throws IOException never from the in-memory composition; declared by the OutputStream write contract
	 */
	private static JpegSegment buildExtendedXmpChunk(String guid, int offset, String body)
		throws IOException
	{
		// Total-length field = 0 (a placeholder the extractor reads but doesn't validate).
		byte[] payload = JpegFixtures.extendedXmpChunk(guid, 0L, offset, body.getBytes(StandardCharsets.UTF_8));
		return new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, payload));
	}

	/**
	 * Build a synthetic XMP APP1 segment with the given XML body wrapped in the canonical
	 * "http://ns.adobe.com/xap/1.0/\0" identifier, suitable for HorizonDetector.detectFromMetadata's primary-loop
	 * entry.
	 *
	 * @param xml XMP XML body, encoded as UTF-8 after the namespace identifier
	 * @return standard XMP APP1 segment carrying the body
	 * @throws IOException never from the in-memory composition; declared by the OutputStream write contract
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
	 * Build a string of ASCII filler chars — used to stretch synthetic APP1 payloads past the 50-byte minimum
	 * that detectFromMetadata's extended-XMP fallback loop requires.
	 *
	 * @param length number of filler chars
	 * @return string of exactly `length` space characters
	 */
	private static String pad(int length)
	{
		return " ".repeat(length);
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
