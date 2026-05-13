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
 * same reference. Per CLAUDE.md's "byte[] components are fine" guidance, defensive copies are NOT made (every
 * load decodes 5-15 segments and copying on every accessor read would be a measurable hot-path tax) — the
 * convention is documentation-level immutability, enforced by code review and the patchers' replace-don't-mutate
 * pattern. Callers that need an actually-defensive copy can use `Arrays.copyOf(seg.data(), seg.data().length)`.
 */
public record JpegSegment(int marker, byte[] data)
{
	/**
	 * Canonical XMP namespace identifier that prefixes the XML body of an XMP APP1 segment. The 29-byte
	 * "http://ns.adobe.com/xap/1.0/\0" string is the spec-defined signature; centralised here so both isXmp() and
	 * HorizonDetector.detectFromMetadata reference one constant rather than two duplicate string literals.
	 */
	public static final String XMP_HEADER = "http://ns.adobe.com/xap/1.0/\0";

	/**
	 * Canonical Extended XMP namespace identifier — Adobe's continuation-chunk header that lives on each APP1
	 * segment of a multi-chunk Extended XMP packet (used when the standard XMP body exceeds 64 KiB and has to be
	 * split across several APP1 segments). Centralised here so ExtendedXmpReassembler, HdrSignature, and
	 * XmpItemLengthPatcher all reference the same string rather than redeclaring it three different ways with
	 * three different constant names (Codex round-46 style-agent F1).
	 */
	public static final String EXTENDED_XMP_HEADER = "http://ns.adobe.com/xmp/extension/\0";

	/**
	 * Cap on a JPEG APP segment's total byte length, INCLUDING the 2-byte length field itself. The segLen field
	 * is u16, so 65535 is the absolute spec ceiling. Centralised here so ExifPatcher and XmpItemLengthPatcher
	 * both reference the same constant rather than declaring near-duplicate copies with different names (Codex
	 * round-46 style-agent F2). Note: this is the spec cap; some encoders cap practical segments lower (e.g.
	 * Samsung writes EXIF at 65535 but XMP at 65533 to leave room for stuffing bytes).
	 */
	public static final int MAX_SEGMENT_BYTES = 65535;

	/**
	 * Check if this is an EXIF APP1 segment (starts with "Exif\0\0").
	 */
	public boolean isExif()
	{
		return marker == 0xE1 && data.length >= 10 && data[4] == 'E' && data[5] == 'x' && data[6] == 'i'
			&& data[7] == 'f' && data[8] == 0 && data[9] == 0;
	}

	/**
	 * Check if this is an ICC Profile APP2 segment (starts with "ICC_PROFILE\0").
	 */
	public boolean isIcc()
	{
		if (marker != 0xE2 || data.length < 18)
		{
			return false;
		}
		String id = "ICC_PROFILE\0";
		for (int i = 0; i < id.length(); i++)
		{
			if ((data[4 + i] & 0xFF) != id.charAt(i))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Check if this is an MPF APP2 segment (starts with "MPF\0").
	 */
	public boolean isMpf()
	{
		return marker == 0xE2 && data.length >= 8
			&& data[4] == 'M' && data[5] == 'P' && data[6] == 'F' && data[7] == 0;
	}

	/**
	 * Check if this is an XMP APP1 segment.
	 */
	public boolean isXmp()
	{
		if (marker != 0xE1 || data.length < 33)
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
