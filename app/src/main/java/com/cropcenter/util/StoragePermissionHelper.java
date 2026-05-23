package com.cropcenter.util;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

/**
 * Wraps the MANAGE_EXTERNAL_STORAGE check and the deep-link to its Settings page. MES gates the
 * in-app FolderPickerDialog flow (direct File I/O save bypassing Samsung's SAF picker) and the
 * file-I/O Replace fallback. Surfaced from three call sites: MainActivity.showAllFilesAccessPrompt
 * (up-front first-launch prompt when MES isn't granted, gated on savedInstanceState == null so it
 * doesn't reappear on every recreate), SettingsDialog's Permissions card (user-discoverable grant
 * affordance), and ReplaceStrategy.showReplaceFailureDialog (recovery path after a Replace-on-
 * collision SAF-permission failure).
 */
public final class StoragePermissionHelper
{
	private static final String TAG = "StoragePermissionHelper";

	private final Activity activity;

	/**
	 * Bind the helper to an Activity. The Activity is captured for `openStoragePermissionSettings`'s
	 * `startActivity` call; the helper does NOT retain extra strong references beyond this field.
	 *
	 * @param activity hosting Activity, used for the Settings-page deep link
	 */
	public StoragePermissionHelper(Activity activity)
	{
		this.activity = activity;
	}

	/**
	 * Query whether the app currently holds the MANAGE_EXTERNAL_STORAGE permission. Returns the live OS
	 * verdict every call — no caching — so a grant/revoke that happens while the app is foregrounded
	 * surfaces immediately on next check.
	 *
	 * @return true when Environment.isExternalStorageManager() reports the permission is granted
	 */
	public boolean hasStoragePermission()
	{
		return Environment.isExternalStorageManager();
	}

	/**
	 * Launch the system Settings page where the user can grant MANAGE_EXTERNAL_STORAGE for this app.
	 * Callers documented at the class level. Catches any Intent dispatch failure (no Settings app on
	 * the device, ActivityNotFoundException on heavily-skinned OEMs) so the caller's UX flow doesn't
	 * crash — failure is logged and the calling dialog stays open so the user can either retry the
	 * tap or back out and pick a different action.
	 */
	public void openStoragePermissionSettings()
	{
		try
		{
			Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
			intent.setData(Uri.parse("package:" + activity.getPackageName()));
			activity.startActivity(intent);
		}
		catch (Exception e)
		{
			Log.w(TAG, "Cannot open MANAGE_EXTERNAL_STORAGE settings", e);
		}
	}
}
