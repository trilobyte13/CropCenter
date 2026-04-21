package com.cropcenter.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

/**
 * Wraps MANAGE_EXTERNAL_STORAGE checks and the "grant now?" dialog flow. Needed for reliable
 * file-based Replace (which bypasses SAF's inconsistent delete/rename) and for Samsung Gallery
 * Revert backups.
 */
public final class StoragePermissionHelper
{
	private static final String TAG = "StoragePermissionHelper";

	private final Activity activity;

	public StoragePermissionHelper(Activity activity)
	{
		this.activity = activity;
	}

	/**
	 * Prompt for MANAGE_EXTERNAL_STORAGE on first launch when missing. Shown as an explanatory
	 * dialog with a "Grant" button rather than silently jumping to Settings so the user understands
	 * why. Save-time re-prompt handled by the caller covers the case where the user dismissed this.
	 */
	public void ensureStoragePermission()
	{
		if (hasStoragePermission())
		{
			return;
		}
		String message = "CropCenter needs this permission to reliably overwrite saved images "
			+ "and to write Samsung Gallery Revert backups. You can grant it now and come "
			+ "back, or skip for now.";
		new AlertDialog.Builder(activity)
			.setTitle("Grant \u201CAll files access\u201D?")
			.setMessage(message)
			.setPositiveButton("Grant", (dialog, which) -> openStoragePermissionSettings())
			.setNegativeButton("Skip", null)
			.show();
	}

	public boolean hasStoragePermission()
	{
		return Environment.isExternalStorageManager();
	}

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
