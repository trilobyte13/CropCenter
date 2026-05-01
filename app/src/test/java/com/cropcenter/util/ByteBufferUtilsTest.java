package com.cropcenter.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Tests for ByteBufferUtils' read/write helpers and the round-6 overflow-safe bounds
 * checks. The bounds checks are private but exercised through every public read/write
 * variant — driving them via the public API gives realistic call-shape coverage and
 * pins down the exact-fit boundary, the one-past-end rejection, the negative-offset
 * rejection, and the wraparound rejection at offsets near Integer.MAX_VALUE that the
 * round-6 fix made impossible.
 */
public class ByteBufferUtilsTest
{
	@Test
	public void readU16BeAtZeroReturnsValue()
	{
		byte[] data = { 0x12, 0x34 };
		assertEquals(0x1234, ByteBufferUtils.readU16BE(data, 0));
	}

	@Test
	public void readU16LeAtZeroReturnsValue()
	{
		byte[] data = { 0x34, 0x12 };
		assertEquals(0x1234, ByteBufferUtils.readU16LE(data, 0));
	}

	@Test
	public void readU32LeRoundTripsWrite()
	{
		byte[] data = new byte[8];
		ByteBufferUtils.writeU32LE(data, 2, 0xDEADBEEFL);
		assertEquals(0xDEADBEEFL, ByteBufferUtils.readU32LE(data, 2));
	}

	@Test
	public void readU32BeRoundTripsWrite()
	{
		byte[] data = new byte[8];
		ByteBufferUtils.writeU32BE(data, 2, 0xDEADBEEFL);
		assertEquals(0xDEADBEEFL, ByteBufferUtils.readU32BE(data, 2));
	}

	@Test
	public void readU16AtExactEndOffsetSucceeds()
	{
		// offset == data.length - length is the legal-fit boundary; must NOT throw.
		byte[] data = { 0x00, 0x00, 0x12, 0x34 };
		assertEquals(0x1234, ByteBufferUtils.readU16BE(data, 2));
	}

	@Test
	public void readU32AtExactEndOffsetSucceeds()
	{
		byte[] data = { 0x12, 0x34, 0x56, 0x78 };
		assertEquals(0x12345678L, ByteBufferUtils.readU32BE(data, 0));
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
		}
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
		}
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
		}
	}

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
		}
	}

	@Test
	public void readU16AtMaxIntOffsetThrows()
	{
		// Round-6 fix target. Under the old `offset + length > data.length` form,
		// `Integer.MAX_VALUE + 2` wraps to a negative int that fails the bounds
		// check by being < data.length, silently passing — and then the array
		// access at data[Integer.MAX_VALUE] AIOOBEs anyway, but with a less
		// useful trace. Subtraction-based check (`offset > data.length - length`)
		// rejects up-front: MAX_VALUE > (8 - 2) = 6 → throws.
		byte[] data = new byte[8];
		try
		{
			ByteBufferUtils.readU16BE(data, Integer.MAX_VALUE);
			fail("expected IndexOutOfBoundsException for wraparound offset");
		}
		catch (IndexOutOfBoundsException ignored)
		{
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
		}
	}

	@Test
	public void readU32EndianDispatchPicksLittleEndianForTrueFlag()
	{
		// readU32 is the endian-dispatched wrapper used at every metadata-pipeline
		// call site; pin down that the boolean dispatches to the correct native
		// reader rather than always going through one path.
		byte[] data = { 0x12, 0x34, 0x56, 0x78 };
		assertEquals(0x78563412L, ByteBufferUtils.readU32(data, 0, true));
		assertEquals(0x12345678L, ByteBufferUtils.readU32(data, 0, false));
	}
}
