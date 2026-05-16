package com.cropcenter.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the JPEG marker constants against ITU-T T.81 / ISO/IEC 10918-1. These constants are the second byte of
 * each FF-prefixed marker pair (e.g. SOI is FF D8, so SOI = 0xD8) and are referenced by every JPEG byte-walker
 * in the codebase. A regression that flipped one would mis-route the entire marker walk and produce malformed
 * JPEG output.
 */
public final class JpegMarkerTest
{
	@Test
	public void appFamilyMarkerRange()
	{
		// APP0..APP15 spans FF E0..FF EF (16 markers). The walkers iterate every APP-marker via
		// `marker >= APP0 && marker <= APP_LAST`. A regression that flipped APP1 from 0xE1 to
		// 0xE2 would silently route every EXIF reader through the MPF marker code path; the
		// existing walker tests pass byte fixtures, so a constant-drift bug wouldn't surface
		// there. Pin every value the codebase uses plus the 16-marker range invariant.
		assertEquals(0xE0, JpegMarker.APP0);
		assertEquals(0xE1, JpegMarker.APP1);
		assertEquals(0xE2, JpegMarker.APP2);
		assertEquals(0xEF, JpegMarker.APP_LAST);
		assertEquals("APP0..APP15 spans 16 markers", 16, JpegMarker.APP_LAST - JpegMarker.APP0 + 1);
	}

	@Test
	public void prefixAndCommentMarkers()
	{
		// PREFIX = 0xFF introduces every JPEG marker pair (FF MM). COM = 0xFE (FF FE) is the
		// comment marker. Both are referenced directly by test fixtures and byte-walkers; a
		// regression in either would mis-route the entire marker walk.
		assertEquals(0xFF, JpegMarker.PREFIX);
		assertEquals(0xFE, JpegMarker.COM);
	}

	@Test
	public void rstMarkerRange()
	{
		// Restart markers FF D0 through FF D7 — RST0..RST7 standalone markers interleaved into SOS data for
		// MCU-boundary resync. Walkers skip them.
		assertEquals(0xD0, JpegMarker.RST_FIRST);
		assertEquals(0xD7, JpegMarker.RST_LAST);
		assertTrue("RST_LAST > RST_FIRST", JpegMarker.RST_LAST > JpegMarker.RST_FIRST);
		assertEquals("8 restart markers (RST0..RST7)", 8, JpegMarker.RST_LAST - JpegMarker.RST_FIRST + 1);
	}

	@Test
	public void soiSosEoiMarkers()
	{
		// Spec values: SOI = FF D8, SOS = FF DA, EOI = FF D9. Out-of-order ordering relative to SOS / EOI
		// position would silently break entry-into-scan and end-of-image detection.
		assertEquals(0xD8, JpegMarker.SOI);
		assertEquals(0xDA, JpegMarker.SOS);
		assertEquals(0xD9, JpegMarker.EOI);
	}

	@Test
	public void stuffingAndTem()
	{
		// FF 00 byte-stuffing escape — a literal FF inside entropy-coded data. FF 01 = TEM, the temporary
		// marker for arithmetic coding (rare). Both are standalone (no length field).
		assertEquals(0x00, JpegMarker.STUFFING);
		assertEquals(0x01, JpegMarker.TEM);
	}
}
