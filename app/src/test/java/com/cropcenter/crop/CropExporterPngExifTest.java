package com.cropcenter.crop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;

import com.cropcenter.metadata.ExifPatcher;
import com.cropcenter.metadata.TiffFixtures;
import com.cropcenter.util.ByteBufferUtils;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

/**
 * Tests for the package-private `CropExporter.patchPngExifTiff` helper that wraps PNG eXIf raw TIFF bytes for
 * `ExifPatcher.patch`. The PNG eXIf chunk has a u31 length field so the source TIFF can be many megabytes;
 * `ExifPatcher.spliceExistingThumbnail` still enforces the JPEG APP1 u16 cap (65535) on the rebuilt segment, so a
 * too-large rebuild would silently reject the splice and leave the SOURCE's IFD1 thumbnail in place — leaking pre-edit
 * content via the embedded preview.
 *
 * A naive `tiff.length + thumbnail.length` sum would force-strip splices that actually shrink the segment (because the
 * old thumbnail is removed before the new one is inserted). `ExifPatcher.maxThumbnailBytes` subtracts the old
 * thumbnail's bytes from the segment size before measuring remaining APP1 room. These tests pin both branches: splice
 * when the rebuilt segment fits, strip when it genuinely does not.
 */
public final class CropExporterPngExifTest
{
	@Test
	public void forceTiffOrientationToUprightOverwritesNonUprightTag() throws IOException
	{
		// buildTiffWithThumbnail emits IFD0 orientation=6 (Rotate 90 CW). The verbatim PNG-eXIf path for >64 KB
		// TIFFs ships source TIFF bytes as-is, so without this force-upright pass the saved PNG would carry
		// orientation=6 onto already-upright pixels (BitmapUtils.applyOrientation rotated them at load) — and
		// EXIF-aware viewers would double-rotate. Verify the patcher returns a NEW byte[] (not the input ref)
		// with orientation overwritten to 1 in IFD0.
		byte[] tiff = buildTiffWithThumbnail(100);
		// Pre-condition: source TIFF carries orientation 6 at IFD0's value slot (entry index 0 in IFD0). IFD0
		// starts at offset 8; entry count at 8; first entry starts at 10. Value field at entry + 8.
		int preValue = ByteBufferUtils.readU16(tiff, 10 + 8, true);
		assertEquals("test fixture must seed orientation=6", 6, preValue);
		byte[] patched = CropExporter.forceTiffOrientationToUpright(tiff);
		assertNotSame("non-upright orientation must produce a NEW byte[] (not in-place mutation of input)",
			tiff, patched);
		int postValue = ByteBufferUtils.readU16(patched, 10 + 8, true);
		assertEquals("post-patch IFD0 orientation must be 1 (upright)", 1, postValue);
	}

	@Test
	public void forceTiffOrientationToUprightPatchesBigEndianTiff() throws IOException
	{
		// Big-endian ("MM") branch: every other fixture is little-endian or malformed-magic, so a regression
		// hardcoding isLittleEndian=true would silently return MM TIFFs unchanged — shipping the source
		// orientation tag onto already-upright pixels and double-rotating in every EXIF-aware viewer, on a
		// spec-legal, camera-common eXIf shape. Build a minimal MM TIFF with IFD0 orientation=6 and assert
		// the patcher walks it big-endian and rewrites the value to 1 in a fresh array.
		byte[] tiff = TiffFixtures.orientationTiff(false, 6);
		// Pre-condition: IFD0's first entry value slot (entry at TIFF offset 10, value at +8) reads 6 in
		// big-endian order.
		assertEquals("test fixture must seed orientation=6 big-endian", 6,
			ByteBufferUtils.readU16(tiff, 10 + 8, false));
		byte[] patched = CropExporter.forceTiffOrientationToUpright(tiff);
		assertNotSame("non-upright MM TIFF must produce a NEW byte[] (not in-place mutation)", tiff, patched);
		assertEquals("post-patch IFD0 orientation must be 1 read big-endian", 1,
			ByteBufferUtils.readU16(patched, 10 + 8, false));
		assertEquals("input TIFF must be left unmodified", 6, ByteBufferUtils.readU16(tiff, 10 + 8, false));
	}

	@Test
	public void forceTiffOrientationToUprightReturnsInputWhenAlreadyUpright() throws IOException
	{
		// When orientation is already 1, the patcher returns the input reference unchanged so callers don't pay
		// for a defensive copy. Build a TIFF, manually overwrite IFD0's orientation value to 1, then assert the
		// patcher returns the same reference.
		byte[] tiff = buildTiffWithThumbnail(100);
		ByteBufferUtils.writeU16(tiff, 10 + 8, 1, true);
		byte[] result = CropExporter.forceTiffOrientationToUpright(tiff);
		assertSame("already-upright TIFF must return the input ref (no allocation)", tiff, result);
	}

	@Test
	public void forceTiffOrientationToUprightReturnsInputWhenMagicMalformed()
	{
		// Malformed byte order header → patcher can't walk IFD0, must return input ref unchanged. The
		// verbatim-preserve contract documents this fallback: metadata survives even when orientation can't be
		// force-corrected (the pixels themselves are still upright on disk).
		byte[] malformed = new byte[] { 'X', 'Y', 0, 0, 8, 0, 0, 0, 0, 0 };
		byte[] result = CropExporter.forceTiffOrientationToUpright(malformed);
		assertSame("malformed magic must return input ref unchanged", malformed, result);
	}

	@Test
	public void patchPngExifTiffSplicesWhenOldThumbnailRemovalFreesEnoughBudget() throws IOException
	{
		// 50 KB old thumbnail + 30 KB fresh thumbnail. Naive sum (50,068 + 30,000 + 8 = 80,076) exceeds the
		// APP1 cap, but the splice removes the 50 KB old thumbnail before inserting the 30 KB fresh one, so the
		// rebuilt segment is roughly 30 KB + IFD overhead — comfortably under 65,535. The A naive-sum guard
		// would force-strip this case; routing through ExifPatcher.maxThumbnailBytes correctly accounts for
		// old-thumbnail removal. Pin splice-not-strip behaviour: IFD0's next-IFD pointer must remain non-zero
		// (still pointing at the rebuilt IFD1 carrying the new thumbnail).
		byte[] tiff = buildTiffWithThumbnail(50_000);
		byte[] freshThumb = new byte[30_000];
		Arrays.fill(freshThumb, (byte) 0x55);

		byte[] patched = CropExporter.patchPngExifTiff(tiff, 1000, 2000, freshThumb)
			.orElseThrow(() -> new AssertionError("patch should succeed (returns the modified TIFF)"));

		long nextIfdPointer = readIfd0NextPointer(patched);
		assertNotEquals("Splice must preserve IFD0's next-IFD pointer (rebuilt IFD1 carries new thumbnail)",
			0L, nextIfdPointer);

		// Pin content provenance — without this, a regression where the splice silently writes the source 0xAA
		// bytes (or no-ops with source's IFD1 intact) still passes the non-zero-pointer check. Walk the rebuilt
		// IFD1, read JPEGInterchangeFormat, and assert the bytes are the fresh 0x55 sentinel rather than the
		// source 0xAA pattern.
		int ifd1Off = (int) nextIfdPointer; // TIFF-relative; patched bytes are unwrapped TIFF
		int ifd1EntryCount = ByteBufferUtils.readU16(patched, ifd1Off, true);
		int thumbOff = -1;
		int thumbLen = -1;
		for (int i = 0; i < ifd1EntryCount; i++)
		{
			int entryOff = ifd1Off + 2 + i * 12;
			int tag = ByteBufferUtils.readU16(patched, entryOff, true);
			if (tag == 0x0201)
			{
				thumbOff = (int) ByteBufferUtils.readU32(patched, entryOff + 8, true);
			}
			else if (tag == 0x0202)
			{
				thumbLen = (int) ByteBufferUtils.readU32(patched, entryOff + 8, true);
			}
		}
		assertEquals("rebuilt IFD1 must record the fresh thumbnail's exact length",
			freshThumb.length, thumbLen);
		assertEquals("first thumbnail byte must be the fresh 0x55 sentinel, not the source 0xAA",
			0x55, patched[thumbOff] & 0xFF);
		assertEquals("last thumbnail byte must be the fresh 0x55 sentinel, not the source 0xAA",
			0x55, patched[thumbOff + thumbLen - 1] & 0xFF);
	}

	@Test
	public void patchPngExifTiffStripsWhenRebuiltSegmentStillExceedsApp1Cap() throws IOException
	{
		// Same TIFF shape, but a fresh thumbnail bigger than the post-old-removal APP1 budget. With the 50 KB
		// old thumbnail removed, the available budget is ≈ 65,535 − (50,078 − 50,000) = 65,457 B; a 65,500 B
		// fresh thumbnail still won't fit, so spliceExistingThumbnail's internal cap check would silently
		// reject and leave the source's IFD1 thumbnail in place. The guard must instead route through
		// STRIP_IFD1_THUMBNAIL: IFD0's next-IFD pointer is zeroed so no IFD1 is reachable through the spec
		// parse chain.
		byte[] tiff = buildTiffWithThumbnail(50_000);
		byte[] freshThumb = new byte[65_500];
		Arrays.fill(freshThumb, (byte) 0x55);

		byte[] patched = CropExporter.patchPngExifTiff(tiff, 1000, 2000, freshThumb)
			.orElseThrow(() -> new AssertionError("patch should succeed (returns the modified TIFF)"));

		long nextIfdPointer = readIfd0NextPointer(patched);
		assertEquals("STRIP must zero IFD0's next-IFD pointer when rebuilt segment exceeds APP1 cap",
			0L, nextIfdPointer);
	}

	private static byte[] buildTiffWithThumbnail(int thumbnailSize) throws IOException
	{
		// TiffFixtures' IFD0 -> IFD1 layout (thumbnail at offset 68) with a 0xAA-filled thumbnail;
		// thumbnailSize is the only knob.
		byte[] thumb = new byte[thumbnailSize];
		Arrays.fill(thumb, (byte) 0xAA);
		return TiffFixtures.tiffWithThumbnail(thumb, thumbnailSize, new byte[0]);
	}

	private static long readIfd0NextPointer(byte[] patched)
	{
		// Read IFD0's next-IFD pointer from the patched TIFF. The TIFF starts at offset 0 in the returned bytes
		// (no wrapper) — `patchPngExifTiff` unwraps the synthetic APP1 it built. IFD0 offset is at TIFF bytes
		// 4..7; entry count at IFD0; next-IFD pointer at IFD0 + 2 + count*12.
		int ifd0Off = (int) ByteBufferUtils.readU32(patched, 4, true);
		int ifd0EntryCount = ByteBufferUtils.readU16(patched, ifd0Off, true);
		int nextIfdPointerOff = ifd0Off + 2 + ifd0EntryCount * 12;
		return ByteBufferUtils.readU32(patched, nextIfdPointerOff, true);
	}

}
