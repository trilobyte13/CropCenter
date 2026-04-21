package com.cropcenter.util;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

/**
 * Wraps the MANAGE_EXTERNAL_STORAGE check and the deep-link to its Settings page. The
 * permission is only needed for reliable file-I/O Replace; the prompt is offered from
 * ReplaceStrategy.showReplaceFailureDialog when an actual collision-overwrite hits an
 * SAF-permission failure, never up-front at app start or save-dialog open.
 */
public final class StoragePermissionHelper
{
	private static final String TAG = "StoragePermissionHelper";

	private final Activity activity;

	public StoragePermissionHelper(Activity activity)
	{
		this.activity = activity;
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
