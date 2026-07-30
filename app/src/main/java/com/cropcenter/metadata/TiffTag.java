package com.cropcenter.metadata;

/**
 * EXIF / TIFF tag IDs and entry-type codes shared across the metadata-walking parsers and patchers. Hoisted from
 * scattered hex literals (`0x0112` for Orientation appearing in `ExifPatcher`, `BitmapUtils`, `PngMetadataExtractor`;
 * `0x0100/0x0101/0xA002/0xA003` width/height variants in `ExifPatcher`; `0xB002` MP Entry in `MpfPatcher`) so the
 * EXIF-tag dictionary lives in one place. A future "support tag X" change lands in one file rather than a multi-site
 * bare-literal hunt.
 *
 * Values are from the TIFF 6.0 spec, EXIF 2.32 spec, and CIPA DC-007-2009 (MPF). All tags are u16 wire-format.
 */
public final class TiffTag
{
	// Compression scheme (IFD1 for JPEG primary thumbnails — value=6=JPEG). Per EXIF 2.32 the tag should be OMITTED
	// from IFD0 for JPEG-encoded primaries because the FF D8 JPEG header already implies the compression scheme;
	// some EXIF round-trips leak it into IFD0 anyway, where ExifPatcher.scanIfd neutralises the entry at depth=0 to
	// keep parsers from acting on a redundant value (zeroing alone would write Compression=0 which is invalid TIFF;
	// instead the entire 12-byte entry is zeroed so parsers see unknown tag 0x0000 and skip).
	public static final int COMPRESSION = 0x0103;
	// EXIF SubIFD pointer (IFD0 → ExifSubIFD). u32 offset to the sub-IFD relative to TIFF header.
	public static final int EXIF_SUB_IFD = 0x8769;
	// Image length / height (IFD0). SHORT or LONG. CropExporter rewrites this to the cropped dimension.
	public static final int IMAGE_LENGTH = 0x0101;
	// Image width (IFD0). SHORT or LONG. CropExporter rewrites this to the cropped dimension.
	public static final int IMAGE_WIDTH = 0x0100;
	// JPEGInterchangeFormat — offset to the IFD1 thumbnail JPEG (LONG).
	public static final int JPEG_INTERCHANGE_FORMAT = 0x0201;
	// JPEGInterchangeFormatLength — byte length of the IFD1 thumbnail JPEG (LONG).
	public static final int JPEG_INTERCHANGE_FORMAT_LENGTH = 0x0202;
	// TIFF magic number (always 42 = 0x002A, u16 in declared byte order). Sits at offset +2 inside the TIFF header,
	// just after the byte-order marker (II/MM). Every TIFF-walker uses this as the second half of the "is this a
	// valid TIFF body?" check (alongside the II/MM byte-order pair) — extracted as a constant so the literal 42
	// isn't repeated alongside its `// TIFF magic = 42` comment at the five call sites (ExifPatcher's four entry
	// points, plus TiffIfd0 on behalf of BitmapUtils / PngMetadataExtractor / CropExporter).
	public static final int MAGIC = 42;
	// MP Entry list (APP2 / MPF IFD). Per CIPA DC-007 the type is UNDEFINED and count is a BYTE count = 16 ×
	// NumberOfImages (each MP Entry is a fixed 16-byte block) — MpfPatcher.patchMpEntry's `byteCount / 16`
	// entry-count math depends on this.
	public static final int MP_ENTRY = 0xB002;
	// Orientation (IFD0). SHORT, count = 1, value 1..8 per EXIF 2.32.
	public static final int ORIENTATION = 0x0112;
	// PixelXDimension (ExifSubIFD). SHORT or LONG.
	public static final int PIXEL_X_DIMENSION = 0xA002;
	// PixelYDimension (ExifSubIFD). SHORT or LONG.
	public static final int PIXEL_Y_DIMENSION = 0xA003;
	// TIFF entry type LONG — u32 value field. Used for LONG-form dimensions and IFD offsets.
	public static final int TYPE_LONG = 4;
	// TIFF entry type SHORT — u16 value field. Used for Orientation and SHORT-form dimensions.
	public static final int TYPE_SHORT = 3;

	private TiffTag() {}
}
