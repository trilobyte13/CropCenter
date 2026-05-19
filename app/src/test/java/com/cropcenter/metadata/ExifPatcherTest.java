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
	public void buildMinimalExifSegmentAcceptsThumbnailAtExactApp1Cap() throws IOException
	{
		// Direct test of buildMinimalExifSegment with an at-cap thumbnail — the synthesise path is
		// otherwise exercised only via patch() with small inputs, and an off-by-one regression in the
		// cap math would slip. Layout: 2 bytes FF E1 marker +
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
		// Pin the synthesised segment's IFD0 layout (3 entries: Orientation, ImageWidth, ImageLength) and
		// IFD1 thumbnail pointer/length tags so a regression that reorders entries or mis-sizes IFD0
		// surfaces here rather than via downstream EXIF-aware viewer wrong-rendering. The segment is
		// little-endian per the documented format.
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
	public void hasIfd1ThumbnailReturnsFalseForSourcesWithoutPreComputedThumbnail() throws IOException
	{
		// Three "no thumbnail" shapes that have surfaced bugs. All must return false so canBypassEncode
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
	public void hasIfd1ThumbnailReturnsTrueForSourceWithExistingIfd1Thumbnail() throws IOException
	{
		// `ExportPipeline.canBypassEncode` uses `hasIfd1Thumbnail` to gate the verbatim-write bypass:
		// when the source already has a thumbnail, bypass is allowed; when it doesn't, force re-encode
		// so `CropExporter` can synthesise one. Pin the happy path here.
		byte[] oldThumb = uniqueThumbnailBytes((byte) 0xAA, 80);
		JpegSegment seg = buildSegmentWithExistingThumbnail(oldThumb);
		assertTrue("source carrying IFD1 with valid thumb tags must report hasIfd1Thumbnail = true",
			ExifPatcher.hasIfd1Thumbnail(Collections.singletonList(seg)));
	}

	@Test
	public void maxThumbnailBytesFallsBackToDefaultWhenIfd0OffsetWrapsIntCast() throws IOException
	{
		// Pin the long-arithmetic-first fix: a u32 ifd0Rel near Integer.MAX_VALUE - TIFF_HEADER_OFFSET
		// must be caught by the long-sum bounds check, not by an int-cast that wraps to a small positive
		// offset and slips through. maxThumbnailBytes is on the strip-vs-splice critical path, so the
		// bypass would route adversarial PNG eXIf inputs to a doomed splice instead of force-stripping.
		// 0x80000000 + 16 sums to a positive long > Integer.MAX_VALUE but the int cast wraps to 26.
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 0x80000010L); // adversarial IFD0 offset
		// Pad past offset 26 so a bounds check on the int-wrapped value (ifd0 + 2 > data.length)
		// wouldn't catch it either — only the long-arithmetic guard catches it.
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
	public void maxThumbnailBytesReturnsBaselineForValidByteOrder() throws IOException
	{
		// Baseline: a valid IFD0 with no IFD1 returns
		//   JpegSegment.MAX_SEGMENT_BYTES - (data.length + the 42-byte IFD1-overhead estimate).
		// Pinning down a specific value here lets the byte-order-rejection test below
		// distinguish "rejected, returned DEFAULT" from "parsed clean, returned the computed budget".
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
	public void patchAppendsFreshIfd1OnRealSamsungGalaxyS25UltraExif() throws IOException
	{
		// Real-world Samsung Galaxy S25 Ultra EXIF segment (dropped into resources/samsung-exif.bin):
		// the synthetic 16-entry IFD0 test below passes but the real fixture revealed a missing IFD1
		// thumbnail in saved output. Run patch with a real thumbnail and assert the output has an
		// appended IFD1 + thumb.
		byte[] exifBytes;
		try (InputStream stream = ExifPatcherTest.class.getClassLoader()
			.getResourceAsStream("samsung-exif.bin"))
		{
			assertNotNull("samsung-exif.bin fixture must be present in test resources", stream);
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			int read;
			while ((read = stream.read(chunk)) > 0)
			{
				buffer.write(chunk, 0, read);
			}
			exifBytes = buffer.toByteArray();
		}
		JpegSegment seg = new JpegSegment(0xE1, exifBytes);
		assertTrue("source fixture must be recognised as EXIF", seg.isExif());

		byte[] freshThumb = uniqueThumbnailBytes((byte) 0x55, 5000);
		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 1880, 2350, freshThumb);

		byte[] resultData = patched.get(0).data();
		// Exact size: appendFreshIfd1WithThumbnail extends the segment by 42 (fresh IFD1 header) +
		// thumbnail.length. A `>= source + 4000` slop would hide a regression that drops or duplicates
		// up to ~1000 bytes of MakerNote.
		assertEquals("patched EXIF must equal source + 42 (IFD1 header) + thumbnail.length",
			exifBytes.length + 42 + freshThumb.length, resultData.length);
		assertTrue("fresh thumbnail bytes must appear in the patched segment",
			indexOfSubsequence(resultData, freshThumb) >= 0);
		// Pin the JPEG segLen field too — without this check, a regression that plants bytes in the
		// buffer but fails to update FF E1 LL LL would slip past the prior assertions.
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
		// Mimic the Samsung Galaxy S25 Ultra source shape — big-endian MM byte order, 16-entry IFD0
		// with JPEGInterchangeFormat in IFD0, IFD0's next-IFD pointer = 0, no IFD1. All-LE +
		// 1-entry-IFD0 unit tests miss the failure mode appendFreshIfd1WithThumbnail had on this
		// fixture (saved segLen still 1280, IFD1 silently dropped).
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
		//    slop hid regressions that dropped up to 1KB of payload.
		assertEquals("appendFresh must extend the segment by exactly 42 (IFD1 header) + thumbnail.length",
			seg.data().length + 42 + freshThumb.length, resultData.length);
		// 2. JPEG segLen header must reflect the new length (FF E1 LL LL is big-endian per JPEG spec,
		//    regardless of TIFF byte order). Without this assertion, a regression that grew the buffer
		//    but left segLen stale would slip past the size check above.
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
	public void patchAppendsFreshIfd1WhenSourceIfd1LacksThumbnailTags() throws IOException
	{
		// Source has IFD1 carrying only non-thumbnail entries (Compression but no
		// JPEGInterchangeFormat / JPEGInterchangeFormatLength — typical of minimal-EXIF encoders). A
		// strip-on-reject behaviour zeroes IFD0's next-IFD pointer because findThumbnailTags returns
		// null → spliceExistingThumbnail returns the same data reference → replaceThumbnail sees
		// `rebuilt == data` and routes to strip. Splice-reject must instead route through
		// appendFreshIfd1WithThumbnail so the saved file carries the fresh thumbnail.
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
		// `findThumbnailTags`' u32 sign-bit-set rejection (`off > Integer.MAX_VALUE`) is load-bearing —
		// without it, a u32 ≥ 0x80000000 in the JPEGInterchangeFormat value field would sign-extend to
		// a negative int and slip past spliceExistingThumbnail's bounds check via
		// `tiffStart + (negative) = small-positive`. The recovery path routes through
		// `appendFreshIfd1WithThumbnail`, so the adversarial source IFD1 orphans (unreachable through
		// IFD0's repointed next-IFD pointer) while a fresh IFD1 carrying the user's new thumbnail
		// becomes the active one. Same security property (no source-bytes leak), better functionality
		// (fresh thumb appears). Mirrors the existing `patchIgnoresSubIfdOffsetExceedingIntMax` test
		// but for IFD1.
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
		// Adversarial exercise of spliceExistingThumbnail's long-arithmetic guard
		// (`(long) absOldOff + oldThumbLen > data.length`). findThumbnailTags clamps oldThumbLen to
		// [0, Integer.MAX_VALUE], so a value like 0x7FFFFF00 passes per-tag rejection but, summed with
		// a small absOldOff, overflows data.length — without the guard splice's arraycopy hits AIOOBE.
		// Splice reject routes through appendFreshIfd1WithThumbnail: IFD0's next-IFD pointer redirects
		// at a fresh IFD1 carrying the user's new thumbnail (the adversarial source IFD1 becomes
		// orphan bytes — unreachable through the spec parse chain).
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
		// `-10` was undocumented — replaced with exact arithmetic.
		assertEquals("output size must equal source + 42 (IFD1 header) + thumbnail.length",
			seg.data().length + 42 + thumbnail.length, resultData.length);
		assertTrue("output should contain thumbnail sentinel bytes",
			indexOfSubsequence(resultData, thumbnail) >= 0);
	}

	@Test
	public void patchDoesNotSynthesizeExifWhenThumbnailIsNullOrEmpty()
	{
		// Synthesise only fires for a real fresh thumbnail. Null thumbnail (preserve sentinel) and
		// byte[0] STRIP_IFD1_THUMBNAIL must both leave an empty input empty — otherwise the synthesise
		// path would create a phantom EXIF segment carrying a zero-length thumbnail or pollute
		// metadata-only round-trips (where the caller passed null specifically to mean "don't touch
		// IFD1").
		assertEquals("null thumbnail must not synthesise EXIF on empty input",
			0, ExifPatcher.patch(Collections.emptyList(), 100, 200, null).size());
		assertEquals("STRIP_IFD1_THUMBNAIL must not synthesise EXIF on empty input",
			0, ExifPatcher.patch(Collections.emptyList(), 100, 200,
				ExifPatcher.STRIP_IFD1_THUMBNAIL).size());
	}

	@Test
	public void patchFallsBackToAppendFreshWhenSpliceWouldShiftTrailingBytes() throws IOException
	{
		// Splice-shift regression. Build a segment with non-empty bytes AFTER the IFD1 thumbnail —
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
		// Trailing sentinel offset preserved: a splice would shift it by
		// (newThumbnail.length - oldThumbnail.length) = +20 bytes. appendFresh leaves trailing bytes
		// at their original absolute position.
		int trailingPreFixOffset = indexOfSubsequence(seg.data(), trailingSentinel);
		int trailingPostFixOffset = indexOfSubsequence(resultData, trailingSentinel);
		assertEquals("trailing bytes must NOT be shifted (offsets in EXIF would otherwise break)",
			trailingPreFixOffset, trailingPostFixOffset);
		assertTrue("output must grow vs input (appendFresh adds IFD1+thumbnail at end)",
			resultData.length > inputLen);
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
		//   IFD0 at offset 8, IFD1' at offset 8 + 30 = 38, IFD2' at 68, ..., IFD5' at 158.
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

	@Test
	public void patchNeutralisesIfd0ThumbnailPointerTags() throws IOException
	{
		// Tags 0x0103 (Compression), 0x0201 (JPEGInterchangeFormat), and 0x0202
		// (JPEGInterchangeFormatLength) are IFD1-only per the EXIF spec. Some EXIF round-trips
		// (Samsung Gallery, earlier CropCenter) leak them into IFD0 carrying stale offsets that
		// point at the source's pre-edit thumbnail (or worse, at byte ranges outside the export's
		// EXIF segment entirely). Strict EXIF parsers walk IFD0 first and follow the stale pointer,
		// extracting garbage. Regression for that case: scanIfd at depth=0 must zero the entire
		// 12-byte entry for each of these tags in IFD0 so parsers see unknown tag 0x0000 and skip.
		// IFD1's real fresh thumbnail pointers (written by buildFreshIfd1Header /
		// spliceExistingThumbnail) are unaffected.
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8);
		byte[] ifd0 = buildIfd(new int[][] {
			{ 0x0112, 3, 1, 0x0006 },                                 // Orientation (legitimate)
			{ TiffTag.COMPRESSION, 3, 1, 0x0006 },                    // hoisted Compression
			{ TiffTag.JPEG_INTERCHANGE_FORMAT, 4, 1, 9999 },          // stale pointer
			{ TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH, 4, 1, 12345 },  // stale length
		});
		tiff.write(ifd0);
		JpegSegment seg = wrapTiffAsExifSegment(tiff.toByteArray());

		List<JpegSegment> patched = ExifPatcher.patch(Collections.singletonList(seg), 100, 200, null);

		// Segment layout: FF E1 (2) + segLen (2) + "Exif\0\0" (6) + TIFF body. TIFF starts at offset
		// TIFF_HEADER_OFFSET (10). Inside TIFF: II*\0 (4) + IFD0 offset = 8 (4). IFD0 starts at TIFF+8,
		// so IFD0 in segment-relative bytes starts at TIFF_HEADER_OFFSET + 8 = 18.
		byte[] resultData = patched.get(0).data();
		int ifd0Off = TIFF_HEADER_OFFSET + 8;
		// Entry 0 = Orientation (legitimate IFD0 tag; preserved as value=1, scanIfd sets
		// orientation=1 on patch).
		assertEquals("Orientation entry preserved at tag 0x0112",
			0x0112, ByteBufferUtils.readU16(resultData, ifd0Off + 2, true));
		// Entries 1-3 = the hoisted IFD1 tags. Each entry's full 12 bytes must be zeroed.
		for (int entryIdx = 1; entryIdx <= 3; entryIdx++)
		{
			int entryStart = ifd0Off + 2 + entryIdx * 12;
			for (int b = 0; b < 12; b++)
			{
				assertEquals("entry " + entryIdx + " byte " + b + " must be zeroed in IFD0",
					0, resultData[entryStart + b] & 0xFF);
			}
		}
	}

	@Test
	public void patchPassesThroughEmptyList()
	{
		// Empty input → empty output, no NPE on the for-each. The audit flagged this as untested.
		List<JpegSegment> patched = ExifPatcher.patch(Collections.emptyList(), 100, 200, null);
		assertEquals(0, patched.size());
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
	public void patchRejectsThumbnailExceedingApp1Cap() throws IOException
	{
		// A thumbnail so large it would push the rebuilt segment past 65535 bytes triggers the
		// "newSegLen > JpegSegment.MAX_SEGMENT_BYTES" guard inside `spliceExistingThumbnail`. A
		// silently-return-the-cloned-source-data behaviour would leave the saved segment carrying the
		// SOURCE's pre-edit IFD1 thumbnail — exactly the leak class addressed elsewhere for the `null`
		// thumbnail API but missed for "non-null thumbnail + splice rejected". Routing splice rejection
		// through `stripIfd1Thumbnail`: the rebuilt segment stays the same size (orientation rewrite is
		// in-place; the strip zeros IFD0's next-IFD pointer in place too) but the source thumbnail is
		// now unreachable through the spec parse chain, so EXIF-aware viewers render no preview rather
		// than the wrong one. The orphan thumbnail bytes are still byte-present in the buffer (soft
		// strip) — documented and asserted below so a future hard-strip change reads as intentional.
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
		// Rejected splice MUST strip IFD0's next-IFD pointer instead of silently leaving IFD1 reachable
		// with the source's pre-edit thumbnail. IFD0 next-IFD pointer sits at TIFF_HEADER_OFFSET + 8
		// (IFD0 start) + 2 (count) + 12 (one entry).
		int nextIfdPointerOff = TIFF_HEADER_OFFSET + 8 + 2 + 12;
		long nextIfdPointer = ByteBufferUtils.readU32(resultData, nextIfdPointerOff, true);
		assertEquals("strip-on-reject must zero IFD0's next-IFD pointer", 0L, nextIfdPointer);
		// Source thumbnail bytes orphan-remain in the segment — soft strip leaves them present but
		// unreachable through the IFD0 → next-IFD → IFD1 parse chain. Pinned so a future hard-strip
		// change is read as intentional.
		assertTrue("soft-strip leaves orphan thumbnail bytes byte-present in segment",
			indexOfSubsequence(resultData, oldThumbnail) >= 0);
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
	public void patchRewritesAllFourDimensionTagsAcrossIfd0AndExifSubIfd() throws IOException
	{
		// All four dimension tags split across IFD0 (IMAGE_WIDTH=LONG, IMAGE_LENGTH=SHORT) and
		// ExifSubIFD (PIXEL_X_DIMENSION=LONG, PIXEL_Y_DIMENSION=SHORT) so both branches of writeValue's
		// TYPE_SHORT vs TYPE_LONG dispatch fire on both axes. Catches regressions that swap newW ↔ newH,
		// drop the SubIFD recursion, or mis-order SHORT vs LONG writes (orientation-only assertions
		// leave those code paths unverified).
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
	public void patchStripIsNoOpWhenSourceHasNoIfd1() throws IOException
	{
		// STRIP_IFD1_THUMBNAIL on a segment whose IFD0 already has nextIfdPointer=0
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
		// STRIP_IFD1_THUMBNAIL must NOT bypass the IFD0 dimension rewrite. A regression that early-returns
		// from patch() after the strip (skipping scanIfd's width/height/orientation updates) would ship saved
		// files with the SOURCE's pre-crop dimensions in EXIF — silently breaking the dimension contract on
		// every cropped+strip-fallback save.
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
	public void patchStripsIfd1ThumbnailWhenStripSentinelPassed() throws IOException
	{
		// Passing ExifPatcher.STRIP_IFD1_THUMBNAIL (or any byte[0]) tells the patcher
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
	public void patchSynthesizesFreshExifSegmentWhenSourceHasNone() throws IOException
	{
		// Source has NO EXIF segment at all (screenshots, generated bitmaps, files re-encoded by tools
		// that strip metadata). patch must synthesise a minimal EXIF segment with IFD0 (orientation=1,
		// ImageWidth=newW, ImageLength=newH) + IFD1 (compression, JPEGInterchangeFormat,
		// JPEGInterchangeFormatLength) + thumbnail bytes — otherwise the freshly-generated thumbnail
		// is silently dropped. Empty-list input is the canonical screenshot case
		// (state.getJpegMeta() returned an empty list).
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
	public void patchSynthesizesFreshExifWhenSourceHasOnlyNonExifSegments() throws IOException
	{
		// Source with no pre-computed thumbnail: a JPEG that carries APP0 JFIF (and maybe APP2 ICC /
		// APP1 XMP) but no APP1 EXIF. Without the synthesize-fresh-EXIF path, patch would iterate the
		// JFIF segment verbatim, replaceThumbnail would never fire, and the fresh thumbnail would be
		// silently dropped. Result list must contain BOTH the original JFIF AND a synthesised EXIF
		// carrying the fresh thumbnail bytes.
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
	public void patchedNonThumbBytesPredictionMatchesActualAppendOutput() throws IOException
	{
		// Prediction-vs-reality contract for the append path. Source has IFD0 + no IFD1 →
		// patch() routes through appendFreshIfd1WithThumbnail which adds a 42-byte fresh IFD1
		// header + new thumbnail bytes. The exact-budget caller in CropExporter relies on the
		// prediction staying in sync with patch's actual write — a 42-byte drift here would
		// overflow the APP1 segment on at-cap thumbnails.
		byte[] ifd0Only = buildIfd(new int[][] { { 0x0112, 3, 1, 0x0001 } });
		JpegSegment seg = wrapAsExifSegment(ifd0Only);
		int predicted = ExifPatcher.patchedNonThumbBytes(Collections.singletonList(seg));
		assertEquals("append-path prediction must equal source.length + 42 (fresh IFD1 header)",
			seg.data().length + 42, predicted);

		byte[] newThumb = uniqueThumbnailBytes((byte) 0xCC, 200);
		List<JpegSegment> patched = ExifPatcher.patch(
			Collections.singletonList(seg), 100, 200, newThumb);
		assertEquals("actual patched segment size must equal prediction + newThumb.length "
			+ "(no estimation drift)",
			predicted + newThumb.length, patched.get(0).data().length);
	}

	@Test
	public void patchedNonThumbBytesPredictionMatchesActualSpliceOutput() throws IOException
	{
		// Prediction-vs-reality contract for the splice path. Source has a valid IFD1 thumbnail;
		// patch swaps the old bytes for new ones in-place. Output size = source.length minus the
		// old thumbnail bytes plus the new thumbnail bytes. Pin the prediction-vs-actual delta
		// here so a future refactor of spliceExistingThumbnail can't silently drift the
		// non-thumb byte count.
		byte[] oldThumb = uniqueThumbnailBytes((byte) 0xAA, 80);
		JpegSegment seg = buildSegmentWithExistingThumbnail(oldThumb);
		int predicted = ExifPatcher.patchedNonThumbBytes(Collections.singletonList(seg));
		assertEquals("splice-path prediction must equal source.length - oldThumbLen",
			seg.data().length - oldThumb.length, predicted);

		byte[] newThumb = uniqueThumbnailBytes((byte) 0xCC, 200);
		List<JpegSegment> patched = ExifPatcher.patch(
			Collections.singletonList(seg), 100, 200, newThumb);
		assertEquals("actual patched segment size must equal prediction + newThumb.length "
			+ "(no estimation drift)",
			predicted + newThumb.length, patched.get(0).data().length);
	}

	@Test
	public void patchedNonThumbBytesPredictsAppendForBigEndianSourceWithoutIfd1() throws IOException
	{
		// Big-endian (MM) byte-order coverage — the bulk of Samsung Galaxy sources are MM, so a
		// byte-order-conditional bug in the predictor would silently miss the common case. Build
		// a minimal MM TIFF with IFD0 + no IFD1 and verify the append-path prediction parses
		// correctly across endianness.
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('M');
		tiff.write('M');
		tiff.write(0);
		tiff.write(42);
		writeU32Be(tiff, 8);
		writeU16Be(tiff, 1);        // 1 entry
		writeU16Be(tiff, 0x0112);   // Orientation
		writeU16Be(tiff, 3);        // SHORT
		writeU32Be(tiff, 1);        // count
		writeU16Be(tiff, 1);        // value = 1
		writeU16Be(tiff, 0);        // padding
		writeU32Be(tiff, 0);        // next-IFD = 0 (no IFD1)
		JpegSegment seg = wrapTiffAsExifSegment(tiff.toByteArray());
		int predicted = ExifPatcher.patchedNonThumbBytes(Collections.singletonList(seg));
		assertEquals("big-endian source with no IFD1 → append estimate",
			seg.data().length + 42, predicted);
	}

	@Test
	public void patchedNonThumbBytesPredictsAppendForSourceWithIfd1ButNoThumbnailTags() throws IOException
	{
		// Minimal-EXIF encoder shape: IFD1 exists but carries only resolution metadata
		// (Compression tag here), no JPEGInterchangeFormat / JPEGInterchangeFormatLength. patch()
		// rejects splice (findThumbnailTags returns null) and falls through to
		// appendFreshIfd1WithThumbnail. Predictor must match that fallback's overhead.
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8);
		byte[] ifd0 = buildIfd(new int[][] { { 0x0112, 3, 1, 0x0001 } });
		// Strip the default next-IFD = 0 from buildIfd's output and rewrite to point at IFD1.
		tiff.write(ifd0, 0, ifd0.length - 4);
		writeU32Le(tiff, 26);
		tiff.write(buildIfd(new int[][] { { 0x0103, 3, 1, 6 } }));
		JpegSegment seg = wrapTiffAsExifSegment(tiff.toByteArray());
		int predicted = ExifPatcher.patchedNonThumbBytes(Collections.singletonList(seg));
		assertEquals("IFD1 missing JPEGInterchangeFormat/Length tags → append estimate",
			seg.data().length + 42, predicted);
	}

	@Test
	public void patchedNonThumbBytesPredictsAppendForSourceWithIfd1ThumbOffsetZero() throws IOException
	{
		// IFD1 has both thumbnail tags present but JPEGInterchangeFormat offset = 0, i.e.
		// "thumbnail tag declared but no thumbnail bytes recorded." findThumbnailTags rejects
		// (offset==0 triggers null return), so patch falls through to append. Pin that the
		// predictor also returns the append estimate rather than the splice estimate (which
		// would incorrectly subtract 0 bytes and report data.length).
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 8);
		// IFD0: 1 entry + next-IFD pointer at TIFF offset 26.
		writeU16Le(tiff, 1);
		writeU16Le(tiff, 0x0112);
		writeU16Le(tiff, 3);
		writeU32Le(tiff, 1);
		writeU16Le(tiff, 6);
		writeU16Le(tiff, 0);
		writeU32Le(tiff, 26);
		// IFD1: JPEGInterchangeFormat with offset=0, JPEGInterchangeFormatLength.
		writeU16Le(tiff, 2);
		writeU16Le(tiff, 0x0201);
		writeU16Le(tiff, 4);
		writeU32Le(tiff, 1);
		writeU32Le(tiff, 0);            // offset = 0 → no thumbnail
		writeU16Le(tiff, 0x0202);
		writeU16Le(tiff, 4);
		writeU32Le(tiff, 1);
		writeU32Le(tiff, 0);
		writeU32Le(tiff, 0);            // next-IFD = 0
		JpegSegment seg = wrapTiffAsExifSegment(tiff.toByteArray());
		int predicted = ExifPatcher.patchedNonThumbBytes(Collections.singletonList(seg));
		assertEquals("IFD1 thumb-offset zero → append estimate (splice rejects)",
			seg.data().length + 42, predicted);
	}

	@Test
	public void patchedNonThumbBytesReturnsAppendEstimateOnOutOfBoundsIfd0Offset() throws IOException
	{
		// Adversarial IFD0 offset that the long-arithmetic guard catches (mirrors the
		// maxThumbnailBytes regression test above). When the IFD0 walk fails this way,
		// replaceThumbnail's own bounds check also fires and falls through to
		// stripIfd1Thumbnail (no thumb embedded). Predictor returns the append estimate as a
		// conservative non-overestimate: the actual patch either lands on append (if IFD0 walk
		// succeeds) or strip (if not), and the append estimate never reports MORE budget than
		// the actual patch can hold.
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		tiff.write('*');
		tiff.write(0);
		writeU32Le(tiff, 0x80000010L);  // u32 that wraps to small-positive int post-cast
		for (int i = 0; i < 100; i++)
		{
			tiff.write(0);
		}
		JpegSegment seg = wrapTiffAsExifSegment(tiff.toByteArray());
		int predicted = ExifPatcher.patchedNonThumbBytes(Collections.singletonList(seg));
		assertEquals("out-of-bounds IFD0 offset → append estimate (data.length + 42)",
			seg.data().length + 42, predicted);
	}

	@Test
	public void patchedNonThumbBytesReturnsSynthesizeEstimateForEmptySegments()
	{
		// Empty segment list → patch's synthesise fallback fires (foundExif stays false,
		// buildMinimalExifSegment runs). The synthesise layout is FF E1 marker (2) + segLen
		// field (2) + "Exif\0\0" (6) + TIFF II*\0 + IFD0 offset (8) + IFD0 (42) + IFD1 (42)
		// = 102 bytes of non-thumb overhead.
		assertEquals("empty segment list → synthesise estimate",
			102, ExifPatcher.patchedNonThumbBytes(Collections.emptyList()));
	}

	@Test
	public void patchedNonThumbBytesReturnsSynthesizeEstimateForMalformedByteOrder() throws IOException
	{
		// "IM" byte-order rejection: both bytes must match ("II" or "MM"). Falls through
		// (continue) to the end of the loop and returns the synthesise estimate when no
		// parseable EXIF was found. In reality patch preserves the malformed segment without
		// adding a thumbnail; the predicted budget is only relevant to whether
		// CropExporter bothers running the thumbnail encode at all (bounded compute, not a
		// correctness issue).
		byte[] ifd = buildIfd(new int[][] { { 0x0112, 3, 1, 0x0001 } });
		JpegSegment seg = wrapAsExifSegmentWithByteOrder(ifd, (byte) 'I', (byte) 'M');
		assertEquals("IM byte-order → synthesise estimate (no parseable EXIF found)",
			102, ExifPatcher.patchedNonThumbBytes(Collections.singletonList(seg)));
	}

	@Test
	public void patchedNonThumbBytesReturnsSynthesizeEstimateForNullSegments()
	{
		// Defensive contract: caller may pass null (CropState.getJpegMeta returns an
		// unmodifiableList which is never null today, but the public method should still
		// tolerate it). Matches hasIfd1Thumbnail's null-handling shape.
		assertEquals("null segments → synthesise estimate",
			102, ExifPatcher.patchedNonThumbBytes(null));
	}

	@Test
	public void synthesizedExifSegmentRoundtripsThroughJpegMetadataInjector() throws IOException
	{
		// End-to-end check of the save-without-pre-computed-thumbnail flow: empty source
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
	 * Like buildSegmentWithExistingThumbnail but with an extra non-empty trailing byte block AFTER the
	 * thumbnail in the TIFF payload. Simulates non-Samsung EXIF layouts where MakerNote / SubIFD value
	 * blocks live past the IFD1 thumbnail. Used by the splice-shift regression test.
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
