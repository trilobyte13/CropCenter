package com.cropcenter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.cropcenter.crop.CropEngine;
import com.cropcenter.metadata.GainMapExtractor;
import com.cropcenter.metadata.JpegMetadataExtractor;
import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.metadata.SeftExtractor;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.ExportConfig;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.StoragePermissionHelper;
import com.cropcenter.view.CropEditorView;
import com.cropcenter.view.RotationRulerView;
import com.cropcenter.view.SettingsDialog;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity implements SaveHost, UiHost, ToolbarHost
{
	private static final String TAG = "CropCenter";

	private final AtomicBoolean busy = new AtomicBoolean(false);
	// Single-thread executor with daemon-threaded worker — serialises load/export/horizon-detect
	// so only one heavyweight CropState-touching task runs at a time. Daemon thread doesn't
	// prevent JVM exit, so we don't shut it down on onDestroy — any in-flight task that outlives
	// the Activity harmlessly finishes (UI callbacks no-op via isDestroyed).
	private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "CropCenter-bg");
		t.setDaemon(true);
		return t;
	});
	private final SafFileHelper safFiles = new SafFileHelper(this);
	private final StoragePermissionHelper permissions = new StoragePermissionHelper(this);
	private final SaveController saveController = new SaveController(this, safFiles, permissions);
	private final UiSync ui = new UiSync(this);
	private final ToolbarBinder toolbar = new ToolbarBinder(this, ui);

	private ActivityResultLauncher<String[]> openLauncher;
	private ActivityResultLauncher<String> saveAsLauncher;
	private CenterMode moveLockPref = CenterMode.VERTICAL;
	private CenterMode selectLockPref = CenterMode.BOTH;
	private CropEditorView editorView;
	private CropState state = new CropState();
	private RotationRulerView rotationRuler;
	private TextView txtImageFormats;
	private TextView txtImageInfo;
	private TextView txtRotDegrees;
	private TextView txtSidebarCropSize;
	private TextView txtTransformArrow;
	private TextView txtZoomBadge;
	private boolean rulerUpdating;

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
		rotationRuler = findViewById(R.id.rotationRuler);
		txtZoomBadge = findViewById(R.id.txtZoomBadge);
		txtSidebarCropSize = findViewById(R.id.txtSidebarCropSize);
		txtImageInfo = findViewById(R.id.txtImageInfo);
		txtImageFormats = findViewById(R.id.txtImageFormats);
		txtRotDegrees = findViewById(R.id.txtRotDegrees);
		txtTransformArrow = findViewById(R.id.txtTransformArrow);

		editorView.setState(state);
		editorView.setOnZoomChangedListener(ui::updateZoomBadge);
		editorView.setOnPointsChangedListener(ui::updatePointButtonStates);
		state.setListener(() -> runOnUiThread(this::applyStateToUi));

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
			new ActivityResultContracts.CreateDocument(ExportConfig.JPEG_MIME)
			{
				@Override
				public Intent createIntent(Context ctx, String input)
				{
					Intent intent = super.createIntent(ctx, input);
					if (input != null && input.endsWith(ExportConfig.PNG_EXT))
					{
						intent.setType(ExportConfig.PNG_MIME);
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
					// Take persistable permission so Replace's SAF fallbacks can reopen the same
					// document later (e.g. to re-read it or re-rename). SAF grants write access
					// on creation, but without this the grant expires at process death. Failure
					// is non-fatal — file-I/O fallback still works when MANAGE_EXTERNAL_STORAGE
					// is granted.
					try
					{
						int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
							| Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
						getContentResolver().takePersistableUriPermission(uri, flags);
					}
					catch (Exception e)
					{
						Log.w(TAG, "takePersistableUriPermission (save) failed for " + uri, e);
					}
					saveController.handleSaveAsResult(uri);
				}
				else
				{
					saveController.onSaveCancelled();
				}
			});

		findViewById(R.id.btnOpen).setOnClickListener(view ->
			openLauncher.launch(new String[] { ExportConfig.JPEG_MIME, ExportConfig.PNG_MIME }));
		findViewById(R.id.btnSave).setOnClickListener(view -> saveController.showSaveDialog());
		setBusyUi(false); // Save stays disabled until an image is loaded
		findViewById(R.id.btnSettings).setOnClickListener(view -> SettingsDialog.show(this, state));

		// Display-only toggle; full grid settings live in the Settings dialog.
		CheckBox chkGrid = findViewById(R.id.chkGridMain);
		chkGrid.setChecked(state.getGridConfig().enabled());
		chkGrid.setOnCheckedChangeListener((button, isChecked) ->
			state.updateGridConfig(g -> g.withEnabled(isChecked)));

		toolbar.bindAll();

		ui.updateModeHighlight();
		ui.updateLockHighlight();
		permissions.ensureStoragePermission();
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

	@Override
	public void applyLockMode()
	{
		state.setCenterMode(isPanning() ? CenterMode.LOCKED : getCurrentPref());
	}

	@Override
	public void ensureCropCenter()
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

	@Override
	public Activity getActivity()
	{
		return this;
	}

	@Override
	public AtomicBoolean getBusy()
	{
		return busy;
	}

	@Override
	public CenterMode getCurrentPref()
	{
		return state.getEditorMode() == EditorMode.SELECT_FEATURE ? selectLockPref : moveLockPref;
	}

	@Override
	public CropEditorView getEditorView()
	{
		return editorView;
	}

	@Override
	public CenterMode getMoveLockPref()
	{
		return moveLockPref;
	}

	@Override
	public RotationRulerView getRotationRuler()
	{
		return rotationRuler;
	}

	@Override
	public TextView getRotDegreesTextView()
	{
		return txtRotDegrees;
	}

	@Override
	public ActivityResultLauncher<String> getSaveAsLauncher()
	{
		return saveAsLauncher;
	}

	@Override
	public TextView getSidebarCropSizeTextView()
	{
		return txtSidebarCropSize;
	}

	@Override
	public CropState getState()
	{
		return state;
	}

	@Override
	public TextView getTransformArrowTextView()
	{
		return txtTransformArrow;
	}

	@Override
	public TextView getZoomBadgeTextView()
	{
		return txtZoomBadge;
	}

	@Override
	public void hideProgress()
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

	@Override
	public boolean isCenterLocked()
	{
		return state.isCenterLocked();
	}

	@Override
	public boolean isPanning()
	{
		return ((CheckBox) findViewById(R.id.chkPan)).isChecked();
	}

	@Override
	public boolean isRulerUpdating()
	{
		return rulerUpdating;
	}

	/**
	 * Recenter the crop on selection points without resizing (for Move mode axis switch).
	 */
	@Override
	public void recenterOnSelection()
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

	@Override
	public void recomputeForLockChange()
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

	@Override
	public void runInBackground(Runnable task)
	{
		backgroundExecutor.execute(task);
	}

	/**
	 * Disable Save/Open while busy so rapid taps can't stack up. UI thread only.
	 */
	@Override
	public void setBusyUi(boolean isBusy)
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

	@Override
	public void setCurrentPref(CenterMode pref)
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

	@Override
	public void setMoveLockPref(CenterMode pref)
	{
		this.moveLockPref = pref;
	}

	@Override
	public void setRulerUpdating(boolean updating)
	{
		this.rulerUpdating = updating;
	}

	@Override
	public void showBusyToast()
	{
		Toast.makeText(this, "Busy — try again", Toast.LENGTH_SHORT).show();
	}

	/**
	 * Show the full-screen progress overlay with the given message.
	 */
	@Override
	public void showProgress(String message)
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

	/**
	 * UI-thread-safe toast helper — noop if Activity is destroyed.
	 */
	@Override
	public void toastIfAlive(String msg, int length)
	{
		if (!isDestroyed())
		{
			Toast.makeText(this, msg, length).show();
		}
	}

	/**
	 * State-listener body. Reads CropState and fans out to the UI sync calls, optionally
	 * running CropEngine.recomputeCrop first when the crop size is marked dirty.
	 *
	 * Wrapped in state.beginBatch / endBatch so the setters called by recomputeCrop (which
	 * would otherwise refire this listener recursively) have their notifyChanged buffered
	 * and coalesced into at most one follow-up listener invocation. The follow-up invocation
	 * sees isCropSizeDirty=false (recompute cleared it), skips recompute, and just re-runs
	 * the idempotent UI updates — no infinite loop, no explicit reentrancy guard needed.
	 */
	private void applyStateToUi()
	{
		// Listener may fire after onDestroy (background-thread setter + runOnUiThread posted
		// before destroy but dispatched after); drop it then.
		if (isDestroyed())
		{
			return;
		}
		state.beginBatch();
		try
		{
			if (state.isCropSizeDirty())
			{
				if (!state.hasCenter() && state.getSourceImage() != null)
				{
					float initialCx = state.getImageWidth() / 2f;
					float initialCy = state.getImageHeight() / 2f;
					state.setCenter(initialCx, initialCy);
					// Seed the rotation anchor so the no-selection recompute has a stable
					// starting position (image center) to re-read each rotation tick.
					state.setAnchor(initialCx, initialCy);
				}
				if (state.hasCenter())
				{
					CropEngine.recomputeCrop(state);
				}
			}
			ui.updateCropInfo();
			ui.updateZoomBadge();
			ui.updatePointButtonStates();
			ui.updateAutoRotateVisibility();
			ui.syncRotationUI();
			editorView.invalidate();
		}
		finally
		{
			state.endBatch();
		}
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

	/**
	 * Copies URI to a cache file first to guarantee raw byte access. Some ContentProviders
	 * (Samsung MediaStore) strip post-EOI data from JPEGs, which would lose the HDR gain map.
	 * Copying to local file bypasses this.
	 */
	private void loadImage(Uri uri)
	{
		if (!busy.compareAndSet(false, true))
		{
			showBusyToast();
			return;
		}
		setBusyUi(true);

		runInBackground(() ->
		{
			try
			{
				byte[] fileBytes = safFiles.readUriBytes(uri);
				Log.d(TAG, "Loaded " + fileBytes.length + " raw bytes (via cache)");

				Bitmap raw = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.length);
				if (raw == null || raw.getWidth() <= 0 || raw.getHeight() <= 0)
				{
					runOnUiThread(() -> toastIfAlive("Failed to decode", Toast.LENGTH_SHORT));
					return;
				}
				int orientation = BitmapUtils.readExifOrientation(fileBytes);
				Bitmap bmp = BitmapUtils.applyOrientation(raw, orientation);

				String origName = safFiles.getDisplayName(uri);
				String[] pathAndId = safFiles.getFilePathAndId(uri);

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
		});
	}

	/**
	 * Scan the loaded JPEG for EXIF/ICC/XMP/HDR/SEFT markers, populate state, and build the
	 * human-readable format string shown in the info bar.
	 */
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

}
