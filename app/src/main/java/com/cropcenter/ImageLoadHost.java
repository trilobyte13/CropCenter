package com.cropcenter;

import android.graphics.Bitmap;

/**
 * Host surface consumed by ImageLoadController. Extends EditorHost (which already provides busy / progress / toast /
 * registerTransientDialog plumbing) with one image-installation callback that lets the controller hand the decoded
 * bitmap and info-bar strings back to the Activity for view mutation, plus a dismiss-transient-dialogs hook for the
 * pre-load cleanup. Does NOT extend SaveHost because ImageLoadController calls none of the save-flow surfaces
 * (clearTransientDialog / getSaveAsLauncher / setActiveTransientDialog); narrower interface lets a future
 * non-MainActivity ImageLoadHost implementer skip the unused save plumbing.
 */
interface ImageLoadHost extends EditorHost
{
	/**
	 * Dismiss whatever state-mutating dialog is currently open before a load begins. Called by
	 * ImageLoadController on the UI thread immediately before busy.compareAndSet so any open dialog
	 * (SettingsDialog, SaveDialog, FolderPickerDialog, the in-app collision / rename / overwrite-
	 * confirm dialogs, Replace dialog, Custom-AR or precise-rotation toolbar dialogs, the all-files-
	 * access prompt, or the graft sanity-check dialog) can't keep committing to CropState while a
	 * bg state.reset() races them. Without this forced dismissal, an open dialog at the moment a
	 * Share/View intent fires races the reset's clears with the user's still-active widget commits.
	 * No-op when no transient dialog is open.
	 */
	void dismissTransientDialogs();

	/**
	 * Install a freshly-loaded bitmap on the UI thread. Called from ImageLoadController's runOnUiThread block once
	 * decode + metadata extraction + display-proxy creation finished on the bg thread. Implementer is expected to:
	 * reset toolbar checkboxes (chkPan, chkLockCenter), apply lock mode, refresh ui highlights, write
	 * setSourceImage(source, display) on CropState, populate the info-bar text views with sizeInfo and metaInfo,
	 * and clear the editor's undo history. Implementer must also guard with isDestroyed() — the bg dispatch may
	 * have queued this Runnable before onDestroy ran.
	 *
	 * @param source   decoded + EXIF-oriented bitmap at full source resolution (ownership transfers to the
	 *                 implementer; the controller will not reference it after this call)
	 * @param display  display-proxy bitmap derived from source via BitmapUtils.createDisplayProxy (≤
	 *                 MAX_DISPLAY_PIXELS); may be the same reference as source when source already fits
	 *                 MAX_DISPLAY_PIXELS — the aliasing case
	 * @param sizeInfo "WIDTH×HEIGHT" formatted string for the dimensions readout
	 * @param metaInfo human-readable format string ("EXIF+ICC+HDR+Samsung", etc.)
	 */
	void installImageOnUi(Bitmap source, Bitmap display, String sizeInfo, String metaInfo);
}
