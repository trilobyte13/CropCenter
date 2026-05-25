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
	 * Callers documented at the class level. Tries three intents in priority order:
	 *   1. ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION with a package URI — opens the per-app
	 *      "All files access" toggle. Heavily-skinned OEMs (One UI, MIUI, ColorOS) sometimes don't
	 *      handle this exact action with the package URI.
	 *   2. ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION without a package URI — opens the system-wide
	 *      "All files access" app list, where the user can scroll to find this app.
	 *   3. ACTION_APPLICATION_DETAILS_SETTINGS — opens this app's general settings page, from
	 *      which the user navigates to "Permissions > Files and media" manually.
	 *
	 * The FLAG_ACTIVITY_NEW_TASK is set because the call site can be a dialog whose Activity context
	 * is mid-dismiss, and the launched Settings activity needs its own task stack. The intent chain
	 * silently advances to the next fallback on ActivityNotFoundException or SecurityException.
	 *
	 * @return true when one of the intents succeeded; false when all three failed (caller can toast
	 *         a "Cannot open settings" message, which is rare enough we don't post one ourselves)
	 */
	public boolean openStoragePermissionSettings()
	{
		Uri packageUri = Uri.parse("package:" + activity.getPackageName());
		Intent[] attempts = {
			new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).setData(packageUri),
			new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
			new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri),
		};
		for (Intent intent : attempts)
		{
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			try
			{
				activity.startActivity(intent);
				return true;
			}
			catch (Exception e)
			{
				// ActivityNotFoundException on OEMs that don't handle the action, SecurityException
				// on policy-restricted devices. Either way, fall through to the next intent —
				// only escalate to a log warning if ALL attempts fail.
				Log.d(TAG, "intent " + intent.getAction() + " rejected: "
					+ e.getClass().getSimpleName());
			}
		}
		Log.w(TAG, "all MANAGE_EXTERNAL_STORAGE intents rejected; user must navigate manually");
		return false;
	}
}
