package com.cropcenter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for ImageLoadController's signature-detection helpers. These gate the "unsupported image format" rejection that
 * prevents HEIC / WebP / GIF / etc. from silently loading as PNG via the share/view intent path. Without explicit
 * signature checks, BitmapFactory's tolerant decode would let the rest of the pipeline misclassify non-JPEG/non-PNG
 * sources, the save format toggle would default to PNG, and the file would re-encode under a misleading toast — exactly
 * the "UI must not lie about state" failure mode CLAUDE.md flags.
 */
public class ImageLoadControllerTest
{
	@Test
	public void isJpegSignatureAcceptsValidSoi()
	{
		byte[] jpeg = { (byte) 0xFF, (byte) 0xD8, 0x00, 0x10 };
		assertTrue(ImageLoadController.isJpegSignature(jpeg));
	}

	@Test
	public void isJpegSignatureRejectsTruncatedInput()
	{
		// Single byte — not enough for the SOI check.
		byte[] tooShort = { (byte) 0xFF };
		assertFalse(ImageLoadController.isJpegSignature(tooShort));
	}

	@Test
	public void isJpegSignatureRejectsNonSoiPrefix()
	{
		// PNG signature start — must not be misidentified as JPEG.
		byte[] png = { (byte) 0x89, 'P', 'N', 'G' };
		assertFalse(ImageLoadController.isJpegSignature(png));
	}

	@Test
	public void isPngSignatureAcceptsFullEightByteSignature()
	{
		byte[] png = {
			(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
			0x00, 0x00, 0x00, 0x0D,   // start of IHDR length
		};
		assertTrue(ImageLoadController.isPngSignature(png));
	}

	@Test
	public void isPngSignatureRejectsTruncatedInput()
	{
		// First seven bytes correct, eighth byte missing — not enough for the signature.
		byte[] truncated = {
			(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A,
		};
		assertFalse(ImageLoadController.isPngSignature(truncated));
	}

	@Test
	public void isPngSignatureRejectsCorruptedSignatureBytes()
	{
		// Final byte wrong (PNG sig requires 0x0A; 0x0B would mean some other format or a corruption).
		byte[] corrupted = {
			(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0B,
		};
		assertFalse(ImageLoadController.isPngSignature(corrupted));
	}

	@Test
	public void heicMagicBytesAreRejectedByBothChecks()
	{
		// HEIC ftyp box: 4-byte length + "ftyp" + brand. BitmapFactory on Android 28+ can decode this, which is
		// exactly why the explicit signature gate matters — without it, HEIC would slip past the JPEG check and
		// fall through to PNG classification.
		byte[] heic = {
			0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0x00, 0x00, 0x00, 0x00,
		};
		assertFalse("HEIC must not pass JPEG check", ImageLoadController.isJpegSignature(heic));
		assertFalse("HEIC must not pass PNG check", ImageLoadController.isPngSignature(heic));
	}

	@Test
	public void webpMagicBytesAreRejectedByBothChecks()
	{
		// WebP: "RIFF????WEBP" container. Same rejection requirement as HEIC.
		byte[] webp = {
			'R', 'I', 'F', 'F', 0x00, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' ',
		};
		assertFalse("WebP must not pass JPEG check", ImageLoadController.isJpegSignature(webp));
		assertFalse("WebP must not pass PNG check", ImageLoadController.isPngSignature(webp));
	}

	@Test
	public void emptyBytesAreRejectedByBothChecks()
	{
		byte[] empty = new byte[0];
		assertFalse(ImageLoadController.isJpegSignature(empty));
		assertFalse(ImageLoadController.isPngSignature(empty));
	}
}
