package com.cropcenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.util.Log;
import android.view.View;

import androidx.annotation.AnyThread;
import androidx.annotation.UiThread;

import com.cropcenter.model.CropState;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Activity-shaped surface shared by every editor helper. Concrete helpers take a role-specific sub-interface (SaveHost
 * / UiHost / ToolbarHost / ImageLoadHost) that extends this base so each sees only what it needs. EditorHost itself
 * bundles the operations every helper genuinely touches: Activity plumbing, the CropState snapshot, background-executor
 * dispatch, AND the progress / busy / toast / transient-dialog plumbing — kept here rather than on each sub-interface
 * so the shared busy / dialog / progress contract has a single declaration site.
 *
 * The narrower role-specific interfaces still exist (SaveHost / UiHost / ToolbarHost / ImageLoadHost) so a helper that
 * ONLY needs busy + dialog plumbing can take an EditorHost directly without claiming a broader capability.
 */
interface EditorHost
{
	/**
	 * Guarded transient-dialog ceremony shared by every registerTransientDialog producer: bail via onDestroyed
	 * when the host Activity is already destroyed, otherwise build → register → show, mapping any
	 * RuntimeException to a warn log plus onShowFailed. The expected throw is BadTokenException from a
	 * config-change race landing between the isDestroyed check and the WindowManager accepting the dialog.
	 * Registration means a later image load / session can dismiss the dialog via dismissTransientDialogs()
	 * before the old Activity window leaks; forced dismissal uses cancel(), so each dialog's own
	 * OnCancelListener still runs. The abort actions stay per-site runnables so every flow keeps its exact
	 * rollback semantics on both abort paths.
	 *
	 * @param host         host whose Activity anchors the dialog and whose tracker registers it
	 * @param tag          calling class's log TAG for the show-failure warn
	 * @param label        short dialog name; the show-failure warn reads "label failed to show"
	 * @param onDestroyed  abort action when the Activity is already destroyed (may be a no-op)
	 * @param onShowFailed abort action when build/register/show throws, run after the warn log (may be a
	 *                     no-op)
	 * @param build        builds the ready-to-show AlertDialog; runs inside the guarded try
	 */
	@UiThread
	static void showTransientGuarded(EditorHost host, String tag, String label, Runnable onDestroyed,
		Runnable onShowFailed, Supplier<AlertDialog> build)
	{
		if (host.isDestroyed())
		{
			onDestroyed.run();
			return;
		}
		try
		{
			AlertDialog dialog = build.get();
			host.registerTransientDialog(dialog);
			dialog.show();
		}
		catch (RuntimeException e)
		{
			Log.w(tag, label + " failed to show", e);
			onShowFailed.run();
		}
	}

	<T extends View> T findViewById(int id);

	/**
	 * Release busy ownership to the fully-idle state, correctly ordered. Re-enables the busy-gated controls, hides
	 * the progress overlay, THEN clears the busy flag — all inside one UI-thread runnable so the clear is atomic
	 * with the teardown. Background tails must release through here rather than clearing busy on the bg thread
	 * before posting the UI teardown: that window lets an onNewIntent Share/View load acquire busy for a new op,
	 * show its overlay, and then be unmasked by this op's still-pending teardown runnable. Clearing busy last, on
	 * the UI thread, means the next op can't acquire until this op's UI is fully torn down. Safe to call from any
	 * thread (runOnUiThread runs inline when already on the UI thread).
	 */
	@AnyThread
	default void finishBusy()
	{
		runOnUiThread(() ->
		{
			setBusyUi(false);
			hideProgress();
			getBusy().set(false);
		});
	}

	/**
	 * Hosting Activity, surfaced so helpers can reach Context-shaped dialog / toast / SAF / MediaScanner APIs
	 * without inheriting from Activity themselves.
	 *
	 * @return the hosting Activity instance — never null while the helper is alive
	 */
	Activity getActivity();

	/**
	 * Shared busy gate that serializes Save and Load. Both flows acquire it with compareAndSet(false, true) on
	 * entry and release in a finally so a rapid double-tap can't stack two background threads that both mutate
	 * CropState. Exposed via this accessor rather than wrapped method calls because GraftController holds it across
	 * a multi-step async sequence (picker → validate → apply) and needs to release at a different control point
	 * than where it acquired.
	 *
	 * @return shared busy AtomicBoolean (never null); true when a save / load / detect task is in flight
	 */
	AtomicBoolean getBusy();

	/**
	 * Single CropState instance backing this editor session. Always the same object across the helper's lifetime;
	 * never reassigned (state mutation happens through CropState's own setters / reset).
	 *
	 * @return the CropState; never null
	 */
	CropState getState();

	/**
	 * Hide the full-screen progress overlay. Safe to call from any thread.
	 */
	@AnyThread
	void hideProgress();

	boolean isDestroyed();

	/**
	 * Track an AlertDialog as the active state-mutating transient dialog and install an OnDismissListener that
	 * clears the tracked reference when the dialog dismisses normally. Called by every dialog producer whose
	 * widgets commit to CropState directly (SaveDialog format/grid choices, custom AR dimensions) and the Replace
	 * dialog (writes to the SAF target on Replace/Keep) so a Share/View intent or graft apply that arrives
	 * mid-dialog can dismiss it via dismissTransientDialogs() before bg state.reset() races the dialog's UI
	 * commits.
	 *
	 * @param newDialog dialog to track; passed through and returned for fluent chaining
	 * @return same dialog, returned so callers can chain or assign in one line
	 */
	@UiThread
	AlertDialog registerTransientDialog(AlertDialog newDialog);

	/**
	 * Submit a task to the shared single-thread background executor — the single bg chokepoint: call sites never
	 * spawn their own threads, so concurrency policy and thread lifecycle live in one place. Tasks execute
	 * serially in submission order; UI posting uses runOnUiThread as usual.
	 *
	 * @param task background work; not retained after it returns
	 */
	void runInBackground(Runnable task);

	void runOnUiThread(Runnable task);

	/**
	 * Toggle Save / Open button enabled state while a background task is running.
	 *
	 * @param busy true while a save / load / detect task is in flight; false when complete
	 */
	void setBusyUi(boolean busy);

	/**
	 * Post the shared "Busy — try again" toast. Used when the user triggers Save/Load while another task is already
	 * running.
	 */
	void showBusyToast();

	/**
	 * Show the full-screen progress overlay with a status message. Safe to call from any thread.
	 *
	 * @param msg status text shown over the overlay
	 */
	@AnyThread
	void showProgress(String msg);

	/**
	 * UI-thread-safe toast helper — safe to call directly from any thread. Implementations marshal to the UI thread
	 * internally (runOnUiThread, which runs inline when already on the UI thread) and no-op when the Activity is
	 * destroyed, evaluating the guard on the UI thread at show time. Callers that need a toast from a background
	 * thread route through here rather than Toast.makeText directly; no caller-side runOnUiThread wrapping is
	 * required.
	 *
	 * @param msg    user-facing toast text
	 * @param length Toast.LENGTH_SHORT or Toast.LENGTH_LONG
	 */
	@AnyThread
	void toastIfAlive(String msg, int length);
}
