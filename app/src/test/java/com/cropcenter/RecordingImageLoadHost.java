package com.cropcenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.view.View;

import com.cropcenter.model.CropState;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Recording ImageLoadHost fake shared by the ImageLoadController test classes. Methods the controller legitimately
 * reaches record their invocation (toast message, setBusyUi argument, hideProgress / showBusyToast flags, dismiss
 * counter) so tests can assert on the exact call pattern; methods a test scenario should never reach
 * (getActivity / getState / installImageOnUi) throw UnsupportedOperationException to surface accidental code-path
 * drift. runOnUiThread runs inline so assertions can fire immediately, and runInBackground throws
 * RejectedExecutionException when runInBackgroundThrows is set (modelling executor shutdown after onDestroy),
 * otherwise swallows the task.
 *
 * Not a runtime helper — lives only in the test source set, promoted to package level so the ImageLoadController
 * test classes share one fake instead of drifting copies.
 */
final class RecordingImageLoadHost implements ImageLoadHost
{
	final AtomicBoolean busy = new AtomicBoolean(false);
	Boolean lastSetBusyUiArg;
	String lastToastMessage;
	boolean hideProgressCalled;
	boolean runInBackgroundThrows;
	boolean showBusyToastCalled;
	// Counter for dismissTransientDialogs invocations. The dismiss call runs BEFORE the busy gate so an open
	// SettingsDialog is closed regardless of whether a parallel load is in flight; tests verify the dismiss runs
	// even when the busy.cas rejects and even when runInBackground throws.
	int dismissTransientDialogsCount;

	@Override
	public void dismissTransientDialogs()
	{
		dismissTransientDialogsCount++;
	}

	// Stub override returning null — the tests never read the result. SuppressWarnings keeps the
	// generic-method override quiet even though `return null` doesn't strictly require it.
	@Override
	@SuppressWarnings("unchecked")
	public <T extends View> T findViewById(int id) { return null; }

	@Override
	public Activity getActivity() { throw new UnsupportedOperationException(); }

	@Override
	public AtomicBoolean getBusy() { return busy; }

	@Override
	public CropState getState() { throw new UnsupportedOperationException(); }

	@Override
	public void hideProgress()
	{
		hideProgressCalled = true;
	}

	@Override
	public void installImageOnUi(Bitmap source, Bitmap display, String sizeInfo, String metaInfo)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isDestroyed() { return false; }

	@Override
	public AlertDialog registerTransientDialog(AlertDialog newDialog) { return newDialog; }

	@Override
	public void runInBackground(Runnable task)
	{
		if (runInBackgroundThrows)
		{
			throw new RejectedExecutionException("simulated executor shutdown after onDestroy");
		}
	}

	@Override
	public void runOnUiThread(Runnable task) { task.run(); }

	@Override
	public void setBusyUi(boolean busyUi)
	{
		lastSetBusyUiArg = busyUi;
	}

	@Override
	public void showBusyToast()
	{
		showBusyToastCalled = true;
	}

	@Override
	public void showProgress(String msg) {}

	@Override
	public void toastIfAlive(String msg, int length)
	{
		lastToastMessage = msg;
	}
}
