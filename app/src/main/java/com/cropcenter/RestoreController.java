package com.cropcenter;

import android.net.Uri;
import android.os.Bundle;

import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.SelectionPoint;

import java.util.List;

/**
 * Process-death restore + persistence for the editor's CropState. Owns the STATE_* bundle keys,
 * the pendingRestoreBundle field that bridges onCreate → installImageOnUi, and the bundle
 * read/write logic. Extracted from MainActivity (where it lived as ~120 lines of controller-grade
 * logic mixed into Activity wiring) so the bundle <-> CropState marshalling is testable in
 * isolation and so MainActivity returns to wiring + lifecycle.
 *
 * Flow:
 *   1. MainActivity.onSaveInstanceState calls writeTo(outState, sourceUri) — captures URI +
 *      every restore-able CropState field into the bundle.
 *   2. MainActivity.onCreate, on a non-null savedInstanceState with STATE_SOURCE_URI: calls
 *      stash(bundle) AND triggers imageLoader.load(uri). The bundle waits on this controller.
 *   3. MainActivity.installImageOnUi calls applyIfPending() after setSourceImage. If the bundle
 *      was stashed in step 2, this consumes it and applies the geometry inside a CropState
 *      beginBatch/endBatch so the cascade of setters fires the listener exactly once.
 *   4. MainActivity.setBusyUi(false) calls clearPendingIfUnconsumed() — drops the bundle when a
 *      load fails before installImageOnUi runs, so a subsequent manual Open doesn't inherit the
 *      stale geometry.
 */
final class RestoreController
{
	/**
	 * Outcome of applyIfPending: whether a bundle was actually consumed plus the
	 * per-mode lock-axis preferences that were stashed alongside CropState. The two pref fields
	 * are null when no restore was performed; otherwise they hold the bundle's restored values
	 * (or null when the bundle key was absent — restore-bundle written by an older app version).
	 * Returned as a record so the caller can re-seed its lock-pref fields BEFORE syncing toolbar
	 * UI from CropState — the prefs are MainActivity-private and don't live in CropState, so
	 * persisting them through this controller keeps the entire process-death restore contract in
	 * one place rather than scattering pref-handling across MainActivity.onCreate.
	 *
	 * @param consumed           true when a stashed bundle was applied; false on no-bundle paths
	 * @param restoredSelectPref Select-mode lock pref recovered from the bundle (BOTH /
	 *                           HORIZONTAL / VERTICAL); null when no bundle was consumed OR the
	 *                           bundle predates the pref keys
	 * @param restoredMovePref   Move-mode lock pref recovered from the bundle (HORIZONTAL /
	 *                           VERTICAL — BOTH is Select-only); null when no bundle was consumed
	 *                           OR the bundle predates the pref keys
	 */
	record Outcome(boolean consumed, CenterMode restoredSelectPref, CenterMode restoredMovePref)
	{
		static final Outcome NONE = new Outcome(false, null, null);
	}

	static final String STATE_ANCHOR_X = "anchor_x";
	static final String STATE_ANCHOR_Y = "anchor_y";
	static final String STATE_AR_HEIGHT = "ar_h";
	static final String STATE_AR_WIDTH = "ar_w";
	static final String STATE_CENTER_LOCKED = "ctr_locked";
	static final String STATE_CENTER_MODE = "ctr_mode";
	static final String STATE_CENTER_X = "ctr_x";
	static final String STATE_CENTER_Y = "ctr_y";
	static final String STATE_CROP_H = "crop_h";
	static final String STATE_CROP_W = "crop_w";
	static final String STATE_EDITOR_MODE = "editor_mode";
	static final String STATE_HAS_CENTER = "has_center";
	// Per-mode lock prefs. Persisted INDEPENDENTLY of STATE_CENTER_MODE because centerMode
	// collapses to LOCKED while Pan is on, hiding the underlying axis preference. Without these
	// keys, a user who picked HORIZONTAL for Select / VERTICAL for Move, then enabled Pan, then
	// got process-killed would see their lock axis fall back to defaults (BOTH / VERTICAL) when
	// they untoggle Pan after restore — contradicting REQUIREMENTS.md's "each mode remembers its
	// own lock setting" contract.
	static final String STATE_MOVE_LOCK_PREF = "move_lock_pref";
	static final String STATE_ROTATION = "rot_deg";
	static final String STATE_SELECTION_POINTS = "sel_pts";
	static final String STATE_SELECT_LOCK_PREF = "select_lock_pref";
	static final String STATE_SOURCE_URI = "src_uri";

	private final CropState state;
	// Stashed by stash(); consumed by applyIfPending() on a successful load OR cleared by
	// clearPendingIfUnconsumed() when setBusyUi(false) fires without an intervening install.
	private Bundle pendingRestoreBundle;

	RestoreController(CropState state)
	{
		this.state = state;
	}

	/**
	 * Extract the persisted source URI from a saved-state bundle so MainActivity.onCreate can decide
	 * whether to re-fire the load (URI present → restore path) or fall through to the normal Share-intent
	 * handling (URI absent → fresh launch / nothing was loaded at kill time). Static because the URI
	 * read happens before the RestoreController instance is constructed (the bundle's presence drives
	 * whether we even need a RestoreController at all).
	 *
	 * @param savedInstanceState the bundle from Android lifecycle; may be null
	 * @return the persisted source URI from savedInstanceState, or null when no URI was saved
	 *         (fresh launch, or never had a loaded image at kill time)
	 */
	static Uri readSourceUri(Bundle savedInstanceState)
	{
		if (savedInstanceState == null)
		{
			return null;
		}
		String saved = savedInstanceState.getString(STATE_SOURCE_URI);
		return saved != null ? Uri.parse(saved) : null;
	}

	/**
	 * Persist source URI + restore-able CropState fields + per-mode lock prefs into an Android
	 * Bundle. Called by MainActivity.onSaveInstanceState. No-op (writes nothing) when sourceUri is
	 * null — pendingRestoreBundle's null-check in onCreate then falls through to the normal
	 * Share-intent handling path on the next launch. Also a no-op when a graft is applied: the
	 * graft bytes are in-memory only (lastLoadedUri still points at the pre-graft source), so a
	 * restore would silently reload the pre-graft image, re-apply the geometry, and present it as
	 * if the user's external edit were still applied — they'd save a crop of the original instead.
	 * Skipping the bundle for grafted sessions forces the next launch into the normal cold-start
	 * path (no restore, fresh state) rather than producing a misleading restore.
	 *
	 * @param outState        destination bundle from Android lifecycle
	 * @param sourceUri       URI of the currently-loaded source, or null when nothing is loaded
	 * @param state           current CropState; every restore-able field is read here
	 * @param selectLockPref  Select-mode lock-axis preference (BOTH / HORIZONTAL / VERTICAL).
	 *                        Persisted independently of CropState.centerMode because Pan collapses
	 *                        centerMode to LOCKED, hiding the underlying axis choice — without
	 *                        this key, the user's prior axis pref reverts to BOTH after a
	 *                        Pan-on-during-kill restore.
	 * @param moveLockPref    Move-mode lock-axis preference (HORIZONTAL / VERTICAL — BOTH is
	 *                        Select-only). Same persistence rationale as selectLockPref.
	 */
	static void writeTo(Bundle outState, Uri sourceUri, CropState state,
		CenterMode selectLockPref, CenterMode moveLockPref)
	{
		if (sourceUri == null || state.isGraftApplied())
		{
			return;
		}
		outState.putString(STATE_SOURCE_URI, sourceUri.toString());
		AspectRatio ar = state.getAspectRatio();
		outState.putFloat(STATE_AR_WIDTH, ar.width());
		outState.putFloat(STATE_AR_HEIGHT, ar.height());
		outState.putFloat(STATE_CENTER_X, state.getCenterX());
		outState.putFloat(STATE_CENTER_Y, state.getCenterY());
		outState.putBoolean(STATE_HAS_CENTER, state.hasCenter());
		outState.putInt(STATE_CROP_W, state.getCropW());
		outState.putInt(STATE_CROP_H, state.getCropH());
		outState.putFloat(STATE_ANCHOR_X, state.getAnchorX());
		outState.putFloat(STATE_ANCHOR_Y, state.getAnchorY());
		outState.putFloat(STATE_ROTATION, state.getRotationDegrees());
		outState.putString(STATE_EDITOR_MODE, state.getEditorMode().name());
		outState.putString(STATE_CENTER_MODE, state.getCenterMode().name());
		outState.putBoolean(STATE_CENTER_LOCKED, state.isCenterLocked());
		if (selectLockPref != null)
		{
			outState.putString(STATE_SELECT_LOCK_PREF, selectLockPref.name());
		}
		if (moveLockPref != null)
		{
			outState.putString(STATE_MOVE_LOCK_PREF, moveLockPref.name());
		}
		List<SelectionPoint> points = state.getSelectionPoints();
		float[] flat = new float[points.size() * 2];
		for (int i = 0; i < points.size(); i++)
		{
			SelectionPoint point = points.get(i);
			flat[2 * i] = point.x();
			flat[2 * i + 1] = point.y();
		}
		outState.putFloatArray(STATE_SELECTION_POINTS, flat);
	}

	/**
	 * Apply the stashed bundle (if any) to CropState AND return the per-mode lock-axis prefs that
	 * were stashed alongside. Called by MainActivity.installImageOnUi after setSourceImage commits
	 * the reloaded bitmap. Consumes + nulls the bundle so a subsequent normal load (user taps
	 * Open) doesn't re-apply the stale snapshot. The returned Outcome lets the caller re-seed its
	 * lock-pref fields BEFORE syncing toolbar UI from the restored CropState — without that
	 * re-sync the toolbar would show the new-load defaults (chkPan unchecked, lock prefs reset to
	 * BOTH/VERTICAL, AR spinner on R4_5) while the model holds whatever the user had before the
	 * kill, and the hidden lock-axis prefs (per-mode preferences that aren't visible while Pan
	 * forces centerMode = LOCKED) would silently fall back to defaults.
	 *
	 * @return Outcome with `consumed = true` plus the restored per-mode prefs when a bundle was
	 *         applied; Outcome.NONE when no bundle was stashed
	 */
	Outcome applyIfPending()
	{
		if (pendingRestoreBundle == null)
		{
			return Outcome.NONE;
		}
		Bundle restore = pendingRestoreBundle;
		pendingRestoreBundle = null;
		CenterMode selectPref = readLockPref(restore, STATE_SELECT_LOCK_PREF);
		CenterMode movePref = readLockPref(restore, STATE_MOVE_LOCK_PREF);
		applyRestoreBundle(restore);
		return new Outcome(true, selectPref, movePref);
	}

	/**
	 * Drop the stashed bundle when a load completes (setBusyUi(false)) without a successful
	 * install. On the success path applyIfPending() runs first (inside installImageOnUi) and
	 * leaves pendingRestoreBundle null; on the failure path no install fires and the bundle
	 * would otherwise leak into the next manual Open.
	 */
	void clearPendingIfUnconsumed()
	{
		if (pendingRestoreBundle != null)
		{
			pendingRestoreBundle = null;
		}
	}

	/**
	 * Stash a savedInstanceState bundle for later application. Called by MainActivity.onCreate
	 * when the saved bundle carries a STATE_SOURCE_URI and the load is about to be triggered.
	 *
	 * @param savedInstanceState the bundle from Android lifecycle; must not be null and must
	 *                           carry the STATE_SOURCE_URI key (caller verifies)
	 */
	void stash(Bundle savedInstanceState)
	{
		this.pendingRestoreBundle = savedInstanceState;
	}

	/**
	 * Parse a CenterMode enum stored under `key` in the bundle. Returns null when the key is
	 * absent (older restore-bundle versions OR caller passed null at writeTo time) OR the saved
	 * string doesn't match any current CenterMode constant (forward-compat with a future rename).
	 *
	 * @param restore bundle to read from
	 * @param key     STATE_SELECT_LOCK_PREF or STATE_MOVE_LOCK_PREF
	 * @return parsed CenterMode, or null when the key is missing / malformed
	 */
	private static CenterMode readLockPref(Bundle restore, String key)
	{
		String name = restore.getString(key);
		if (name == null)
		{
			return null;
		}
		try
		{
			return CenterMode.valueOf(name);
		}
		catch (IllegalArgumentException ignored)
		{
			// Forward-compat: enum constant renamed between app versions. Drop to default — same
			// rationale as the EditorMode / CenterMode try/catch blocks inside applyRestoreBundle.
			return null;
		}
	}

	/**
	 * Apply a saved-state Bundle to CropState on the UI thread. Wraps the cascade of setters in
	 * a beginBatch/endBatch so the listener fires exactly once after every restored field is
	 * applied — without the batch, each setter would trigger applyStateToUi → recomputeCrop
	 * against intermediate state, silently clamping the about-to-be-restored values.
	 *
	 * @param restore bundle written by writeTo; reads the STATE_* keys and applies each present
	 *                key to CropState. Missing keys are tolerated (forward-compat with restore
	 *                bundles written by older app versions)
	 */
	private void applyRestoreBundle(Bundle restore)
	{
		state.beginBatch();
		try
		{
			// Restore aspect ratio FIRST so subsequent setCenter / setCropSizeSilent calls run
			// against the user's chosen AR, not the post-load default (R4_5). Reconstruct
			// AspectRatio from its (width, height) record components — both default to FREE's
			// (0, 0) if the keys are absent.
			float arWidth = restore.getFloat(STATE_AR_WIDTH, 0f);
			float arHeight = restore.getFloat(STATE_AR_HEIGHT, 0f);
			state.setAspectRatio(new AspectRatio(arWidth, arHeight));

			// Restore editor + lock modes BEFORE geometry so any setCenter clamping uses the
			// right CenterMode (BOTH allows free movement, HORIZONTAL / VERTICAL constrain to
			// one axis).
			String editorModeName = restore.getString(STATE_EDITOR_MODE);
			if (editorModeName != null)
			{
				try
				{
					state.setEditorMode(EditorMode.valueOf(editorModeName));
				}
				catch (IllegalArgumentException ignored)
				{
					// Saved bundle from a different app version with a renamed enum constant —
					// fall through with the default mode. Better than throwing and aborting the
					// restore.
				}
			}
			String centerModeName = restore.getString(STATE_CENTER_MODE);
			if (centerModeName != null)
			{
				try
				{
					state.setCenterMode(CenterMode.valueOf(centerModeName));
				}
				catch (IllegalArgumentException ignored)
				{
					// Same forward-compat concern as above; tolerate and continue.
				}
			}
			state.setCenterLocked(restore.getBoolean(STATE_CENTER_LOCKED, false));

			// Rotation BEFORE crop dimensions so the recompute that fires on cropW/H change
			// reads the final rotation rather than 0 — otherwise the crop rect would briefly
			// snap to the un-rotated AABB before settling.
			state.setRotationDegrees(restore.getFloat(STATE_ROTATION, 0f));

			// Crop dimensions via setCropSizeSilent (no notifyChanged so we don't trigger an
			// intermediate recompute that the subsequent setCenter would override). The final
			// state listener fire happens at endBatch.
			int cropW = restore.getInt(STATE_CROP_W, 0);
			int cropH = restore.getInt(STATE_CROP_H, 0);
			if (cropW > 0 && cropH > 0)
			{
				state.setCropSizeSilent(cropW, cropH);
				state.setCropSizeDirty(false);
			}

			// Center + anchor. hasCenter=false means the source was loaded but never positioned
			// — installImageOnUi's downstream ensureCropCenter will handle the default
			// centering. Use the CLAMPING setCenter (not setCenterUnclamped) so a restored
			// position that's now off-image (different EXIF orientation on restore, image
			// dimensions changed) is gently pulled back inside bounds rather than left as an
			// invalid coordinate the renderer would have to compensate for on every draw.
			if (restore.getBoolean(STATE_HAS_CENTER, false))
			{
				float centerX = restore.getFloat(STATE_CENTER_X, 0f);
				float centerY = restore.getFloat(STATE_CENTER_Y, 0f);
				state.setCenter(centerX, centerY);
				float anchorX = restore.getFloat(STATE_ANCHOR_X, centerX);
				float anchorY = restore.getFloat(STATE_ANCHOR_Y, centerY);
				state.setAnchor(anchorX, anchorY);
			}

			// Selection points — float[] of (x, y) pairs. Walk the array in pairs and rebuild
			// the list. The batch wrapper collapses what would otherwise be N+1 listener fires
			// (clearSelectionPoints + N addSelectionPoint) into one.
			float[] flat = restore.getFloatArray(STATE_SELECTION_POINTS);
			if (flat != null && flat.length >= 2)
			{
				state.clearSelectionPoints();
				int pairs = flat.length / 2;
				for (int i = 0; i < pairs; i++)
				{
					state.addSelectionPoint(new SelectionPoint(flat[2 * i], flat[2 * i + 1]));
				}
			}
		}
		finally
		{
			state.endBatch();
		}
	}
}
