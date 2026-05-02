package com.cropcenter.metadata;

/**
 * Represents a single JPEG marker segment (APPn or COM). The data array includes the full segment: FF marker + length +
 * payload.
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
