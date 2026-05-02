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
import java.util.List;

/**
 * Tests the pure-Java precision refinements inside HorizonDetector. The bitmap edge pipeline itself depends on
 * android.graphics, but once edge coordinates have been gathered the Hough / line-fit math is ordinary geometry and can
 * be pinned down on the host JVM.
 */
public class HorizonDetectorTest
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
		// > 25° is treated as bad sensor data — return NaN so the auto-rotate UI doesn't apply a 30°-correction
		// that's clearly wrong. (A 30° device tilt would normally trigger orientation, not roll-correction.)
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:Camera='c' Camera:Roll=\"30.0\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertTrue(Float.isNaN(result));
	}

	@Test
	public void detectFromMetadataAcceptsBoundaryAtTwentyFiveDegrees() throws IOException
	{
		// Predicate is `Math.abs(deg) > 25f` (strict greater-than) — 25.0 exactly is accepted.
		JpegSegment xmp = buildXmpSegment("<rdf:Description xmlns:Camera='c' Camera:Roll=\"25.0\"/>");
		float result = HorizonDetector.detectFromMetadata(Collections.singletonList(xmp));
		assertEquals(-25.0f, result, TOL);
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
}
