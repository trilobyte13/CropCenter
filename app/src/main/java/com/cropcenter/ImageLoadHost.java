package com.cropcenter;

import android.graphics.Bitmap;

/**
 * Host surface consumed by ImageLoadController. Extends SaveHost (which already provides busy / progress / toast
 * plumbing) with one image-installation callback that lets the controller hand the decoded bitmap and info-bar strings
 * back to the Activity for view mutation. Keeps the view-touching logic on the Activity side and the bg-thread flow
 * (decode, EXIF orientation, metadata extract, busy claim/release) inside the controller.
 */
interface ImageLoadHost extends SaveHost
{
	/**
	 * Install a freshly-loaded bitmap on the UI thread. Called from ImageLoadController's runOnUiThread block once
	 * decode + metadata extraction finished on the bg thread. Implementer is expected to: reset toolbar checkboxes
	 * (chkPan, chkLockCenter), apply lock mode, refresh ui highlights, write setSourceImage on CropState, populate
	 * the info-bar text views with sizeInfo and metaInfo, and clear the editor's undo history. Implementer must
	 * also guard with isDestroyed() — the bg dispatch may have queued this Runnable before onDestroy ran.
	 *
	 * @param bmp      decoded + EXIF-oriented bitmap (ownership transfers to the
	 *                 implementer; the controller will not reference it after this
	 *                 call)
	 * @param sizeInfo "WIDTH×HEIGHT" formatted string for the dimensions readout
	 * @param metaInfo human-readable format string ("EXIF+ICC+HDR+Samsung", etc.)
	 */
	void installImageOnUi(Bitmap bmp, String sizeInfo, String metaInfo);
}
