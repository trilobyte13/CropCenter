package com.cropcenter.metadata;

/**
 * JPEG marker byte constants. The second byte of each FF-prefixed marker pair — e.g. SOI is FF D8, so SOI = 0xD8.
 * Centralised here so the JPEG byte-walking code in CropExporter, GainMapExtractor, GraftWriter, JpegMetadataExtractor,
 * and MpfPatcher can refer to named constants instead of repeating hex literals with explanatory comments at every call
 * site. Per the JPEG spec (ITU-T T.81 / ISO/IEC 10918-1).
 */
public final class JpegMarker
{
	// APP0 — first of the APPn family (FF E0). Reserved for JFIF / JFXX in most encoders. Inside the canonical
	// APP/COM range every walker skips past with a length-field read.
	public static final int APP0 = 0xE0;

	// APP1 — FF E1. Carries EXIF (`Exif\0\0` payload prefix) plus Adobe standard XMP (namespace
	// `http://ns.adobe.com/xap/1.0/`) and Adobe Extended XMP. Centralised here so the half-dozen sites that
	// test `marker == 0xE1` route through the constant.
	public static final int APP1 = 0xE1;

	// APP2 — FF E2. Carries ICC profile (`ICC_PROFILE\0` payload prefix), MPF (`MPF\0`), and the ISO 21496-1 Ultra
	// HDR signaling block.
	public static final int APP2 = 0xE2;

	// APP15 — FF EF. Highest APPn marker; the APP range walkers test as `marker >= APP0 && marker <= APP_LAST`.
	public static final int APP_LAST = 0xEF;

	// COM — comment marker (FF FE). Treated as a generic length-prefixed segment by walkers. Round-trips through
	// `JpegMetadataExtractor` alongside the APP family.
	public static final int COM = 0xFE;

	// EOI — End Of Image. Closes the primary stream. Also encountered inside SOS entropy-coded data when scanning
	// forward for the primary's terminator.
	public static final int EOI = 0xD9;

	// Marker prefix — the 0xFF byte that introduces every JPEG marker pair. Every marker in this file is the
	// SECOND byte of a (PREFIX, X) pair; centralised here so test fixtures (and any byte-walking caller that
	// synthesises markers rather than scanning existing bytes) can write `(byte) JpegMarker.PREFIX, (byte)
	// JpegMarker.SOI` instead of repeating the 0xFF hex literal with an explanatory comment.
	public static final int PREFIX = 0xFF;

	// Lowest restart marker (FF D0).
	public static final int RST_FIRST = 0xD0;

	// Highest restart marker (FF D7). Restart markers FF D0..FF D7 are interleaved into entropy-coded SOS data for
	// resync; walkers must skip them as standalone (no length field).
	public static final int RST_LAST = 0xD7;

	// SOI — Start Of Image. The first 2 bytes of every JPEG are FF D8.
	public static final int SOI = 0xD8;

	// SOS — Start Of Scan. Begins entropy-coded data; walkers must read the segment length, advance past the SOS
	// header, then scan byte-by-byte (honouring byte-stuff FF 00 and restart markers) until the next real marker.
	public static final int SOS = 0xDA;

	// Byte-stuffing escape — FF 00 means a literal FF inside entropy-coded data (the FF doesn't introduce a
	// marker). Walkers skip the pair.
	public static final int STUFFING = 0x00;

	// TEM — Temporary marker for arithmetic coding. Standalone (no length field). Rare in baseline JPEGs but the
	// walkers handle it for robustness alongside RST and STUFFING.
	public static final int TEM = 0x01;

	private JpegMarker() {}
}
