package com.cropcenter.metadata;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Builders for synthetic JPEG byte sequences used by the metadata-extractor / patcher tests, plus a lying-length File
 * stand-in for the streaming save paths. Produces minimal-valid layouts that exercise the parsers' marker walks without
 * needing real-photo fixtures (which are user-supplied and not committed). Each byte-building helper returns the
 * assembled byte array; callers compose them as needed.
 *
 * Not a runtime helper — lives only in the test source set. Public so all test classes (including those outside
 * com.cropcenter.metadata, like BitmapUtilsTest in com.cropcenter.util) can share builders without copy-paste.
 */
public final class JpegFixtures
{
	private JpegFixtures() {}

	/**
	 * Wrap `payload` as the body of an APPn (or COM) segment: FF marker pair, then the 2-byte big-endian length
	 * field, then the payload bytes.
	 *
	 * @param marker  second byte of the marker pair (e.g., 0xE0 for APP0, 0xE1 for APP1, 0xFE for COM); the
	 *                leading FF is implied
	 * @param payload segment body; the emitted length field is payload.length + 2 because segLen counts its own
	 *                2 bytes per JPEG spec
	 * @return assembled segment bytes: marker pair + length field + payload
	 * @throws IOException never from the in-memory stream; declared by the OutputStream write contract
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
	 *
	 * @param parts arrays to join, in argument order; none are modified
	 * @return a new array holding every part's bytes back to back
	 * @throws IOException never from the in-memory stream; declared by the OutputStream write contract
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
	 * Minimal DQT segment stub (FF DB, length field 4, two zero payload bytes). Gives fixture JPEGs a non-APP head
	 * segment so marker walks exercise their skip-past-DQT step.
	 *
	 * @return assembled 6-byte DQT segment
	 * @throws IOException never from the in-memory stream; declared by the OutputStream write contract
	 */
	public static byte[] dqtStub() throws IOException
	{
		return appSegment(0xDB, new byte[] { 0x00, 0x00 });
	}

	/**
	 * EOI marker (FF D9) — terminates a JPEG / gain-map.
	 *
	 * @return a fresh 2-byte array holding FF D9
	 */
	public static byte[] eoi()
	{
		return new byte[] { (byte) JpegMarker.PREFIX, (byte) JpegMarker.EOI };
	}

	/**
	 * Build a minimal-valid EXIF APP1 payload: "Exif\0\0" + a stub TIFF header and single-entry IFD. Sufficient for
	 * JpegSegment.isExif() to return true.
	 *
	 * @return 14-byte payload: EXIF identifier, little-endian TIFF header, IFD0 offset of 8
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
	 * Build an Adobe Extended XMP chunk body: the 35-byte extension namespace prefix (JpegSegment's
	 * EXTENDED_XMP_HEADER, including its trailing NUL) + 32-character ASCII GUID + 4-byte big-endian total-length +
	 * 4-byte big-endian chunk offset + chunk body. Wrap the result via appSegment(0xE1, ...) for a full APP1
	 * segment.
	 *
	 * @param guid        32-character ASCII GUID grouping chunks of one logical XMP document
	 * @param totalLength value of the total-length field; the parsers under test treat it as a placeholder, so
	 *                    any pinned value is valid
	 * @param offset      chunk position within the reassembled document, written as an unsigned 32-bit field —
	 *                    honoured by ExtendedXmpReassembler's unsigned sort
	 * @param body        chunk content appended after the offset field; encoding is the caller's choice
	 * @return Extended XMP chunk bytes, namespace prefix through body
	 * @throws IOException never from the in-memory stream; declared by the OutputStream write contract
	 */
	public static byte[] extendedXmpChunk(String guid, long totalLength, long offset, byte[] body)
		throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(JpegSegment.EXTENDED_XMP_HEADER.getBytes(StandardCharsets.US_ASCII));
		out.write(guid.getBytes(StandardCharsets.US_ASCII));
		out.write((int) ((totalLength >> 24) & 0xFF));
		out.write((int) ((totalLength >> 16) & 0xFF));
		out.write((int) ((totalLength >> 8) & 0xFF));
		out.write((int) (totalLength & 0xFF));
		out.write((int) ((offset >> 24) & 0xFF));
		out.write((int) ((offset >> 16) & 0xFF));
		out.write((int) ((offset >> 8) & 0xFF));
		out.write((int) (offset & 0xFF));
		out.write(body);
		return out.toByteArray();
	}

	/**
	 * Wrap an on-disk file in a File whose length() reports `reportedLength` instead of the real size. Streaming
	 * code sizes read buffers from File.length() and must tolerate the stream returning EOF before that many bytes
	 * arrive (the file shrank between the length() call and the read) — this stand-in makes that short-read window
	 * deterministic in tests.
	 *
	 * @param realFile       existing file whose on-disk bytes back the returned handle
	 * @param reportedLength value the returned File's length() reports regardless of the real size
	 * @return a File that opens realFile's path but lies about its length
	 */
	public static File fileReportingLength(File realFile, long reportedLength)
	{
		return new File(realFile.getPath())
		{
			@Override
			public long length()
			{
				return reportedLength;
			}
		};
	}

	/**
	 * Find the first occurrence of a byte subsequence inside a larger array — the assertion workhorse for "output
	 * carries these fixture bytes verbatim" checks.
	 *
	 * @param haystack array to scan end to end
	 * @param needle   byte pattern to locate; must be non-empty
	 * @return index of the first occurrence, or -1 when absent
	 */
	public static int indexOfSubsequence(byte[] haystack, byte[] needle)
	{
		for (int i = 0; i <= haystack.length - needle.length; i++)
		{
			boolean match = true;
			for (int j = 0; j < needle.length; j++)
			{
				if (haystack[i + j] != needle[j])
				{
					match = false;
					break;
				}
			}
			if (match)
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Minimal SOS segment + entropy-coded scan + EOI. The scan body is just a few sample-data bytes followed by the
	 * EOI marker. SOS length = 6 (the minimum legal value for 1-component scans, even though the marker walk
	 * doesn't care).
	 *
	 * @return SOS marker + 4-byte scan header body + 3 entropy-coded bytes + EOI marker
	 * @throws IOException never from the in-memory stream; declared by the OutputStream write contract
	 */
	public static byte[] minimalScanAndEoi() throws IOException
	{
		return concat(scanBody(), eoi());
	}

	/**
	 * Minimal SOS segment + entropy-coded scan body WITHOUT the trailing EOI. Compositions that append a gain map /
	 * SEFT trailer directly after the primary keep the EOI separate — concat(scanBody(), eoi(), gainMap, ...).
	 *
	 * @return SOS marker + 4-byte scan header body + 3 entropy-coded bytes
	 * @throws IOException never from the in-memory stream; declared by the OutputStream write contract
	 */
	public static byte[] scanBody() throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(JpegMarker.PREFIX);
		out.write(JpegMarker.SOS);
		out.write(0x00);
		out.write(0x06);                // length = 6
		out.write(new byte[]{ 0x01, 0x00, 0x00, 0x00 });   // scan header body
		out.write(new byte[]{ 0x77, 0x77, 0x77 });          // entropy-coded payload
		return out.toByteArray();
	}

	/**
	 * SOI marker (FF D8) — required first 2 bytes of every JPEG.
	 *
	 * @return a fresh 2-byte array holding FF D8
	 */
	public static byte[] soi()
	{
		return new byte[] { (byte) JpegMarker.PREFIX, (byte) JpegMarker.SOI };
	}
}
