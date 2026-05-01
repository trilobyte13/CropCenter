package com.cropcenter.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Tests for the SOS-finder bounds guards in GraftWriter.findPrimaryEoi. Two adversarial
 * edit-JPEG shapes are exercised through the public graft() entry point: a truncated
 * SOS header that would IndexOutOfBoundsException without the (off + 4 > file.length)
 * guard, and an SOS-length value of 0xFFFF that would either index past EOF or wrap
 * scanOff to a small-positive int without the (scanOff > file.length) guard. Both must
 * surface as a clean "no recoverable primary scan" IOException so the caller can show
 * a real "graft failed" toast — silently returning a corrupt splice would wipe Samsung
 * Gallery's Revert pre-flight on round-tripped edits.
 */
public class GraftWriterTest
{
	private static final byte[] DQT_STUB =
	{
		(byte) 0xFF, (byte) 0xDB, 0x00, 0x04, 0x00, 0x00,
	};

	@Test
	public void graftAcceptsValidEditWithLegalSosAndEoi() throws IOException
	{
		// Sanity counterpart for the two failure tests: confirm the new guards don't
		// false-trip on a well-formed edit. orig = SOI + DQT + minimal SOS scan + EOI;
		// edit = same shape. graft should produce a non-null result that begins with
		// SOI and ends with EOI.
		byte[] valid = JpegFixtures.concat(
			JpegFixtures.soi(),
			DQT_STUB,
			JpegFixtures.minimalScanAndEoi());

		byte[] result = GraftWriter.graft(valid, valid.clone());
		assertNotNull(result);
		assertEquals((byte) 0xFF, result[0]);
		assertEquals((byte) 0xD8, result[1]);
		assertEquals((byte) 0xFF, result[result.length - 2]);
		assertEquals((byte) 0xD9, result[result.length - 1]);
	}

	@Test
	public void graftRejectsEditTruncatedMidSosHeader() throws IOException
	{
		// Edit = SOI + DQT + SOS marker truncated (only 1 byte after FF DA, missing
		// the second byte of the 2-byte length field). Without the (off + 4 >
		// file.length) guard, findPrimaryEoi would call ByteBufferUtils.readU16BE on
		// out-of-range bytes and throw AIOOBE, crashing the bg thread silently. With
		// the guard, findPrimaryEoi returns -1 and graft throws IOException so the
		// caller can show a real toast.
		byte[] orig = JpegFixtures.concat(
			JpegFixtures.soi(),
			DQT_STUB,
			JpegFixtures.minimalScanAndEoi());
		byte[] edit = JpegFixtures.concat(
			JpegFixtures.soi(),
			DQT_STUB,
			new byte[] { (byte) 0xFF, (byte) 0xDA, 0x00 });

		try
		{
			GraftWriter.graft(orig, edit);
			fail("expected IOException for truncated SOS header");
		}
		catch (IOException e)
		{
			assertTrue("message should mention recoverable primary scan: " + e.getMessage(),
				e.getMessage().contains("primary scan"));
		}
	}

	@Test
	public void graftRejectsEditWithSosLenCausingScanOffPastEof() throws IOException
	{
		// Edit = SOI + DQT + SOS with 0xFFFF length but only a handful of bytes after.
		// scanOff = off + 2 + 0xFFFF lands ~65k past EOF; without the
		// (scanOff > file.length) guard, the inner scan loop would run on a phantom
		// region of bytes outside the array (caught by the loop's own bounds check
		// returning -1 silently) — but on overflow paths where sosLen is even larger,
		// scanOff itself wraps to a small positive int that satisfies the inner
		// loop's bounds and reads from arbitrary positions. The clamp makes both
		// cases bail explicitly.
		byte[] orig = JpegFixtures.concat(
			JpegFixtures.soi(),
			DQT_STUB,
			JpegFixtures.minimalScanAndEoi());
		ByteArrayOutputStream editOut = new ByteArrayOutputStream();
		editOut.write(JpegFixtures.soi());
		editOut.write(DQT_STUB);
		editOut.write(0xFF);
		editOut.write(0xDA);
		editOut.write(0xFF);   // sosLen high byte
		editOut.write(0xFF);   // sosLen low byte = 0xFFFF
		editOut.write(new byte[] { 0x01, 0x02, 0x03, 0x04 }); // few padding bytes
		byte[] edit = editOut.toByteArray();

		try
		{
			GraftWriter.graft(orig, edit);
			fail("expected IOException for adversarial SOS length");
		}
		catch (IOException e)
		{
			assertTrue("message should mention recoverable primary scan: " + e.getMessage(),
				e.getMessage().contains("primary scan"));
		}
	}
}
