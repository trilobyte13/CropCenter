package com.cropcenter.metadata;

import static com.cropcenter.metadata.EndianStreamFixtures.writeU16Le;
import static com.cropcenter.metadata.EndianStreamFixtures.writeU32Le;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Builders for synthetic MPF (Multi-Picture Format) APP2 payloads used by the Ultra HDR patcher / composer / graft
 * tests. The payload layout (little-endian "II" form):
 *
 *   "II*\0" + IFD offset (= 8, points right after the header)
 *   IFD: 2 entries { tag 0xB000 Version "0100", tag 0xB002 MPEntries at relative offset 38 } + next-IFD = 0
 *   MP Entries: entries.length * 16 bytes (attr, size, dataOffset, dependents)
 *
 * The offset-38 arithmetic (8-byte endian header + 2-byte count + two 12-byte IFD entries + 4-byte next-IFD) is the
 * load-bearing constant every consumer's assertions navigate by; it lives here only.
 *
 * Not a runtime helper — lives only in the test source set. Public to match the JpegFixtures / PngFixtures shared
 * fixture surface, so consumers outside com.cropcenter.metadata need no copy-paste.
 */
public final class MpfFixtures
{
	/**
	 * One MP Entry row of the fixture's MP Entries table. The dependents field is always written as 0.
	 *
	 * @param attr       32-bit attribute / MPType field; plant 0x00010005 (Original Preservation) to mark the
	 *                   gain-map slot, 0x20000000 for a representative-image-bit primary
	 * @param size       pre-patch image-size field — stale by design; patchers under test rewrite or ignore it
	 * @param dataOffset pre-patch data-offset field — stale by design, same contract as size
	 */
	public record MpfEntry(long attr, long size, long dataOffset) {}

	private MpfFixtures() {}

	/**
	 * Prefix a payload body with the 4-byte MPF magic. A string literal "MPF\0" through getBytes drops the trailing
	 * null, so the signature is built byte-by-byte.
	 *
	 * @param body MPF payload bytes (endian header onward); not modified
	 * @return new array: 'M', 'P', 'F', NUL, then body verbatim
	 */
	public static byte[] prefixMpfMagic(byte[] body)
	{
		byte[] sig = { 'M', 'P', 'F', 0 };
		byte[] result = new byte[sig.length + body.length];
		System.arraycopy(sig, 0, result, 0, sig.length);
		System.arraycopy(body, 0, result, sig.length, body.length);
		return result;
	}

	/**
	 * Canonical 2-image Ultra HDR MP Entry shape: entry[0] primary (attr 0x20000000, stale size placeholder 999,
	 * offset 0) and entry[1] gain map (attr 0x00010005 Original Preservation, stale zero size / offset). The
	 * patcher pass under test rewrites the stale fields.
	 *
	 * @return MPF payload bytes without the "MPF\0" magic; wrap via prefixMpfMagic for an APP2 body
	 */
	public static byte[] ultraHdrMpfPayload()
	{
		return ultraHdrMpfPayload(new MpfEntry(0x20000000L, 999L, 0L), new MpfEntry(0x00010005L, 0L, 0L));
	}

	/**
	 * Build an Ultra-HDR-shaped MPF payload (endian header + 2-entry IFD + MP Entries table) carrying one 16-byte
	 * MP Entry row per element of entries.
	 *
	 * @param entries one row per image, in table order; the 0xB002 byteCount field records entries.length * 16
	 * @return MPF payload bytes without the "MPF\0" magic; wrap via prefixMpfMagic for an APP2 body
	 */
	public static byte[] ultraHdrMpfPayload(MpfEntry... entries)
	{
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		// MP Endian header: "II*\0" + IFD offset = 8.
		payload.write('I');
		payload.write('I');
		payload.write('*');
		payload.write(0);
		writeU32Le(payload, 8L);

		// IFD: 2 entries (Version, MPEntries) + 4-byte next-IFD = 0.
		writeU16Le(payload, 2);
		// Entry: tag 0xB000 Version, type UNDEFINED, count 4, value "0100".
		writeU16Le(payload, 0xB000);
		writeU16Le(payload, 7);
		writeU32Le(payload, 4L);
		payload.write('0');
		payload.write('1');
		payload.write('0');
		payload.write('0');
		// Entry: tag 0xB002 MPEntries, type UNDEFINED, count = entries * 16, dataOffset = 38 (after IFD).
		writeU16Le(payload, 0xB002);
		writeU16Le(payload, 7);
		writeU32Le(payload, entries.length * 16L);
		writeU32Le(payload, 38L);
		writeU32Le(payload, 0L); // next IFD

		for (MpfEntry entry : entries)
		{
			writeU32Le(payload, entry.attr());
			writeU32Le(payload, entry.size());
			writeU32Le(payload, entry.dataOffset());
			writeU32Le(payload, 0L); // dependents
		}
		return payload.toByteArray();
	}
}
