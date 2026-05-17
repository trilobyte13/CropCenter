package com.cropcenter.crop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import com.cropcenter.metadata.ExifPatcher;
import com.cropcenter.util.ByteBufferUtils;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Tests for the package-private `CropExporter.patchPngExifTiff` helper that wraps PNG eXIf raw TIFF bytes for
 * `ExifPatcher.patch`. The PNG eXIf chunk has a u31 length field so the source TIFF can be many megabytes;
 * `ExifPatcher.spliceExistingThumbnail` still enforces the JPEG APP1 u16 cap (65535) on the rebuilt segment,
 * so a too-large rebuild would silently reject the splice and leave the SOURCE's IFD1 thumbnail in place —
 * leaking pre-edit content via the embedded preview.
 *
 * A naive `tiff.length + thumbnail.length` sum would force-strip splices that actually shrink the segment
 * (because the old thumbnail is removed before the new one is inserted). `ExifPatcher.maxThumbnailBytes`
 * subtracts the old thumbnail's bytes from the segment size before measuring remaining APP1 room. These
 * tests pin both branches: splice when the rebuilt segment fits, strip when it genuinely does not.
 */
public final class CropExporterPngExifTest
{
	@Test
	public void patchPngExifTiffSplicesWhenOldThumbnailRemovalFreesEnoughBudget() throws IOException
	{
		// 50 KB old thumbnail + 30 KB fresh thumbnail. Naive sum (50,068 + 30,000 + 8 = 80,076) exceeds
		// the APP1 cap, but the splice removes the 50 KB old thumbnail before inserting the 30 KB fresh
		// one, so the rebuilt segment is roughly 30 KB + IFD overhead — comfortably under 65,535. The
		// A naive-sum guard would force-strip this case; routing through ExifPatcher.maxThumbnailBytes
		// correctly accounts for old-thumbnail removal. Pin
		// splice-not-strip behaviour: IFD0's next-IFD pointer must remain non-zero (still pointing at
		// the rebuilt IFD1 carrying the new thumbnail).
		byte[] tiff = buildTiffWithThumbnail(50_000);
		byte[] freshThumb = new byte[30_000];
		for (int i = 0; i < freshThumb.length; i++)
		{
			freshThumb[i] = (byte) 0x55;
		}

		byte[] patched = CropExporter.patchPngExifTiff(tiff, 1000, 2000, freshThumb);
		assertNotNull("patch should succeed (returns the modified TIFF, not null)", patched);

		long nextIfdPointer = readIfd0NextPointer(patched);
		assertNotEquals("Splice must preserve IFD0's next-IFD pointer (rebuilt IFD1 carries new thumbnail)",
			0L, nextIfdPointer);

		// Pin content provenance — without this, a regression where the splice silently writes the
		// source 0xAA bytes (or no-ops with source's IFD1 intact) still passes the non-zero-pointer
		// check. Walk the rebuilt IFD1, read JPEGInterchangeFormat, and assert the bytes are the fresh
		// 0x55 sentinel rather than the source 0xAA pattern.
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
		// Same TIFF shape, but a fresh thumbnail bigger than the post-old-removal APP1 budget. With the
		// 50 KB old thumbnail removed, the available budget is ≈ 65,535 − (50,078 − 50,000) = 65,457 B;
		// a 65,500 B fresh thumbnail still won't fit, so spliceExistingThumbnail's internal cap check
		// would silently reject and leave the source's IFD1 thumbnail in place. The guard must instead
		// route through STRIP_IFD1_THUMBNAIL: IFD0's next-IFD pointer is zeroed so no IFD1 is reachable
		// through the spec parse chain.
		byte[] tiff = buildTiffWithThumbnail(50_000);
		byte[] freshThumb = new byte[65_500];
		for (int i = 0; i < freshThumb.length; i++)
		{
			freshThumb[i] = (byte) 0x55;
		}

		byte[] patched = CropExporter.patchPngExifTiff(tiff, 1000, 2000, freshThumb);
		assertNotNull("patch should succeed (returns the modified TIFF, not null)", patched);

		long nextIfdPointer = readIfd0NextPointer(patched);
		assertEquals("STRIP must zero IFD0's next-IFD pointer when rebuilt segment exceeds APP1 cap",
			0L, nextIfdPointer);
	}

	private static byte[] buildTiffWithThumbnail(int thumbnailSize) throws IOException
	{
		// Build a little-endian TIFF: header (II*\0 + IFD0 offset = 8) → IFD0 (1 entry: Orientation,
		// next-IFD = 26) → IFD1 (3 entries: Compression / JPEGInterchangeFormat / Length, next-IFD =
		// 0) → thumbnail data at offset 26 + 42 = 68. Layout matches ExifPatcherTest's
		// buildSegmentWithExistingThumbnail; thumbnailSize is the only knob.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write('I');
		out.write('I');
		out.write('*');
		out.write(0);
		writeU32Le(out, 8L);   // IFD0 offset = 8

		// IFD0: 1 entry (Orientation), next-IFD pointer to IFD1 at offset 26.
		writeU16Le(out, 1);                  // entry count
		writeU16Le(out, 0x0112);             // Orientation
		writeU16Le(out, 3);                  // SHORT
		writeU32Le(out, 1L);                 // count
		writeU16Le(out, 6);                  // value = 6 (Rotate 90 CW)
		writeU16Le(out, 0);                  // padding
		writeU32Le(out, 26L);                // next-IFD = IFD1 at offset 26

		// IFD1: 3 entries (Compression, JPEGInterchangeFormat, JPEGInterchangeFormatLength), next-IFD
		// = 0. Layout: 2 (count) + 36 (3 entries) + 4 (next-IFD) = 42 bytes. Thumbnail offset = 26 +
		// 42 = 68.
		int thumbDataOff = 26 + 42;
		writeU16Le(out, 3);
		writeU16Le(out, 0x0103);              // Compression
		writeU16Le(out, 3);                   // SHORT
		writeU32Le(out, 1L);
		writeU16Le(out, 6);                   // value = 6 (JPEG)
		writeU16Le(out, 0);
		writeU16Le(out, 0x0201);              // JPEGInterchangeFormat
		writeU16Le(out, 4);                   // LONG
		writeU32Le(out, 1L);
		writeU32Le(out, thumbDataOff);
		writeU16Le(out, 0x0202);              // JPEGInterchangeFormatLength
		writeU16Le(out, 4);
		writeU32Le(out, 1L);
		writeU32Le(out, thumbnailSize);
		writeU32Le(out, 0L);                  // next-IFD = 0

		// Thumbnail data
		byte[] thumb = new byte[thumbnailSize];
		for (int i = 0; i < thumbnailSize; i++)
		{
			thumb[i] = (byte) 0xAA;
		}
		out.write(thumb);

		return out.toByteArray();
	}

	private static long readIfd0NextPointer(byte[] patched)
	{
		// Read IFD0's next-IFD pointer from the patched TIFF. The TIFF starts at offset 0 in the
		// returned bytes (no wrapper) — `patchPngExifTiff` unwraps the synthetic APP1 it built. IFD0
		// offset is at TIFF bytes 4..7; entry count at IFD0; next-IFD pointer at IFD0 + 2 + count*12.
		int ifd0Off = (int) ByteBufferUtils.readU32(patched, 4, true);
		int ifd0EntryCount = ByteBufferUtils.readU16(patched, ifd0Off, true);
		int nextIfdPointerOff = ifd0Off + 2 + ifd0EntryCount * 12;
		return ByteBufferUtils.readU32(patched, nextIfdPointerOff, true);
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
