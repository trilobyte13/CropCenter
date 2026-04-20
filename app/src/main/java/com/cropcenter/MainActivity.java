package com.cropcenter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.cropcenter.crop.CropEngine;
import com.cropcenter.crop.CropExporter;
import com.cropcenter.metadata.GainMapExtractor;
import com.cropcenter.metadata.JpegMetadataExtractor;
import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.metadata.SeftExtractor;
import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.ExportConfig;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.HorizonDetector;
import com.cropcenter.util.TextFormat;
import com.cropcenter.util.ThemeColors;
import com.cropcenter.util.UltraHdrCompat;
import com.cropcenter.view.CropEditorView;
import com.cropcenter.view.RotationRulerView;
import com.cropcenter.view.SaveDialog;
import com.cropcenter.view.SettingsDialog;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity
{
	private static final AspectRatio[] AR_VALUES = {
		AspectRatio.R4_5, AspectRatio.FREE, AspectRatio.R16_9, AspectRatio.R3_2,
		AspectRatio.R4_3, AspectRatio.R5_4, AspectRatio.R1_1, AspectRatio.R3_4,
		AspectRatio.R2_3, AspectRatio.R9_16, null
	};
	private static final String BUSY_TOAST = "Busy — try again";
	private static final String JPEG_EXT = ".jpg";
	private static final String JPEG_MIME = "image/jpeg";
	private static final String PNG_EXT = ".png";
	private static final String PNG_MIME = "image/png";
	private static final String TAG = "CropCenter";
	private static final String[] AR_LABELS = {
		"4:5", "Full", "16:9", "3:2", "4:3", "5:4", "1:1", "3:4", "2:3", "9:16", "Custom"
	};
	private static final int IO_BUFFER = 8192;

	private final AtomicBoolean busy = new AtomicBoolean(false);

	private ActivityResultLauncher<String[]> openLauncher;
	private ActivityResultLauncher<String> saveAsLauncher;
	private CenterMode moveLockPref = CenterMode.VERTICAL;
	private CenterMode selectLockPref = CenterMode.BOTH;
	private CropEditorView editorView;
	private CropState state = new CropState();
	private RotationRulerView rotationRuler;
	// Filename we asked SAF to create. When SAF silently auto-renames to avoid a collision (e.g.
	// "vacation.jpg" → "vacation (1).jpg"), the returned URI's display name won't match this —
	// that's how we detect the rename.
	private String pendingSaveName;
	// One-shot prefix consumed by the next successful save's toast. Used when SAF auto-renamed a
	// collision and we want the user to see both the rename notice and the save result as a single
	// toast instead of two.
	private String pendingSaveToastPrefix;
	private TextView txtImageFormats;
	private TextView txtImageInfo;
	private TextView txtRotDegrees;
	private TextView txtSidebarCropSize;
	private TextView txtTransformArrow;
	private TextView txtZoomBadge;
	private boolean rulerUpdating;
	// Set when we launch the SAF picker and cleared when its result arrives (URI or cancel) or the
	// Replace confirmation finishes. Gates rapid taps between launch and result, and also between
	// the result and the Replace dialog response — busy.get() doesn't flip until exportTo actually
	// runs, so it isn't sufficient on its own.
	private boolean savePending;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// Handle edge-to-edge: apply system bar insets as padding to root layout
		View root = findViewById(android.R.id.content);
		ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) ->
		{
			Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			view.setPadding(sys.left, sys.top, sys.right, sys.bottom);
			return insets;
		});

		editorView = findViewById(R.id.editorView);
		txtZoomBadge = findViewById(R.id.txtZoomBadge);
		txtSidebarCropSize = findViewById(R.id.txtSidebarCropSize);
		txtImageInfo = findViewById(R.id.txtImageInfo);
		txtImageFormats = findViewById(R.id.txtImageFormats);
		txtTransformArrow = findViewById(R.id.txtTransformArrow);

		editorView.setState(state);
		editorView.setOnZoomChangedListener(this::updateZoomBadge);
		editorView.setOnPointsChangedListener(this::updatePointButtonStates);
		final boolean[] inListener = { false };
		state.setListener(() -> runOnUiThread(() ->
		{
			// Runnable enqueued by a background task can fire after onDestroy; drop it then.
			if (isDestroyed() || inListener[0])
			{
				return;
			}
			inListener[0] = true;
			try
			{
				if (state.isCropSizeDirty())
				{
					if (!state.hasCenter() && state.getSourceImage() != null)
					{
						float initialCx = state.getImageWidth() / 2f;
						float initialCy = state.getImageHeight() / 2f;
						state.setCenter(initialCx, initialCy);
						// Seed the rotation anchor so the no-selection recompute has a
						// stable starting position (image center) to re-read each
						// rotation tick.
						state.setAnchor(initialCx, initialCy);
					}
					if (state.hasCenter())
					{
						CropEngine.recomputeCrop(state);
					}
				}
				updateCropInfo();
				updateZoomBadge();
				updatePointButtonStates();
				updateAutoRotateVisibility();
				syncRotationUI();
				editorView.invalidate();
			}
			finally
			{
				inListener[0] = false;
			}
		}));

		openLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri ->
		{
			if (uri != null)
			{
				// Take persistable permission. Non-fatal if it fails — we just lose the ability
				// to re-open this URI across app restarts.
				try
				{
					int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
					getContentResolver().takePersistableUriPermission(uri, flags);
				}
				catch (Exception e)
				{
					Log.w(TAG, "takePersistableUriPermission failed for " + uri, e);
				}
				loadImage(uri);
			}
		});

		saveAsLauncher = registerForActivityResult(
			new ActivityResultContracts.CreateDocument(JPEG_MIME)
			{
				@Override
				public Intent createIntent(Context ctx, String input)
				{
					Intent intent = super.createIntent(ctx, input);
					if (input != null && input.endsWith(PNG_EXT))
					{
						intent.setType(PNG_MIME);
					}
					return intent;
				}
			},
			uri ->
			{
				// SAF result: URI (file picked) or null (cancelled). Clear the pending-save flag
				// either way so the Save button re-enables.
				if (uri != null)
				{
					handleSaveAsResult(uri);
				}
				else
				{
					savePending = false;
					pendingSaveName = null;
				}
			});

		findViewById(R.id.btnOpen).setOnClickListener(view ->
			openLauncher.launch(new String[] { JPEG_MIME, PNG_MIME }));
		findViewById(R.id.btnSave).setOnClickListener(view -> showSaveDialog());
		setBusyUi(false); // Save stays disabled until an image is loaded
		findViewById(R.id.btnSettings).setOnClickListener(view ->
			SettingsDialog.show(this, state.getGridConfig(), () -> editorView.invalidate()));

		// Display-only toggle; full grid settings live in the Settings dialog.
		CheckBox chkGrid = findViewById(R.id.chkGridMain);
		chkGrid.setChecked(state.getGridConfig().enabled);
		chkGrid.setOnCheckedChangeListener((button, isChecked) ->
		{
			state.getGridConfig().enabled = isChecked;
			editorView.invalidate();
		});

		setupARSpinner();
		setupModeButtons();
		setupCenterModeButtons();
		setupUndoRedo();
		setupClearPointsButton();
		setupRotation();
		setupAutoRotate();

		updateModeHighlight();
		updateLockHighlight();
		ensureStoragePermission();
		handleIncomingIntent(getIntent());
	}

	@Override
	protected void onDestroy()
	{
		// Clear the state listener FIRST so any in-flight notifyChanged() from a background
		// task doesn't fire an Activity-destroyed callback.
		state.setListener(null);
		// Do NOT recycle the source bitmap here. A background thread may still be reading it
		// (export encode or horizon detection) and recycle() would crash those threads. On
		// minSdk 35 the GC reclaims bitmap memory once the last reference is released, so the
		// trade-off — delayed reclaim vs. a hard crash — favours not recycling.
		super.onDestroy();
	}

	@Override
	protected void onNewIntent(Intent intent)
	{
		super.onNewIntent(intent);
		handleIncomingIntent(intent);
	}

	// Pick encoder based on the extension the user typed in the SAF picker.
	private void applyFormatFromFilename(String name)
	{
		if (name == null)
		{
			return;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(PNG_EXT))
		{
			state.getExportConfig().format = ExportConfig.FORMAT_PNG;
		}
		else if (lower.endsWith(JPEG_EXT) || lower.endsWith(".jpeg"))
		{
			state.getExportConfig().format = ExportConfig.FORMAT_JPEG;
		}
		// Anything else leaves the format unchanged (source default).
	}

	private void applyLockMode()
	{
		state.setCenterMode(isPanning() ? CenterMode.LOCKED : getCurrentPref());
	}

	// Stream the contents of `src` into `dst`, truncating whatever was at `dst`. Returns true
	// on a fully successful copy, false on any error (permission denied, provider doesn't grant
	// sibling write access, etc.). Used by the Replace flow to overwrite the original file's URI
	// directly — skipping the delete/rename dance that some providers silently fail.
	private boolean copyUriContents(Uri src, Uri dst)
	{
		try (InputStream in = getContentResolver().openInputStream(src);
				OutputStream out = getContentResolver().openOutputStream(dst, "w"))
		{
			if (in == null || out == null)
			{
				return false;
			}
			byte[] buf = new byte[IO_BUFFER];
			int n;
			while ((n = in.read(buf)) != -1)
			{
				out.write(buf, 0, n);
			}
			return true;
		}
		catch (Exception e)
		{
			Log.w(TAG, "copyUriContents " + src + " -> " + dst + " failed: " + e.getMessage());
			return false;
		}
	}

	// Export pipeline: encode → write → verify → report.
	//
	// Success signal hierarchy:
	//   1. Write path completes without exception → definitively saved.
	//   2. Write path threw → read the file back to count persisted bytes. Many SAF providers
	//      throw harmless EPIPE/IOException on close yet persist the full payload, so readback is
	//      the ground truth.
	//   3. Neither → genuine failure; delete the partial file.
	// Returns the exact bytes written when the file on disk is verified to hold the full payload,
	// or null on failure. Callers that need to run a post-write step (e.g. Replace's File-I/O
	// swap) use the returned bytes so they don't have to re-read the placeholder from the
	// filesystem — that read can go through FUSE/MediaStore layers that aren't necessarily in
	// sync with the SAF write we just did.
	//
	// `suppressSuccessToast` is set by the Replace flow so doExport doesn't announce "Saved" for
	// what is only the placeholder write — the Replace swap that follows may still fail, and
	// verifyReplace will fire the real outcome message. Failure toasts always fire regardless.
	private byte[] doExport(Uri uri, boolean suppressSuccessToast)
	{
		boolean isPng = ExportConfig.FORMAT_PNG.equals(state.getExportConfig().format);

		// ── Phase 1: encode ──
		byte[] data;
		boolean srcHadHdr;
		boolean backupFailed = false;
		try
		{
			CropExporter.BackupStatus backup = CropExporter.saveOriginalBackup(state);
			backupFailed = (backup == CropExporter.BackupStatus.FAILED);
			data = CropExporter.export(state, getCacheDir()).data();
			srcHadHdr = state.getGainMap() != null && state.getGainMap().length > 0;
			Log.d(TAG, "Encoded " + data.length + " bytes (srcHdr=" + srcHadHdr
				+ " isPng=" + isPng + " backup=" + backup + ")");
		}
		catch (Exception e)
		{
			Log.e(TAG, "Encode failed", e);
			final String emsg = "Export failed: " + e.getMessage();
			runOnUiThread(() -> toastIfAlive(emsg, Toast.LENGTH_SHORT));
			return null;
		}

		// saveOriginalBackup returns FAILED only when the source is a MediaStore file (so Gallery
		// Revert is relevant) AND the backup couldn't be written. NOT_APPLICABLE sources and
		// already-existing backups skip the warning. SAF's picker — not this app — decides whether
		// the user is overwriting, so we warn any time the backup didn't survive regardless of URI
		// identity.
		if (backupFailed)
		{
			runOnUiThread(() -> toastIfAlive(
				"Warning: couldn't write revert backup — Gallery Revert won't work if you overwrite",
				Toast.LENGTH_LONG));
		}

		// ── Phase 2: write ──
		// try-with-resources: close() runs after writeReturned=true, so close failures can't
		// invalidate a successful write (the catch block sets writeException but writeReturned is
		// already locked true).
		boolean writeReturned = false;
		Exception writeException = null;
		try (OutputStream os = getContentResolver().openOutputStream(uri, "w"))
		{
			if (os == null)
			{
				throw new IOException("openOutputStream returned null");
			}
			os.write(data);
			writeReturned = true;
		}
		catch (Exception e)
		{
			writeException = e;
			Log.w(TAG, "Write path threw (may still have persisted)", e);
		}

		// ── Phase 3: verify ──
		// If the write returned cleanly, trust it. If it threw, read the file back and
		// compare byte-for-byte against what we meant to write — this is the ground-truth
		// verification because SAF providers often throw on close yet persist the full payload.
		boolean savedOk = writeReturned;
		long verifiedBytes = -1;
		if (!savedOk)
		{
			verifiedBytes = readbackByteCount(uri, data);
			savedOk = verifiedBytes == data.length;
			if (savedOk)
			{
				Log.d(TAG, "Recovered via content-verified readback: " + verifiedBytes + " bytes");
			}
		}
		Log.d(TAG, "Save result: writeReturned=" + writeReturned
			+ " verifiedBytes=" + verifiedBytes + " expected=" + data.length
			+ " → savedOk=" + savedOk);

		// ── Phase 4: report ──
		if (savedOk)
		{
			// HDR suffix is informational. PNG can't carry gain maps — that's a format limitation,
			// NOT a failure, so suppress the suffix in that case. "[HDR dropped]" only fires when
			// JPEG export dropped an HDR source.
			final String hdrSuffix;
			if (!srcHadHdr || isPng)
			{
				hdrSuffix = "";
			}
			else if (UltraHdrCompat.containsHdrgm(data))
			{
				hdrSuffix = " [HDR OK]";
			}
			else
			{
				hdrSuffix = " [HDR dropped]";
			}

			// Consume any pending rename notice so the user sees one toast, not two ("Saved as
			// X — name was in use" combined with size). Always clear the field to keep it from
			// leaking to a future save.
			String prefix = pendingSaveToastPrefix;
			pendingSaveToastPrefix = null;
			if (!suppressSuccessToast)
			{
				final String msg = (prefix != null ? prefix : "Saved ")
					+ data.length / 1024 + "KB" + hdrSuffix;
				final int length = prefix != null ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT;
				runOnUiThread(() -> toastIfAlive(msg, length));
			}
			return data;
		}
		final String emsg = writeException != null
			? "Export failed: " + writeException.getMessage()
			: "Export failed";
		runOnUiThread(() -> toastIfAlive(emsg, Toast.LENGTH_SHORT));
		tryDeleteSafDocument(uri);
		return null;
	}

	// If no crop center exists, auto-set to image center so AR changes take effect.
	private void ensureCropCenter()
	{
		if (!state.hasCenter() && state.getSourceImage() != null)
		{
			float imgMidX = state.getImageWidth() / 2f;
			float imgMidY = state.getImageHeight() / 2f;
			state.markCropSizeDirty();
			state.setCenter(imgMidX, imgMidY);
			state.setAnchor(imgMidX, imgMidY);
		}
	}

	// Prompt for MANAGE_EXTERNAL_STORAGE on first launch when missing. The permission is needed
	// for reliable file-based Replace (which bypasses SAF's inconsistent delete/rename) and for
	// Samsung Gallery Revert backups. Shown as an explanatory dialog with a "Grant" button rather
	// than silently jumping to Settings so the user understands why. Save-time re-prompt in
	// showSaveDialog covers the case where the user dismissed this prompt.
	private void ensureStoragePermission()
	{
		if (hasStoragePermission())
		{
			return;
		}
		new AlertDialog.Builder(this)
			.setTitle("Grant \u201CAll files access\u201D?")
			.setMessage("CropCenter needs this permission to reliably overwrite saved images "
				+ "and to write Samsung Gallery Revert backups. You can grant it now and come "
				+ "back, or skip for now.")
			.setPositiveButton("Grant", (dialog, which) -> openStoragePermissionSettings())
			.setNegativeButton("Skip", null)
			.show();
	}

	private boolean hasStoragePermission()
	{
		return Environment.isExternalStorageManager();
	}

	private void openStoragePermissionSettings()
	{
		try
		{
			Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
			intent.setData(Uri.parse("package:" + getPackageName()));
			startActivity(intent);
		}
		catch (Exception e)
		{
			Log.w(TAG, "Cannot open MANAGE_EXTERNAL_STORAGE settings", e);
		}
	}

	private void exportTo(Uri uri)
	{
		exportTo(uri, null);
	}

	// Encode-and-write on a background thread. If onSavedBg is non-null and the write verifies
	// successfully, it runs on the SAME background thread before the busy flag is released. The
	// callback receives the exact bytes that were written — so the Replace flow can drop them
	// straight onto the target file instead of re-reading the placeholder (FUSE/MediaStore
	// caching can make that read unreliable). Because onSavedBg's follow-up work may still fail
	// (e.g. Replace leaves an extra copy), doExport's "Saved" toast is suppressed when onSavedBg
	// is present — the callback issues its own final-outcome message.
	private void exportTo(Uri uri, Consumer<byte[]> onSavedBg)
	{
		if (state.getSourceImage() == null)
		{
			return;
		}
		if (!busy.compareAndSet(false, true))
		{
			Toast.makeText(this, BUSY_TOAST, Toast.LENGTH_SHORT).show();
			return;
		}
		setBusyUi(true);
		showProgress("Saving\u2026");
		new Thread(() ->
		{
			try
			{
				byte[] data = doExport(uri, onSavedBg != null);
				if (data != null && onSavedBg != null)
				{
					try
					{
						onSavedBg.accept(data);
					}
					catch (Exception e)
					{
						Log.w(TAG, "post-save step threw", e);
					}
				}
			}
			finally
			{
				busy.set(false);
				runOnUiThread(() -> setBusyUi(false));
				hideProgress();
			}
		}).start();
	}

	// Translate a SAF or MediaStore URI to a java.io.File in shared storage. Returns null when
	// the URI isn't path-addressable. Supported docId formats:
	//   - "primary:relative/path/file.ext"  — ExternalStorageProvider (DCIM, Pictures, …)
	//   - "raw:/absolute/filesystem/path"   — DownloadStorageProvider when the file lives on
	//                                         the real filesystem (Download/...). The "raw"
	//                                         prefix is literal — the rest is an absolute path.
	// Plus a MediaStore _data column fallback. getFilePathAndId can throw SecurityException for
	// URIs the app doesn't have active read permission on (common post-uninstall/reinstall for
	// non-app-owned documents) — that's expected, we just return null and let the SAF paths
	// try their luck.
	private File fileFromSafUri(Uri uri)
	{
		String docId = null;
		try
		{
			if (DocumentsContract.isDocumentUri(this, uri))
			{
				docId = DocumentsContract.getDocumentId(uri);
			}
		}
		catch (Exception ignored)
		{
			// DocumentsContract misbehaved — fall through to the MediaStore fallback.
		}
		int colon = (docId == null) ? -1 : docId.indexOf(':');
		if (colon > 0)
		{
			String volume = docId.substring(0, colon);
			String tail = docId.substring(colon + 1);
			if ("primary".equalsIgnoreCase(volume))
			{
				File primaryRoot = Environment.getExternalStorageDirectory();
				return new File(primaryRoot, tail);
			}
			// DownloadStorageProvider "raw:<absolute path>" — use the path as-is.
			if ("raw".equalsIgnoreCase(volume))
			{
				return new File(tail);
			}
		}
		// Fall back to MediaStore _data column.
		String[] pathAndId = getFilePathAndId(uri);
		if (pathAndId != null && pathAndId[0] != null)
		{
			return new File(pathAndId[0]);
		}
		return null;
	}

	private CenterMode getCurrentPref()
	{
		return state.getEditorMode() == EditorMode.SELECT_FEATURE ? selectLockPref : moveLockPref;
	}

	private String getDisplayName(Uri uri)
	{
		try (Cursor cursor = getContentResolver().query(uri,
			new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null))
		{
			if (cursor != null && cursor.moveToFirst())
			{
				int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (idx >= 0)
				{
					return cursor.getString(idx);
				}
			}
		}
		catch (SecurityException ignored)
		{
			// Expected when the app doesn't hold read permission for this URI (common for
			// sibling/derived URIs we constructed ourselves).
		}
		catch (Exception e)
		{
			Log.w(TAG, "getDisplayName query failed for " + uri, e);
		}
		return null;
	}

	// Query MediaStore for file path and _ID. Returns [path, id] or null.
	private String[] getFilePathAndId(Uri uri)
	{
		final String COL_ID = "_id";
		final String COL_DATA = "_data";
		try
		{
			try (Cursor cursor = getContentResolver().query(uri,
				new String[] { COL_ID, COL_DATA }, null, null, null))
			{
				if (cursor != null && cursor.moveToFirst())
				{
					int idIdx = cursor.getColumnIndex(COL_ID);
					int dataIdx = cursor.getColumnIndex(COL_DATA);
					String id = idIdx >= 0 ? cursor.getString(idIdx) : null;
					String path = dataIdx >= 0 ? cursor.getString(dataIdx) : null;
					if (path != null && id != null)
					{
						Log.d(TAG, "MediaStore: path=" + path + " id=" + id);
						return new String[] { path, id };
					}
				}
			}
			// For SAF URIs, try to extract document ID and look up path in MediaStore
			if ("com.android.providers.media.documents".equals(uri.getAuthority()))
			{
				String docId = DocumentsContract.getDocumentId(uri);
				if (docId != null && docId.startsWith("image:")) // docId format: "image:12345"
				{
					String msId = docId.substring(6);
					Uri msUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
					String[] projection = { COL_DATA };
					String[] selectionArgs = { msId };
					try (Cursor cursor = getContentResolver().query(msUri, projection,
						COL_ID + "=?", selectionArgs, null))
					{
						if (cursor != null && cursor.moveToFirst())
						{
							String path = cursor.getString(0);
							if (path != null)
							{
								return new String[] { path, msId };
							}
						}
					}
				}
			}
		}
		catch (SecurityException ignored)
		{
			// Expected when the URI belongs to a provider we don't hold read permission on
			// (e.g. constructed sibling of a foreign document).
		}
		catch (Exception e)
		{
			Log.w(TAG, "getFilePathAndId failed", e);
		}
		return null;
	}

	private void handleIncomingIntent(Intent intent)
	{
		if (intent == null)
		{
			return;
		}
		String action = intent.getAction();
		if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_VIEW.equals(action))
		{
			return;
		}
		Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
		if (uri == null)
		{
			uri = intent.getData();
		}
		if (uri == null)
		{
			return;
		}
		// Share/View intents often don't carry persistable permission — failure here is expected.
		try
		{
			getContentResolver().takePersistableUriPermission(uri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		}
		catch (Exception e)
		{
			Log.d(TAG, "No persistable permission for shared URI: " + e.getMessage());
		}
		loadImage(uri);
	}

	// Route the SAF-returned URI to the correct save path.
	//
	// SAF's ACTION_CREATE_DOCUMENT behaviour on filename collision is inconsistent across
	// providers. The returned URI's display name tells us what actually happened:
	//
	// (A) chosen == requested — SAF kept the name. Either the file didn't exist (new file) or SAF
	//     prompted "Replace?" and the user accepted. Either way, write to newUri directly; SAF
	//     already handled any confirmation, so no extra dialog from us.
	//
	// (B) chosen matches the "requested (N).ext" auto-rename pattern — SAF silently renamed to
	//     dodge a collision. Offer Replace / Keep / Cancel on the chosen location. If the user
	//     picks Replace we delete the colliding original, rename the new file to the requested
	//     name, and write there. Cancel cleans up the placeholder.
	//
	// (C) chosen differs from requested but NOT in the auto-rename pattern — the user
	//     deliberately changed the filename in the picker. Save as-is.
	private void handleSaveAsResult(Uri newUri)
	{
		String requested = pendingSaveName;
		pendingSaveName = null;

		String chosen = getDisplayName(newUri);
		applyFormatFromFilename(chosen);

		// Case (A): SAF accepted the requested name exactly.
		if (requested != null && chosen != null && requested.equalsIgnoreCase(chosen))
		{
			savePending = false;
			exportTo(newUri);
			return;
		}

		// Case (B): SAF auto-renamed. Offer Replace / Keep / Cancel in the chosen directory.
		if (looksLikeAutoRename(requested, chosen))
		{
			showReplaceDialog(newUri, requested, chosen);
			return;
		}

		// Case (C): user changed the name intentionally.
		savePending = false;
		exportTo(newUri);
	}

	private void hideProgress()
	{
		runOnUiThread(() ->
		{
			if (isDestroyed())
			{
				return;
			}
			findViewById(R.id.progressOverlay).setVisibility(View.GONE);
		});
	}

	private boolean isCenterLocked()
	{
		return state.isCenterLocked();
	}

	private boolean isPanning()
	{
		return ((CheckBox) findViewById(R.id.chkPan)).isChecked();
	}

	// Copies URI to a cache file first to guarantee raw byte access. Some ContentProviders
	// (Samsung MediaStore) strip post-EOI data from JPEGs, which would lose the HDR gain map.
	// Copying to local file bypasses this.
	private void loadImage(Uri uri)
	{
		if (!busy.compareAndSet(false, true))
		{
			Toast.makeText(this, "Busy", Toast.LENGTH_SHORT).show();
			return;
		}
		setBusyUi(true);

		new Thread(() ->
		{
			try
			{
				byte[] fileBytes = readUriBytes(uri);
				Log.d(TAG, "Loaded " + fileBytes.length + " raw bytes (via cache)");

				Bitmap raw = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.length);
				if (raw == null || raw.getWidth() <= 0 || raw.getHeight() <= 0)
				{
					runOnUiThread(() -> toastIfAlive("Failed to decode", Toast.LENGTH_SHORT));
					return;
				}
				int orientation = BitmapUtils.readExifOrientation(fileBytes);
				Bitmap bmp = BitmapUtils.applyOrientation(raw, orientation);

				String origName = getDisplayName(uri);
				String[] pathAndId = getFilePathAndId(uri);

				state.reset();
				state.setOriginalFileBytes(fileBytes);
				state.setOriginalFilename(origName);
				if (pathAndId != null)
				{
					state.setOriginalFilePath(pathAndId[0]);
					try
					{
						state.setMediaStoreId(Long.parseLong(pathAndId[1]));
					}
					catch (NumberFormatException ignored)
					{
					}
				}

				final String metaInfo = extractMetadata(state, fileBytes);

				final int width = bmp.getWidth();
				final int height = bmp.getHeight();
				final String sizeInfo = width + "\u00D7" + height;

				runOnUiThread(() ->
				{
					state.setSourceImage(bmp);
					editorView.setState(state);
					editorView.clearUndoHistory();
					txtImageInfo.setText(sizeInfo);
					txtImageFormats.setText(metaInfo);
				});
			}
			catch (Exception e)
			{
				Log.e(TAG, "Load failed", e);
				runOnUiThread(() -> toastIfAlive("Load failed: " + e.getMessage(), Toast.LENGTH_SHORT));
			}
			finally
			{
				busy.set(false);
				runOnUiThread(() -> setBusyUi(false));
			}
		}).start();
	}

	// Byte-for-byte verify the file at `uri` matches `expected`. Used as ground-truth verification
	// when the write path threw — many SAF providers throw harmless EPIPE/IOException on close yet
	// persist the full payload. A byte-count match was previously considered "good enough" but that
	// can mask truncation/corruption where the stored byte count happens to reach `expected.length`
	// with divergent content, so we now compare content too. Returns:
	//   full bytes verified equal  → expected.length (save ok)
	//   any mismatch or short file → number of bytes read before divergence/EOF (save failed)
	//   provider can't serve file  → -1
	private long readbackByteCount(Uri uri, byte[] expected)
	{
		long total = 0;
		try (InputStream is = getContentResolver().openInputStream(uri))
		{
			if (is == null)
			{
				return -1;
			}
			byte[] buf = new byte[IO_BUFFER];
			int n;
			while ((n = is.read(buf)) != -1)
			{
				if (total + n > expected.length)
				{
					// Trailing bytes beyond what we wrote — treat as corruption.
					Log.w(TAG, "readback: provider returned more bytes than written");
					return total;
				}
				for (int i = 0; i < n; i++)
				{
					if (buf[i] != expected[(int) total + i])
					{
						Log.w(TAG, "readback: byte mismatch at offset " + (total + i));
						return total + i;
					}
				}
				total += n;
				if (total == expected.length)
				{
					// Verify nothing follows the expected bytes.
					int trailing = is.read(buf);
					if (trailing > 0)
					{
						Log.w(TAG, "readback: unexpected trailing " + trailing + " bytes");
						return total;
					}
					return total;
				}
			}
			return total;
		}
		catch (Exception e)
		{
			Log.w(TAG, "readbackByteCount: " + e.getMessage());
			return total > 0 ? total : -1;
		}
	}

	// Copy the URI to a cache file, then slurp raw bytes. The two-step routing (stream → cache file
	// → in-memory byte[]) is deliberate: some ContentProviders (notably Samsung MediaStore) strip
	// post-EOI bytes from JPEGs when streaming, which would lose the HDR gain map. Materialising
	// to a local file first bypasses that.
	private byte[] readUriBytes(Uri uri) throws IOException
	{
		File cacheFile = new File(getCacheDir(), "input_raw");
		try
		{
			try (InputStream is = getContentResolver().openInputStream(uri);
					FileOutputStream fos = new FileOutputStream(cacheFile))
			{
				if (is == null)
				{
					throw new IOException("Cannot open URI");
				}
				byte[] buf = new byte[IO_BUFFER];
				int n;
				while ((n = is.read(buf)) != -1)
				{
					fos.write(buf, 0, n);
				}
			}
			try (FileInputStream fis = new FileInputStream(cacheFile))
			{
				long fileLen = cacheFile.length();
				if (fileLen <= 0)
				{
					throw new IOException("Empty input: " + fileLen);
				}
				byte[] bytes = new byte[(int) fileLen];
				int read = 0;
				while (read < bytes.length)
				{
					int n = fis.read(bytes, read, bytes.length - read);
					if (n < 0)
					{
						break;
					}
					read += n;
				}
				return bytes;
			}
		}
		finally
		{
			// Ensure cache file is cleaned even when the read throws.
			if (cacheFile.exists() && !cacheFile.delete())
			{
				Log.d(TAG, "couldn't delete cache file " + cacheFile);
			}
		}
	}

	// Recenter the crop on selection points without resizing (for Move mode axis switch).
	private void recenterOnSelection()
	{
		var points = state.getSelectionPoints();
		if (points.isEmpty())
		{
			return;
		}
		float[] mid = CropEngine.selectionMidpoint(points);
		state.setCropSizeDirty(false);
		state.setCenter(mid[0], mid[1]);
		// Move mode: user just moved the crop to the selection midpoint. Update the
		// rotation anchor so subsequent rotations start from here.
		state.setAnchor(mid[0], mid[1]);
		// Snap the display center to the pixel grid — in Move mode the crop borders
		// must land on whole-pixel boundaries, but a half-integer selection midpoint
		// paired with even cropW (or vice versa) would otherwise leave them mid-pixel.
		CropEngine.recomputeCrop(state);
	}

	private void recomputeForLockChange()
	{
		if (!state.getSelectionPoints().isEmpty())
		{
			CropEngine.autoComputeFromPoints(state);
		}
		else if (state.hasCenter())
		{
			state.markCropSizeDirty();
			CropEngine.recomputeCrop(state);
		}
		editorView.invalidate();
	}

	// Replace the colliding file in the user's chosen directory (NOT the opened file's directory).
	//
	// Write-first-then-swap ordering: the new bytes are written to the SAF placeholder (which has
	// a "(N)" auto-renamed suffix) and verified BEFORE we touch the original. This makes the
	// operation crash-safe — if encode/write fails the original is preserved, and the worst case
	// is a leftover placeholder with the auto-suffix name.
	//
	// Strategy after the verified write (onSavedBg), tried in order of reliability:
	//   A. File I/O via MANAGE_EXTERNAL_STORAGE — FileOutputStream bypasses SAF's inconsistent
	//      delete/rename semantics entirely.
	//   B. SAF direct overwrite — stream placeholder bytes into the colliding original URI when
	//      the provider grants sibling access.
	//   C. SAF delete-colliding + rename-placeholder — last resort.
	//
	// Ordering is critical: File I/O, when it succeeds, writes the TARGET file (the original at
	// requestedName) with the new bytes. The SAF fallback paths that follow operate on the same
	// two URIs, and their delete/rename calls could accidentally destroy the just-written target
	// if we ran them unconditionally. So File I/O short-circuits the rest of the flow on a
	// fully-successful outcome; SAF paths only run when File I/O couldn't touch the target.
	// verifyReplace runs in both branches to catch partial states.
	private void replaceCollidingInChosenDir(Uri newUri, String requestedName)
	{
		exportTo(newUri, data ->
		{
			// A. File I/O. Writes the encoded bytes directly from `data` — avoids re-reading the
			// placeholder through FUSE/MediaStore layers that may not be in sync yet. Verifies
			// the target's on-disk length matches before reporting success. When this path
			// succeeds, skip SAF paths entirely — running them on the already-correct target
			// could delete it.
			boolean targetWrittenViaFile = replaceViaFileIo(newUri, requestedName, data);

			if (!targetWrittenViaFile)
			{
				// File I/O couldn't write the target — try SAF paths.
				Uri colliding = deriveSiblingUri(newUri, requestedName);
				// B. SAF direct overwrite.
				if (colliding != null && copyUriContents(newUri, colliding))
				{
					tryDeleteSafDocument(newUri);
				}
				else
				{
					// C. SAF delete-then-rename.
					if (colliding != null)
					{
						tryDeleteSafDocument(colliding);
					}
					try
					{
						DocumentsContract.renameDocument(
							getContentResolver(), newUri, requestedName);
					}
					catch (Exception e)
					{
						Log.w(TAG, "renameDocument(" + requestedName + ") failed: "
							+ e.getMessage());
					}
				}
			}

			// Verify + announce. verifyReplace shows a failure dialog internally when the end
			// state isn't clean; on clean replace we issue the one and only "Replaced" toast
			// (doExport's "Saved" toast was suppressed for this exact reason). The expected
			// length is passed so existence + zero-byte files aren't mistaken for success.
			if (verifyReplace(newUri, requestedName, data.length))
			{
				final String announce = "Replaced " + requestedName;
				runOnUiThread(() -> toastIfAlive(announce, Toast.LENGTH_SHORT));
			}
		});
	}

	// Replace via plain java.io.File. Writes `data` directly to the target path — reading the
	// placeholder through FileInputStream could return stale or zero bytes on FUSE-backed
	// storage whose view hasn't caught up with the SAF write. Verifies the written length
	// against `data.length` before claiming success so a silent partial/empty write can't pass.
	// Placeholder deletion is best-effort; a leftover is caught and reported by verifyReplace.
	private boolean replaceViaFileIo(Uri placeholderUri, String requestedName, byte[] data)
	{
		File placeholder = fileFromSafUri(placeholderUri);
		if (placeholder == null)
		{
			return false;
		}
		File parent = placeholder.getParentFile();
		if (parent == null)
		{
			return false;
		}
		File target = new File(parent, requestedName);
		try (FileOutputStream out = new FileOutputStream(target))
		{
			out.write(data);
			out.getFD().sync();
		}
		catch (Exception e)
		{
			Log.w(TAG, "replaceViaFileIo write to " + target + " failed: " + e.getMessage());
			return false;
		}
		long written = target.length();
		if (written != data.length)
		{
			Log.w(TAG, "replaceViaFileIo: target length " + written + " != expected "
				+ data.length + " — write didn't land");
			return false;
		}
		boolean placeholderGone = !placeholder.exists() || placeholder.delete();
		if (!placeholderGone)
		{
			Log.w(TAG, "replaceViaFileIo: target written but couldn't delete placeholder "
				+ placeholder);
		}
		// Direct FileOutputStream writes bypass MediaStore, so its cached metadata (thumbnails,
		// date-modified used by Gallery / Files / Photos apps) doesn't refresh on its own for
		// same-path overwrites. Trigger a scan so the new content appears immediately in those
		// apps — without this, the on-disk file is correct but every MediaStore consumer keeps
		// showing the pre-overwrite thumbnail and looks like the save didn't happen.
		String[] pathsToScan = placeholderGone
			? new String[] { target.getAbsolutePath() }
			: new String[] { target.getAbsolutePath(), placeholder.getAbsolutePath() };
		MediaScannerConnection.scanFile(this, pathsToScan, null, null);
		return true;
	}

	// Check disk after a Replace attempt. Returns true when the end state is clean (target
	// present at requestedName with the expected length AND placeholder absent). Anything else
	// fires a failure dialog (not a toast — toasts get truncated on Android 11+) describing
	// what's on disk and, when applicable, offers a direct link to the "All files access"
	// Settings screen, and returns false. `expectedLength` guards against "file exists but
	// empty" — an existence-only check was letting silent-zero-byte writes register as success.
	// Runs on the bg thread; dialog is posted to UI thread.
	private boolean verifyReplace(Uri placeholderUri, String requestedName, int expectedLength)
	{
		File placeholder = fileFromSafUri(placeholderUri);
		// Prefer filesystem checks — authoritative when MANAGE_EXTERNAL_STORAGE is granted.
		if (placeholder != null)
		{
			File parent = placeholder.getParentFile();
			File target = (parent != null) ? new File(parent, requestedName) : null;
			boolean placeholderExists = placeholder.exists();
			boolean targetOk = target != null && target.exists() && target.length() == expectedLength;
			if (targetOk && !placeholderExists)
			{
				return true; // clean replace
			}
			boolean targetExists = target != null && target.exists();
			long targetLen = targetExists ? target.length() : -1;
			final String title;
			final String message;
			if (targetExists && !placeholderExists)
			{
				// Target exists but wrong length — the write silently truncated or corrupted.
				title = "Replace produced an incomplete file";
				message = requestedName + " exists on disk but is " + targetLen + " bytes instead "
					+ "of the expected " + expectedLength + ". The write didn't land correctly. "
					+ "Re-save, and if it keeps happening, grant \u201CAll files access\u201D or "
					+ "move the save target to a folder you own.";
			}
			else if (placeholderExists && targetExists)
			{
				title = "Replace left two files";
				message = "Both " + requestedName + " and " + placeholder.getName()
					+ " now exist on disk. Grant \u201CAll files access\u201D so Replace can "
					+ "clean up automatically, or delete one manually from the Files app.";
			}
			else if (placeholderExists)
			{
				title = "Couldn't replace " + requestedName;
				message = "Your crop was saved as " + placeholder.getName()
					+ ". The original " + requestedName + " is owned by a previous install of "
					+ "CropCenter and can't be replaced by this build. Grant \u201CAll files "
					+ "access\u201D and save again, or delete the original from the Files app.";
			}
			else
			{
				title = "Save may have failed";
				message = "Neither " + requestedName + " nor " + placeholder.getName()
					+ " is on disk. Check your save directory and try again.";
			}
			Log.w(TAG, "verifyReplace: " + title + " — " + message);
			showReplaceFailureDialog(title, message);
			return false;
		}
		// No filesystem access — fall back to SAF query on the placeholder URI.
		String finalName = getDisplayName(placeholderUri);
		if (finalName != null && requestedName.equalsIgnoreCase(finalName))
		{
			return true; // clean replace per SAF
		}
		final String title;
		final String message;
		if (finalName != null)
		{
			title = "Couldn't replace " + requestedName;
			message = "Your crop was saved as " + finalName + ". Grant \u201CAll files access\u201D "
				+ "so Replace can overwrite the existing file, or delete the original from the "
				+ "Files app and save again.";
		}
		else
		{
			title = "Couldn't verify replace";
			message = "Save may not have completed. Check your save directory for a "
				+ requestedName + " or auto-renamed copy.";
		}
		Log.w(TAG, "verifyReplace: " + title + " — " + message);
		showReplaceFailureDialog(title, message);
		return false;
	}

	// Post an AlertDialog to the UI thread describing a failed/partial Replace. Unlike a Toast,
	// this has no line-length cap and stays on screen until dismissed. Offers a one-tap link to
	// the "All files access" Settings screen when the app doesn't hold that permission.
	private void showReplaceFailureDialog(String title, String message)
	{
		runOnUiThread(() ->
		{
			if (isDestroyed())
			{
				return;
			}
			AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this)
				.setTitle(title)
				.setMessage(message)
				.setPositiveButton("OK", null);
			if (!hasStoragePermission())
			{
				builder.setNeutralButton("Grant access",
					(dialog, which) -> openStoragePermissionSettings());
			}
			builder.show();
		});
	}

	// Disable Save/Open while busy so rapid taps can't stack up. UI thread only.
	private void setBusyUi(boolean isBusy)
	{
		View btnSave = findViewById(R.id.btnSave);
		View btnOpen = findViewById(R.id.btnOpen);
		boolean hasImage = state.getSourceImage() != null;
		if (btnSave != null)
		{
			btnSave.setEnabled(!isBusy && hasImage);
		}
		if (btnOpen != null)
		{
			btnOpen.setEnabled(!isBusy);
		}
	}

	private void setCurrentPref(CenterMode pref)
	{
		if (state.getEditorMode() == EditorMode.SELECT_FEATURE)
		{
			selectLockPref = pref;
		}
		else
		{
			moveLockPref = pref;
		}
	}

	private void setupARSpinner()
	{
		Spinner spinner = findViewById(R.id.spinnerAR);
		float density = getResources().getDisplayMetrics().density;
		int padH = (int) (6 * density);
		int padV = (int) (4 * density);

		// Custom adapter with compact item views (tight padding, 12sp text)
		ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
			android.R.layout.simple_spinner_item, AR_LABELS)
		{
			@Override
			public View getView(int position, View convertView, ViewGroup parent)
			{
				return styleARLabel((TextView) super.getView(position, convertView, parent),
					12, padH, padV);
			}

			@Override
			public View getDropDownView(int position, View convertView, ViewGroup parent)
			{
				return styleARLabel((TextView) super.getDropDownView(position, convertView, parent),
					13, padH * 2, padV * 2);
			}
		};
		spinner.setAdapter(adapter);
		spinner.setSelection(0);

		// Size the spinner to exactly fit the widest label + arrow.
		Paint tp = new Paint();
		tp.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
		float maxTextPx = 0;
		for (String label : AR_LABELS)
		{
			maxTextPx = Math.max(maxTextPx, tp.measureText(label));
		}
		int totalPx = (int) maxTextPx + padH * 2 + (int) (24 * density);
		spinner.setMinimumWidth(totalPx);
		ViewGroup.LayoutParams lp = spinner.getLayoutParams();
		if (lp != null)
		{
			lp.width = totalPx;
			spinner.setLayoutParams(lp); // triggers re-layout
		}
		spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
		{
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int pos, long id)
			{
				if (pos < AR_VALUES.length && AR_VALUES[pos] != null)
				{
					state.setAspectRatio(AR_VALUES[pos]);
					if (!state.getSelectionPoints().isEmpty())
					{
						CropEngine.autoComputeFromPoints(state);
					}
					else
					{
						ensureCropCenter();
					}
				}
				else
				{
					showCustomARDialog();
				}
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {}
		});
	}

	private void setupAutoRotate()
	{
		TextView btn = findViewById(R.id.btnAutoRotate);
		btn.setOnClickListener(view ->
		{
			if (state.getSourceImage() == null)
			{
				return;
			}

			if (editorView.isHorizonMode())
			{
				editorView.setHorizonMode(false, null);
				btn.setText("Auto");
				btn.setTextColor(getResources().getColor(R.color.subtext0, null));
				return;
			}

			// Try XMP metadata first (instant)
			float metaAngle = HorizonDetector.detectFromMetadata(state.getJpegMeta());
			if (!Float.isNaN(metaAngle))
			{
				state.setRotationDegrees(metaAngle);
				Toast.makeText(this, "From metadata: " + TextFormat.degrees(metaAngle),
					Toast.LENGTH_SHORT).show();
				return;
			}

			// Enter horizon paint mode — user paints over the horizon area
			btn.setText("Cancel");
			btn.setTextColor(getResources().getColor(R.color.red, null));
			editorView.setHorizonMode(true, () ->
			{
				btn.setText("Auto");
				btn.setTextColor(getResources().getColor(R.color.subtext0, null));

				var points = editorView.getHorizonPoints();
				float brushR = editorView.getHorizonBrushRadius();
				Bitmap src = state.getSourceImage();

				if (points.size() < 2 || src == null)
				{
					Toast.makeText(this, "Paint was too short", Toast.LENGTH_SHORT).show();
					return;
				}

				// Run detection on background thread using only the painted region
				showProgress("Detecting horizon\u2026");
				new Thread(() ->
				{
					float angle = HorizonDetector.detectFromPaintedRegion(src, points, brushR);
					runOnUiThread(() ->
					{
						if (isDestroyed())
						{
							return;
						}
						hideProgress();
						if (Float.isNaN(angle))
						{
							Toast.makeText(this, "No line detected in painted area",
								Toast.LENGTH_SHORT).show();
						}
						else
						{
							float newRot = Math.round(angle * 100f) / 100f;
							state.setRotationDegrees(newRot);
							Toast.makeText(this, TextFormat.degrees(newRot),
								Toast.LENGTH_SHORT).show();
						}
					});
				}).start();
			});
		});
	}

	private void setupCenterModeButtons()
	{
		View.OnClickListener lockClick = view ->
		{
			int id = view.getId();
			CenterMode pref;
			if (id == R.id.btnLockBoth)
			{
				pref = CenterMode.BOTH;
			}
			else if (id == R.id.btnLockH)
			{
				pref = CenterMode.HORIZONTAL;
			}
			else
			{
				pref = CenterMode.VERTICAL;
			}

			setCurrentPref(pref);
			applyLockMode();
			updateLockHighlight();

			if (state.getEditorMode() == EditorMode.SELECT_FEATURE && !isCenterLocked() && !isPanning())
			{
				recomputeForLockChange();
			}
			else if (state.getEditorMode() == EditorMode.MOVE && state.hasCenter()
				&& !state.getSelectionPoints().isEmpty() && !isPanning())
			{
				recenterOnSelection();
			}
			editorView.invalidate();
		};
		findViewById(R.id.btnLockBoth).setOnClickListener(lockClick);
		findViewById(R.id.btnLockH).setOnClickListener(lockClick);
		findViewById(R.id.btnLockV).setOnClickListener(lockClick);

		((CheckBox) findViewById(R.id.chkPan)).setOnCheckedChangeListener((button, isChecked) ->
		{
			applyLockMode();
			updateLockHighlight();
			// Recompute only when turning Pan off in Select mode
			if (!isChecked && state.getEditorMode() == EditorMode.SELECT_FEATURE
				&& !state.isCenterLocked())
			{
				recomputeForLockChange();
			}
			editorView.invalidate();
		});

		// Unlocking in Select mode re-derives the center from selection points; Move mode
		// preserves the user's current position.
		((CheckBox) findViewById(R.id.chkLockCenter)).setOnCheckedChangeListener((button, isChecked) ->
		{
			state.setCenterLocked(isChecked);
			if (!isChecked && state.getEditorMode() == EditorMode.SELECT_FEATURE
				&& !state.getSelectionPoints().isEmpty())
			{
				recomputeForLockChange();
			}
			editorView.invalidate();
		});
	}

	private void setupClearPointsButton()
	{
		findViewById(R.id.btnClearPoints).setOnClickListener(view ->
		{
			state.clearSelectionPoints();
			editorView.clearUndoHistory();
			editorView.resetCropToFullImage();
			editorView.invalidate();
			updatePointButtonStates();
		});
	}

	private void setupModeButtons()
	{
		View.OnClickListener click = view ->
		{
			int id = view.getId();
			if (id == R.id.btnModeMove)
			{
				state.setEditorMode(EditorMode.MOVE);
			}
			else if (id == R.id.btnModeSelect)
			{
				state.setEditorMode(EditorMode.SELECT_FEATURE);
			}
			applyLockMode();
			updateModeHighlight();
			updateLockHighlight();
			if (state.getEditorMode() == EditorMode.SELECT_FEATURE && !isCenterLocked() && !isPanning())
			{
				recomputeForLockChange();
			}
			editorView.invalidate();
		};
		findViewById(R.id.btnModeMove).setOnClickListener(click);
		findViewById(R.id.btnModeSelect).setOnClickListener(click);
	}

	private void setupRotation()
	{
		rotationRuler = findViewById(R.id.rotationRuler);
		txtRotDegrees = findViewById(R.id.txtRotDegrees);

		rotationRuler.setOnRotationChangedListener(deg ->
		{
			if (rulerUpdating)
			{
				return;
			}
			state.setRotationDegrees(deg);
		});

		txtRotDegrees.setOnClickListener(view -> showPreciseRotationDialog());
		rotationRuler.setRulerEnabled(false); // disabled until an image loads
	}

	private void setupUndoRedo()
	{
		findViewById(R.id.btnUndo).setOnClickListener(view -> editorView.undo());
		findViewById(R.id.btnRedo).setOnClickListener(view -> editorView.redo());
	}

	private void showCustomARDialog()
	{
		int dp = (int) getResources().getDisplayMetrics().density;

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.HORIZONTAL);
		layout.setGravity(Gravity.CENTER);
		layout.setPadding(20 * dp, 16 * dp, 20 * dp, 8 * dp);

		EditText editW = numberInput(this, "16", dp);
		layout.addView(editW,
			new LinearLayout.LayoutParams(60 * dp, LinearLayout.LayoutParams.WRAP_CONTENT));

		TextView sep = new TextView(this);
		sep.setText("  :  ");
		sep.setTextSize(16);
		layout.addView(sep);

		EditText editH = numberInput(this, "9", dp);
		layout.addView(editH,
			new LinearLayout.LayoutParams(60 * dp, LinearLayout.LayoutParams.WRAP_CONTENT));

		new AlertDialog.Builder(this)
			.setTitle("Custom Aspect Ratio")
			.setView(layout)
			.setPositiveButton("Apply", (dialog, which) ->
			{
				int ratioW = Math.max(1, parseIntOr(editW.getText().toString(), 16));
				int ratioH = Math.max(1, parseIntOr(editH.getText().toString(), 9));
				state.setAspectRatio(new AspectRatio(ratioW + ":" + ratioH, ratioW, ratioH));
				if (!state.getSelectionPoints().isEmpty())
				{
					CropEngine.autoComputeFromPoints(state);
				}
				else
				{
					ensureCropCenter();
				}
			})
			.setNegativeButton("Cancel", null)
			.show();
	}

	// Dialog for entering an exact rotation value.
	private void showPreciseRotationDialog()
	{
		if (state.getSourceImage() == null)
		{
			return;
		}
		int dp = (int) getResources().getDisplayMetrics().density;

		EditText input = new EditText(this);
		input.setText(String.format(Locale.ROOT, "%.2f", state.getRotationDegrees()));
		input.setTextSize(18);
		input.setGravity(Gravity.CENTER);
		input.setTextColor(ThemeColors.TEXT);
		input.setBackgroundColor(ThemeColors.SURFACE0);
		input.setInputType(InputType.TYPE_CLASS_NUMBER
			| InputType.TYPE_NUMBER_FLAG_DECIMAL
			| InputType.TYPE_NUMBER_FLAG_SIGNED);
		input.setSingleLine(true);
		input.setPadding(12 * dp, 10 * dp, 12 * dp, 10 * dp);

		new AlertDialog.Builder(this)
			.setTitle("Enter Rotation (\u00B0)")
			.setView(input)
			.setPositiveButton("Apply", (dialog, which) ->
			{
				try
				{
					float val = Math.clamp(Float.parseFloat(input.getText().toString().trim()),
						-180f, 180f);
					state.setRotationDegrees(val);
				}
				catch (NumberFormatException ignored)
				{
				}
			})
			.setNegativeButton("Cancel", null)
			.show();
	}

	// Show the full-screen progress overlay with the given message.
	private void showProgress(String message)
	{
		runOnUiThread(() ->
		{
			if (isDestroyed())
			{
				return;
			}
			View overlay = findViewById(R.id.progressOverlay);
			((TextView) findViewById(R.id.progressText)).setText(message);
			overlay.setVisibility(View.VISIBLE);
		});
	}

	// Build and show the Replace / Keep / Cancel dialog for case (B) of the save flow.
	private void showReplaceDialog(Uri newUri, String requested, String safName)
	{
		String message = "A file with this name already exists in the selected location.\n\n"
			+ "Replace \u2014 overwrite it.\n"
			+ "Keep \u2014 save as \"" + safName + "\" instead.\n"
			+ "Cancel \u2014 don't save.";
		Runnable cleanupPlaceholder = () -> tryDeleteSafDocument(newUri);
		new AlertDialog.Builder(this)
			.setTitle("Replace " + requested + "?")
			.setMessage(message)
			.setPositiveButton("Replace", (dialog, which) ->
			{
				savePending = false;
				replaceCollidingInChosenDir(newUri, requested);
			})
			.setNeutralButton("Keep", (dialog, which) ->
			{
				savePending = false;
				exportTo(newUri); // newUri already has the SAF-assigned name
			})
			.setNegativeButton("Cancel", (dialog, which) ->
			{
				cleanupPlaceholder.run();
				savePending = false;
			})
			.setOnCancelListener(dialog -> // BACK or touch-outside behaves like Cancel
			{
				cleanupPlaceholder.run();
				savePending = false;
			})
			.show();
	}

	// Save button handler. First re-checks MANAGE_EXTERNAL_STORAGE (it survives app UPDATE but
	// is typically revoked on uninstall-then-reinstall) and offers to open Settings if missing —
	// without it, Replace can leave an extra copy when the colliding file was created by a
	// previous install. Then runs SaveDialog (format + grid-bake options); on its "Continue"
	// the SAF picker opens with the correct extension pre-filled. Replace/Keep confirmation is
	// handled downstream in handleSaveAsResult().
	private void showSaveDialog()
	{
		if (state.getSourceImage() == null)
		{
			return;
		}
		if (busy.get() || savePending)
		{
			Toast.makeText(this, BUSY_TOAST, Toast.LENGTH_SHORT).show();
			return;
		}
		if (!hasStoragePermission())
		{
			new AlertDialog.Builder(this)
				.setTitle("Grant \u201CAll files access\u201D for reliable overwrite?")
				.setMessage("Without it, Replace can leave an extra copy when overwriting files "
					+ "created by a previous install of CropCenter. Grant now, then tap Save "
					+ "again when you return.")
				.setPositiveButton("Grant", (dialog, which) -> openStoragePermissionSettings())
				.setNegativeButton("Continue without", (dialog, which) -> openSaveOptionsDialog())
				.show();
			return;
		}
		openSaveOptionsDialog();
	}

	private void openSaveOptionsDialog()
	{
		SaveDialog.show(this, state.getExportConfig(), state.getGridConfig(), () ->
		{
			if (busy.get() || savePending)
			{
				Toast.makeText(this, BUSY_TOAST, Toast.LENGTH_SHORT).show();
				return;
			}
			// Extension follows the format the user just picked in SaveDialog; if they change the
			// extension in the SAF picker, applyFormatFromFilename updates ExportConfig.format
			// again before encode.
			String stem = state.getOriginalFilename();
			if (stem == null || stem.isEmpty())
			{
				stem = "crop";
			}
			int dot = stem.lastIndexOf('.');
			if (dot > 0)
			{
				stem = stem.substring(0, dot);
			}
			String name = stem
				+ (ExportConfig.FORMAT_PNG.equals(state.getExportConfig().format) ? PNG_EXT : JPEG_EXT);
			pendingSaveName = name;
			savePending = true;
			saveAsLauncher.launch(name);
		});
	}

	// Sync ruler + readout to current state rotation.
	private void syncRotationUI()
	{
		float deg = state.getRotationDegrees();
		boolean hasImage = state.getSourceImage() != null;

		rulerUpdating = true;
		rotationRuler.setDegrees(deg);
		rulerUpdating = false;
		rotationRuler.setRulerEnabled(hasImage);

		// Clear the readout when there's nothing to rotate so the info bar doesn't display a
		// stale "0°" against no image.
		txtRotDegrees.setText(hasImage ? TextFormat.degrees(deg) : "");
	}

	// UI-thread-safe toast helper — noop if Activity is destroyed.
	private void toastIfAlive(String msg, int length)
	{
		if (!isDestroyed())
		{
			Toast.makeText(this, msg, length).show();
		}
	}

	// Best-effort delete of a SAF document URI. Silently ignores failures.
	private void tryDeleteSafDocument(Uri uri)
	{
		try
		{
			if (DocumentsContract.isDocumentUri(this, uri))
			{
				DocumentsContract.deleteDocument(getContentResolver(), uri);
				return;
			}
		}
		catch (Exception ignored)
		{
		}
		try
		{
			getContentResolver().delete(uri, null, null);
		}
		catch (Exception ignored)
		{
		}
	}

	private void updateAutoRotateVisibility()
	{
		findViewById(R.id.btnAutoRotate).setVisibility(
			state.getSourceImage() != null ? View.VISIBLE : View.GONE);
	}

	private void updateCropInfo()
	{
		boolean hasImage = state.getSourceImage() != null;
		if (state.hasCenter())
		{
			txtSidebarCropSize.setText(state.getCropW() + "\u00D7" + state.getCropH());
		}
		else if (hasImage)
		{
			txtSidebarCropSize.setText("Full");
		}
		else
		{
			txtSidebarCropSize.setText("");
		}
		if (txtTransformArrow != null)
		{
			txtTransformArrow.setVisibility(hasImage ? View.VISIBLE : View.GONE);
		}
	}

	private void updateLockHighlight()
	{
		CenterMode pref = getCurrentPref();
		int active = getResources().getColor(R.color.mauve, null);
		int inactive = getResources().getColor(R.color.surface2, null);
		MaterialButton btnLockBoth = findViewById(R.id.btnLockBoth);
		MaterialButton btnLockH = findViewById(R.id.btnLockH);
		MaterialButton btnLockV = findViewById(R.id.btnLockV);
		btnLockBoth.setTextColor(pref == CenterMode.BOTH ? active : inactive);
		btnLockH.setTextColor(pref == CenterMode.HORIZONTAL ? active : inactive);
		btnLockV.setTextColor(pref == CenterMode.VERTICAL ? active : inactive);
	}

	private void updateModeHighlight()
	{
		EditorMode mode = state.getEditorMode();
		int active = getResources().getColor(R.color.mauve, null);
		int inactive = getResources().getColor(R.color.surface2, null);
		MaterialButton btnModeMove = findViewById(R.id.btnModeMove);
		MaterialButton btnModeSelect = findViewById(R.id.btnModeSelect);
		btnModeMove.setTextColor(mode == EditorMode.MOVE ? active : inactive);
		btnModeSelect.setTextColor(mode == EditorMode.SELECT_FEATURE ? active : inactive);

		boolean isSelect = mode == EditorMode.SELECT_FEATURE;

		findViewById(R.id.btnLockBoth).setVisibility(isSelect ? View.VISIBLE : View.GONE);
		// BOTH is a Select-only option; fall back to Vertical when leaving Select mode.
		if (!isSelect && moveLockPref == CenterMode.BOTH)
		{
			moveLockPref = CenterMode.VERTICAL;
			applyLockMode();
		}

		// Undo/Redo/Clear only visible in Select mode (they act on selection points)
		int pointCtrlVis = isSelect ? View.VISIBLE : View.GONE;
		findViewById(R.id.btnUndo).setVisibility(pointCtrlVis);
		findViewById(R.id.btnRedo).setVisibility(pointCtrlVis);
		findViewById(R.id.btnClearPoints).setVisibility(pointCtrlVis);
	}

	private void updatePointButtonStates()
	{
		boolean canUndo = editorView.canUndo();
		boolean canRedo = editorView.canRedo();
		boolean hasPoints = !state.getSelectionPoints().isEmpty();
		int enabledColor = getResources().getColor(R.color.subtext0, null);
		int disabledColor = getResources().getColor(R.color.surface1, null);

		MaterialButton btnUndo = findViewById(R.id.btnUndo);
		MaterialButton btnRedo = findViewById(R.id.btnRedo);
		MaterialButton btnClear = findViewById(R.id.btnClearPoints);

		btnUndo.setEnabled(canUndo);
		btnUndo.setTextColor(canUndo ? enabledColor : disabledColor);
		btnRedo.setEnabled(canRedo);
		btnRedo.setTextColor(canRedo ? enabledColor : disabledColor);
		btnClear.setEnabled(hasPoints);
		btnClear.setTextColor(hasPoints ? getResources().getColor(R.color.red, null) : disabledColor);
	}

	private void updateZoomBadge()
	{
		float zoom = editorView.getZoom();
		if (zoom <= 1.01f)
		{
			txtZoomBadge.setVisibility(View.GONE);
			return;
		}
		txtZoomBadge.setVisibility(View.VISIBLE);
		// Compact format: "2.5x", "26x" — avoids huge "25600%"
		txtZoomBadge.setText(zoom < 10f
			? String.format(Locale.ROOT, "%.1fx", zoom)
			: Math.round(zoom) + "x");
	}

	// Build a sibling document URI by swapping the last path segment of src's document ID for
	// siblingName. Works on providers that encode paths in their document IDs (notably the
	// built-in external-storage provider). Returns null for opaque-ID providers or providers
	// that don't expose a document ID.
	private static Uri deriveSiblingUri(Uri src, String siblingName)
	{
		try
		{
			String docId = DocumentsContract.getDocumentId(src);
			if (docId == null)
			{
				return null;
			}
			int slash = docId.lastIndexOf('/');
			if (slash < 0)
			{
				return null;
			}
			return DocumentsContract.buildDocumentUri(src.getAuthority(),
				docId.substring(0, slash + 1) + siblingName);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	// Scan the loaded JPEG for EXIF/ICC/XMP/HDR/SEFT markers, populate state, and build the
	// human-readable format string shown in the info bar.
	private static String extractMetadata(CropState state, byte[] fileBytes)
	{
		boolean isJpeg = fileBytes.length > 2
			&& (fileBytes[0] & 0xFF) == 0xFF && (fileBytes[1] & 0xFF) == 0xD8;
		if (!isJpeg)
		{
			state.setSourceFormat(ExportConfig.FORMAT_PNG);
			return "PNG";
		}

		state.setSourceFormat(ExportConfig.FORMAT_JPEG);
		List<JpegSegment> meta = JpegMetadataExtractor.extract(fileBytes);
		state.setJpegMeta(meta);

		boolean hasExif = false;
		boolean hasIcc = false;
		boolean hasXmp = false;
		boolean hasMpf = false;
		for (JpegSegment seg : meta)
		{
			hasExif |= seg.isExif();
			hasIcc |= seg.isIcc();
			hasXmp |= seg.isXmp();
			hasMpf |= seg.isMpf();
		}
		Log.d(TAG, "Segments: " + meta.size()
			+ " EXIF=" + hasExif + " ICC=" + hasIcc + " XMP=" + hasXmp + " MPF=" + hasMpf);

		byte[] gainMap = GainMapExtractor.extract(fileBytes);
		state.setGainMap(gainMap);
		state.setSeftTrailer(SeftExtractor.extract(fileBytes));

		boolean hasSeft = fileBytes.length >= 12
			&& fileBytes[fileBytes.length - 4] == 'S'
			&& fileBytes[fileBytes.length - 3] == 'E'
			&& fileBytes[fileBytes.length - 2] == 'F'
			&& fileBytes[fileBytes.length - 1] == 'T';
		Log.d(TAG, "HDR=" + (gainMap != null ? gainMap.length + "b" : "none")
			+ " SEFT=" + hasSeft + " MPF=" + hasMpf + " XMP=" + hasXmp);

		StringBuilder sb = new StringBuilder();
		if (hasExif)
		{
			sb.append("EXIF");
		}
		appendIf(sb, hasIcc, "ICC");
		appendIf(sb, hasXmp, "XMP");
		appendIf(sb, gainMap != null, "HDR");
		appendIf(sb, hasSeft, "Samsung");
		return sb.toString();
	}

	private static void appendIf(StringBuilder sb, boolean cond, String part)
	{
		if (!cond)
		{
			return;
		}
		if (sb.length() > 0)
		{
			sb.append('+');
		}
		sb.append(part);
	}

	// Detect SAF's auto-rename naming pattern: "stem (N).ext" where stem/ext match the requested
	// filename. Used to separate an SAF silent rename from the user genuinely typing a different
	// name in the picker.
	private static boolean looksLikeAutoRename(String requested, String chosen)
	{
		if (requested == null || chosen == null)
		{
			return false;
		}
		int reqDot = requested.lastIndexOf('.');
		int choDot = chosen.lastIndexOf('.');
		if (reqDot < 0 || choDot < 0)
		{
			return false;
		}
		String reqExt = requested.substring(reqDot);
		String choExt = chosen.substring(choDot);
		if (!reqExt.equalsIgnoreCase(choExt))
		{
			return false;
		}
		String reqStem = requested.substring(0, reqDot);
		String choStem = chosen.substring(0, choDot);
		// "stem (1)", "stem (2)", … — accept 1+ digits, optional whitespace between stem and paren.
		return choStem.matches("\\Q" + reqStem + "\\E\\s*\\(\\d+\\)");
	}

	private static EditText numberInput(Context ctx, String initial, int dp)
	{
		EditText edit = new EditText(ctx);
		edit.setInputType(InputType.TYPE_CLASS_NUMBER);
		edit.setText(initial);
		edit.setGravity(Gravity.CENTER);
		return edit;
	}

	private static int parseIntOr(String text, int def)
	{
		try
		{
			return Integer.parseInt(text.trim());
		}
		catch (NumberFormatException ignored)
		{
			return def;
		}
	}

	private static TextView styleARLabel(TextView tv, int textSize, int padH, int padV)
	{
		tv.setTextSize(textSize);
		tv.setTextColor(ThemeColors.TEXT);
		tv.setPadding(padH, padV, padH, padV);
		return tv;
	}
}
