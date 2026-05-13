package com.cropcenter.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.cropcenter.util.ByteBufferUtils;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Tests for the cycle-and-overflow guards in ExifPatcher.scanIfd. Two adversarial EXIF shapes pin down the recursion
 * bound (cyclic SubIFD pointer) and the long-arithmetic cast guard (SubIFD offset that overflows int when added to
 * tiffStart). Both bypass- the-guard regressions would corrupt the EXIF segment by writing orientation = 1 over
 * arbitrary bytes — the safest crash mode is a benign no-op, which is what the guards deliver.
 */
public final class ExifPatcherTest
{
	// Position of the TIFF header inside the EXIF segment payload, mirroring ExifPatcher.TIFF_HEADER_OFFSET (FF E1
	// + 2-byte length + "Exif\0\0").
	private static final int TIFF_HEADER_OFFSET = 10;

	@Test
	public void patchTerminatesOnCyclicSubIfdChain() throws IOException
	{
		// IFD0 with two entries: a SubIFD (0x8769) pointing back at IFD0 itself (offset 8 == IFD0's offset
		// within the TIFF region), and an Orientation tag (0x0112) so we can verify scanIfd actually ran.
		// Without the depth guard, scanIfd recurses unboundedly and throws StackOverflowError — uncatchable in
		// the calling Exception handlers, which would crash the bg thread.
		byte[] ifd = buildIfd(new int[][] {
			{ 0x8769, 4, 1, 8 },     // SubIFD pointer = 8 → cycle back to IFD0
			{ 0x0112, 3, 1, 0x0006 }, // Orientation = 6 (Rotate 270 CW)
		});
		JpegSegment exifSeg = wrapAsExifSegment(ifd);

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(exifSeg), 100, 200, null);

		// Orientation byte should be rewritten to 1. Address of the orientation value = TIFF_HEADER_OFFSET + 8
		// (IFD0 offset relative to TIFF) + 2 (count) + 12 (skip SubIFD entry) + 8 (skip tag/type/count of
		// orientation entry).
		byte[] data = patched.get(0).data();
		int orientationValueOff = TIFF_HEADER_OFFSET + 8 + 2 + 12 + 8;
		assertEquals(1, data[orientationValueOff] & 0xFF);
		// High byte of u16 should be zero.
		assertEquals(0, data[orientationValueOff + 1] & 0xFF);
	}

	@Test
	public void patchIgnoresSubIfdOffsetExceedingIntMax() throws IOException
	{
		// SubIFD offset = 0xFFFFFFFF (u32 max). Without the long-arithmetic guard, the expression `(int)
		// (TIFF_HEADER_OFFSET + 0xFFFFFFFFL)` truncates to a small positive int (TIFF_HEADER_OFFSET - 1 = 9)
		// which falls inside the buffer — the recursion would re-enter scanIfd on the EXIF header bytes
		// themselves and rewrite arbitrary positions. With the guard, the recursive call is skipped. We verify
		// the byte at the would-be-recursion target is unchanged.
		byte[] ifd = buildIfd(new int[][] {
			{ 0x8769, 4, 1, 0xFFFFFFFF }, // adversarial SubIFD offset
			{ 0x0112, 3, 1, 0x0006 },     // Orientation
		});
		JpegSegment exifSeg = wrapAsExifSegment(ifd);

		// Snapshot the byte at TIFF_HEADER_OFFSET + 9 (the target of the truncated cast). Without the guard
		// scanIfd would write a u16 orientation value at (target + 8) — clobbering EXIF header bytes around
		// offset 17. Capture pre-patch bytes 17..18 so we can verify they're unchanged.
		byte[] preData = exifSeg.data().clone();
		int suspiciousByte1 = preData[TIFF_HEADER_OFFSET + 9 + 8] & 0xFF;
		int suspiciousByte2 = preData[TIFF_HEADER_OFFSET + 9 + 9] & 0xFF;

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(exifSeg), 100, 200, null);

		byte[] data = patched.get(0).data();
		// Real orientation tag (in IFD0 at the legitimate location) was rewritten to 1.
		int orientationValueOff = TIFF_HEADER_OFFSET + 8 + 2 + 12 + 8;
		assertEquals(1, data[orientationValueOff] & 0xFF);
		// Bytes the truncated cast would have targeted are unchanged.
		assertEquals(suspiciousByte1, data[TIFF_HEADER_OFFSET + 9 + 8] & 0xFF);
		assertEquals(suspiciousByte2, data[TIFF_HEADER_OFFSET + 9 + 9] & 0xFF);
	}

	@Test
	public void patchHandlesDeepNestedSubIfdChainWithoutOverflow() throws IOException
	{
		// Six-deep SubIFD chain: IFD0 → IFD1' → IFD2' → IFD3' → IFD4' → IFD5'. Depth guard caps recursion at 4,
		// so IFD5' is reached but its SubIFD pointer is not followed. No exception, no infinite loop, and the
		// legal-depth IFDs all get their orientation tag rewritten.
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8); // IFD0 offset

		// We chain six IFDs back to back. Each IFD has 2 entries (SubIFD pointing at
		// next, Orientation = 6). The final IFD's SubIFD pointer is invalid (points
		// past the buffer). Layout:
		//   IFD0 at offset 8, IFD1' at offset 8 + 30 = 38, IFD2' at 68, ..., IFD5' at
		//   158.
		int ifdSize = 30; // 2-byte count + 2 * 12-byte entry + 4-byte next-IFD
		int chainCount = 6;
		int beyondEof = 999_999;
		for (int level = 0; level < chainCount; level++)
		{
			int nextIfdOff = (level == chainCount - 1)
				? beyondEof
				: 8 + (level + 1) * ifdSize;
			byte[] ifd = buildIfd(new int[][] {
				{ 0x8769, 4, 1, nextIfdOff },
				{ 0x0112, 3, 1, 0x0006 },
			});
			tiff.write(ifd);
		}

		JpegSegment exifSeg = wrapTiffAsExifSegment(tiff.toByteArray());

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(exifSeg), 100, 200, null);

		byte[] data = patched.get(0).data();
		// IFD0..IFD3' (depth 0..3) all see the orientation rewrite.
		int orientationValueOff = TIFF_HEADER_OFFSET + 8 + 2 + 12 + 8;
		for (int level = 0; level < 4; level++)
		{
			int off = TIFF_HEADER_OFFSET + 8 + level * ifdSize + 2 + 12 + 8;
			assertEquals("level " + level, 1, data[off] & 0xFF);
		}
		// IFD4' (depth 4) should also be rewritten — the guard kicks in at depth > 4, so depth=4 is still
		// processed.
		int depth4Off = TIFF_HEADER_OFFSET + 8 + 4 * ifdSize + 2 + 12 + 8;
		assertEquals(1, data[depth4Off] & 0xFF);
		// IFD5' (depth 5) is past the depth cap; orientation stays at 6.
		int depth5Off = TIFF_HEADER_OFFSET + 8 + 5 * ifdSize + 2 + 12 + 8;
		assertEquals(6, data[depth5Off] & 0xFF);
		// Verify the orientation rewrite at IFD0's known location too.
		assertEquals(1, data[orientationValueOff] & 0xFF);
	}

	@Test
	public void patchReturnsSegmentUnchangedOnImByteOrder() throws IOException
	{
		// EXIF byte-order field is 2 bytes — "II" (little) or "MM" (big). A malformed "IM" half-pair must not
		// be silently treated as little-endian (which would then parse subsequent u32 offsets with wrong byte
		// order, possibly wandering into the wrong corner of the segment buffer). Patch should fall through and
		// leave the segment bytes verbatim.
		byte[] ifd = buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0006 },
		});
		JpegSegment seg = wrapAsExifSegmentWithByteOrder(ifd, (byte) 'I', (byte) 'M');
		byte[] originalBytes = seg.data().clone();

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200, null);

		assertEquals(1, patched.size());
		// Bytes must be identical to input — no orientation rewrite happened because the byte-order rejection
		// skipped scanIfd entirely.
		byte[] resultBytes = patched.get(0).data();
		assertEquals(originalBytes.length, resultBytes.length);
		for (int i = 0; i < originalBytes.length; i++)
		{
			assertEquals("byte " + i + " mutated despite byte-order rejection",
				originalBytes[i], resultBytes[i]);
		}
	}

	@Test
	public void patchReturnsSegmentUnchangedOnMiByteOrder() throws IOException
	{
		// Symmetric counterpart of patchReturnsSegmentUnchangedOnImByteOrder. A malformed "MI" half-pair would
		// be treated as big-endian if the validator only checked the second byte; both halves must be checked.
		byte[] ifd = buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0006 },
		});
		JpegSegment seg = wrapAsExifSegmentWithByteOrder(ifd, (byte) 'M', (byte) 'I');
		byte[] originalBytes = seg.data().clone();

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200, null);

		byte[] resultBytes = patched.get(0).data();
		assertEquals(originalBytes.length, resultBytes.length);
		for (int i = 0; i < originalBytes.length; i++)
		{
			assertEquals("byte " + i + " mutated despite byte-order rejection",
				originalBytes[i], resultBytes[i]);
		}
	}

	@Test
	public void maxThumbnailBytesReturnsBaselineForValidByteOrder() throws IOException
	{
		// Baseline: a valid IFD0 with no IFD1 returns
		//   APP1_MAX_SEGMENT_BYTES - (data.length + IFD1_ESTIMATED_OVERHEAD).
		// Pinning down a specific value here lets the byte-order-rejection test below
		// distinguish "rejected, returned DEFAULT" from "parsed clean, returned the
		// computed budget".
		byte[] ifd = buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0001 },   // Orientation = 1 (Normal)
		});
		JpegSegment seg = wrapAsExifSegment(ifd);
		int budget = ExifPatcher.maxThumbnailBytes(Collections.singletonList(seg));
		// segment data layout: FF E1 LL LL + (Exif\0\0 + II*\0 + 4-byte ifd0Off + 18-byte IFD0) = 4 + 6 + 4 + 4
		// + 18 = 36 bytes. Budget = 65535 - 36 - 42 = 65457.
		assertEquals(65457, budget);
	}

	@Test
	public void maxThumbnailBytesReturnsDefaultOnImByteOrder() throws IOException
	{
		// The rejection branch (continue → fall through to "return DEFAULT_THUMB_BUDGET" at end of loop) should
		// fire for "IM". Without the fix, maxThumbnailBytes would have parsed the segment with isLittleEndian=
		// true (only first byte 'I' checked) and returned a measured value — distinct from DEFAULT_THUMB_BUDGET
		// (20_000).
		byte[] ifd = buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0001 },
		});
		JpegSegment seg = wrapAsExifSegmentWithByteOrder(ifd, (byte) 'I', (byte) 'M');
		int budget = ExifPatcher.maxThumbnailBytes(Collections.singletonList(seg));
		assertEquals(20_000, budget);
	}

	@Test
	public void maxThumbnailBytesReturnsDefaultOnMiByteOrder() throws IOException
	{
		byte[] ifd = buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0001 },
		});
		JpegSegment seg = wrapAsExifSegmentWithByteOrder(ifd, (byte) 'M', (byte) 'I');
		int budget = ExifPatcher.maxThumbnailBytes(Collections.singletonList(seg));
		assertEquals(20_000, budget);
	}

	@Test
	public void patchPassesThroughEmptyList()
	{
		// Empty input → empty output, no NPE on the for-each. The audit flagged this as untested.
		List<JpegSegment> patched = ExifPatcher.patch(Collections.emptyList(), 100, 200, null);
		assertEquals(0, patched.size());
	}

	@Test
	public void buildMinimalExifSegmentAcceptsThumbnailAtExactApp1Cap() throws IOException
	{
		// Round-38 test-coverage P0: direct test of buildMinimalExifSegment with an at-cap thumbnail
		// — the round-36 synthesise path is otherwise exercised only via patch() with small inputs,
		// and an off-by-one regression in the cap math would slip. Layout: 2 bytes FF E1 marker +
		// segLen field (counts itself = 2) + "Exif\0\0" (6) + TIFF body (TIFF header 8 + IFD0 42 +
		// IFD1 42 + thumbnail.length). segLen field value = 2 + 6 + 92 + thumbnail.length =
		// 100 + thumbnail.length. The cap rejects when segLen > 65535, so the largest legal thumbnail
		// is 65435 bytes (segLen = 65535 exactly), and 65436 must reject.
		byte[] thumbAtCap = uniqueThumbnailBytes((byte) 0x55, 65_435);
		JpegSegment atCap = ExifPatcher.buildMinimalExifSegment(800, 600, thumbAtCap);
		assertNotNull("thumbnail sized to land segLen at exactly 65535 must succeed", atCap);
		assertEquals("data array must be 2 (FF E1 marker) + segLen value (65535) = 65537",
			65_537, atCap.data().length);
		int segLenField = ((atCap.data()[2] & 0xFF) << 8) | (atCap.data()[3] & 0xFF);
		assertEquals("segLen field at data[2..3] must be exactly 65535 (the cap)",
			65_535, segLenField);
		assertTrue("thumbnail bytes must appear in the synthesised segment",
			indexOfSubsequence(atCap.data(), thumbAtCap) >= 0);

		byte[] thumbOverCap = uniqueThumbnailBytes((byte) 0x55, 65_436);
		JpegSegment overCap = ExifPatcher.buildMinimalExifSegment(800, 600, thumbOverCap);
		assertNull("thumbnail one byte past the cap must return null", overCap);
	}

	@Test
	public void buildMinimalExifSegmentProducesCanonicalIfd0Layout() throws IOException
	{
		// Round-38 test-coverage P0: pin the synthesised segment's IFD0 layout (3 entries:
		// Orientation, ImageWidth, ImageLength) and IFD1 thumbnail pointer/length tags so a future
		// regression that reorders entries or mis-sizes IFD0 surfaces here rather than via downstream
		// EXIF-aware viewer wrong-rendering. The segment is little-endian per the documented format.
		byte[] thumb = uniqueThumbnailBytes((byte) 0x77, 1024);
		JpegSegment seg = ExifPatcher.buildMinimalExifSegment(1920, 1080, thumb);
		assertNotNull(seg);
		assertTrue("synthesised segment must be recognised as EXIF", seg.isExif());
		byte[] data = seg.data();
		// IFD0 starts at data offset TIFF_HEADER_OFFSET + 8 = 18.
		int ifd0Off = TIFF_HEADER_OFFSET + 8;
		int ifd0EntryCount = ByteBufferUtils.readU16(data, ifd0Off, true);
		assertEquals("IFD0 must declare 3 entries (Orientation, ImageWidth, ImageLength)",
			3, ifd0EntryCount);
		// Orientation entry: tag at ifd0+2, value (SHORT) at ifd0+10.
		assertEquals("first IFD0 entry must be Orientation (tag 0x0112)",
			0x0112, ByteBufferUtils.readU16(data, ifd0Off + 2, true));
		assertEquals("Orientation value must be 1 (upright — rotation baked into pixels)",
			1, ByteBufferUtils.readU16(data, ifd0Off + 10, true));
		// ImageWidth entry: tag at ifd0+14, value (LONG) at ifd0+22.
		assertEquals("second IFD0 entry must be ImageWidth (tag 0x0100)",
			0x0100, ByteBufferUtils.readU16(data, ifd0Off + 14, true));
		assertEquals("ImageWidth value must equal newW (1920)",
			1920L, ByteBufferUtils.readU32(data, ifd0Off + 22, true));
		// ImageLength entry: tag at ifd0+26, value (LONG) at ifd0+34.
		assertEquals("third IFD0 entry must be ImageLength (tag 0x0101)",
			0x0101, ByteBufferUtils.readU16(data, ifd0Off + 26, true));
		assertEquals("ImageLength value must equal newH (1080)",
			1080L, ByteBufferUtils.readU32(data, ifd0Off + 34, true));
		// IFD0's next-IFD pointer (at ifd0+38) must point at IFD1 (TIFF offset 50).
		assertEquals("IFD0 next-IFD pointer must redirect at synthesised IFD1 at TIFF offset 50",
			50L, ByteBufferUtils.readU32(data, ifd0Off + 38, true));
	}

	@Test
	public void patchAppendsFreshIfd1OnRealSamsungGalaxyS25UltraExif() throws IOException
	{
		// Round-36 user-reported P0: user shared a Samsung Galaxy S25 Ultra photo whose saved-through-
		// CropCenter output had no IFD1 thumbnail despite the round-36 fixes being deployed. The
		// synthetic 16-entry IFD0 test below passes, but reality didn't. Feed the actual source EXIF
		// segment (extracted from the user's diag-input.jpg, dropped into resources/samsung-exif.bin)
		// through `patch` with a real thumbnail and assert the output has an appended IFD1 + thumb.
		// If THIS test fails, we've reproduced the bug at the unit level.
		InputStream stream = ExifPatcherTest.class.getClassLoader()
			.getResourceAsStream("samsung-exif.bin");
		assertNotNull("samsung-exif.bin fixture must be present in test resources", stream);
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		int read;
		while ((read = stream.read(chunk)) > 0)
		{
			buffer.write(chunk, 0, read);
		}
		stream.close();
		byte[] exifBytes = buffer.toByteArray();
		JpegSegment seg = new JpegSegment(0xE1, exifBytes);
		assertTrue("source fixture must be recognised as EXIF", seg.isExif());

		byte[] freshThumb = uniqueThumbnailBytes((byte) 0x55, 5000);
		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 1880, 2350, freshThumb);

		byte[] resultData = patched.get(0).data();
		// Exact size: appendFreshIfd1WithThumbnail extends the segment by 42 (fresh IFD1 header) +
		// thumbnail.length. The previous `>= source + 4000` slop hid regressions that dropped/duplicated
		// up to ~1000 bytes of MakerNote (round-38 test-coverage audit P2).
		assertEquals("patched EXIF must equal source + 42 (IFD1 header) + thumbnail.length",
			exifBytes.length + 42 + freshThumb.length, resultData.length);
		assertTrue("fresh thumbnail bytes must appear in the patched segment",
			indexOfSubsequence(resultData, freshThumb) >= 0);
		// Pin the JPEG segLen field too — round-35 audit caught that the prior assertions would have
		// passed for a regression that planted bytes in the buffer but failed to update FF E1 LL LL.
		// segLen u16 BE at result[2..3]; expected value = result.length - 2 (excludes marker).
		int rebuiltSegLen = ((resultData[2] & 0xFF) << 8) | (resultData[3] & 0xFF);
		assertEquals("rebuilt segLen header must reflect the post-append segment length",
			resultData.length - 2, rebuiltSegLen);
		// Pin IFD0's next-IFD pointer redirected at the appended IFD1 (was 0 in the source). Big-endian
		// (Samsung MM); IFD0 starts at TIFF offset 8 (data offset 18); 16 entries × 12 bytes + 2-byte
		// count → next-IFD pointer at data offset 18 + 2 + 192 = 212.
		int nextIfdPointerOff = TIFF_HEADER_OFFSET + 8 + 2 + 16 * 12;
		long nextIfdPointer = ByteBufferUtils.readU32(resultData, nextIfdPointerOff, false);
		assertNotEquals("IFD0 next-IFD pointer must redirect at the appended fresh IFD1",
			0L, nextIfdPointer);
	}

	@Test
	public void patchAppendsFreshIfd1WhenBigEndianSourceHasMultiEntryIfd0AndNoIfd1() throws IOException
	{
		// Round-36 user-reported P0 (continued): user shared a Samsung Galaxy S25 Ultra JPEG (big-endian
		// MM byte order, 16-entry IFD0 with JPEGInterchangeFormat in IFD0, IFD0's next-IFD pointer = 0,
		// no IFD1). After running through round-36 fixes the saved output's segLen was still 1280 —
		// `appendFreshIfd1WithThumbnail` was silently failing on this real-world fixture even though
		// all existing tests (which used little-endian II + 1-entry IFD0) pass. This test mimics the
		// exact source shape so the failure is reproducible at the unit level.
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('M');
		tiff.write('M');
		tiff.write(0);
		tiff.write(42);
		writeU32Be(tiff, 8);
		// IFD0: 16 entries (mimicking Samsung's typical Galaxy S25 layout). Pack them all as TYPE_LONG
		// with arbitrary values; the patcher doesn't care about most tag IDs, it only updates
		// dim/orientation tags it recognises. The structure being non-trivial is what matters.
		writeU16Be(tiff, 16);
		for (int i = 0; i < 16; i++)
		{
			writeU16Be(tiff, 0x1000 + i);  // some tag id
			writeU16Be(tiff, 4);            // TYPE_LONG
			writeU32Be(tiff, 1);            // count 1
			writeU32Be(tiff, 0);            // value 0
		}
		writeU32Be(tiff, 0);  // next-IFD pointer = 0 (no IFD1)
		// Pad to a realistic source size (~1.2KB of string data, MakerNote, etc.)
		for (int i = 0; i < 1000; i++)
		{
			tiff.write(0);
		}
		JpegSegment seg = wrapTiffAsExifSegment(tiff.toByteArray());

		byte[] freshThumb = uniqueThumbnailBytes((byte) 0x55, 5000);
		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200, freshThumb);

		byte[] resultData = patched.get(0).data();
		// 1. Exact size: appendFresh extends by 42 (IFD1 header) + thumbnail.length. The earlier `+4000`
		//    slop hid regressions that dropped up to 1KB of payload (round-38 test-coverage audit P2).
		assertEquals("appendFresh must extend the segment by exactly 42 (IFD1 header) + thumbnail.length",
			seg.data().length + 42 + freshThumb.length, resultData.length);
		// 2. JPEG segLen header must reflect the new length (FF E1 LL LL is big-endian per JPEG spec,
		//    regardless of TIFF byte order). Round-35 audit caught that earlier assertions would have
		//    passed for a regression that grew the buffer but left segLen stale.
		int rebuiltSegLen = ((resultData[2] & 0xFF) << 8) | (resultData[3] & 0xFF);
		assertEquals("rebuilt segLen header must reflect the post-append segment length",
			resultData.length - 2, rebuiltSegLen);
		// 3. IFD0's next-IFD pointer must now point at the appended IFD1 (TIFF-relative offset > 0).
		int ifd0Off = TIFF_HEADER_OFFSET + 8;
		int nextIfdPointerOff = ifd0Off + 2 + 16 * 12;
		assertTrue("IFD0's next-IFD pointer must be redirected at the appended IFD1",
			ByteBufferUtils.readU32(resultData, nextIfdPointerOff, false) > 0);
		// 4. Fresh thumbnail bytes must appear in the result.
		assertTrue("fresh thumbnail bytes must be present in the rebuilt segment",
			indexOfSubsequence(resultData, freshThumb) >= 0);
	}

	@Test
	public void hasIfd1ThumbnailReturnsTrueForSourceWithExistingIfd1Thumbnail() throws IOException
	{
		// `ExportPipeline.canBypassEncode` uses `hasIfd1Thumbnail` to gate the verbatim-write bypass:
		// when the source already has a thumbnail, bypass is allowed; when it doesn't, force re-encode
		// so `CropExporter` can synthesise one (round-36 user-reported bug). Pin the happy path here.
		byte[] oldThumb = uniqueThumbnailBytes((byte) 0xAA, 80);
		JpegSegment seg = buildSegmentWithExistingThumbnail(oldThumb);
		assertTrue("source carrying IFD1 with valid thumb tags must report hasIfd1Thumbnail = true",
			ExifPatcher.hasIfd1Thumbnail(Collections.singletonList(seg)));
	}

	@Test
	public void hasIfd1ThumbnailReturnsFalseForSourcesWithoutPreComputedThumbnail() throws IOException
	{
		// Three "no thumbnail" shapes the round-36 bug surfaced. All must return false so canBypassEncode
		// rejects bypass and forces the re-encode path.
		assertFalse("empty segment list must report no thumbnail",
			ExifPatcher.hasIfd1Thumbnail(Collections.emptyList()));
		assertFalse("null segment list must report no thumbnail",
			ExifPatcher.hasIfd1Thumbnail(null));

		// Source with EXIF + IFD0 but no IFD1.
		byte[] ifd0Only = buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0006 },
		});
		assertFalse("source with EXIF + IFD0 but no IFD1 must report no thumbnail",
			ExifPatcher.hasIfd1Thumbnail(Collections.singletonList(wrapAsExifSegment(ifd0Only))));

		// Source with EXIF + IFD1 but no thumbnail tags (typical of minimal-EXIF encoders that emit
		// IFD1 carrying only resolution metadata). Build IFD0 with a next-IFD pointer to IFD1, then
		// IFD1 with just a Compression entry.
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8);
		byte[] ifd0 = buildIfd(new int[][] { { 0x0112, 3, 1, 0x0006 } });
		tiff.write(ifd0, 0, ifd0.length - 4);
		writeU32Le(tiff, 26);
		tiff.write(buildIfd(new int[][] { { 0x0103, 3, 1, 6 } }));
		JpegSegment ifd1NoThumb = wrapTiffAsExifSegment(tiff.toByteArray());
		assertFalse("source with IFD1 lacking JPEGInterchangeFormat must report no thumbnail",
			ExifPatcher.hasIfd1Thumbnail(Collections.singletonList(ifd1NoThumb)));
	}

	@Test
	public void maxThumbnailBytesFallsBackToDefaultWhenIfd0OffsetWrapsIntCast() throws IOException
	{
		// Round-35 logic-audit P0: `maxThumbnailBytes` used to cast `(int)(TIFF_HEADER_OFFSET + ifd0Rel)`
		// BEFORE the bounds check, which on a u32 ifd0Rel near `Integer.MAX_VALUE - TIFF_HEADER_OFFSET`
		// produced a small-positive int that passed the `< TIFF_HEADER_OFFSET` guard by truncation. The
		// function is in the round-34 F1 critical path (`patchPngExifTiff` uses it to decide strip-vs-
		// splice), so the bypass let adversarial PNG eXIf inputs through to a doomed splice instead of
		// force-stripping. Pin the long-arithmetic-first fix with an ifd0Rel value that ONLY the
		// long-sum guard catches: 0x80000000 + 16 sums to a positive long > Integer.MAX_VALUE, but the
		// pre-fix int cast wraps to 26 (a valid-looking small offset inside the buffer).
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 0x80000010L); // adversarial IFD0 offset
		// Pad past offset 26 so the OLD code's bounds check (ifd0 + 2 > data.length) wouldn't catch the
		// wrapped value either — only the long-arithmetic guard catches it.
		for (int i = 0; i < 100; i++)
		{
			tiff.write(0);
		}
		JpegSegment seg = wrapTiffAsExifSegment(tiff.toByteArray());

		int budget = ExifPatcher.maxThumbnailBytes(Collections.singletonList(seg));

		// defaultThumbBudget is 20_000 (private inside maxThumbnailBytes). The OLD code would walk into
		// the zero-padded bytes and produce a garbage non-default return; the FIXED code returns the
		// documented fallback the moment the long-arithmetic guard fires.
		assertEquals("long-arithmetic guard must catch wrapped IFD0 offset and return defaultThumbBudget",
			20_000, budget);
	}

	@Test
	public void patchAppendsFreshIfd1WithThumbnailWhenSourceHasNoIfd1() throws IOException
	{
		// Source IFD0 has 1 entry (Orientation) and next-IFD pointer = 0 — no IFD1 exists. Passing a non-null
		// thumbnail forces the appendFreshIfd1WithThumbnail path: a new IFD1 with thumbnail tags (0x0201 /
		// 0x0202) is constructed and appended. Verify the thumbnail bytes show up in the output and the output
		// is materially larger than the input.
		byte[] ifd = buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0006 },
		});
		JpegSegment seg = wrapAsExifSegment(ifd);
		byte[] thumbnail = uniqueThumbnailBytes((byte) 0x77, 100);

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200, thumbnail);

		assertEquals(1, patched.size());
		byte[] resultData = patched.get(0).data();
		// Exact size: source + 42 (fresh IFD1 header) + thumbnail.length. Previous slop window of
		// `-10` was undocumented — replaced with exact arithmetic (round-38 test-coverage audit P2).
		assertEquals("output size must equal source + 42 (IFD1 header) + thumbnail.length",
			seg.data().length + 42 + thumbnail.length, resultData.length);
		assertTrue("output should contain thumbnail sentinel bytes",
			indexOfSubsequence(resultData, thumbnail) >= 0);
	}

	@Test
	public void patchSplicesNewThumbnailWhenSourceHasExistingIfd1() throws IOException
	{
		// Source has IFD0 + IFD1 with an existing thumbnail. Passing a new thumbnail triggers
		// spliceExistingThumbnail; the output should contain the NEW thumbnail bytes, not the old.
		byte[] oldThumbnail = uniqueThumbnailBytes((byte) 0xAA, 60);
		byte[] newThumbnail = uniqueThumbnailBytes((byte) 0xBB, 80);
		JpegSegment seg = buildSegmentWithExistingThumbnail(oldThumbnail);

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200, newThumbnail);

		byte[] resultData = patched.get(0).data();
		assertTrue("output should contain NEW thumbnail bytes",
			indexOfSubsequence(resultData, newThumbnail) >= 0);
		assertFalse("output should NOT contain OLD thumbnail bytes",
			indexOfSubsequence(resultData, oldThumbnail) >= 0);
	}

	@Test
	public void patchFallsBackToAppendFreshWhenSpliceWouldShiftTrailingBytes() throws IOException
	{
		// Codex round-41 P2 regression. Build a segment with non-empty bytes AFTER the IFD1 thumbnail —
		// simulates a non-Samsung source where MakerNote value blocks / SubIFD value data / GPS offsets
		// live past the thumbnail. Then pass a new thumbnail of DIFFERENT length to force a size shift.
		// Pre-fix, spliceExistingThumbnail copied the trailing bytes verbatim into a shifted position
		// without updating any TIFF offsets that referenced them, corrupting the EXIF for any reader
		// that followed those offsets. The fix detects this case and bails (returns input data
		// unchanged), routing replaceThumbnail through appendFreshIfd1WithThumbnail which preserves
		// the original byte layout (old IFD1 + thumbnail + trailing bytes stay in place; new IFD1 +
		// new thumbnail get appended at the end). Verify all three layout invariants.
		byte[] oldThumbnail = uniqueThumbnailBytes((byte) 0xAA, 60);
		byte[] trailingSentinel = uniqueThumbnailBytes((byte) 0xCC, 24);
		byte[] newThumbnail = uniqueThumbnailBytes((byte) 0xBB, 80);
		JpegSegment seg = buildSegmentWithThumbnailAndTrailingBytes(oldThumbnail, trailingSentinel);
		int inputLen = seg.data().length;

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200, newThumbnail);

		byte[] resultData = patched.get(0).data();
		assertTrue("output should contain NEW thumbnail bytes (appended)",
			indexOfSubsequence(resultData, newThumbnail) >= 0);
		assertTrue("output should still contain OLD thumbnail bytes (orphaned, not destroyed)",
			indexOfSubsequence(resultData, oldThumbnail) >= 0);
		assertTrue("trailing sentinel bytes must survive unchanged in the orphaned region",
			indexOfSubsequence(resultData, trailingSentinel) >= 0);
		// Trailing sentinel offset preserved: pre-fix the splice would have shifted it by
		// (newThumbnail.length - oldThumbnail.length) = +20 bytes. With the fix, appendFresh leaves
		// the trailing bytes at their original absolute position.
		int trailingPreFixOffset = indexOfSubsequence(seg.data(), trailingSentinel);
		int trailingPostFixOffset = indexOfSubsequence(resultData, trailingSentinel);
		assertEquals("trailing bytes must NOT be shifted (offsets in EXIF would otherwise break)",
			trailingPreFixOffset, trailingPostFixOffset);
		assertTrue("output must grow vs input (appendFresh adds IFD1+thumbnail at end)",
			resultData.length > inputLen);
	}

	@Test
	public void patchStripsIfd1ThumbnailWhenStripSentinelPassed() throws IOException
	{
		// Codex round-31 F1 — passing ExifPatcher.STRIP_IFD1_THUMBNAIL (or any byte[0]) tells the patcher
		// to strip the source's IFD1 thumbnail by zeroing IFD0's next-IFD pointer. After the strip a
		// spec-compliant TIFF parser walking IFD0 → next-IFD-pointer → IFD1 sees pointer=0 and stops,
		// so the embedded thumbnail bytes are no longer reachable. The orphaned IFD1 + thumbnail bytes
		// remain in the segment buffer (soft strip — length-preserving).
		byte[] oldThumbnail = uniqueThumbnailBytes((byte) 0xAA, 60);
		JpegSegment seg = buildSegmentWithExistingThumbnail(oldThumbnail);
		int inputLen = seg.data().length;

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200,
			ExifPatcher.STRIP_IFD1_THUMBNAIL);

		byte[] resultData = patched.get(0).data();
		assertEquals("strip is length-preserving (next-IFD pointer zeroed in place)",
			inputLen, resultData.length);
		// Walk IFD0 → next-IFD pointer location and verify it's now 0. The fixture uses little-endian
		// TIFF and IFD0 starts at offset 18 (TIFF_HEADER_OFFSET=10 + 8-byte TIFF header), but
		// buildSegmentWithExistingThumbnail's exact layout doesn't matter — find the first non-zero
		// next-IFD pointer location by parsing IFD0's entry count.
		int tiffStart = 10;
		int ifd0Off = tiffStart + ((resultData[tiffStart + 4] & 0xFF)
			| ((resultData[tiffStart + 5] & 0xFF) << 8)
			| ((resultData[tiffStart + 6] & 0xFF) << 16)
			| ((resultData[tiffStart + 7] & 0xFF) << 24));
		int ifd0EntryCount = (resultData[ifd0Off] & 0xFF) | ((resultData[ifd0Off + 1] & 0xFF) << 8);
		int nextIfdPointerOff = ifd0Off + 2 + ifd0EntryCount * 12;
		int nextIfdPointer = (resultData[nextIfdPointerOff] & 0xFF)
			| ((resultData[nextIfdPointerOff + 1] & 0xFF) << 8)
			| ((resultData[nextIfdPointerOff + 2] & 0xFF) << 16)
			| ((resultData[nextIfdPointerOff + 3] & 0xFF) << 24);
		assertEquals("IFD0 next-IFD pointer should be zeroed after strip", 0, nextIfdPointer);
	}

	@Test
	public void patchStripIsNoOpWhenSourceHasNoIfd1() throws IOException
	{
		// Codex round-32 T3 — STRIP_IFD1_THUMBNAIL on a segment whose IFD0 already has nextIfdPointer=0
		// (no IFD1 to begin with) must be a length-preserving no-op. The strip helper writes 0 over
		// an already-0 pointer; segment data should be byte-identical to the input EXCEPT for the
		// dimension/orientation rewrites that scanIfd applies.
		byte[] ifd = buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0006 },   // Orientation=6 (will be rewritten to 1)
		});
		JpegSegment seg = wrapAsExifSegment(ifd);
		int inputLen = seg.data().length;

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200,
			ExifPatcher.STRIP_IFD1_THUMBNAIL);

		byte[] resultData = patched.get(0).data();
		assertEquals("strip-when-no-ifd1 is length-preserving", inputLen, resultData.length);
		// Verify orientation was still rewritten to 1 (the strip path must NOT skip scanIfd).
		int tiffStart = 10;
		int ifd0Off = tiffStart + ((resultData[tiffStart + 4] & 0xFF)
			| ((resultData[tiffStart + 5] & 0xFF) << 8)
			| ((resultData[tiffStart + 6] & 0xFF) << 16)
			| ((resultData[tiffStart + 7] & 0xFF) << 24));
		// Orientation entry at IFD0 + 2 + 0*12 = ifd0Off + 2; value field at +8 from entry start.
		int orientation = resultData[ifd0Off + 2 + 8] & 0xFF;
		assertEquals("strip path must still normalize orientation to 1", 1, orientation);
	}

	@Test
	public void patchStripStillRewritesIfd0Dimensions() throws IOException
	{
		// Codex round-32 T4 — STRIP_IFD1_THUMBNAIL must NOT bypass the IFD0 dimension rewrite. A
		// regression that early-returns from patch() after the strip (skipping scanIfd's
		// width/height/orientation updates) would ship saved files with the SOURCE's pre-crop
		// dimensions in EXIF — silently breaking the dimension contract on every cropped+strip-
		// fallback save.
		byte[] oldThumbnail = uniqueThumbnailBytes((byte) 0xAA, 60);
		JpegSegment seg = buildSegmentWithExistingThumbnail(oldThumbnail);

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200,
			ExifPatcher.STRIP_IFD1_THUMBNAIL);

		byte[] resultData = patched.get(0).data();
		// The fixture's IFD0 has Orientation as its only entry; after strip+scanIfd, scan IFD0 for
		// the rewritten orientation (which was 6 in the fixture, now 1).
		int tiffStart = 10;
		int ifd0Off = tiffStart + ((resultData[tiffStart + 4] & 0xFF)
			| ((resultData[tiffStart + 5] & 0xFF) << 8)
			| ((resultData[tiffStart + 6] & 0xFF) << 16)
			| ((resultData[tiffStart + 7] & 0xFF) << 24));
		int orientation = resultData[ifd0Off + 2 + 8] & 0xFF;
		assertEquals("strip path must still normalize orientation to 1", 1, orientation);
		// Also verify the strip did its job — next-IFD pointer at end of IFD0 entries should be 0.
		int ifd0EntryCount = (resultData[ifd0Off] & 0xFF) | ((resultData[ifd0Off + 1] & 0xFF) << 8);
		int nextIfdPointerOff = ifd0Off + 2 + ifd0EntryCount * 12;
		int nextIfdPointer = (resultData[nextIfdPointerOff] & 0xFF)
			| ((resultData[nextIfdPointerOff + 1] & 0xFF) << 8)
			| ((resultData[nextIfdPointerOff + 2] & 0xFF) << 16)
			| ((resultData[nextIfdPointerOff + 3] & 0xFF) << 24);
		assertEquals("IFD0 next-IFD pointer should be zeroed after strip", 0, nextIfdPointer);
	}

	@Test
	public void patchRewritesAllFourDimensionTagsAcrossIfd0AndExifSubIfd() throws IOException
	{
		// Round-34 test-coverage P0: every prior dimension-rewrite test only assertion-checked
		// Orientation, leaving scanIfd's IMAGE_WIDTH / IMAGE_LENGTH / PIXEL_X_DIMENSION /
		// PIXEL_Y_DIMENSION branches and writeValue's TYPE_SHORT vs TYPE_LONG dispatch entirely
		// unverified. A regression that swapped newW ↔ newH, dropped the SubIFD recursion, or
		// mis-ordered SHORT vs LONG writes would ship without surfacing. Build a TIFF with all
		// four dimension tags split between IFD0 (IMAGE_WIDTH=LONG, IMAGE_LENGTH=SHORT) and
		// ExifSubIFD (PIXEL_X_DIMENSION=LONG, PIXEL_Y_DIMENSION=SHORT) so both branches of the
		// type dispatcher fire on both axes, then read all four back to confirm 100/200 landed
		// in the right slots.
		int subIfdOffset = 8 + 54; // IFD0 = 2 + 4*12 + 4 = 54 bytes; SubIFD starts right after
		byte[] ifd0 = buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0006 },         // Orientation = 6 SHORT
			{ 0x0100, 4, 1, 4096 },            // IMAGE_WIDTH = 4096 LONG
			{ 0x0101, 3, 1, 3072 },            // IMAGE_LENGTH = 3072 SHORT
			{ 0x8769, 4, 1, subIfdOffset },    // ExifSubIFD pointer LONG
		});
		byte[] subIfd = buildIfd(new int[][] {
			{ 0xA002, 4, 1, 4096 },            // PIXEL_X_DIMENSION = 4096 LONG
			{ 0xA003, 3, 1, 3072 },            // PIXEL_Y_DIMENSION = 3072 SHORT
		});
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8); // IFD0 offset
		tiff.write(ifd0);
		tiff.write(subIfd);
		JpegSegment exifSeg = wrapTiffAsExifSegment(tiff.toByteArray());

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(exifSeg), 100, 200, null);

		// IFD0 starts at TIFF_HEADER_OFFSET + 8. Each entry is 12 bytes: tag(2) + type(2) +
		// count(4) + value(4); the value field sits at entryStart + 8.
		byte[] data = patched.get(0).data();
		int ifd0Off = TIFF_HEADER_OFFSET + 8;
		int orientationValueOff = ifd0Off + 2 + 8;
		int imageWidthValueOff = ifd0Off + 2 + 12 + 8;
		int imageLengthValueOff = ifd0Off + 2 + 24 + 8;
		assertEquals("Orientation must be normalised to 1",
			1, ByteBufferUtils.readU16(data, orientationValueOff, true));
		assertEquals("IMAGE_WIDTH (LONG) must be rewritten to newW",
			100L, ByteBufferUtils.readU32(data, imageWidthValueOff, true));
		assertEquals("IMAGE_LENGTH (SHORT) must be rewritten to newH",
			200, ByteBufferUtils.readU16(data, imageLengthValueOff, true));

		// ExifSubIFD starts at TIFF_HEADER_OFFSET + subIfdOffset.
		int subIfdAbsOff = TIFF_HEADER_OFFSET + subIfdOffset;
		int pixelXValueOff = subIfdAbsOff + 2 + 8;
		int pixelYValueOff = subIfdAbsOff + 2 + 12 + 8;
		assertEquals("PIXEL_X_DIMENSION (LONG) must be rewritten to newW",
			100L, ByteBufferUtils.readU32(data, pixelXValueOff, true));
		assertEquals("PIXEL_Y_DIMENSION (SHORT) must be rewritten to newH",
			200, ByteBufferUtils.readU16(data, pixelYValueOff, true));
	}

	@Test
	public void patchSynthesizesFreshExifSegmentWhenSourceHasNone() throws IOException
	{
		// Round-36 user-reported P0 (continued): when the source has NO EXIF segment at all
		// (screenshots, generated bitmaps, files re-encoded by minimal tools that strip metadata),
		// `patch` previously iterated the non-EXIF segments verbatim and the freshly-generated
		// thumbnail was silently dropped on the floor. The fix synthesises a minimal EXIF segment
		// with IFD0 (orientation=1, ImageWidth=newW, ImageLength=newH) + IFD1 (compression,
		// JPEGInterchangeFormat, JPEGInterchangeFormatLength) + thumbnail bytes when no source EXIF
		// is found AND a real thumbnail is requested. Empty-list input is the canonical screenshot
		// case (state.getJpegMeta() returned an empty list); pin the synthesise path with that
		// input and verify the result contains exactly one EXIF segment carrying the fresh bytes.
		byte[] freshThumb = uniqueThumbnailBytes((byte) 0x55, 500);
		List<JpegSegment> patched = ExifPatcher.patch(Collections.emptyList(), 1024, 768, freshThumb);

		assertEquals("synthesised result must carry exactly one segment", 1, patched.size());
		JpegSegment synthesized = patched.get(0);
		assertTrue("synthesised segment must be EXIF (APP1 + Exif identifier)", synthesized.isExif());
		assertTrue("synthesised segment must contain the fresh 0x55 thumbnail bytes",
			indexOfSubsequence(synthesized.data(), freshThumb) >= 0);
		// IFD0 of the synthesised segment must declare ImageWidth = 1024, ImageLength = 768. IFD0
		// starts at TIFF_HEADER_OFFSET + 8; entry layout per slot is tag(2)+type(2)+count(4)+value(4)
		// at slot offset 0/+12/+24 within the entry block. We declared 3 entries in this order:
		// Orientation, ImageWidth, ImageLength; ImageWidth/Length values are LONG so we read u32.
		byte[] data = synthesized.data();
		int ifd0Off = TIFF_HEADER_OFFSET + 8;
		int imageWidthValueOff = ifd0Off + 2 + 12 + 8;
		int imageLengthValueOff = ifd0Off + 2 + 24 + 8;
		assertEquals("synthesised IFD0 ImageWidth must equal newW",
			1024L, ByteBufferUtils.readU32(data, imageWidthValueOff, true));
		assertEquals("synthesised IFD0 ImageLength must equal newH",
			768L, ByteBufferUtils.readU32(data, imageLengthValueOff, true));
	}

	@Test
	public void synthesizedExifSegmentRoundtripsThroughJpegMetadataInjector() throws IOException
	{
		// End-to-end check of the round-36 save-without-pre-computed-thumbnail flow: empty source
		// meta + a fresh thumbnail must produce an output JPEG containing the synthesised EXIF
		// segment with the new thumbnail bytes after `JpegMetadataInjector.inject` runs. Mirrors what
		// `CropExporter.injectExifMetadata` does on a source whose `state.getJpegMeta()` was empty
		// (screenshots, generated images). Without this integration test the patch-level
		// `patchSynthesizesFreshExifSegmentWhenSourceHasNone` could pass while the inject step
		// silently drops the synthesised segment (e.g., a marker mis-detection on the byte layout).
		byte[] freshThumb = uniqueThumbnailBytes((byte) 0x55, 1024);
		List<JpegSegment> patched = ExifPatcher.patch(Collections.emptyList(), 800, 600, freshThumb);
		assertEquals(1, patched.size());

		byte[] reencoded = JpegFixtures.concat(
			JpegFixtures.soi(),
			JpegFixtures.appSegment(0xE0, new byte[] { 'J', 'F', 'I', 'F', 0 }),
			new byte[] { (byte) 0xFF, (byte) 0xDB, 0x00, 0x04, 0x00, 0x00 },
			JpegFixtures.minimalScanAndEoi());

		byte[] result = JpegMetadataInjector.inject(reencoded, patched);

		// Output must start with SOI then immediately (or near-immediately) carry the FF E1 APP1
		// EXIF marker. JpegMetadataInjector writes segments verbatim after SOI, so the synthesized
		// segment's first two bytes (FF E1) should land at result[2..3].
		assertEquals("byte 0 must be FF (SOI start)", (byte) 0xFF, result[0]);
		assertEquals("byte 1 must be D8 (SOI marker)", (byte) 0xD8, result[1]);
		assertEquals("byte 2 must be FF (start of APP1 EXIF marker)", (byte) 0xFF, result[2]);
		assertEquals("byte 3 must be E1 (APP1 EXIF marker)", (byte) 0xE1, result[3]);

		// The Exif identifier should land at result[6..9] (after FF E1 + segLen).
		assertEquals('E', result[6]);
		assertEquals('x', result[7]);
		assertEquals('i', result[8]);
		assertEquals('f', result[9]);

		// The fresh thumbnail bytes must appear verbatim inside the output.
		assertTrue("output must contain the fresh 0x55 thumbnail bytes",
			indexOfSubsequence(result, freshThumb) >= 0);
	}

	@Test
	public void patchSynthesizesFreshExifWhenSourceHasOnlyNonExifSegments() throws IOException
	{
		// Real-world shape of a source-with-no-pre-computed-thumbnail: a JPEG that carries APP0 JFIF
		// (and maybe APP2 ICC / APP1 XMP) but no APP1 EXIF. Without the round-36 synthesize path,
		// patch would iterate the JFIF segment verbatim, the loop would never call replaceThumbnail
		// (since there's no EXIF segment to patch), and the freshly-generated thumbnail would be
		// silently dropped. This test pins that the result list now contains BOTH the original JFIF
		// AND a synthesized EXIF carrying the fresh thumbnail bytes.
		byte[] jfifPayload = new byte[] { 'J', 'F', 'I', 'F', 0, 0x01, 0x02, 0x01, 0x00 };
		byte[] jfifBytes = JpegFixtures.appSegment(0xE0, jfifPayload);
		JpegSegment jfif = new JpegSegment(0xE0, jfifBytes);
		byte[] freshThumb = uniqueThumbnailBytes((byte) 0x55, 500);

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(jfif), 800, 600, freshThumb);

		assertEquals("result must contain both original JFIF and synthesised EXIF", 2, patched.size());
		assertTrue("first segment must be the synthesised EXIF (prepended)", patched.get(0).isExif());
		assertEquals("second segment must be the original JFIF unchanged",
			0xE0, patched.get(1).marker());
		assertTrue("synthesised EXIF must contain the fresh 0x55 thumbnail bytes",
			indexOfSubsequence(patched.get(0).data(), freshThumb) >= 0);
	}

	@Test
	public void patchDoesNotSynthesizeExifWhenThumbnailIsNullOrEmpty()
	{
		// Synthesise only fires for a real fresh thumbnail. Null thumbnail (preserve sentinel) and
		// byte[0] STRIP_IFD1_THUMBNAIL must both leave an empty input empty — otherwise the round-36
		// synthesise path would create a phantom EXIF segment carrying a zero-length thumbnail or
		// pollute metadata-only round-trips (where the caller passed null specifically to mean
		// "don't touch IFD1").
		assertEquals("null thumbnail must not synthesise EXIF on empty input",
			0, ExifPatcher.patch(Collections.emptyList(), 100, 200, null).size());
		assertEquals("STRIP_IFD1_THUMBNAIL must not synthesise EXIF on empty input",
			0, ExifPatcher.patch(Collections.emptyList(), 100, 200,
				ExifPatcher.STRIP_IFD1_THUMBNAIL).size());
	}

	@Test
	public void patchAppendsFreshIfd1WhenSourceIfd1LacksThumbnailTags() throws IOException
	{
		// Round-36 user-reported P0: source has IFD1 carrying only non-thumbnail entries (e.g.
		// Compression but no JPEGInterchangeFormat / JPEGInterchangeFormatLength — typical of
		// minimal-EXIF encoders). The round-35 behaviour was to STRIP IFD0's next-IFD pointer
		// because `findThumbnailTags` returns null → `spliceExistingThumbnail` returns the same
		// `data` reference → `replaceThumbnail` saw `rebuilt == data` and routed to strip. The
		// user reported "no new thumbnail when source has no pre-computed thumbnail"; that's
		// exactly this shape — the saved file ended up with no IFD1 instead of a fresh one.
		// Round-36 routes splice-reject through `appendFreshIfd1WithThumbnail` first: a new IFD1
		// is appended at end-of-segment and IFD0's next-IFD pointer is redirected to it, so the
		// saved file carries the user's fresh thumbnail rather than nothing.
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8); // IFD0 offset = 8
		// IFD0: 1 entry (Orientation), next-IFD = 26.
		tiff.write(buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0006 },
		}));
		// Overwrite IFD0's next-IFD pointer (last 4 bytes of the just-written IFD) so it points
		// to IFD1 at offset 26 instead of the buildIfd default of 0.
		byte[] tiffSoFar = tiff.toByteArray();
		tiff.reset();
		tiff.write(tiffSoFar, 0, tiffSoFar.length - 4);
		writeU32Le(tiff, 26);
		// IFD1 with ONLY Compression — no JPEGInterchangeFormat / JPEGInterchangeFormatLength.
		tiff.write(buildIfd(new int[][] {
			{ 0x0103, 3, 1, 6 },
		}));
		JpegSegment seg = wrapTiffAsExifSegment(tiff.toByteArray());

		byte[] freshThumb = uniqueThumbnailBytes((byte) 0x55, 500);
		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200, freshThumb);

		byte[] resultData = patched.get(0).data();
		int ifd0Off = TIFF_HEADER_OFFSET + 8;
		int ifd0EntryCount = ByteBufferUtils.readU16(resultData, ifd0Off, true);
		int nextIfdPointerOff = ifd0Off + 2 + ifd0EntryCount * 12;
		long nextIfdPointer = ByteBufferUtils.readU32(resultData, nextIfdPointerOff, true);
		assertNotEquals("IFD0's next-IFD pointer must point at a fresh IFD1 carrying the new thumbnail",
			0L, nextIfdPointer);
		assertTrue("fresh 0x55 thumbnail bytes must appear in the rebuilt segment",
			indexOfSubsequence(resultData, freshThumb) >= 0);
	}

	@Test
	public void patchAppendsFreshIfd1WhenSourceIfd1ThumbOffsetHasSignBitSet() throws IOException
	{
		// Round-35 test-coverage P1: `findThumbnailTags`' u32 sign-bit-set rejection
		// (`off > Integer.MAX_VALUE` at ExifPatcher.java:519) is documented as load-bearing —
		// without it, a u32 ≥ 0x80000000 in the JPEGInterchangeFormat value field would
		// sign-extend to a negative int and slip past spliceExistingThumbnail's bounds check via
		// `tiffStart + (negative) = small-positive`. The rejection mechanism still fires the same
		// way under round-36; only the recovery outcome changed: instead of stripping, the
		// rebuilder now routes through `appendFreshIfd1WithThumbnail` so the adversarial source
		// IFD1 orphans (unreachable through IFD0's repointed next-IFD pointer) while a fresh
		// IFD1 carrying the user's new thumbnail becomes the active one. Same security property
		// (no source-bytes leak), better functionality (fresh thumb appears). Mirrors the
		// existing `patchIgnoresSubIfdOffsetExceedingIntMax` test but for IFD1.
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8); // IFD0 offset
		// IFD0: 1 entry (Orientation), next-IFD = 26.
		byte[] ifd0 = buildIfd(new int[][] { { 0x0112, 3, 1, 0x0006 } });
		tiff.write(ifd0, 0, ifd0.length - 4);
		writeU32Le(tiff, 26);
		// IFD1: JPEGInterchangeFormat = 0xFFFFFFFF (u32 max — must reject after sign extension),
		// JPEGInterchangeFormatLength = 100 (legal). next-IFD = 0.
		tiff.write(buildIfd(new int[][] {
			{ 0x0201, 4, 1, 0xFFFFFFFF },
			{ 0x0202, 4, 1, 100 },
		}));
		JpegSegment seg = wrapTiffAsExifSegment(tiff.toByteArray());

		byte[] freshThumb = uniqueThumbnailBytes((byte) 0x55, 500);
		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200, freshThumb);

		byte[] resultData = patched.get(0).data();
		int ifd0Off = TIFF_HEADER_OFFSET + 8;
		int ifd0EntryCount = ByteBufferUtils.readU16(resultData, ifd0Off, true);
		int nextIfdPointerOff = ifd0Off + 2 + ifd0EntryCount * 12;
		long nextIfdPointer = ByteBufferUtils.readU32(resultData, nextIfdPointerOff, true);
		assertNotEquals("IFD0's next-IFD pointer must point at a fresh IFD1 (sign-bit-set source orphaned)",
			0L, nextIfdPointer);
		assertTrue("fresh 0x55 thumbnail bytes must appear in the rebuilt segment",
			indexOfSubsequence(resultData, freshThumb) >= 0);
	}

	@Test
	public void patchAppendsFreshIfd1WhenSpliceLongArithmeticGuardCatchesOverflowingOldThumbLen()
		throws IOException
	{
		// Round-35 test-coverage P1: `spliceExistingThumbnail`'s long-arithmetic guard at
		// ExifPatcher.java:405 (`(long) absOldOff + oldThumbLen > data.length`) is documented as
		// load-bearing but was never adversarially exercised. `findThumbnailTags` clamps
		// `oldThumbLen` to `[0, Integer.MAX_VALUE]`, so a value like 0x7FFFFF00 passes the
		// per-tag rejection but, summed with a small `absOldOff`, produces a long-sum that
		// vastly exceeds `data.length`. The guard's early return is still required (sign-bit
		// arithmetic would otherwise bypass bounds and OOM/AIOOBE inside splice's arraycopy).
		// Round-36 changes only the recovery outcome: the splice reject now routes through
		// `appendFreshIfd1WithThumbnail`, so the saved IFD0's next-IFD pointer redirects to a
		// fresh IFD1 carrying the user's new thumbnail rather than zeroing out entirely.
		// The adversarial source IFD1 (with its overflowing JPEGInterchangeFormatLength) becomes
		// orphan bytes — unreachable through the spec parse chain (same security as strip).
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8); // IFD0 offset
		byte[] ifd0 = buildIfd(new int[][] { { 0x0112, 3, 1, 0x0006 } });
		tiff.write(ifd0, 0, ifd0.length - 4);
		writeU32Le(tiff, 26);
		// IFD1: JPEGInterchangeFormat = 68 (just past the end of the IFD layout), but
		// JPEGInterchangeFormatLength = 0x7FFFFF00 (Integer.MAX_VALUE - 255 — passes the
		// findThumbnailTags clamp, blows the spliceExistingThumbnail bounds check).
		tiff.write(buildIfd(new int[][] {
			{ 0x0201, 4, 1, 68 },
			{ 0x0202, 4, 1, 0x7FFFFF00 },
		}));
		JpegSegment seg = wrapTiffAsExifSegment(tiff.toByteArray());

		byte[] freshThumb = uniqueThumbnailBytes((byte) 0x55, 500);
		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200, freshThumb);

		byte[] resultData = patched.get(0).data();
		int ifd0Off = TIFF_HEADER_OFFSET + 8;
		int ifd0EntryCount = ByteBufferUtils.readU16(resultData, ifd0Off, true);
		int nextIfdPointerOff = ifd0Off + 2 + ifd0EntryCount * 12;
		long nextIfdPointer = ByteBufferUtils.readU32(resultData, nextIfdPointerOff, true);
		assertNotEquals("IFD0 next-IFD must point at a fresh IFD1 (overflowing-length source orphaned)",
			0L, nextIfdPointer);
		assertTrue("fresh 0x55 thumbnail bytes must appear in the rebuilt segment",
			indexOfSubsequence(resultData, freshThumb) >= 0);
	}

	@Test
	public void patchRejectsThumbnailExceedingApp1Cap() throws IOException
	{
		// A thumbnail so large it would push the rebuilt segment past 65535 bytes triggers the
		// "newSegLen > APP1_MAX_SEGMENT_BYTES" guard inside `spliceExistingThumbnail`. Pre round-34
		// logic-audit P0 the splice rejection silently returned the cloned source data, so the saved
		// segment carried the SOURCE's pre-edit IFD1 thumbnail — exactly the leak class the round-30 /
		// round-31 F1 fixes addressed for the `null` thumbnail API but missed for "non-null thumbnail
		// + splice rejected". The fix routes splice rejection through `stripIfd1Thumbnail`: the
		// rebuilt segment stays the same size (orientation rewrite is in-place; the strip zeros IFD0's
		// next-IFD pointer in place too) but the source thumbnail is now unreachable through the spec
		// parse chain, so EXIF-aware viewers render no preview rather than the wrong one. The orphan
		// thumbnail bytes are still byte-present in the buffer (soft strip) — that's documented and
		// asserted below so a future hard-strip change reads as intentional.
		byte[] oldThumbnail = uniqueThumbnailBytes((byte) 0xAA, 60);
		// Need a thumbnail size that pushes (78-byte segment overhead + thumbnail) past APP1's 65537-byte
		// total-segment cap to trigger the rejection. 65500 leaves the rebuilt segment at 65578 bytes which is
		// past 65537.
		byte[] hugeThumbnail = uniqueThumbnailBytes((byte) 0xCC, 65500);
		JpegSegment seg = buildSegmentWithExistingThumbnail(oldThumbnail);
		int inputLen = seg.data().length;

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200, hugeThumbnail);

		byte[] resultData = patched.get(0).data();
		// Splice rejected → result is the same size as input (orientation rewrite happens in-place but no
		// resize). A successful splice would have grown the segment by ~65000 bytes.
		assertEquals("rejected splice should leave segment size unchanged", inputLen, resultData.length);
		// Round-34 logic-audit P0: rejected splice MUST strip IFD0's next-IFD pointer instead of
		// silently leaving IFD1 reachable with the source's pre-edit thumbnail. IFD0 next-IFD pointer
		// sits at TIFF_HEADER_OFFSET + 8 (IFD0 start) + 2 (count) + 12 (one entry).
		int nextIfdPointerOff = TIFF_HEADER_OFFSET + 8 + 2 + 12;
		long nextIfdPointer = ByteBufferUtils.readU32(resultData, nextIfdPointerOff, true);
		assertEquals("strip-on-reject must zero IFD0's next-IFD pointer (round-34 P0)", 0L, nextIfdPointer);
		// Source thumbnail bytes orphan-remain in the segment — soft strip leaves them present but
		// unreachable through the IFD0 → next-IFD → IFD1 parse chain. Pinned so a future hard-strip
		// change is read as intentional.
		assertTrue("soft-strip leaves orphan thumbnail bytes byte-present in segment",
			indexOfSubsequence(resultData, oldThumbnail) >= 0);
	}

	@Test
	public void patchProcessesEachExifSegmentWhenInputHasMultiple() throws IOException
	{
		// Pathological input: two EXIF segments (illegal per JPEG spec but possible from broken sources). Both
		// should be processed identically — the loop doesn't short-circuit after the first.
		byte[] ifd1 = buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0006 },
		});
		byte[] ifd2 = buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0008 },   // different orientation value
		});
		JpegSegment seg1 = wrapAsExifSegment(ifd1);
		JpegSegment seg2 = wrapAsExifSegment(ifd2);

		List<JpegSegment> patched = ExifPatcher.patch(Arrays.asList(seg1, seg2), 100, 200, null);

		assertEquals(2, patched.size());
		// Both got their orientation rewritten to 1. Single-entry IFD0: orientation value sits at
		// TIFF_HEADER_OFFSET + 8 (IFD0 start) + 2 (count) + 8 (skip tag/type/count of single entry).
		int orientationOff = TIFF_HEADER_OFFSET + 8 + 2 + 8;
		assertEquals(1, patched.get(0).data()[orientationOff] & 0xFF);
		assertEquals(1, patched.get(1).data()[orientationOff] & 0xFF);
	}

	@Test
	public void patchLeavesNonExifSegmentsUnchanged() throws IOException
	{
		// Non-EXIF segment passes through ExifPatcher.patch unchanged. Pinned because the patcher's main loop
		// has an early-continue for !seg.isExif() and a regression that skipped the early-continue could clone
		// every segment.
		byte[] xmpData = JpegFixtures.appSegment(0xE1, "http://ns.adobe.com/xap/1.0/\0body".getBytes());
		JpegSegment xmpSeg = new JpegSegment(0xE1, xmpData);

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(xmpSeg), 100, 200, null);

		assertEquals(1, patched.size());
		// Same instance reference — no clone for non-EXIF.
		assertSame("non-EXIF segment should pass through by reference, not be cloned",
			xmpSeg.data(), patched.get(0).data());
	}

	/**
	 * Build an EXIF segment with IFD0 + an existing IFD1 carrying a thumbnail. IFD1 has three tags: 0x0103
	 * (Compression=6=JPEG), 0x0201 (JPEGInterchangeFormat = thumbnail offset), 0x0202 (JPEGInterchangeFormatLength
	 * = thumbnail byte count). The thumbnail bytes sit immediately after the IFD1 header.
	 */
	/**
	 * Like buildSegmentWithExistingThumbnail but with an extra non-empty trailing byte block AFTER the
	 * thumbnail in the TIFF payload. Simulates non-Samsung EXIF layouts where MakerNote / SubIFD value
	 * blocks live past the IFD1 thumbnail. Used by the round-41 P2 splice-shift regression test.
	 */
	private static JpegSegment buildSegmentWithThumbnailAndTrailingBytes(byte[] thumbnail, byte[] trailing)
		throws IOException
	{
		// Identical TIFF structure to buildSegmentWithExistingThumbnail, with `trailing` appended after
		// the thumbnail bytes. The trailing bytes aren't referenced from any IFD entry — they just
		// occupy space, simulating value-block data that an offset elsewhere in the EXIF would point at.
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8);

		writeU16Le(tiff, 1);
		writeU16Le(tiff, 0x0112);
		writeU16Le(tiff, 3);
		writeU32Le(tiff, 1);
		writeU16Le(tiff, 6);
		writeU16Le(tiff, 0);
		writeU32Le(tiff, 26);

		int thumbDataOff = 26 + 42;
		writeU16Le(tiff, 3);
		writeU16Le(tiff, 0x0103);
		writeU16Le(tiff, 3);
		writeU32Le(tiff, 1);
		writeU16Le(tiff, 6);
		writeU16Le(tiff, 0);
		writeU16Le(tiff, 0x0201);
		writeU16Le(tiff, 4);
		writeU32Le(tiff, 1);
		writeU32Le(tiff, thumbDataOff);
		writeU16Le(tiff, 0x0202);
		writeU16Le(tiff, 4);
		writeU32Le(tiff, 1);
		writeU32Le(tiff, thumbnail.length);
		writeU32Le(tiff, 0);

		tiff.write(thumbnail);
		tiff.write(trailing);

		return wrapTiffAsExifSegment(tiff.toByteArray());
	}

	private static JpegSegment buildSegmentWithExistingThumbnail(byte[] thumbnail) throws IOException
	{
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8);   // IFD0 offset = 8

		// IFD0: count=1, one Orientation entry, next-IFD pointer points to IFD1. IFD0 layout: 2 (count) + 12 (1
		// entry) + 4 (next-IFD ptr) = 18 bytes. IFD0 starts at TIFF offset 8, ends at offset 26. IFD1 = offset
		// 26.
		writeU16Le(tiff, 1);                  // entry count
		writeU16Le(tiff, 0x0112);             // Orientation
		writeU16Le(tiff, 3);                  // SHORT
		writeU32Le(tiff, 1);                  // count
		writeU16Le(tiff, 6);                  // value = 6
		writeU16Le(tiff, 0);                  // padding
		writeU32Le(tiff, 26);                 // next-IFD pointer → IFD1 at offset 26

		// IFD1: count=3, three entries (Compression, JPEGInterchangeFormat, JPEGInterchangeFormatLength),
		// next-IFD = 0. Layout: 2 + 36 + 4 = 42 bytes. Thumbnail data follows at IFD1 + 42 = offset 68.
		int thumbDataOff = 26 + 42;
		writeU16Le(tiff, 3);                  // entry count
		writeU16Le(tiff, 0x0103);             // Compression
		writeU16Le(tiff, 3);                  // SHORT
		writeU32Le(tiff, 1);                  // count
		writeU16Le(tiff, 6);                  // value = 6 (JPEG)
		writeU16Le(tiff, 0);                  // padding
		writeU16Le(tiff, 0x0201);             // JPEGInterchangeFormat
		writeU16Le(tiff, 4);                  // LONG
		writeU32Le(tiff, 1);                  // count
		writeU32Le(tiff, thumbDataOff);       // value = thumbnail offset
		writeU16Le(tiff, 0x0202);             // JPEGInterchangeFormatLength
		writeU16Le(tiff, 4);                  // LONG
		writeU32Le(tiff, 1);                  // count
		writeU32Le(tiff, thumbnail.length);   // value = thumbnail byte count
		writeU32Le(tiff, 0);                  // next-IFD pointer = 0

		// Thumbnail data follows.
		tiff.write(thumbnail);

		return wrapTiffAsExifSegment(tiff.toByteArray());
	}

	/**
	 * Search `haystack` for the first occurrence of `needle`. Returns -1 if absent. Used to verify thumbnail
	 * provenance — the thumbnail bytes are unique sentinel patterns that only one side of the test should plant.
	 */
	private static int indexOfSubsequence(byte[] haystack, byte[] needle)
	{
		if (needle.length == 0 || haystack.length < needle.length)
		{
			return -1;
		}
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++)
		{
			for (int j = 0; j < needle.length; j++)
			{
				if (haystack[i + j] != needle[j])
				{
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	/**
	 * Build a thumbnail-shaped byte buffer of the requested length filled with the sentinel byte. Used to plant a
	 * provenance-detectable pattern in test thumbnails so containsSubsequence can verify which side contributed the
	 * bytes that ended up in the output.
	 */
	private static byte[] uniqueThumbnailBytes(byte sentinel, int length)
	{
		byte[] out = new byte[length];
		Arrays.fill(out, sentinel);
		return out;
	}

	/**
	 * Build an IFD body: 2-byte little-endian entry count + N * 12-byte entries + 4-byte next-IFD pointer (set to
	 * 0). Each entry is {tag, type, count, value}.
	 */
	private static byte[] buildIfd(int[][] entries) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeU16Le(out, entries.length);
		for (int[] entry : entries)
		{
			writeU16Le(out, entry[0]);
			writeU16Le(out, entry[1]);
			writeU32Le(out, entry[2]);
			writeU32Le(out, entry[3]);
		}
		writeU32Le(out, 0); // next-IFD pointer
		return out.toByteArray();
	}

	/**
	 * Wrap an IFD body as a complete EXIF JpegSegment: FF E1 + length + "Exif\0\0" + TIFF header (II*\0 + IFD0
	 * offset = 8) + IFD body. Caller's IFD body sits at TIFF + 8 (i.e. payload offset TIFF_HEADER_OFFSET + 8).
	 */
	private static JpegSegment wrapAsExifSegment(byte[] ifd) throws IOException
	{
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8); // IFD0 offset
		tiff.write(ifd);
		return wrapTiffAsExifSegment(tiff.toByteArray());
	}

	/**
	 * Wrap an arbitrary TIFF region as an EXIF JpegSegment. Used for the multi-IFD chain test where the TIFF body
	 * holds several IFDs.
	 */
	private static JpegSegment wrapTiffAsExifSegment(byte[] tiff) throws IOException
	{
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write('E');
		payload.write('x');
		payload.write('i');
		payload.write('f');
		payload.write(0);
		payload.write(0);
		payload.write(tiff);
		byte[] segData = JpegFixtures.appSegment(0xE1, payload.toByteArray());
		return new JpegSegment(0xE1, segData);
	}

	/**
	 * Like wrapAsExifSegment but lets the caller pick the 2-byte TIFF byte-order marker. Used for
	 * byte-order-rejection tests; pass ('I','M') or ('M','I') to exercise the validation branch.
	 */
	private static JpegSegment wrapAsExifSegmentWithByteOrder(byte[] ifd, byte high, byte low) throws IOException
	{
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write(high);
		tiff.write(low);
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8); // IFD0 offset
		tiff.write(ifd);
		return wrapTiffAsExifSegment(tiff.toByteArray());
	}

	private static void writeU16Be(ByteArrayOutputStream out, int value)
	{
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	private static void writeU16Le(ByteArrayOutputStream out, int value)
	{
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
	}

	private static void writeU32Be(ByteArrayOutputStream out, long value)
	{
		out.write((int) ((value >> 24) & 0xFF));
		out.write((int) ((value >> 16) & 0xFF));
		out.write((int) ((value >> 8) & 0xFF));
		out.write((int) (value & 0xFF));
	}

	private static void writeU32Le(ByteArrayOutputStream out, long value)
	{
		out.write((int) (value & 0xFF));
		out.write((int) ((value >> 8) & 0xFF));
		out.write((int) ((value >> 16) & 0xFF));
		out.write((int) ((value >> 24) & 0xFF));
	}
}
