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
