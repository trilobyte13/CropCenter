package com.cropcenter.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins ColorPickerDialog.parseHexColor — the pure seam behind the hex-field TextWatcher's hex↔alpha mediation. The
 * watcher fires per keystroke, so the empty-for-partial contract is load-bearing: a regression that returned a value
 * for incomplete input would push a half-typed color into the alpha slider (and through the slider's listener, back
 * into the field) mid-typing. The unsigned-parse path matters too: an Integer.parseInt rewrite rejects every
 * translucent color with alpha at or above 0x80, silently breaking typed entry for exactly the palette half the
 * translucent grid ships — HexFormat.fromHexDigits reads the eight digits as an unsigned 32-bit value.
 */
public final class ColorPickerDialogTest
{
	@Test
	public void parseHexColorAcceptsEightDigitArgbWithAndWithoutHash()
	{
		assertEquals(0x80FF00FF, ColorPickerDialog.parseHexColor("#80FF00FF").orElseThrow().intValue());
		assertEquals(0x80FF00FF, ColorPickerDialog.parseHexColor("80FF00FF").orElseThrow().intValue());
		// Lowercase digits parse the same — EditText input isn't case-normalized.
		assertEquals(0x80FF00FF, ColorPickerDialog.parseHexColor("#80ff00ff").orElseThrow().intValue());
	}

	@Test
	public void parseHexColorHandlesTopBitSetAlphaWithoutOverflow()
	{
		// "FFFFFFFF" is a valid 32-bit ARGB bit pattern (opaque white) but exceeds Integer.MAX_VALUE as a
		// positive number — Integer.parseInt would throw. The seam must parse the digits as an unsigned
		// 32-bit value (HexFormat.fromHexDigits), yielding the negative-int bit pattern.
		assertEquals(0xFFFFFFFF, ColorPickerDialog.parseHexColor("FFFFFFFF").orElseThrow().intValue());
		assertEquals(0xFF000000, ColorPickerDialog.parseHexColor("#FF000000").orElseThrow().intValue());
	}

	@Test
	public void parseHexColorPrependsOpaqueAlphaToSixDigitForms()
	{
		assertEquals(0xFF3366CC, ColorPickerDialog.parseHexColor("#3366CC").orElseThrow().intValue());
		assertEquals(0xFF3366CC, ColorPickerDialog.parseHexColor("3366CC").orElseThrow().intValue());
	}

	@Test
	public void parseHexColorRejectsNonAsciiHexDigits()
	{
		// The hex-digit guard is ASCII-strict (HexFormat.isHexDigit). Character.digit-based guards accept
		// Unicode presentation forms — fullwidth "ＦＦ00FF00" (an IME-insertable shape) would parse as a color.
		// A hex color field is ASCII by contract; pin the rejection.
		assertTrue(ColorPickerDialog.parseHexColor("ＦＦ00FF00").isEmpty());
		assertTrue(ColorPickerDialog.parseHexColor("#ＦＦ00FF00").isEmpty());
	}

	@Test
	public void parseHexColorRejectsNonHexCharacters()
	{
		// 6-character garbage gets the FF prefix and reaches the parse, which must reject it — the pre-seam
		// code relied on the same NumberFormatException path.
		assertTrue(ColorPickerDialog.parseHexColor("#1Z34AB").isEmpty());
		assertTrue(ColorPickerDialog.parseHexColor("GGGGGGGG").isEmpty());
	}

	@Test
	public void parseHexColorRejectsSignPrefixedInput()
	{
		// A sign-tolerant numeric parse would let "-1234567" / "+1234567" (a sign plus SEVEN hex digits) pass
		// the length-8 gate and parse ("-1234567" → 0xFEDCBA99) — a phantom color pushed into the alpha
		// slider and swatch by the per-keystroke watcher mid-typing, violating the documented "complete 6- or
		// 8-digit hex" contract. The hex-digit guard must reject both signs.
		assertTrue(ColorPickerDialog.parseHexColor("-1234567").isEmpty());
		assertTrue(ColorPickerDialog.parseHexColor("+1234567").isEmpty());
		// Sign-prefixed 6-char forms take the FF-prefix path ("-12345" → "FF-12345") — the mid-string sign must
		// reject there too.
		assertTrue(ColorPickerDialog.parseHexColor("#-12345").isEmpty());
	}

	@Test
	public void parseHexColorReturnsEmptyForPartialInput()
	{
		// Every per-keystroke prefix of a color must read as "not yet a color", never a value.
		assertTrue(ColorPickerDialog.parseHexColor("").isEmpty());
		assertTrue(ColorPickerDialog.parseHexColor("#").isEmpty());
		assertTrue(ColorPickerDialog.parseHexColor("F").isEmpty());
		assertTrue(ColorPickerDialog.parseHexColor("FF00").isEmpty());
		// 7 digits: too long for the FF-prefix rule, too short for full ARGB.
		assertTrue(ColorPickerDialog.parseHexColor("1234567").isEmpty());
		assertTrue(ColorPickerDialog.parseHexColor("#123456789").isEmpty());
	}

	@Test
	public void parseHexColorTrimsSurroundingWhitespace()
	{
		assertEquals(0xFF3366CC, ColorPickerDialog.parseHexColor(" #3366CC ").orElseThrow().intValue());
	}
}
