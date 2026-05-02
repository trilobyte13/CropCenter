package com.cropcenter.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cropcenter.metadata.JpegFixtures;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Tests for the pure-Java pieces of BitmapUtils — anything that doesn't touch a Bitmap. isCardinalRotation is the
 * high-leverage one: it gates the lossless integer-pixel-remap fast path in drawCropped, so a regression that
 * mis-classifies a near-cardinal angle silently downgrades export quality without any other failure signal.
 */
public class BitmapUtilsTest
{
	private static final float EPS = BitmapUtils.ROTATION_EPSILON;

	@Test
	public void epsilonValueIsHalfOfFinestRulerStep()
	{
		// Ruler's finest tick is 0.01°; epsilon sits at 0.005° so every nonzero value the ruler can produce
		// survives the snap in CropState.setRotationDegrees. A regression that bumped epsilon back to 0.05f
		// would silently turn 0.01°-0.04° rotations into no-ops — the bug we just spent a session fixing.
		assertEquals(0.005f, EPS, 0f);
	}

	@Test
	public void isCardinalRotationAcceptsNegativeCardinal()
	{
		// Negative multiples of 90 normalize through the ((x % 360) + 360) % 360 dance to positive 270 / 180 /
		// 90 — same cardinal set, same result. Note that -360 normalizes to 0, which the predicate does NOT
		// treat as cardinal (see the "exact multiples" test) — that's pinned down separately.
		assertTrue(BitmapUtils.isCardinalRotation(-90f));
		assertTrue(BitmapUtils.isCardinalRotation(-180f));
		assertTrue(BitmapUtils.isCardinalRotation(-270f));
		assertFalse(BitmapUtils.isCardinalRotation(-360f));   // normalizes to 0
	}

	@Test
	public void isCardinalRotationAcceptsExactMultiplesOf90()
	{
		// 0 and 360 normalize to 0, which the predicate explicitly does NOT treat as cardinal (drawCropped's
		// cardinal branch is the rotated path; 0° goes through the unrotated fast path higher up). Verifying
		// both cases keeps the contract honest: cardinal == 90/180/270 (mod 360), not "any multiple of 90".
		assertFalse(BitmapUtils.isCardinalRotation(0f));
		assertTrue(BitmapUtils.isCardinalRotation(90f));
		assertTrue(BitmapUtils.isCardinalRotation(180f));
		assertTrue(BitmapUtils.isCardinalRotation(270f));
		assertFalse(BitmapUtils.isCardinalRotation(360f));
		assertTrue(BitmapUtils.isCardinalRotation(450f));   // = 90
	}

	@Test
	public void isCardinalRotationRejectsAtEpsilonBoundary()
	{
		// At exactly epsilon the predicate uses strict less-than, so 90 + epsilon is the FIRST non-cardinal
		// value above 90. A regression to <= would incorrectly classify 90.005° as cardinal and use
		// nearest-neighbor sampling on a fractionally-rotated image — visible as a hard 1-pixel jitter at the
		// edges.
		assertFalse(BitmapUtils.isCardinalRotation(90f + EPS));
		assertFalse(BitmapUtils.isCardinalRotation(90f - EPS));
		assertFalse(BitmapUtils.isCardinalRotation(180f + EPS));
		assertFalse(BitmapUtils.isCardinalRotation(270f - EPS));
	}

	@Test
	public void isCardinalRotationRejectsNonCardinalAngles()
	{
		assertFalse(BitmapUtils.isCardinalRotation(45f));
		assertFalse(BitmapUtils.isCardinalRotation(89f));
		assertFalse(BitmapUtils.isCardinalRotation(91f));
		assertFalse(BitmapUtils.isCardinalRotation(135f));
		assertFalse(BitmapUtils.isCardinalRotation(1.5f));
		assertFalse(BitmapUtils.isCardinalRotation(0.01f));
	}

	@Test
	public void isCardinalRotationToleratesSubEpsilonNearCardinal()
	{
		// Sub-epsilon offsets from 90/180/270 stay cardinal — that's the whole point of the epsilon. A user who
		// lands a rotation at 89.9999° gets the lossless path.
		assertTrue(BitmapUtils.isCardinalRotation(90f + EPS / 2f));
		assertTrue(BitmapUtils.isCardinalRotation(90f - EPS / 2f));
		assertTrue(BitmapUtils.isCardinalRotation(180f + EPS / 10f));
		assertTrue(BitmapUtils.isCardinalRotation(270f - EPS / 10f));
	}

	@Test
	public void readExifOrientationReturnsLittleEndianOrientation() throws IOException
	{
		// Baseline: a valid little-endian ("II") EXIF segment with Orientation=6 produces the expected u16
		// value. Pinning the baseline lets the mismatched-byte-order test below distinguish "1 because
		// rejected" from "1 because no orientation tag found".
		byte[] jpeg = buildJpegWithOrientation(true, 6);
		assertEquals(6, BitmapUtils.readExifOrientation(jpeg));
	}

	@Test
	public void readExifOrientationReturnsBigEndianOrientation() throws IOException
	{
		// Symmetric baseline for "MM" big-endian EXIF.
		byte[] jpeg = buildJpegWithOrientation(false, 6);
		assertEquals(6, BitmapUtils.readExifOrientation(jpeg));
	}

	@Test
	public void readExifOrientationReturnsUprightOnImByteOrder() throws IOException
	{
		// Byte-order field "IM" (mismatched halves) must be rejected as a malformed pair rather than treated as
		// little-endian. Function falls through to the "EXIF found but no orientation tag" return branch —
		// value 1 (upright).
		byte[] jpeg = buildJpegWithOrientation(true, 6);
		// Locate TIFF header — at SOI(2) + APP1 marker(2) + APP1 length(2) + "Exif\0\0"(6) = offset 12 in the
		// assembled JPEG. Corrupt the second byte of "II" to "M".
		jpeg[13] = 'M';
		assertEquals(1, BitmapUtils.readExifOrientation(jpeg));
	}

	@Test
	public void readExifOrientationReturnsUprightOnMiByteOrder() throws IOException
	{
		// Symmetric counterpart: "MI" must also be rejected.
		byte[] jpeg = buildJpegWithOrientation(false, 6);
		// "MM" sits at offset 12, 13. Change second byte to 'I'.
		jpeg[13] = 'I';
		assertEquals(1, BitmapUtils.readExifOrientation(jpeg));
	}

	/**
	 * Build a minimal-valid JPEG with one EXIF APP1 segment carrying a single IFD0 Orientation entry. Caller picks
	 * little-endian (II) or big-endian (MM) and the orientation value (1..8 per EXIF spec, but the function reads
	 * any u16).
	 */
	private static byte[] buildJpegWithOrientation(boolean isLittleEndian, int orientation) throws IOException
	{
		// EXIF payload: "Exif\0\0" + TIFF header + IFD0 with one Orientation entry.
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write('E');
		payload.write('x');
		payload.write('i');
		payload.write('f');
		payload.write(0);
		payload.write(0);

		// TIFF header.
		if (isLittleEndian)
		{
			payload.write('I');
			payload.write('I');
			payload.write(0x2A);
			payload.write(0x00);
			writeU32(payload, 8L, true);     // IFD0 offset = 8
		}
		else
		{
			payload.write('M');
			payload.write('M');
			payload.write(0x00);
			payload.write(0x2A);
			writeU32(payload, 8L, false);
		}

		// IFD0: 1 entry + next-IFD pointer.
		writeU16(payload, 1, isLittleEndian);                    // entry count
		writeU16(payload, 0x0112, isLittleEndian);               // tag = Orientation
		writeU16(payload, 3, isLittleEndian);                    // type = SHORT
		writeU32(payload, 1L, isLittleEndian);                   // count
		writeU16(payload, orientation, isLittleEndian);          // value (u16 in low half)
		writeU16(payload, 0, isLittleEndian);                    // padding
		writeU32(payload, 0L, isLittleEndian);                   // next-IFD = 0

		return JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.appSegment(0xE1, payload.toByteArray()),
			JpegFixtures.minimalScanAndEoi());
	}

	private static void writeU16(ByteArrayOutputStream out, int value, boolean isLittleEndian)
	{
		if (isLittleEndian)
		{
			out.write(value & 0xFF);
			out.write((value >> 8) & 0xFF);
		}
		else
		{
			out.write((value >> 8) & 0xFF);
			out.write(value & 0xFF);
		}
	}

	private static void writeU32(ByteArrayOutputStream out, long value, boolean isLittleEndian)
	{
		if (isLittleEndian)
		{
			out.write((int) (value & 0xFF));
			out.write((int) ((value >> 8) & 0xFF));
			out.write((int) ((value >> 16) & 0xFF));
			out.write((int) ((value >> 24) & 0xFF));
		}
		else
		{
			out.write((int) ((value >> 24) & 0xFF));
			out.write((int) ((value >> 16) & 0xFF));
			out.write((int) ((value >> 8) & 0xFF));
			out.write((int) (value & 0xFF));
		}
	}
}
