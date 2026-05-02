package com.cropcenter.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Tests for the cycle-and-overflow guards in ExifPatcher.scanIfd. Two adversarial EXIF shapes pin down the recursion
 * bound (cyclic SubIFD pointer) and the long-arithmetic cast guard (SubIFD offset that overflows int when added to
 * tiffStart). Both bypass- the-guard regressions would corrupt the EXIF segment by writing orientation = 1 over
 * arbitrary bytes — the safest crash mode is a benign no-op, which is what the guards deliver.
 */
public class ExifPatcherTest
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
		assertTrue("output should be larger than input (IFD1 + thumbnail appended)",
			resultData.length > seg.data().length + thumbnail.length - 10);
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
	public void patchRejectsThumbnailExceedingApp1Cap() throws IOException
	{
		// A thumbnail so large it would push the rebuilt segment past 65535 bytes triggers the "newSegLen >
		// APP1_MAX_SEGMENT_BYTES" guard, returning the segment unchanged in size. Caller is expected to retry
		// with a smaller thumbnail.
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
		// Old thumbnail bytes are still present at the original offset.
		assertTrue("old thumbnail should remain since splice was rejected",
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

		List<JpegSegment> patched = ExifPatcher.patch(java.util.Arrays.asList(seg1, seg2), 100, 200, null);

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
		java.util.Arrays.fill(out, sentinel);
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

	private static void writeU16Le(ByteArrayOutputStream out, int value)
	{
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
	}

	private static void writeU32Le(ByteArrayOutputStream out, long value)
	{
		out.write((int) (value & 0xFF));
		out.write((int) ((value >> 8) & 0xFF));
		out.write((int) ((value >> 16) & 0xFF));
		out.write((int) ((value >> 24) & 0xFF));
	}
}
