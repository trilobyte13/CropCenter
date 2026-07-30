package com.cropcenter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Integration test for ImageLoadController.applyBytes — specifically the unsupported-image-format gate. Without that
 * gate, BitmapFactory's tolerant decoder would happily accept HEIC / WebP / GIF bytes and pass them through to the rest
 * of the pipeline (which only models JPEG and PNG). The save flow would then default to "PNG" and re-encode the source
 * under a misleading toast — exactly the "UI must not lie about state" failure mode CLAUDE.md flags.
 *
 * The unit-level isJpeg/isPngSignature tests already pin the signature checks. This test runs ONE level higher:
 * `applyBytes(unsupported, name)` returns false AND posts the user-facing rejection toast, asserted through the
 * shared RecordingImageLoadHost fake.
 */
public final class ImageLoadControllerApplyBytesTest
{
	@Test
	public void applyBytesRejectsEmptyInput()
	{
		// Zero-length array — both signature checks short-circuit on the length predicate. Pin the rejection
		// AND the user-visible toast so a future change that loosens the length guard still surfaces as a
		// user-facing rejection rather than a silent state mutation on whatever default Bitmap.decodeByteArray
		// returns for empty bytes. Matches the assertion shape used by the HEIC / WebP sibling tests so all
		// three unsupported-input paths pin the toast contract symmetrically.
		RecordingImageLoadHost fake = new RecordingImageLoadHost();
		ImageLoadController controller = new ImageLoadController(fake, null);

		boolean result = controller.applyBytes(new byte[0], "empty.jpg");

		assertFalse("empty bytes must not be accepted", result);
		assertNotNull("rejection toast must fire for empty input too", fake.lastToastMessage);
	}

	@Test
	public void applyBytesRejectsHeicWithUnsupportedFormatToast()
	{
		// HEIC ftyp box: 4-byte length + "ftyp" + brand. Falls outside both signature checks.
		byte[] heic = {
			0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0x00, 0x00, 0x00, 0x00,
		};
		RecordingImageLoadHost fake = new RecordingImageLoadHost();
		ImageLoadController controller = new ImageLoadController(fake, null);

		boolean result = controller.applyBytes(heic, "image.heic");

		assertFalse("HEIC bytes must not be accepted as a JPEG / PNG source", result);
		assertNotNull("rejection toast must fire so the user knows what happened", fake.lastToastMessage);
		// Pin the toast prefix so a future cosmetic-only edit to the message keeps the contract that the toast
		// names the format problem rather than just saying "Failed".
		String toast = fake.lastToastMessage;
		assertFalse("toast must not be the generic decode-failure message: " + toast,
			toast.toLowerCase().contains("failed to decode"));
	}

	@Test
	public void applyBytesRejectsWebpWithUnsupportedFormatToast()
	{
		byte[] webp = {
			'R', 'I', 'F', 'F', 0x00, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' ',
		};
		RecordingImageLoadHost fake = new RecordingImageLoadHost();
		ImageLoadController controller = new ImageLoadController(fake, null);

		boolean result = controller.applyBytes(webp, "image.webp");

		assertFalse("WebP bytes must not be accepted as a JPEG / PNG source", result);
		assertNotNull("rejection toast must fire so the user knows what happened", fake.lastToastMessage);
	}
}
