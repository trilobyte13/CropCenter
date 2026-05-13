package com.cropcenter.metadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Builders for synthetic JPEG byte sequences used by the metadata-extractor / patcher tests. Produces minimal-valid
 * layouts that exercise the parsers' marker walks without needing real-photo fixtures (which are user-supplied and not
 * committed). Each helper returns the assembled byte array; callers compose them as needed.
 *
 * Not a runtime helper — lives only in the test source set. Public so all test classes (including those outside
 * com.cropcenter.metadata, like BitmapUtilsTest in com.cropcenter .util) can share builders without copy-paste.
 */
public final class JpegFixtures
{
	private JpegFixtures() {}

	/**
	 * Append `payload` as the body of an APPn (or COM) segment with the given marker. Adds the FF marker, the
	 * 2-byte big-endian length field, and the payload bytes. `marker` is the second byte of the marker pair (e.g.,
	 * 0xE0 for APP0, 0xE1 for APP1, 0xFE for COM); the leading FF is implied.
	 */
	public static byte[] appSegment(int marker, byte[] payload) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(JpegMarker.PREFIX);
		out.write(marker);
		// segLen INCLUDES the 2 length bytes themselves per JPEG spec.
		int segLen = 2 + payload.length;
		out.write((segLen >> 8) & 0xFF);
		out.write(segLen & 0xFF);
		out.write(payload);
		return out.toByteArray();
	}

	/**
	 * Concatenate an arbitrary number of byte arrays in order. Lets each test express "SOI + APP0 + APP1 + SOS body
	 * + EOI" as a single composition.
	 */
	public static byte[] concat(byte[]... parts) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts)
		{
			out.write(part);
		}
		return out.toByteArray();
	}

	/**
	 * EOI marker (FF D9) — terminates a JPEG / gain-map.
	 */
	public static byte[] eoi()
	{
		return new byte[] { (byte) JpegMarker.PREFIX, (byte) JpegMarker.EOI };
	}

	/**
	 * Build a minimal-valid EXIF APP1 segment: "Exif\0\0" + a stub TIFF header and single-entry IFD. Sufficient for
	 * JpegSegment.isExif() to return true.
	 */
	public static byte[] exifAppPayload()
	{
		byte[] payload = new byte[14];
		payload[0] = 'E';
		payload[1] = 'x';
		payload[2] = 'i';
		payload[3] = 'f';
		payload[4] = 0;
		payload[5] = 0;
		payload[6] = 'I';   // little-endian TIFF
		payload[7] = 'I';
		payload[8] = '*';
		payload[9] = 0;
		payload[10] = 8;    // IFD0 offset = 8 (right after the header)
		payload[11] = 0;
		payload[12] = 0;
		payload[13] = 0;
		return payload;
	}

	/**
	 * Minimal SOS segment + entropy-coded scan + EOI. The scan body is just a few sample-data bytes followed by the
	 * EOI marker. SOS length = 6 (the minimum legal value for 1-component scans, even though the marker walk
	 * doesn't care).
	 */
	public static byte[] minimalScanAndEoi() throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(JpegMarker.PREFIX);
		out.write(JpegMarker.SOS);
		out.write(0x00);
		out.write(0x06);                // length = 6
		out.write(new byte[]{ 0x01, 0x00, 0x00, 0x00 });   // scan header body
		out.write(new byte[]{ 0x77, 0x77, 0x77 });          // entropy-coded payload
		out.write(JpegMarker.PREFIX);
		out.write(JpegMarker.EOI);
		return out.toByteArray();
	}

	/**
	 * SOI marker (FF D8) — required first 2 bytes of every JPEG.
	 */
	public static byte[] soi()
	{
		return new byte[] { (byte) JpegMarker.PREFIX, (byte) JpegMarker.SOI };
	}
}
