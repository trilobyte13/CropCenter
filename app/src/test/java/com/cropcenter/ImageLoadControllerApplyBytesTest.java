package com.cropcenter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;

import com.cropcenter.model.CropState;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Integration test for ImageLoadController.applyBytes — specifically the unsupported-image-format gate. Without that
 * gate, BitmapFactory's tolerant decoder would happily accept HEIC / WebP / GIF bytes and pass them through to the
 * rest of the pipeline (which only models JPEG and PNG). The save flow would then default to "PNG" and re-encode
 * the source under a misleading toast — exactly the "UI must not lie about state" failure mode CLAUDE.md flags.
 *
 * The unit-level isJpeg/isPngSignature tests already pin the signature checks. This test runs ONE level higher:
 * `applyBytes(unsupported, name)` returns false AND posts the user-facing rejection toast.
 */
public final class ImageLoadControllerApplyBytesTest
{
	@Test
	public void applyBytesRejectsHeicWithUnsupportedFormatToast()
	{
		// HEIC ftyp box: 4-byte length + "ftyp" + brand. Falls outside both signature checks.
		byte[] heic = {
			0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0x00, 0x00, 0x00, 0x00,
		};
		FakeImageLoadHost fake = new FakeImageLoadHost();
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
		FakeImageLoadHost fake = new FakeImageLoadHost();
		ImageLoadController controller = new ImageLoadController(fake, null);

		boolean result = controller.applyBytes(webp, "image.webp");

		assertFalse("WebP bytes must not be accepted as a JPEG / PNG source", result);
		assertNotNull("rejection toast must fire so the user knows what happened", fake.lastToastMessage);
	}

	@Test
	public void applyBytesRejectsEmptyInput()
	{
		// Zero-length array — both signature checks short-circuit on the length predicate. Pin the
		// rejection so a future change that loosens the length guard still surfaces as a user-visible
		// rejection rather than a silent state mutation on whatever default Bitmap.decodeByteArray returns
		// for empty bytes.
		FakeImageLoadHost fake = new FakeImageLoadHost();
		ImageLoadController controller = new ImageLoadController(fake, null);

		boolean result = controller.applyBytes(new byte[0], "empty.jpg");

		assertFalse("empty bytes must not be accepted", result);
	}

	/**
	 * Minimal ImageLoadHost fake — captures toast messages and routes runOnUiThread to direct invocation so the
	 * test can assert on the toast immediately. Other methods that applyBytes might reach beyond the
	 * unsupported-format guard throw to surface accidental code-path drift.
	 */
	private static final class FakeImageLoadHost implements ImageLoadHost
	{
		final AtomicBoolean busy = new AtomicBoolean(false);
		String lastToastMessage;

		// Counter mirrors ImageLoadControllerDispatchFailureTest's fake so any future test in this class
		// can also pin the dismiss-before-busy-gate contract without re-adding the field.
		int dismissTransientDialogsCount;

		@Override
		public void dismissTransientDialogs()
		{
			dismissTransientDialogsCount++;
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T extends View> T findViewById(int id) { return null; }

		@Override
		public Activity getActivity() { throw new UnsupportedOperationException(); }

		@Override
		public AtomicBoolean getBusy() { return busy; }

		@Override
		public ActivityResultLauncher<String> getSaveAsLauncher() { throw new UnsupportedOperationException(); }

		@Override
		public CropState getState() { throw new UnsupportedOperationException(); }

		@Override
		public void hideProgress() {}

		@Override
		public void installImageOnUi(Bitmap bmp, String sizeInfo, String metaInfo)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isDestroyed() { return false; }

		@Override
		public AlertDialog registerTransientDialog(AlertDialog newDialog) { return newDialog; }

		@Override
		public void runInBackground(Runnable task) { throw new UnsupportedOperationException(); }

		@Override
		public void runOnUiThread(Runnable r) { r.run(); }

		@Override
		public void setBusyUi(boolean busyUi) {}

		@Override
		public void showBusyToast() {}

		@Override
		public void showProgress(String msg) {}

		@Override
		public void toastIfAlive(String msg, int length)
		{
			lastToastMessage = msg;
		}
	}
}
