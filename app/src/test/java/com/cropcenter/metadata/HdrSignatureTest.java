package com.cropcenter.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Direct unit tests for HdrSignature, the HDR-source gate that drives gain-map extraction at load and graft and
 * the HDR-drop strip on the export path. Three predicates are exposed, all pinned here:
 *
 *   - hasHdrgmInXmp(List of JpegSegment) — XMP-segment-only walk used by ImageLoadController.extractMetadata and
 *     GraftWriter.graft. Narrowed from a full-file scan because a stray "hdrgm" 5-byte sequence in
 *     MakerNote / COM / vendor blob / SEFT edit history / entropy could falsely flag SDR files as HDR.
 *   - isHdrgmXmpSegment(JpegSegment) — per-segment predicate used by CropExporter.stripHdrSegments to drop
 *     the XMP-with-hdrgm segments without touching unrelated APP1 segments. Lives here next to its sibling
 *     so a regression that mis-classifies extended-XMP or non-XMP segments shows up alongside the list-walk
 *     tests rather than as a downstream stripHdrSegments mis-strip.
 *   - isHdrSource(byte[]) — full-file scan used by UltraHdrCompat post-Bitmap.compress diagnostic only. Exercised
 *     here so a regression that broke the byte-pattern walk would be caught at the public API layer rather than
 *     transitively through UltraHdrCompatTest.
 *
 * Pure JUnit, no Android infrastructure needed — all three methods are pure Java byte-pattern matchers.
 */
public final class HdrSignatureTest
{
	private static final byte[] HDRGM_BYTES = "hdrgm".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] XMP_HEADER_BYTES =
		JpegSegment.XMP_HEADER.getBytes(StandardCharsets.US_ASCII);

	@Test
	public void hasHdrgmInXmpFindsMarkerAtEndOfXmpBody() throws IOException
	{
		// Marker at the very end of the body — pin that the loop's index bound includes positions where
		// the 5-byte pattern starts at (length - 5).
		JpegSegment xmp = newXmpSeg(prefixedWith("body content with marker at end ", HDRGM_BYTES));
		assertTrue(HdrSignature.hasHdrgmInXmp(List.of(xmp)));
	}

	@Test
	public void hasHdrgmInXmpFindsMarkerAtStartOfXmpBody() throws IOException
	{
		// Marker at the very start of the body — pin that the scan starts at index 0 (no off-by-one
		// skipping the leading bytes).
		JpegSegment xmp = newXmpSeg(prefixedWith("", HDRGM_BYTES, " rest of body"));
		assertTrue(HdrSignature.hasHdrgmInXmp(List.of(xmp)));
	}

	@Test
	public void hasHdrgmInXmpFindsMarkerInExtensionXmp() throws IOException
	{
		// Spec-compliant Extended XMP splits a large XMP packet across multiple APP1
		// segments. The first carries the standard 28-byte XMP header; subsequent extension segments use
		// the "http://ns.adobe.com/xmp/extension/\0" header. Pin that hasHdrgmInXmp finds the marker in
		// an extension segment — without this, a vendor whose hdrgm declaration spilled into the
		// extension would be mis-classified as SDR and the gain map would be lost.
		byte[] body = prefixedWith("xmlns:hdrgm=\"...\"");
		JpegSegment extension = newExtendedXmpSeg(body);
		assertTrue(HdrSignature.hasHdrgmInXmp(List.of(extension)));
	}

	@Test
	public void hasHdrgmInXmpFindsMarkerInSecondOfTwoXmpSegments() throws IOException
	{
		// Multi-XMP: the gate must visit ALL XMP segments, not just the first.
		JpegSegment xmpFirst = newXmpSeg(prefixedWith("ordinary content here, no marker"));
		JpegSegment xmpSecond = newXmpSeg(prefixedWith("xmlns:hdrgm=\"...\""));
		assertTrue(HdrSignature.hasHdrgmInXmp(List.of(xmpFirst, xmpSecond)));
	}

	@Test
	public void hasHdrgmInXmpIgnoresHdrgmInNonXmpSegments() throws IOException
	{
		// COM segment carrying the literal bytes — must NOT count. The XMP-only scan exists to close
		// this false-positive class.
		JpegSegment com = nonXmpSeg(0xFE, prefixedWith("comment with marker ", HDRGM_BYTES));
		// EXIF segment with the marker in MakerNote — also must NOT count.
		JpegSegment exif = nonXmpSeg(0xE1, exifPayloadWithMarker());
		assertFalse(HdrSignature.hasHdrgmInXmp(List.of(com, exif)));
	}

	@Test
	public void hasHdrgmInXmpIsCaseSensitive() throws IOException
	{
		// "HDRGM" / "Hdrgm" must NOT match — the spec literal is exact-case lowercase.
		JpegSegment uppercase = newXmpSeg(prefixedWith("xmlns:HDRGM=\"...\""));
		JpegSegment titlecase = newXmpSeg(prefixedWith("xmlns:Hdrgm=\"...\""));
		assertFalse(HdrSignature.hasHdrgmInXmp(List.of(uppercase, titlecase)));
	}

	@Test
	public void hasHdrgmInXmpReturnsFalseForEmptyList()
	{
		assertFalse(HdrSignature.hasHdrgmInXmp(Collections.emptyList()));
	}

	@Test
	public void hasHdrgmInXmpReturnsFalseForNullList()
	{
		assertFalse(HdrSignature.hasHdrgmInXmp(null));
	}

	@Test
	public void hasHdrgmInXmpReturnsFalseWhenNoXmpSegmentsPresent() throws IOException
	{
		// A real loaded JPEG has lots of segments; only XMP ones matter for the gate. Pin that an
		// EXIF-only / MPF-only / COM-only file with NO XMP returns false even when other segments are
		// present (and benign).
		List<JpegSegment> segs = new ArrayList<>();
		segs.add(nonXmpSeg(0xE1, exifPayloadNoMarker()));     // EXIF — not XMP
		segs.add(nonXmpSeg(0xE2, mpfPayload()));              // MPF — not XMP
		segs.add(nonXmpSeg(0xFE, "ordinary comment".getBytes(StandardCharsets.US_ASCII))); // COM
		assertFalse(HdrSignature.hasHdrgmInXmp(segs));
	}

	@Test
	public void isHdrSourceFindsMarkerAnywhereInBytes()
	{
		// Sanity check on the full-file delegate. Used post-Bitmap.compress by UltraHdrCompat.
		byte[] data = prefixedWith("arbitrary bytes ", HDRGM_BYTES, " trailing");
		assertTrue(HdrSignature.isHdrSource(data));
	}

	@Test
	public void isHdrSourceReturnsFalseForNullOrTooShort()
	{
		assertFalse(HdrSignature.isHdrSource(null));
		assertFalse(HdrSignature.isHdrSource(new byte[0]));
		assertFalse(HdrSignature.isHdrSource(new byte[4])); // shorter than the 5-byte marker
	}

	@Test
	public void isHdrgmXmpSegmentFalseOnExtendedXmpWithoutHdrgm() throws IOException
	{
		JpegSegment ext = newExtendedXmpSeg(prefixedWith("extension body without marker"));
		assertFalse(HdrSignature.isHdrgmXmpSegment(ext));
	}

	@Test
	public void isHdrgmXmpSegmentFalseOnNonXmpSegmentCarryingHdrgmBytes() throws IOException
	{
		// Contract re-asserted at the per-segment layer: a COM segment whose body
		// literally contains "hdrgm" must NOT count. CropExporter.stripHdrSegments calls this directly,
		// so a regression that weakened the predicate to "any segment containing hdrgm" would silently
		// drop COM/MakerNote segments on the HDR-drop path.
		JpegSegment com = nonXmpSeg(0xFE, prefixedWith("comment with marker ", HDRGM_BYTES));
		assertFalse(HdrSignature.isHdrgmXmpSegment(com));
	}

	@Test
	public void isHdrgmXmpSegmentFalseOnNonXmpSegmentWithoutHdrgm() throws IOException
	{
		JpegSegment com = nonXmpSeg(0xFE, "ordinary comment".getBytes(StandardCharsets.US_ASCII));
		assertFalse(HdrSignature.isHdrgmXmpSegment(com));
	}

	@Test
	public void isHdrgmXmpSegmentFalseOnStandardXmpWithoutHdrgm() throws IOException
	{
		JpegSegment xmp = newXmpSeg(prefixedWith("ordinary xmp body, no marker"));
		assertFalse(HdrSignature.isHdrgmXmpSegment(xmp));
	}

	@Test
	public void isHdrgmXmpSegmentTrueOnExtendedXmpWithHdrgm() throws IOException
	{
		JpegSegment ext = newExtendedXmpSeg(prefixedWith("xmlns:hdrgm=\"...\""));
		assertTrue(HdrSignature.isHdrgmXmpSegment(ext));
	}

	@Test
	public void isHdrgmXmpSegmentTrueOnStandardXmpWithHdrgm() throws IOException
	{
		JpegSegment xmp = newXmpSeg(prefixedWith("xmlns:hdrgm=\"...\""));
		assertTrue(HdrSignature.isHdrgmXmpSegment(xmp));
	}

	private static byte[] exifPayloadNoMarker() throws IOException
	{
		// "Exif\0\0" + minimal TIFF header. No "hdrgm" sequence.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write('E'); out.write('x'); out.write('i'); out.write('f'); out.write(0); out.write(0);
		out.write(new byte[] { 'I', 'I', '*', 0x00, 8, 0, 0, 0 });
		return out.toByteArray();
	}

	private static byte[] exifPayloadWithMarker() throws IOException
	{
		// "Exif\0\0" + minimal TIFF header + "hdrgm" embedded as if in MakerNote bytes.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write('E'); out.write('x'); out.write('i'); out.write('f'); out.write(0); out.write(0);
		out.write(new byte[] { 'I', 'I', '*', 0x00, 8, 0, 0, 0 });
		out.write(HDRGM_BYTES, 0, HDRGM_BYTES.length);
		return out.toByteArray();
	}

	private static byte[] mpfPayload() throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write('M'); out.write('P'); out.write('F'); out.write(0);
		out.write(new byte[] { 'I', 'I', '*', 0x00 });
		return out.toByteArray();
	}

	/**
	 * Build a JpegSegment with the Extended XMP namespace header — used by the extension-XMP test pinning
	 * that hdrgm in extension segments is also detected.
	 */
	private static JpegSegment newExtendedXmpSeg(byte[] body)
	{
		byte[] header = "http://ns.adobe.com/xmp/extension/\0".getBytes(StandardCharsets.US_ASCII);
		byte[] data = new byte[4 + header.length + body.length];
		data[0] = (byte) 0xFF;
		data[1] = (byte) 0xE1;
		System.arraycopy(header, 0, data, 4, header.length);
		System.arraycopy(body, 0, data, 4 + header.length, body.length);
		return new JpegSegment(0xE1, data);
	}

	/**
	 * Build a JpegSegment whose data array carries the canonical XMP header at offset 4 (matching the layout
	 * JpegMetadataExtractor produces — data[] starts with FF E1 + 2-byte length, then the body). The body
	 * argument is appended after the XMP_HEADER bytes.
	 */
	private static JpegSegment newXmpSeg(byte[] body)
	{
		byte[] data = new byte[4 + XMP_HEADER_BYTES.length + body.length];
		// Bytes 0..3 are the FF E1 + 2-byte length prefix (isXmp() reads past via data[4 + i]); fill with
		// any non-zero so the array isn't trivially zeroed but the prefix bytes don't matter for isXmp.
		data[0] = (byte) 0xFF;
		data[1] = (byte) 0xE1;
		System.arraycopy(XMP_HEADER_BYTES, 0, data, 4, XMP_HEADER_BYTES.length);
		System.arraycopy(body, 0, data, 4 + XMP_HEADER_BYTES.length, body.length);
		return new JpegSegment(0xE1, data);
	}

	/**
	 * Build a JpegSegment whose data array is laid out per JpegMetadataExtractor (FF + marker + 2-byte length +
	 * payload) but which is NOT XMP — payload is whatever the caller provides. Used to construct COM / EXIF /
	 * MPF segments for the negative cases.
	 */
	private static JpegSegment nonXmpSeg(int marker, byte[] payload)
	{
		byte[] data = new byte[4 + payload.length];
		data[0] = (byte) 0xFF;
		data[1] = (byte) marker;
		System.arraycopy(payload, 0, data, 4, payload.length);
		return new JpegSegment(marker, data);
	}

	/**
	 * Concatenate the literal-string and byte-array fragments into a single byte array. Convenience helper for
	 * composing test payloads inline.
	 */
	private static byte[] prefixedWith(Object... parts)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Object part : parts)
		{
			byte[] bytes = (part instanceof byte[] b) ? b
				: ((String) part).getBytes(StandardCharsets.US_ASCII);
			out.write(bytes, 0, bytes.length);
		}
		return out.toByteArray();
	}
}
