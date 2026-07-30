package com.cropcenter.crop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.cropcenter.metadata.JpegFixtures;
import com.cropcenter.metadata.JpegSegment;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Tests for CropExporter.stripHdrSegments — the HDR-drop helper that removes MPF + hdrgm-containing XMP segments from
 * the injected metadata when the gain-map compose path didn't actually produce a gain map. Without this strip, the
 * saved JPEG's metadata would claim HDR (via XMP hdrgm namespace + APP2 MPF anchor) that the file doesn't actually
 * carry — strict decoders' multi-picture pre-flight would reject the orphaned-metadata shape, lenient decoders that
 * scan for hdrgm would render with the wrong offset.
 *
 * The Extended-XMP branch is load-bearing detection: vendors who write XMP larger than the JPEG APP1 ~64 KB cap split
 * the hdrgm declaration across multiple APP1 segments (the first carries the standard XMP_HEADER +
 * xmpNote:HasExtendedXMP, subsequent segments use the extension prefix). A regression that drops the Extended-XMP
 * branch would silently leak the HDR claim past the strip; this test pins both the standard-XMP and Extended-XMP cases.
 */
public final class CropExporterStripHdrTest
{
	@Test
	public void emptyListReturnsEmptyList()
	{
		// Defensive: edge case where source had no segments at all. Strip is a no-op.
		List<JpegSegment> out = CropExporter.stripHdrSegments(Collections.emptyList());
		assertTrue("empty input must yield empty output", out.isEmpty());
	}

	@Test
	public void exifAndIccPreservedThroughStrip() throws IOException
	{
		// Non-HDR segments (EXIF, ICC) must survive the strip — only MPF + hdrgm-XMP get removed. Pin the
		// fall-through `filtered.add(seg)` at the end of the loop so a future "filter is restrictive instead of
		// permissive" refactor doesn't accidentally drop the identity metadata.
		JpegSegment exif = makeExifSegment();
		JpegSegment icc = makeIccSegment();
		List<JpegSegment> out = CropExporter.stripHdrSegments(Arrays.asList(exif, icc));
		assertEquals("EXIF + ICC must both survive", 2, out.size());
		assertTrue("EXIF preserved", out.contains(exif));
		assertTrue("ICC preserved", out.contains(icc));
	}

	@Test
	public void extendedXmpHdrgmStripped() throws IOException
	{
		// Regression target. Build an Extended-XMP segment carrying hdrgm — the kind a vendor splits the second
		// half of their XMP body into when the standard XMP packet exceeds 64 KB. strip must drop it via
		// HdrSignature.isHdrgmXmpSegment's Extended-XMP branch; a regression that only looks at standard XMP
		// would silently let the HDR claim through.
		JpegSegment extXmp = makeExtendedXmpSegment(true);
		List<JpegSegment> out = CropExporter.stripHdrSegments(Collections.singletonList(extXmp));
		assertTrue("Extended-XMP with hdrgm must be stripped", out.isEmpty());
	}

	@Test
	public void extendedXmpWithoutHdrgmPreserved() throws IOException
	{
		// Mirror: an Extended-XMP segment whose body doesn't contain hdrgm (e.g., vendor-specific extension
		// metadata unrelated to HDR) must survive. Pins that the strip is hdrgm-gated, not a blanket
		// Extended-XMP drop.
		JpegSegment extXmp = makeExtendedXmpSegment(false);
		List<JpegSegment> out = CropExporter.stripHdrSegments(Collections.singletonList(extXmp));
		assertEquals("Extended-XMP without hdrgm must survive", 1, out.size());
	}

	@Test
	public void mixedListProducesCorrectStripPattern() throws IOException
	{
		// Realistic case: source carries EXIF + ICC + standard XMP (hdrgm) + Extended XMP (hdrgm) + MPF. After
		// strip: only EXIF and ICC remain (both XMPs and MPF gone). Order of remaining segments matches input
		// order (the filter preserves traversal order via filtered.add).
		JpegSegment exif = makeExifSegment();
		JpegSegment standardXmp = makeStandardXmpSegment(true);
		JpegSegment icc = makeIccSegment();
		JpegSegment extXmp = makeExtendedXmpSegment(true);
		JpegSegment mpf = makeMpfSegment();
		List<JpegSegment> out = CropExporter.stripHdrSegments(
			Arrays.asList(exif, standardXmp, icc, extXmp, mpf));
		// Position-pinned: with exactly 2 survivors at the input-relative positions, the three HDR segments are
		// necessarily gone AND traversal order is proven, not just membership.
		assertEquals("only EXIF + ICC should remain", 2, out.size());
		assertSame("EXIF first (input order preserved)", exif, out.get(0));
		assertSame("ICC second (input order preserved)", icc, out.get(1));
	}

	@Test
	public void mpfSegmentStripped() throws IOException
	{
		// Any APP2 MPF segment gets dropped on the HDR-drop path. The MPF entries point at offsets where the
		// gain map WOULD have been; leaving the segment in produces orphan multi-picture metadata (the
		// orphan-MPF leak appears under the load-time strip path as well, with a different trigger).
		JpegSegment mpf = makeMpfSegment();
		List<JpegSegment> out = CropExporter.stripHdrSegments(Collections.singletonList(mpf));
		assertTrue("MPF must be stripped on the HDR-drop path", out.isEmpty());
	}

	@Test
	public void standardXmpHdrgmStripped() throws IOException
	{
		// Standard-XMP path — the canonical HDR-claim location. Strip drops it.
		JpegSegment xmp = makeStandardXmpSegment(true);
		List<JpegSegment> out = CropExporter.stripHdrSegments(Collections.singletonList(xmp));
		assertTrue("standard XMP with hdrgm must be stripped", out.isEmpty());
	}

	@Test
	public void standardXmpWithoutHdrgmPreserved() throws IOException
	{
		// Mirror: standard XMP without hdrgm (e.g., basic copyright / camera-info XMP from any non-HDR-aware
		// encoder) must survive. Strip is hdrgm-gated, not a blanket XMP drop.
		JpegSegment xmp = makeStandardXmpSegment(false);
		List<JpegSegment> out = CropExporter.stripHdrSegments(Collections.singletonList(xmp));
		assertEquals("standard XMP without hdrgm must survive", 1, out.size());
	}

	private static JpegSegment makeExifSegment() throws IOException
	{
		return new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, JpegFixtures.exifAppPayload()));
	}

	private static JpegSegment makeExtendedXmpSegment(boolean withHdrgm) throws IOException
	{
		String body = withHdrgm
			? "<rdf:Description xmlns:hdrgm='http://ns.adobe.com/hdr-gain-map/1.0/'/>"
			: "<rdf:Description xmlns:custom='http://example.com/'/>";
		byte[] payload = JpegFixtures.extendedXmpChunk("A1B2C3D4E5F60718293A4B5C6D7E8F90", 0L, 0L,
			body.getBytes(StandardCharsets.US_ASCII));
		return new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, payload));
	}

	private static JpegSegment makeIccSegment() throws IOException
	{
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write("ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII));
		payload.write(new byte[] { 1, 1, 0, 0, 0, 0 });
		return new JpegSegment(0xE2, JpegFixtures.appSegment(0xE2, payload.toByteArray()));
	}

	private static JpegSegment makeMpfSegment() throws IOException
	{
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write("MPF\0".getBytes(StandardCharsets.US_ASCII));
		payload.write(new byte[] { 'I', 'I', 0x2A, 0, 8, 0, 0, 0 });
		payload.write(new byte[] { 0, 0, 0, 0 });
		return new JpegSegment(0xE2, JpegFixtures.appSegment(0xE2, payload.toByteArray()));
	}

	private static JpegSegment makeStandardXmpSegment(boolean withHdrgm) throws IOException
	{
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write(JpegSegment.XMP_HEADER.getBytes(StandardCharsets.US_ASCII));
		if (withHdrgm)
		{
			payload.write("<rdf:Description xmlns:hdrgm='http://ns.adobe.com/hdr-gain-map/1.0/'/>"
				.getBytes(StandardCharsets.US_ASCII));
		}
		else
		{
			payload.write("<rdf:Description xmlns:dc='http://purl.org/dc/elements/1.1/'/>"
				.getBytes(StandardCharsets.US_ASCII));
		}
		return new JpegSegment(0xE1, JpegFixtures.appSegment(0xE1, payload.toByteArray()));
	}
}
