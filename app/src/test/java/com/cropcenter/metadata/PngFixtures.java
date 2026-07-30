package com.cropcenter.metadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

/**
 * Builders for synthetic PNG byte sequences used by the PNG metadata-extractor / load-controller tests. Mirrors
 * JpegFixtures' role on the PNG side — minimal-valid layouts that exercise the parsers' chunk walks without needing
 * real-photo fixtures (which are user-supplied and not committed).
 *
 * Two consumers: PngMetadataExtractorTest (parser-direct) and ImageLoadControllerExtractMetadataTest (format-routing).
 * Before extraction each carried its own copy of the same chunk + CRC builder; centralising here means a future fix to
 * the chunk format (a new CRC seed, a different length encoding) can't drift between the two test files.
 *
 * Not a runtime helper — lives only in the test source set. Public so callers outside the metadata package can use it
 * without copy-paste.
 */
public final class PngFixtures
{
	/**
	 * The canonical 8-byte PNG signature that every well-formed PNG file starts with — used by PngMetadataExtractor
	 * to gate parsing (no signature → bail). Tests prepend this verbatim before chunk concatenation.
	 */
	public static final byte[] PNG_SIGNATURE = {
		(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
	};

	private PngFixtures() {}

	/**
	 * Build a complete PNG chunk (length + type + data + CRC32-of-type-and-data) ready to splice into a synthetic
	 * file. The 4-byte length is big-endian and counts only `data` bytes, NOT the type / CRC fields, per the PNG
	 * spec.
	 *
	 * @param type 4-character ASCII chunk type (`IHDR`, `eXIf`, `IDAT`, `IEND`, etc.); case sensitivity matters for
	 *             the spec's "ancillary / private / reserved / safe-to-copy" decoding bits
	 * @param data chunk payload bytes (may be zero-length for chunks like IEND)
	 * @return the 4-byte length + 4-byte type + payload + 4-byte CRC, ready for ByteArrayOutputStream.write
	 * @throws IOException only when the type cannot be encoded as US-ASCII (i.e. caller passed non-ASCII)
	 */
	public static byte[] buildChunk(String type, byte[] data) throws IOException
	{
		byte[] typeBytes = type.getBytes("US-ASCII");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int len = data.length;
		out.write((len >> 24) & 0xFF);
		out.write((len >> 16) & 0xFF);
		out.write((len >> 8) & 0xFF);
		out.write(len & 0xFF);
		out.write(typeBytes);
		out.write(data);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		long crc32Value = crc.getValue();
		out.write((int) ((crc32Value >> 24) & 0xFF));
		out.write((int) ((crc32Value >> 16) & 0xFF));
		out.write((int) ((crc32Value >> 8) & 0xFF));
		out.write((int) (crc32Value & 0xFF));
		return out.toByteArray();
	}

	/**
	 * Minimal valid IHDR for a 1×1 8-bit RGB PNG. Every well-formed PNG must lead with IHDR; this is the smallest
	 * legal one that decoders accept, sufficient to gate the format-routing tests on a real PNG signature without
	 * shipping image data.
	 *
	 * @return 13-byte IHDR data field wrapped in the standard length / type / CRC envelope
	 * @throws IOException if buildChunk's US-ASCII encoding of "IHDR" fails (never in practice)
	 */
	public static byte[] buildIhdrChunk() throws IOException
	{
		// 13-byte IHDR data: width (4), height (4), bit-depth (1), color-type (1), compression (1), filter (1),
		// interlace (1). 1×1 / 8-bit / color-type 2 (RGB) is the minimum decoders will not reject.
		byte[] data = { 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0 };
		return buildChunk("IHDR", data);
	}

	/**
	 * Minimal valid PNG: 8-byte signature + IHDR (1×1 8-bit RGB) + empty IEND — 8 + 25 + 12 = 45 bytes. The eXIf
	 * insertion point the injection tests navigate by sits at byte 33 (after IHDR's CRC, before IEND's length
	 * field).
	 *
	 * @return 45-byte PNG composed from PNG_SIGNATURE + buildIhdrChunk() + an empty IEND chunk
	 * @throws IOException if buildChunk's US-ASCII encoding of "IEND" fails (never in practice)
	 */
	public static byte[] minimalPng() throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(PNG_SIGNATURE);
		out.write(buildIhdrChunk());
		out.write(buildChunk("IEND", new byte[0]));
		return out.toByteArray();
	}
}
