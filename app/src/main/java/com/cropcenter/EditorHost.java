package com.cropcenter;

import android.app.Activity;
import android.view.View;

import com.cropcenter.model.CropState;

/**
 * Minimal Activity-shaped surface shared by every editor helper. Concrete helpers take a
 * role-specific sub-interface (SaveHost / UiHost / ToolbarHost) that extends this base so
 * each sees only what it needs. Methods here are the pieces every helper touches:
 * Activity-inherited plumbing, the CropState snapshot, and the background-executor dispatch.
 */
interface EditorHost
{
	<T extends View> T findViewById(int id);

	/**
	 * Hosting Activity, surfaced so helpers can reach Context-shaped dialog / toast / SAF / MediaScanner APIs
	 * without inheriting from Activity themselves.
	 *
	 * @return the hosting Activity instance — never null while the helper is alive
	 */
	Activity getActivity();

	/**
	 * Single CropState instance backing this editor session. Always the same object across the helper's
	 * lifetime; never reassigned (state mutation happens through CropState's own setters / reset).
	 *
	 * @return the CropState; never null
	 */
	CropState getState();

	boolean isDestroyed();

	/**
	 * Submit a task to the shared single-thread background executor. Replaces ad-hoc
	 * `new Thread(...).start()` at call sites so concurrency policy and thread lifecycle live in one place. Tasks
	 * execute serially in submission order; UI posting uses runOnUiThread as usual.
	 *
	 * @param task background work; not retained after it returns
	 */
	void runInBackground(Runnable task);

	void runOnUiThread(Runnable task);
}
