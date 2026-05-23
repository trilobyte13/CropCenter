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
public final class BitmapUtilsTest
{
	private static final float EPS = BitmapUtils.ROTATION_EPSILON;

	@Test
	public void computeInSampleSizeAtProductionCapAllowsTwoHundredMpAtSampleOne()
	{
		// Pin the production behavior end-to-end: a 200 MP Samsung capture (16384×12288 = 201 megapixels)
		// passed through `computeInSampleSize` with the real `MAX_DECODE_PIXELS = 256 MP` returns 1 — no
		// subsampling, full source resolution preserved for the save path. A future cap downgrade that
		// drops MAX_DECODE_PIXELS below 200 MP would surface here. Companion case `8192×8192 = 67 MP`
		// also fits well under the production cap and confirms sampleSize=1 across realistic captures.
		assertEquals(1, BitmapUtils.computeInSampleSize(16384, 12288, BitmapUtils.MAX_DECODE_PIXELS));
		assertEquals(1, BitmapUtils.computeInSampleSize(8192, 8192, BitmapUtils.MAX_DECODE_PIXELS));
	}

	@Test
	public void computeInSampleSizeDoublesUntilFits()
	{
		// Hypothetical cap below the source size (32 MP cap) to exercise the doubling loop. Production uses
		// `MAX_DECODE_PIXELS = 256 MP`, but the helper is parameter-driven so the doubling logic itself
		// must be correct against any cap. 200 MP source at 32 MP cap: sampleSize=2 → 50 MP (still over);
		// sampleSize=4 → 12.6 MP (fits). Pin the exact result so a regression that uses a non-power-of-2
		// stepping or stops too early is caught regardless of the production cap value.
		assertEquals(4, BitmapUtils.computeInSampleSize(16384, 12288, 32 * 1024 * 1024));
		// 8192×8192 = 67 MP. sampleSize=2 → 16 MP (fits at 32 MP cap).
		assertEquals(2, BitmapUtils.computeInSampleSize(8192, 8192, 32 * 1024 * 1024));
	}

	@Test
	public void computeInSampleSizeProducesPowerOfTwo()
	{
		// Android's BitmapFactory.Options.inSampleSize must be a power of 2 (other values round DOWN
		// internally — a sampleSize=3 would decode at sampleSize=2, blowing the memory budget). Sweep across
		// pixel counts at an arbitrary (sub-production) cap to exercise the doubling loop, and assert the
		// result is always 1, 2, 4, 8, ... A regression that returned 3 or 5 would silently re-introduce
		// the OOM the helper exists to prevent. Cap value is arbitrary — see
		// computeInSampleSizeAtProductionCapAllowsTwoHundredMpAtSampleOne for the real-production case.
		int[][] cases =
		{
			{ 100, 100 }, { 1000, 1000 }, { 5000, 5000 }, { 10000, 10000 },
			{ 20000, 15000 }, { 16384, 12288 }, { 50000, 50000 },
		};
		for (int[] dim : cases)
		{
			int s = BitmapUtils.computeInSampleSize(dim[0], dim[1], 32 * 1024 * 1024);
			// Power-of-2 check: s & (s − 1) == 0 AND s > 0.
			assertTrue("sample size " + s + " for " + dim[0] + "x" + dim[1] + " must be power of 2",
				s > 0 && (s & (s - 1)) == 0);
		}
	}

	@Test
	public void computeInSampleSizeReturnsOneForDegenerateInput()
	{
		// Defensive guards: zero/negative dims (from a corrupt header bounds-decode where outWidth=-1) and
		// zero/negative maxPixels (from a mistaken caller config) all return 1 rather than entering an
		// infinite loop. The caller's downstream decode will fail-out on a real bad header; this helper
		// just doesn't make things worse.
		assertEquals(1, BitmapUtils.computeInSampleSize(0, 100, 1000));
		assertEquals(1, BitmapUtils.computeInSampleSize(100, 0, 1000));
		assertEquals(1, BitmapUtils.computeInSampleSize(-100, 100, 1000));
		assertEquals(1, BitmapUtils.computeInSampleSize(100, 100, 0));
		assertEquals(1, BitmapUtils.computeInSampleSize(100, 100, -1));
	}

	@Test
	public void computeInSampleSizeReturnsOneWhenAlreadyUnderCap()
	{
		// Source already fits within maxPixels → no subsampling needed. Sub-cap inputs pinned at an
		// arbitrary 32 MP cap (production uses 256 MP, see the production-cap test above; this case is
		// about the comparator branch, not the production value): 4000×3000 = 12 MP fits, 5000×6000 =
		// 30 MP fits.
		assertEquals(1, BitmapUtils.computeInSampleSize(4000, 3000, 32 * 1024 * 1024));
		assertEquals(1, BitmapUtils.computeInSampleSize(5000, 6000, 32 * 1024 * 1024));
	}

	@Test
	public void computeProxyDimsDownsamples200MpSourceUnderCap()
	{
		// 16384×12288 = 192 mebipixels source ("200 MP" Samsung capture). Must downsample to ≤ 16 MP cap.
		// sqrt(16/192) ≈ 0.289 → expected ~4738×3553 = ~16 MP. Pin the rounded result to catch a
		// regression in the Math.round biasing that would either undersize the proxy (visible quality
		// loss) or oversize past the cap (texture-cache / GPU-budget concerns). The product check uses
		// strict ≤ rather than a tolerance band — the pathological-aspect-ratio guard in computeProxyDims
		// is documented to keep this invariant tight.
		int[] dims = BitmapUtils.computeProxyDims(16384, 12288);
		assertTrue("Source > cap must produce non-null dims", dims != null);
		long proxyPixels = (long) dims[0] * dims[1];
		assertTrue("Proxy " + proxyPixels + " px must be <= MAX_DISPLAY_PIXELS ("
			+ BitmapUtils.MAX_DISPLAY_PIXELS + ")",
			proxyPixels <= BitmapUtils.MAX_DISPLAY_PIXELS);
		// Aspect-ratio preservation: 16384/12288 = 4:3. proxy width/height should also = 4:3 ± rounding.
		float srcAspect = 16384f / 12288f;
		float proxyAspect = (float) dims[0] / dims[1];
		assertEquals("Aspect ratio must be preserved within rounding", srcAspect, proxyAspect, 0.001f);
	}

	@Test
	public void computeProxyDimsPreservesAspectRatioForHighAspectSources()
	{
		// 32767×1000 — width just under Bitmap.createBitmap's 32768 max-dim. The uniform pixel-budget
		// downscale gives dstW = 23428, dstH = 715 (aspect 32.77:1 preserved within rounding). Width still
		// exceeds MAX_PROXY_AXIS = 16384, so the per-axis cap fires — must apply as a uniform SECOND
		// downscale to both axes (not independent clamping) or the renderer's width-derived proxyToSource
		// would distort the Y axis. Pin both invariants: product <= cap AND aspect ratio preserved within
		// ~0.5%.
		int[] dims = BitmapUtils.computeProxyDims(32767, 1000);
		assertTrue("High-aspect source must produce non-null dims", dims != null);
		long proxyPixels = (long) dims[0] * dims[1];
		assertTrue("Proxy " + proxyPixels + " px must be <= MAX_DISPLAY_PIXELS ("
			+ BitmapUtils.MAX_DISPLAY_PIXELS + ")",
			proxyPixels <= BitmapUtils.MAX_DISPLAY_PIXELS);
		assertTrue("Both axes must be <= MAX_PROXY_AXIS; got " + dims[0] + "x" + dims[1],
			dims[0] <= BitmapUtils.MAX_PROXY_AXIS && dims[1] <= BitmapUtils.MAX_PROXY_AXIS);
		float srcAspect = 32767f / 1000f;
		float proxyAspect = (float) dims[0] / dims[1];
		assertEquals("Aspect ratio must be preserved (uniform axis-cap rescale, not independent)",
			srcAspect, proxyAspect, srcAspect * 0.005f);
	}

	@Test
	public void computeProxyDimsRespectsCapForPathologicalAspectRatios()
	{
		// 1×100M attacker input: scale = sqrt(16/100) = 0.4. Naive per-axis rounding gives dstW = max(1,
		// round(0.4)) = 1 (bumped from 0) and dstH = round(40M) = 40M. Product = 40M, blowing the 16 MP
		// cap by 2.5×. The pathological-aspect-ratio guard must (a) shrink the dominant axis until the
		// pixel-count cap holds AND (b) clamp each axis to MAX_PROXY_AXIS so the result is allocatable on
		// every supported device. Aspect ratio of the proxy will diverge from the source — acceptable
		// trade-off since no real camera produces 1×100M images.
		int[] dims = BitmapUtils.computeProxyDims(1, 100_000_000);
		assertTrue("Pathological source must still produce non-null dims", dims != null);
		long proxyPixels = (long) dims[0] * dims[1];
		assertTrue("Proxy " + proxyPixels + " px must be <= MAX_DISPLAY_PIXELS ("
			+ BitmapUtils.MAX_DISPLAY_PIXELS + ") even for 1x100M input",
			proxyPixels <= BitmapUtils.MAX_DISPLAY_PIXELS);
		assertTrue("Both axes must be >= 1", dims[0] >= 1 && dims[1] >= 1);
		assertTrue("Both axes must be <= MAX_PROXY_AXIS (allocatable on every device); got "
			+ dims[0] + "x" + dims[1],
			dims[0] <= BitmapUtils.MAX_PROXY_AXIS && dims[1] <= BitmapUtils.MAX_PROXY_AXIS);
		// Symmetric: 100M×1 input should also stay under both caps.
		int[] dimsTransposed = BitmapUtils.computeProxyDims(100_000_000, 1);
		long proxyPixelsTransposed = (long) dimsTransposed[0] * dimsTransposed[1];
		assertTrue("Transposed pathological source must also stay <= cap",
			proxyPixelsTransposed <= BitmapUtils.MAX_DISPLAY_PIXELS);
		assertTrue("Both axes must be >= 1 (transposed)",
			dimsTransposed[0] >= 1 && dimsTransposed[1] >= 1);
		assertTrue("Both axes must be <= MAX_PROXY_AXIS (transposed); got "
			+ dimsTransposed[0] + "x" + dimsTransposed[1],
			dimsTransposed[0] <= BitmapUtils.MAX_PROXY_AXIS
				&& dimsTransposed[1] <= BitmapUtils.MAX_PROXY_AXIS);
	}

	@Test
	public void computeProxyDimsReturnsNullWhenSourceAlreadyFits()
	{
		// 3000×2000 = 6 MP source, well under MAX_DISPLAY_PIXELS (16 MP). Null return signals the caller
		// to alias the source rather than allocate — saves the 64 MB-class bitmap copy that would
		// otherwise run on every load for the common sub-16-MP camera capture. Boundary: a source at
		// exactly MAX_DISPLAY_PIXELS pixel count is "already fits" (<=), not "needs downsample".
		assertTrue("Sub-cap source must return null (caller aliases)",
			BitmapUtils.computeProxyDims(3000, 2000) == null);
		// 4096×4096 = exactly 16 MP. Equals the cap -> null (no downsample needed).
		assertTrue("At-cap source must return null", BitmapUtils.computeProxyDims(4096, 4096) == null);
	}

	@Test
	public void epsilonValueIsHalfOfFinestRulerStep()
	{
		// Ruler's finest tick is 0.01°; epsilon sits at 0.005° so every nonzero value the ruler can produce
		// survives the snap in CropState.setRotationDegrees. A regression that bumped epsilon back to 0.05f
		// would silently turn 0.01°-0.04° rotations into no-ops — the bug we just spent a session fixing.
		assertEquals(0.005f, EPS, 0f);
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
	public void readExifOrientationHandlesFillBytesBeforeApp1() throws IOException
	{
		// JPEG spec ITU-T T.81 §B.1.1.2: any marker may be preceded by any number of 0xFF fill bytes. Without
		// fill-byte handling the walker would mis-read the second 0xFF as marker code 0xFF and return 1
		// (upright) without finding the EXIF segment. Splice 2 fill bytes in front of the APP1 to pin the fix.
		byte[] jpeg = buildJpegWithOrientation(true, 6);
		// SOI is at [0..1]. APP1 starts at index 2 (FF E1). Insert two fill 0xFF bytes between SOI and APP1.
		byte[] padded = new byte[jpeg.length + 2];
		padded[0] = jpeg[0];
		padded[1] = jpeg[1];
		padded[2] = (byte) 0xFF;     // fill
		padded[3] = (byte) 0xFF;     // fill
		System.arraycopy(jpeg, 2, padded, 4, jpeg.length - 2);
		assertEquals(6, BitmapUtils.readExifOrientation(padded));
	}

	@Test
	public void readExifOrientationRejectsAdversarialIfdOffsetThatWrapsInt() throws IOException
	{
		// Adversarial IFD0 offset 0xFFFFFFFE (u32) — without the long-arithmetic `absIfd > Integer.MAX_VALUE`
		// guard, casting the sum (long tiffStart + ifdOff) directly to int would wrap to a small positive
		// value that passes the in-bounds check, letting the IFD-entry walk read garbage as tag/type/value.
		// Pin the long-overflow guard: this fixture must return 1.
		byte[] jpeg = buildJpegWithOrientation(true, 6);
		// TIFF header sits at offset 12 (SOI(2) + APP1 hdr(4) + "Exif\0\0"(6)). IFD0 offset field is at
		// tiffStart + 4 = 16..19 (little-endian).
		jpeg[16] = (byte) 0xFE;
		jpeg[17] = (byte) 0xFF;
		jpeg[18] = (byte) 0xFF;
		jpeg[19] = (byte) 0xFF;
		assertEquals("adversarial u32 ifdOff must hit the overflow guard",
			1, BitmapUtils.readExifOrientation(jpeg));
	}

	@Test
	public void readExifOrientationRejectsLengthFieldUnderTwo() throws IOException
	{
		// JPEG segment length field is u16 and INCLUDES the 2 length bytes themselves. Anything < 2 is
		// malformed: 0 or 1 would advance the walk offset by 0 or 1 instead of the real segment size,
		// looping forever (off never grows past the segment) or skipping into the middle of subsequent
		// segments. The `if (segLen < 2) return 1` guard short-circuits cleanly.
		byte[] base = buildJpegWithOrientation(true, 6);
		// APP1 length field is at offset 4..5 (after SOI + FF E1). Overwrite with segLen=1 to trip the
		// guard before the EXIF body parser runs.
		base[4] = 0x00;
		base[5] = 0x01;
		assertEquals("segLen < 2 must return upright (1)",
			1, BitmapUtils.readExifOrientation(base));
	}

	@Test
	public void readExifOrientationRejectsOutOfRangeValues() throws IOException
	{
		// EXIF orientation is defined for values 1..8. A coincidental byte sequence that resolves to 9
		// (or 0, or 0xFFFF) must map to upright (1) — never returned verbatim — because downstream code
		// assumes the value is in-range.
		byte[] jpeg = buildJpegWithOrientation(true, 9);
		assertEquals(1, BitmapUtils.readExifOrientation(jpeg));
		byte[] jpegZero = buildJpegWithOrientation(true, 0);
		assertEquals(1, BitmapUtils.readExifOrientation(jpegZero));
	}

	@Test
	public void readExifOrientationRejectsShortApp1WhereTiffHeaderTruncated() throws IOException
	{
		// The `tiffStart + 8 > jpeg.length` guard catches an APP1 whose Exif identifier is present but
		// whose trailing bytes don't include 8 bytes of TIFF header. Build the segment header + "Exif\0\0"
		// but truncate before the TIFF byte-order field; the function must return 1 without AIOOBE.
		byte[] truncated = {
			(byte) 0xFF, (byte) 0xD8,  // SOI
			(byte) 0xFF, (byte) 0xE1,  // APP1 marker
			0x00, 0x0A,                // segLen = 10 (just enough for Exif\0\0)
			'E', 'x', 'i', 'f', 0, 0,  // EXIF identifier — segment ends here
		};
		assertEquals("short APP1 (no TIFF header bytes) must return upright (1)",
			1, BitmapUtils.readExifOrientation(truncated));
	}

	@Test
	public void readExifOrientationRejectsWrongEntryCount() throws IOException
	{
		// Count != 1 means the value field stores an offset to an array, not the value itself. A coincidental
		// orientation entry with count=2 would otherwise have us read the offset's low u16 as orientation.
		byte[] jpeg = buildJpegWithOrientation(true, 6);
		// Entry count sits at offset 26..29 (u32, little-endian). Overwrite low byte with 2.
		jpeg[26] = 2;
		assertEquals(1, BitmapUtils.readExifOrientation(jpeg));
	}

	@Test
	public void readExifOrientationRejectsWrongEntryType() throws IOException
	{
		// Real EXIF emits Orientation as type SHORT (3). Anything else means we'd be sampling the value field
		// under the wrong type interpretation — typically reading a different number of bytes than the entry
		// actually stores.
		byte[] jpeg = buildJpegWithOrientation(true, 6);
		// Entry type sits at offset 24, 25 (IFD entry: tag=22..23, type=24..25, count=26..29, value=30..31).
		jpeg[24] = 4; // LONG instead of SHORT
		jpeg[25] = 0;
		assertEquals(1, BitmapUtils.readExifOrientation(jpeg));
	}

	@Test
	public void readExifOrientationRejectsWrongTiffMagic() throws IOException
	{
		// TIFF magic must be 42 (0x002A). A coincidental II/MM byte-order match without the magic field means
		// the payload isn't actually TIFF — refuse to read further so a malformed APP1 with plausible offsets
		// and a tag-0x0112 byte sequence can't make us return a non-1 orientation from random bytes.
		byte[] jpeg = buildJpegWithOrientation(true, 6);
		// TIFF magic sits at offset 14, 15 (after II at 12, 13). Overwrite with 0xABCD.
		jpeg[14] = (byte) 0xCD;
		jpeg[15] = (byte) 0xAB;
		assertEquals(1, BitmapUtils.readExifOrientation(jpeg));
	}

	@Test
	public void readExifOrientationReturnsAllValidValuesOneThroughEight() throws IOException
	{
		// Iterate the full valid 1..8 EXIF orientation range across BOTH byte orders. A regression that
		// masked the orientation low byte (e.g. `& 0x07` instead of `& 0xFF`) would still pass orientation=6
		// (6 & 7 = 6) but corrupt orientation=8 (8 & 7 = 0 → out-of-range → maps to 1). The two-loop pass
		// over LE and BE also covers the production code's `isLittleEndian` branch so a single-endian
		// short-circuit regression surfaces here.
		for (int orient = 1; orient <= 8; orient++)
		{
			byte[] le = buildJpegWithOrientation(true, orient);
			assertEquals("LE orient " + orient, orient, BitmapUtils.readExifOrientation(le));
			byte[] be = buildJpegWithOrientation(false, orient);
			assertEquals("BE orient " + orient, orient, BitmapUtils.readExifOrientation(be));
		}
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
