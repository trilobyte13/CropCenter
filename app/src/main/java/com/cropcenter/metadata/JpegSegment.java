package com.cropcenter.metadata;

/**
 * Represents a single JPEG marker segment (APPn or COM). The data array includes the full segment: FF marker + length +
 * payload.
 *
 * Mutability contract — callers MUST NOT mutate data(). Records expose their byte[] component via the
 * auto-generated accessor as a live reference (Java Language Spec §8.10.3). The metadata pipeline
 * (`JpegMetadataExtractor` / `PngMetadataExtractor` / `ExifPatcher` / `MpfPatcher`) treats every segment as
 * immutable: patchers produce NEW segments via `Result.ok(...)` rather than mutating in place, and
 * `CropState.getJpegMeta()` wraps the list with `Collections.unmodifiableList`. A caller who writes through the
 * `data()` accessor would silently corrupt later metadata-injection / save / round-trip steps that share the
 * same reference. Defensive copies are NOT made because every load decodes 5-15 segments and copying on each
 * accessor read would be a measurable hot-path tax — the convention is documentation-level immutability, enforced
 * by code review and the patchers' replace-don't-mutate pattern. Callers that need an actually-defensive copy can
 * use `Arrays.copyOf(seg.data(), seg.data().length)`.
 */
public record JpegSegment(int marker, byte[] data)
{
	// Canonical Extended XMP namespace identifier — Adobe's continuation-chunk header that lives on each APP1
	// segment of a multi-chunk Extended XMP packet (used when the standard XMP body exceeds 64 KiB and has to be
	// split across several APP1 segments). Centralised here so ExtendedXmpReassembler, HdrSignature, and
	// XmpItemLengthPatcher all reference the same string rather than redeclaring it three different ways with
	// three different constant names.
	public static final String EXTENDED_XMP_HEADER = "http://ns.adobe.com/xmp/extension/\0";

	// Canonical ICC profile namespace identifier — 12-byte "ICC_PROFILE\0" body prefix on every APP2 segment
	// that carries embedded ICC color profile data (per ICC.1:2010 §B.4). Centralised here so isIcc() walks
	// the same constant the rest of the metadata layer can reference; mirrors the XMP_HEADER /
	// EXTENDED_XMP_HEADER pattern.
	public static final String ICC_HEADER = "ICC_PROFILE\0";

	// Canonical XMP namespace identifier that prefixes the XML body of an XMP APP1 segment. The 29-byte
	// "http://ns.adobe.com/xap/1.0/\0" string is the spec-defined signature; centralised here so both isXmp() and
	// HorizonDetector.detectFromMetadata reference one constant rather than two duplicate string literals.
	public static final String XMP_HEADER = "http://ns.adobe.com/xap/1.0/\0";

	// Cap on a JPEG APP segment's total byte length, INCLUDING the 2-byte length field itself. The segLen field
	// is u16, so 65535 is the absolute spec ceiling. Centralised here so ExifPatcher and XmpItemLengthPatcher
	// both reference the same constant rather than declaring near-duplicate copies with different names. Note:
	// this is the spec cap; some encoders cap practical segments lower (e.g.
	// Samsung writes EXIF at 65535 but XMP at 65533 to leave room for stuffing bytes).
	public static final int MAX_SEGMENT_BYTES = 65535;

	/**
	 * Check if this is an EXIF APP1 segment — marker 0xE1 with the "Exif\0\0" namespace prefix at the
	 * data[4] body offset (EXIF 2.32 / TIFF 6.0 signature). Centralised here so every consumer (CropExporter,
	 * GraftWriter, ExifPatcher, BitmapUtils.readExifOrientation) shares one prefix-walk implementation.
	 *
	 * @return true when marker == APP1 AND data carries the "Exif\0\0" identifier at the body offset
	 */
	public boolean isExif()
	{
		return marker == JpegMarker.APP1 && data.length >= 10
			&& data[4] == 'E' && data[5] == 'x' && data[6] == 'i'
			&& data[7] == 'f' && data[8] == 0 && data[9] == 0;
	}

	/**
	 * Check if this is an Adobe Extended XMP APP1 segment — marker 0xE1 with the extension namespace prefix
	 * ("http://ns.adobe.com/xmp/extension/\0") at the data[4] body offset. Vendors split a > 64 KB XMP packet
	 * across multiple APP1 segments: the first carries the standard XMP_HEADER + xmpNote:HasExtendedXMP, and
	 * subsequent segments use this extension prefix followed by a 32-byte MD5 GUID + 32-bit total length +
	 * 32-bit offset. Centralised here so HdrSignature.isHdrgmXmpSegment and ExtendedXmpReassembler share
	 * one prefix-walk implementation rather than two near-identical loops; ExtendedXmpReassembler layers a
	 * stricter post-prefix length guard on top of this predicate before decoding the GUID / offset header.
	 *
	 * @return true when the segment is an APP1 carrying the Adobe Extended XMP namespace prefix
	 */
	public boolean isExtendedXmp()
	{
		if (marker != JpegMarker.APP1 || data.length < 4 + EXTENDED_XMP_HEADER.length())
		{
			return false;
		}
		for (int i = 0; i < EXTENDED_XMP_HEADER.length(); i++)
		{
			if ((data[4 + i] & 0xFF) != EXTENDED_XMP_HEADER.charAt(i))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Check if this is an ICC color-profile APP2 segment — marker 0xE2 with the "ICC_PROFILE\0" header at
	 * the data[4] body offset. Vendors split large ICC profiles across multiple APP2 segments; this
	 * predicate just identifies the segment as ICC-bearing without parsing the sequence / total chunk
	 * header that follows the namespace.
	 *
	 * @return true when the segment is an APP2 of at least the 18-byte minimum carrying the ICC_PROFILE
	 *         namespace prefix
	 */
	public boolean isIcc()
	{
		// Minimum length 18 = FF E2 segLen + "ICC_PROFILE\0" + 2-byte chunk header (sequence + total).
		// Preserved verbatim from the pre-constant version to avoid silently relaxing the
		// short-segment rejection contract.
		if (marker != JpegMarker.APP2 || data.length < 18)
		{
			return false;
		}
		for (int i = 0; i < ICC_HEADER.length(); i++)
		{
			if ((data[4 + i] & 0xFF) != ICC_HEADER.charAt(i))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Check if this is a Multi-Picture Format (MPF) APP2 segment — marker 0xE2 with the "MPF\0" namespace
	 * prefix at the data[4] body offset (CIPA DC-007-2009). Centralised so MpfPatcher's offset rewrites and
	 * CropExporter's MPF-strip decisions can share one predicate.
	 *
	 * @return true when marker == APP2 AND data carries the "MPF\0" identifier at the body offset
	 */
	public boolean isMpf()
	{
		return marker == JpegMarker.APP2 && data.length >= 8
			&& data[4] == 'M' && data[5] == 'P' && data[6] == 'F' && data[7] == 0;
	}

	/**
	 * True when this segment is an APP1 XMP packet — APP1 marker carrying the canonical Adobe
	 * `http://ns.adobe.com/xap/1.0/\0` header at offset 4. Distinguishes XMP from EXIF (same
	 * marker, different `Exif\0\0` header) and from Extended XMP (APP1 with the GUID-bearing
	 * `http://ns.adobe.com/xmp/extension/\0` header — see isExtendedXmp).
	 *
	 * @return true when marker is APP1 and bytes after the 2-byte length prefix match the
	 *         XMP namespace identifier; false otherwise (wrong marker, too short, or different
	 *         APP1 sub-format)
	 */
	public boolean isXmp()
	{
		if (marker != JpegMarker.APP1 || data.length < 4 + XMP_HEADER.length())
		{
			return false;
		}
		for (int i = 0; i < XMP_HEADER.length(); i++)
		{
			if ((data[4 + i] & 0xFF) != XMP_HEADER.charAt(i))
			{
				return false;
			}
		}
		return true;
	}
}
