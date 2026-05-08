package com.cropcenter.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Tests for the malformed-segment guard in JpegMetadataInjector.inject. The injector walks the re-encoded JPEG's
 * APP/COM markers to find where image data starts; an APP segment that claims a segLen extending past EOF means the
 * re-encoded buffer is structurally broken (Skia bug, byte-stream corruption between encode and inject). Older code
 * silently fell back to scanStart = 2, which produced a JPEG with the re-encoder's APP markers AND original's APP
 * markers stacked back to back. The fix throws IOException so the caller's "Export failed" toast surfaces the actual
 * error.
 */
public final class JpegMetadataInjectorTest
{
	@Test
	public void injectRejectsNonJpegInput()
	{
		byte[] notJpeg = { 0x12, 0x34, 0x56, 0x78 };
		try
		{
			JpegMetadataInjector.inject(notJpeg, Collections.emptyList());
			fail("expected IOException for non-JPEG input");
		}
		catch (IOException e)
		{
			assertTrue("message should mention valid JPEG: " + e.getMessage(),
				e.getMessage().contains("valid JPEG"));
		}
	}

	@Test
	public void injectThrowsOnAppSegmentClaimingLengthPastEof() throws IOException
	{
		// Re-encoded JPEG where the APP0 segment claims a 65535-byte length but only 4 bytes follow. Older code
		// silently fell back to scanStart = 2, producing a stitched output with duplicate APP markers; the fix
		// throws so the caller's toast matches the actual failure mode.
		byte[] reencoded = {
			(byte) 0xFF, (byte) 0xD8,                         // SOI
			(byte) 0xFF, (byte) 0xE0, (byte) 0xFF, (byte) 0xFF, // APP0 with segLen 0xFFFF
			0x00, 0x01, 0x02, 0x03,                           // 4 garbage bytes
		};

		try
		{
			JpegMetadataInjector.inject(reencoded, Collections.emptyList());
			fail("expected IOException for APP segment past EOF");
		}
		catch (IOException e)
		{
			assertTrue("message should mention claims length: " + e.getMessage(),
				e.getMessage().contains("claims length"));
		}
	}

	@Test
	public void injectAcceptsValidReencodedJpegAndPreservesSegmentBytes() throws IOException
	{
		// Reencoded = SOI + small APP0 + DQT + SOS + EOI. Original metadata = single EXIF segment. Output
		// should: start with SOI, contain the EXIF bytes verbatim, end with EOI, and NOT contain the reencoded
		// APP0 (it's part of the stripped marker walk).
		byte[] reencoded = JpegFixtures.concat(
			JpegFixtures.soi(), JpegFixtures.appSegment(0xE0, new byte[] { 'J', 'F', 'I', 'F', 0 }),
			new byte[] { (byte) 0xFF, (byte) 0xDB, 0x00, 0x04, 0x00, 0x00 }, // DQT
			JpegFixtures.minimalScanAndEoi());

		byte[] exifPayload = new byte[14];
		exifPayload[0] = 'E';
		exifPayload[1] = 'x';
		exifPayload[2] = 'i';
		exifPayload[3] = 'f';
		// trailing nulls already present
		byte[] exifSegBytes = JpegFixtures.appSegment(0xE1, exifPayload);
		JpegSegment exif = new JpegSegment(0xE1, exifSegBytes);
		List<JpegSegment> segments = Collections.singletonList(exif);

		byte[] result = JpegMetadataInjector.inject(reencoded, segments);
		assertNotNull(result);
		assertEquals((byte) 0xFF, result[0]);
		assertEquals((byte) 0xD8, result[1]);
		assertEquals((byte) 0xFF, result[result.length - 2]);
		assertEquals((byte) 0xD9, result[result.length - 1]);

		// EXIF bytes should appear contiguously after SOI.
		boolean foundExif = false;
		for (int i = 0; i <= result.length - exifSegBytes.length; i++)
		{
			boolean match = true;
			for (int j = 0; j < exifSegBytes.length; j++)
			{
				if (result[i + j] != exifSegBytes[j])
				{
					match = false;
					break;
				}
			}
			if (match)
			{
				foundExif = true;
				break;
			}
		}
		assertTrue("output should contain the original EXIF bytes verbatim", foundExif);
	}

	@Test
	public void injectHandlesFillBytesBeforeReencoderApp() throws IOException
	{
		// Per ITU-T T.81 §B.1.1.2, any marker may be preceded by any number of 0xFF fill bytes. Skia's
		// Bitmap.compress could legitimately emit FF FF E0 for APP0 alignment. Without the fill-byte handling
		// added in the recent fix, the injector mis-read the second 0xFF as marker code 0xFF, then parsed
		// garbage as the segment length. This regression test pins the fix.
		byte[] reencoded = JpegFixtures.concat(
			JpegFixtures.soi(),
			new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xE0, // FF + fill + APP0 marker
				0x00, 0x07, 'J', 'F', 'I', 'F', 0 },           // segLen=7 + payload "JFIF\0"
			new byte[] { (byte) 0xFF, (byte) 0xDB, 0x00, 0x04, 0x00, 0x00 }, // DQT
			JpegFixtures.minimalScanAndEoi());

		byte[] result = JpegMetadataInjector.inject(reencoded, Collections.emptyList());
		// Output should start with SOI and end with EOI. Crucially, the JFIF payload from the re-encoder
		// should NOT appear (the walker correctly skipped it).
		assertEquals((byte) 0xFF, result[0]);
		assertEquals((byte) 0xD8, result[1]);
		assertEquals((byte) 0xFF, result[result.length - 2]);
		assertEquals((byte) 0xD9, result[result.length - 1]);
		// Confirm "JFIF" wasn't carried through (proves the APP0 was stripped).
		boolean foundJfif = false;
		for (int i = 0; i < result.length - 4; i++)
		{
			if (result[i] == 'J' && result[i + 1] == 'F' && result[i + 2] == 'I' && result[i + 3] == 'F')
			{
				foundJfif = true;
				break;
			}
		}
		assertTrue("re-encoder JFIF should be stripped from output", !foundJfif);
	}

	@Test
	public void injectRejectsTruncatedReencodedJpeg()
	{
		// Reencoded = just SOI (2 bytes). Length check: < 4, so the up-front "Not a valid JPEG" guard catches
		// it. Pinned to make sure that guard isn't loosened in a future refactor.
		byte[] reencoded = { (byte) 0xFF, (byte) 0xD8 };
		try
		{
			JpegMetadataInjector.inject(reencoded, Collections.emptyList());
			fail("expected IOException for too-short input");
		}
		catch (IOException e)
		{
			assertTrue("message should mention valid JPEG: " + e.getMessage(),
				e.getMessage().contains("valid JPEG"));
		}
	}
}
