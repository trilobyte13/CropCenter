package com.cropcenter.metadata;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins the TIFF / EXIF / MPF constant values against their spec sources. The constants are wire-format tag
 * IDs and entry-type codes — a regression that flips e.g. ORIENTATION to a wrong hex would silently corrupt
 * every saved JPEG / PNG eXIf chunk and only manifest at runtime in real EXIF parsers. These assertions are
 * cheap insurance for a class that's loaded into 4+ metadata-walking sites.
 *
 * Sources: TIFF 6.0 spec (Adobe), EXIF 2.32 (CIPA DC-008-2019), CIPA DC-007-2009 (MPF).
 */
public final class TiffTagTest
{
	@Test
	public void exifSubIfdPointer()
	{
		// EXIF 2.32 §4.6.5 Tag Type 1: Pointer from IFD0 to ExifSubIFD.
		assertEquals(0x8769, TiffTag.EXIF_SUB_IFD);
	}

	@Test
	public void imageDimensionTags()
	{
		// TIFF 6.0 §17 Image File Directory Entries.
		assertEquals(0x0100, TiffTag.IMAGE_WIDTH);
		assertEquals(0x0101, TiffTag.IMAGE_LENGTH);
	}

	@Test
	public void jpegInterchangeFormatTags()
	{
		// TIFF 6.0 §22 thumbnail tags — used in IFD1.
		assertEquals(0x0201, TiffTag.JPEG_INTERCHANGE_FORMAT);
		assertEquals(0x0202, TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH);
	}

	@Test
	public void mpfMpEntryTag()
	{
		// CIPA DC-007-2009 §5.2.3 MP Entry tag in the MPF Index IFD.
		assertEquals(0xB002, TiffTag.MP_ENTRY);
	}

	@Test
	public void orientationTag()
	{
		// TIFF 6.0 / EXIF 2.32 — Orientation tag, value 1..8.
		assertEquals(0x0112, TiffTag.ORIENTATION);
	}

	@Test
	public void pixelDimensionTags()
	{
		// EXIF 2.32 §4.6.5 PixelXDimension / PixelYDimension (in ExifSubIFD).
		assertEquals(0xA002, TiffTag.PIXEL_X_DIMENSION);
		assertEquals(0xA003, TiffTag.PIXEL_Y_DIMENSION);
	}

	@Test
	public void typeCodes()
	{
		// TIFF 6.0 §2 Field Types.
		assertEquals(3, TiffTag.TYPE_SHORT);
		assertEquals(4, TiffTag.TYPE_LONG);
	}
}
