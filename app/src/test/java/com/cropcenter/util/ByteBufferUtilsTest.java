package com.cropcenter.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Tests for ByteBufferUtils' read/write helpers and the overflow-safe bounds checks. The bounds checks are
 * private but exercised through every public read/write variant — driving them via the public API gives realistic
 * call-shape coverage and pins down the exact-fit boundary, the one-past-end rejection, the negative-offset rejection,
 * and the wraparound rejection at offsets near Integer.MAX_VALUE that the subtraction-based check rules out.
 *
 * The negative-throw tests use the JUnit 4 try / fail / catch idiom: the call under test runs inside try, fail()
 * trips if no exception is thrown, and the catch body is empty because reaching the catch IS the assertion. The
 * comment in each catch block documents that intent so an auditor reading the empty body doesn't mistake it for a
 * silently swallowed exception.
 */
public final class ByteBufferUtilsTest
{
	@Test
	public void readNegativeOffsetThrows()
	{
		byte[] data = new byte[8];
		try
		{
			ByteBufferUtils.readU16BE(data, -1);
			fail("expected IndexOutOfBoundsException for negative offset");
		}
		catch (IndexOutOfBoundsException ignored)
		{
			// Reaching the catch IS the assertion; the fail() above trips if no throw arrives.
		}
	}

	@Test
	public void readOnNullDataThrows()
	{
		try
		{
			ByteBufferUtils.readU16BE(null, 0);
			fail("expected IndexOutOfBoundsException for null data");
		}
		catch (IndexOutOfBoundsException ignored)
		{
			// Reaching the catch IS the assertion; the fail() above trips if no throw arrives.
		}
	}

	@Test
	public void readU16AtExactEndOffsetSucceeds()
	{
		// offset == data.length - length is the legal-fit boundary; must NOT throw.
		byte[] data = { 0x00, 0x00, 0x12, 0x34 };
		assertEquals(0x1234, ByteBufferUtils.readU16BE(data, 2));
	}

	@Test
	public void readU16AtMaxIntOffsetThrows()
	{
		// Subtraction-based check target. Under an `offset + length > data.length` form,
		// `Integer.MAX_VALUE + 2` wraps to a negative int that fails the bounds check by being <
		// data.length, silently passing — and then the
		// array access at data[Integer.MAX_VALUE] AIOOBEs anyway, but with a less useful trace.
		// Subtraction-based check (`offset > data.length - length`) rejects up-front: MAX_VALUE > (8 - 2) = 6 →
		// throws.
		byte[] data = new byte[8];
		try
		{
			ByteBufferUtils.readU16BE(data, Integer.MAX_VALUE);
			fail("expected IndexOutOfBoundsException for wraparound offset");
		}
		catch (IndexOutOfBoundsException ignored)
		{
			// Reaching the catch IS the assertion; the fail() above trips if no throw arrives.
		}
	}

	@Test
	public void readU16BeAtZeroReturnsValue()
	{
		byte[] data = { 0x12, 0x34 };
		assertEquals(0x1234, ByteBufferUtils.readU16BE(data, 0));
	}

	@Test
	public void readU16EndianDispatchPicksCorrectVariant()
	{
		// Symmetric to readU32EndianDispatchPicksLittleEndianForTrueFlag and the
		// writeU16AndWriteU32EndianDispatchPicksCorrectVariant test. ExifPatcher and BitmapUtils call
		// readU16 with the boolean-dispatch form for orientation tags and IFD field counts; a regression
		// that wired both branches to the same native variant would silently mis-read every Samsung EXIF
		// orientation tag (the most common case being LE-encoded tags read as BE → garbage values).
		byte[] data = { 0x12, 0x34 };
		assertEquals(0x3412, ByteBufferUtils.readU16(data, 0, true));
		assertEquals(0x1234, ByteBufferUtils.readU16(data, 0, false));
	}

	@Test
	public void readU16LeAtZeroReturnsValue()
	{
		byte[] data = { 0x34, 0x12 };
		assertEquals(0x1234, ByteBufferUtils.readU16LE(data, 0));
	}

	@Test
	public void readU16OnUndersizedBufferThrows()
	{
		byte[] data = { 0x12 };  // only 1 byte; readU16 needs 2
		try
		{
			ByteBufferUtils.readU16BE(data, 0);
			fail("expected IndexOutOfBoundsException");
		}
		catch (IndexOutOfBoundsException ignored)
		{
			// Reaching the catch IS the assertion; the fail() above trips if no throw arrives.
		}
	}

	@Test
	public void readU32AtExactEndOffsetSucceeds()
	{
		byte[] data = { 0x12, 0x34, 0x56, 0x78 };
		assertEquals(0x12345678L, ByteBufferUtils.readU32BE(data, 0));
	}

	@Test
	public void readU32BeRoundTripsWrite()
	{
		byte[] data = new byte[8];
		ByteBufferUtils.writeU32BE(data, 2, 0xDEADBEEFL);
		assertEquals(0xDEADBEEFL, ByteBufferUtils.readU32BE(data, 2));
	}

	@Test
	public void readU32EndianDispatchPicksLittleEndianForTrueFlag()
	{
		// readU32 is the endian-dispatched wrapper used at every metadata-pipeline call site; pin down that the
		// boolean dispatches to the correct native reader rather than always going through one path.
		byte[] data = { 0x12, 0x34, 0x56, 0x78 };
		assertEquals(0x78563412L, ByteBufferUtils.readU32(data, 0, true));
		assertEquals(0x12345678L, ByteBufferUtils.readU32(data, 0, false));
	}

	@Test
	public void readU32LeRoundTripsWrite()
	{
		byte[] data = new byte[8];
		ByteBufferUtils.writeU32LE(data, 2, 0xDEADBEEFL);
		assertEquals(0xDEADBEEFL, ByteBufferUtils.readU32LE(data, 2));
	}

	@Test
	public void readU32OnUndersizedBufferThrows()
	{
		byte[] data = { 0x12, 0x34, 0x56 };  // only 3 bytes
		try
		{
			ByteBufferUtils.readU32LE(data, 0);
			fail("expected IndexOutOfBoundsException");
		}
		catch (IndexOutOfBoundsException ignored)
		{
			// Reaching the catch IS the assertion; the fail() above trips if no throw arrives.
		}
	}

	@Test
	public void writeNegativeOffsetThrows()
	{
		byte[] data = new byte[8];
		try
		{
			ByteBufferUtils.writeU32LE(data, -4, 0L);
			fail("expected IndexOutOfBoundsException for negative offset");
		}
		catch (IndexOutOfBoundsException ignored)
		{
			// Reaching the catch IS the assertion; the fail() above trips if no throw arrives.
		}
	}

	@Test
	public void writeU16AndWriteU32EndianDispatchPicksCorrectVariant()
	{
		// Symmetric to readU32EndianDispatch above. ExifPatcher and MpfPatcher use the boolean-dispatched
		// writeU16 / writeU32 to PATCH metadata in place — a regression that wired both branches to the
		// same native variant would silently corrupt every saved Ultra HDR file (MPF offsets land in the
		// wrong byte order, decoders refuse the gain map). Pin the dispatch directly.
		byte[] data = new byte[8];
		ByteBufferUtils.writeU16(data, 0, 0x1234, true);   // LE
		assertEquals(0x34, data[0] & 0xFF);
		assertEquals(0x12, data[1] & 0xFF);
		ByteBufferUtils.writeU16(data, 2, 0x1234, false);  // BE
		assertEquals(0x12, data[2] & 0xFF);
		assertEquals(0x34, data[3] & 0xFF);
		ByteBufferUtils.writeU32(data, 4, 0xDEADBEEFL, true);
		assertEquals(0xDEADBEEFL, ByteBufferUtils.readU32LE(data, 4));
		ByteBufferUtils.writeU32(data, 4, 0xDEADBEEFL, false);
		assertEquals(0xDEADBEEFL, ByteBufferUtils.readU32BE(data, 4));
	}

	@Test
	public void writeU16AtExactEndOffsetSucceeds()
	{
		byte[] data = new byte[2];
		ByteBufferUtils.writeU16BE(data, 0, 0xABCD);
		assertEquals((byte) 0xAB, data[0]);
		assertEquals((byte) 0xCD, data[1]);
	}

	@Test
	public void writeU16OnePastFitThrows()
	{
		byte[] data = new byte[2];
		try
		{
			ByteBufferUtils.writeU16BE(data, 1, 0x1234);
			fail("expected IndexOutOfBoundsException");
		}
		catch (IndexOutOfBoundsException ignored)
		{
			// Reaching the catch IS the assertion; the fail() above trips if no throw arrives.
		}
	}

	@Test
	public void writeU32AtMaxIntOffsetThrows()
	{
		byte[] data = new byte[8];
		try
		{
			ByteBufferUtils.writeU32LE(data, Integer.MAX_VALUE, 0L);
			fail("expected IndexOutOfBoundsException for wraparound offset");
		}
		catch (IndexOutOfBoundsException ignored)
		{
			// Reaching the catch IS the assertion; the fail() above trips if no throw arrives.
		}
	}
}
