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
import com.cropcenter.model.Graft;
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
	private final CropState state = new CropState();
	// Single-thread executor with daemon-threaded worker — serialises load/export/horizon-detect
	// so only one heavyweight CropState-touching task runs at a time. Daemon thread doesn't
	// prevent JVM exit; onDestroy shuts the executor down gracefully so config-change rotation
	// doesn't leak an orphaned worker per recreation.
	private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "CropCenter-bg");
		t.setDaemon(true);
		return t;
	});
	// safFiles / permissions declared before saveController and graftController because both
	// constructors take them — Java initialises fields in declaration order, so dependencies
	// must come first regardless of strict alphabetical ordering.
	private final SafFileHelper safFiles = new SafFileHelper(this);
	private final StoragePermissionHelper permissions = new StoragePermissionHelper(this);
	private final SaveController saveController = new SaveController(this, safFiles, permissions);
	private final GraftController graftController = new GraftController(this, safFiles,
		this::applyGraftedBytes);
	// ui declared before toolbar for the same dependency reason.
	private final UiSync ui = new UiSync(this);
	private final ToolbarBinder toolbar = new ToolbarBinder(this, ui);

	private ActivityResultLauncher<String[]> graftPickerLauncher;
	private ActivityResultLauncher<String[]> openLauncher;
	private ActivityResultLauncher<String> saveAsLauncher;
	private CenterMode moveLockPref = CenterMode.VERTICAL;
	private CenterMode selectLockPref = CenterMode.BOTH;
	private CropEditorView editorView;
	private RotationRulerView rotationRuler;
	private TextView txtImageFormats;
	private TextView txtImageInfo;
	private TextView txtRotDegrees;
	private TextView txtSidebarCropSize;
	private TextView txtTransformArrow;
	private TextView txtZoomBadge;
	private boolean applyingStateToUi;
	private boolean rulerUpdating;

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
			float imageMidX = state.getImageWidth() / 2f;
			float imageMidY = state.getImageHeight() / 2f;
			state.markCropSizeDirty();
			state.setCenter(imageMidX, imageMidY);
			state.setAnchor(imageMidX, imageMidY);
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
	public TextView getRotDegreesTextView()
	{
		return txtRotDegrees;
	}

	@Override
	public RotationRulerView getRotationRuler()
	{
		return rotationRuler;
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
		// findViewById can return null on a destroyed Activity (or, theoretically,
		// before view inflation completes if a listener fires very early). Without
		// the null check the cast-and-isChecked NPEs the UI thread. Pattern-match on
		// CheckBox to dodge both the null and any unlikely class mismatch.
		View view = findViewById(R.id.chkPan);
		return view instanceof CheckBox checkBox && checkBox.isChecked();
	}

	@Override
	public boolean isRulerUpdating()
	{
		return rulerUpdating;
	}

	/**
	 * Recenter the crop on selection points without resizing (for Move mode axis
	 * switch). Uses CropEngine.rotatedSelectionMidpoint so the center matches exactly
	 * what Select mode's recompute would produce — on a rotated image the un-rotated
	 * AABB midpoint and the rotated AABB midpoint are different points, and this
	 * method has to match Select mode's framing to keep the crop's visual position
	 * stable across mode switches.
	 */
	@Override
	public void recenterOnSelection()
	{
		var points = state.getSelectionPoints();
		if (points.isEmpty())
		{
			return;
		}
		float[] mid = CropEngine.rotatedSelectionMidpoint(
			points, state.getImageWidth(), state.getImageHeight(), state.getRotationDegrees());
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
				tryTakePersistable(uri, "(open)", true);
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
					// Persistable permission lets Replace's SAF fallbacks reopen the same
					// document later (re-read, re-rename). SAF grants write on creation but
					// the grant expires at process death without this; file-I/O fallback
					// still works when MANAGE_EXTERNAL_STORAGE is held, so failure is
					// non-fatal but worth warning about.
					tryTakePersistable(uri, "(save)", true);
					saveController.handleSaveAsResult(uri);
				}
				else
				{
					saveController.onSaveCancelled();
				}
			});

		graftPickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
			uri ->
			{
				if (uri != null)
				{
					graftController.onEditPicked(uri);
				}
				else
				{
					graftController.onEditPickerCancelled();
				}
			});

		View btnOpen = findViewById(R.id.btnOpen);
		btnOpen.setOnClickListener(view ->
			openLauncher.launch(new String[] { ExportConfig.JPEG_MIME, ExportConfig.PNG_MIME }));
		btnOpen.setOnLongClickListener(view -> graftController.start(graftPickerLauncher));
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
		// MANAGE_EXTERNAL_STORAGE is no longer requested at startup — Replace's failure dialog
		// (showReplaceFailureDialog) offers the grant prompt only when an overwrite actually
		// hits a permission-bound failure. Most Save As flows never need this permission.
		handleIncomingIntent(getIntent());
	}

	@Override
	protected void onDestroy()
	{
		// Clear the state listener FIRST so any in-flight notifyChanged() from a background
		// task doesn't fire an Activity-destroyed callback.
		state.setListener(null);
		// Shut down the executor gracefully (NOT shutdownNow): in-flight tasks finish, no
		// new tasks accepted, the daemon worker thread exits cleanly when the queue drains.
		// Without this, configuration changes (rotation) accumulate orphaned executors —
		// each new MainActivity creates a fresh executor while the old one's worker thread
		// idles forever, retaining the queue and any references the worker held.
		backgroundExecutor.shutdown();
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

	/**
	 * Replace the in-memory image with pre-baked graft bytes — used by the graft flow once
	 * GraftController has produced the splice. The bytes carry the full file (original's
	 * identity metadata + edit's pixel content + original's HDR package + SEFT trailer if
	 * any); applyImageBytes treats them like any other freshly-loaded JPEG so the existing
	 * crop / save pipeline operates on them unchanged. Invoked on the UI thread via the
	 * GraftReadyHandler that GraftController fires from runOnUiThread.
	 *
	 * Busy ownership: GraftController.onEditPicked claims busy on the UI thread before
	 * dispatching its bg work, and that claim is held all the way through to here. We
	 * inherit it (no compareAndSet — racing here is impossible because Save / Open
	 * couldn't have started while busy was held) and release it in finally.
	 *
	 * Post-apply state writes flow through state.installGraft, which encapsulates the
	 * "graftApplied AND aiMask, both AFTER reset" invariant. Skipping the call would let
	 * canBypassEncode short-circuit the canvas pass and ship source's gain map verbatim
	 * over the spliced primary, plus leave the gain-map inpaint silent.
	 */
	private void applyGraftedBytes(Graft graft)
	{
		runInBackground(() ->
		{
			try
			{
				// installGraft must only run on a successful apply — if the splice bytes
				// fail to decode, applyImageBytes posts the "Failed to decode" toast and
				// the OLD image stays loaded. Installing graftApplied + aiMask on top of
				// that old state would force the next save through the canvas re-encode
				// path AND apply the failed graft's AI mask to the OLD image's gain map,
				// which produces a corrupted save with a different image's inpaint
				// region. Gating on the boolean return keeps the failure local.
				//
				// The "External edit applied" success toast also fires here, not earlier
				// in GraftController — firing it before applyImageBytes ran could lie
				// about state for a brief window if the apply failed afterward.
				if (applyImageBytes(graft.bytes(), graft.displayName()))
				{
					state.installGraft(graft);
					runOnUiThread(() ->
						toastIfAlive("External edit applied", Toast.LENGTH_SHORT));
				}
			}
			catch (Exception e)
			{
				Log.e(TAG, "Apply graft failed", e);
				runOnUiThread(() ->
					toastIfAlive("Apply failed: " + e.getMessage(), Toast.LENGTH_SHORT));
			}
			finally
			{
				busy.set(false);
				runOnUiThread(() -> setBusyUi(false));
			}
		});
	}

	/**
	 * Shared bg-thread body for installing a fresh image into the editor — used by both the
	 * SAF load flow and the in-memory graft apply flow. Decodes, applies EXIF orientation,
	 * resets crop session state, populates metadata-side state via extractMetadata, posts UI
	 * refresh. The caller has already acquired busy and is responsible for releasing it in a
	 * finally block.
	 *
	 * Returns true on a successful apply; false when the bytes failed to decode (the
	 * "Failed to decode" toast has already been posted). The graft flow gates installGraft
	 * on this so a failed splice can't leave graftApplied / aiMask installed onto the
	 * previously-loaded image.
	 */
	private boolean applyImageBytes(byte[] fileBytes, String origName)
	{
		Bitmap raw = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.length);
		if (raw == null || raw.getWidth() <= 0 || raw.getHeight() <= 0)
		{
			runOnUiThread(() -> toastIfAlive("Failed to decode", Toast.LENGTH_SHORT));
			return false;
		}
		int orientation = BitmapUtils.readExifOrientation(fileBytes);
		Bitmap bmp = BitmapUtils.applyOrientation(raw, orientation);

		// `bmp` is allocated above and ownership transfers to the UI runnable below
		// (which posts setSourceImage). If extractMetadata or any step between here
		// and the post throws — JpegMetadataExtractor / GainMapExtractor /
		// SeftExtractor could raise on a malformed source segment — bmp would
		// otherwise leak its native pixel buffer to the GC finalizer and CropState
		// would be left half-populated (originalFileBytes set, sourceImage null,
		// jpegMeta cleared by reset but not repopulated). The handedOff flag flips
		// the moment the runnable is queued; until then the catch path recycles
		// bmp and rethrows so the caller's catch (in applyGraftedBytes / loadImage)
		// posts a real failure toast.
		boolean handedOff = false;
		try
		{
			// Mutations below run on the background executor. They become visible to the
			// posted UI-thread runnable via Handler.post's happens-before, so the
			// runnable's body (setSourceImage + setState + setText) sees a fully
			// populated state. Concurrent UI-thread reads that beat the post — an
			// onDraw or gesture fired before setSourceImage is dispatched — see the
			// field they read either unchanged (state.reset clears, no tearing) or
			// not yet written; EditorRenderer / tap paths null-check getSourceImage
			// so a null-during-load snapshot is handled as "no image loaded". No
			// tearing, no volatile needed, no visibility gap.
			state.reset();
			state.setOriginalFileBytes(fileBytes);
			state.setOriginalFilename(origName);

			final String metaInfo = extractMetadata(state, fileBytes);

			int width = bmp.getWidth();
			int height = bmp.getHeight();

			final String sizeInfo = width + "\u00D7" + height;

			// handedOff flips only after width/height are read so a (theoretical)
			// recycle race during those reads still leaves the finally responsible
			// for the cleanup. In practice nothing else holds bmp at this point.
			handedOff = true;
			runOnUiThread(() ->
			{
				// Activity-destroyed guard: bg load tasks dispatched via runInBackground
				// can outlive the Activity (rotation, app backgrounded then killed).
				// findViewById on a destroyed Activity returns null, and the immediate
				// CheckBox cast below would NPE the main thread, taking down the app.
				// state.setListener(null) in onDestroy suppresses listener-driven UI
				// updates but not these inline findViewByIds, so the guard has to
				// live here. The leaked bmp from this branch is reclaimed by the GC
				// finalizer once the destroyed Activity itself is GC'd.
				if (isDestroyed())
				{
					return;
				}
				// CropState.reset() restored editorMode and centerMode to defaults
				// (Select + Both) and cleared centerLocked, but the Pan / Lock
				// toolbar checkboxes are UI-driven state that doesn't auto-sync.
				// Uncheck them here so the visual state matches CropState, then
				// call applyLockMode() to propagate the unchecked Pan into
				// centerMode (no-op since reset already set BOTH, but keeps the
				// invariant that state.centerMode follows chkPan + lock-axis pref).
				((CheckBox) findViewById(R.id.chkPan)).setChecked(false);
				((CheckBox) findViewById(R.id.chkLockCenter)).setChecked(false);
				applyLockMode();
				ui.updateModeHighlight();
				ui.updateLockHighlight();

				state.setSourceImage(bmp);
				editorView.setState(state);
				editorView.clearUndoHistory();
				txtImageInfo.setText(sizeInfo);
				txtImageFormats.setText(metaInfo);
			});
			return true;
		}
		finally
		{
			if (!handedOff)
			{
				bmp.recycle();
			}
		}
	}

	/**
	 * State-listener body. Reads CropState and fans out to the UI sync calls, optionally
	 * running CropEngine.recomputeCrop first when the crop size is marked dirty.
	 *
	 * Wrapped in state.beginBatch / endBatch so setters called by recomputeCrop have their
	 * notifyChanged buffered into a single post-batch fire. That post-batch fire otherwise
	 * triggers a recursive applyStateToUi run whose only work is re-running the idempotent
	 * UI updates this call already did — pure waste at high fling velocity. The
	 * applyingStateToUi flag short-circuits that recursive entry: any setter-driven
	 * notifyChanged that happens while the first call is still on the stack is absorbed
	 * (cropSizeDirty is already false by the time the re-entry would fire, so nothing is
	 * actually skipped).
	 */
	private void applyStateToUi()
	{
		// Listener may fire after onDestroy (background-thread setter + runOnUiThread posted
		// before destroy but dispatched after); drop it then.
		if (isDestroyed() || applyingStateToUi)
		{
			return;
		}
		applyingStateToUi = true;
		state.beginBatch();
		try
		{
			if (state.isCropSizeDirty())
			{
				// Seeds center + rotation anchor to image midpoint on first ever recompute;
				// subsequent recomputes are no-op for that seed and just fall through.
				ensureCropCenter();
				if (state.hasCenter())
				{
					CropEngine.recomputeCrop(state);
				}
			}
			ui.updateCropInfo();
			ui.updateZoomBadge();
			ui.updatePointButtonStates();
			ui.updateAutoRotateVisibility();
			ui.syncRotationUi();
			editorView.invalidate();
		}
		finally
		{
			// Hold the guard through endBatch: the endBatch fire (the post-batch listener
			// invocation that triggers the recursive entry we want to suppress) happens
			// inside endBatch itself. Releasing the flag before endBatch would let the
			// recursion through exactly when we're trying to stop it.
			try
			{
				state.endBatch();
			}
			finally
			{
				applyingStateToUi = false;
			}
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
		// Share/View intents routinely don't carry persistable permission — log at debug,
		// not warn, since this is expected for external intents.
		tryTakePersistable(uri, "(share)", false);
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
				applyImageBytes(fileBytes, safFiles.getDisplayName(uri));
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
	 * Take read+write persistable permission on the URI. Logging level follows context:
	 * Open and Save As log at WARN because failure means we'll lose access on next launch
	 * (actionable for the user); Share intents log at DEBUG because external intents
	 * routinely don't grant persistable permission and the failure is expected, not a
	 * regression. The method swallows the exception in all cases — persistable permission
	 * is a nice-to-have, never required for the current session to succeed.
	 */
	private void tryTakePersistable(Uri uri, String contextTag, boolean warnOnFailure)
	{
		try
		{
			int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
				| Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
			getContentResolver().takePersistableUriPermission(uri, flags);
		}
		catch (Exception e)
		{
			if (warnOnFailure)
			{
				Log.w(TAG, "takePersistableUriPermission " + contextTag
					+ " failed for " + uri, e);
			}
			else
			{
				Log.d(TAG, "takePersistableUriPermission " + contextTag
					+ " declined: " + e.getMessage());
			}
		}
	}

	/**
	 * Append `part` to the format string if `cond` is true, separated from prior parts
	 * by '+' (e.g. "EXIF+ICC+HDR"). No-op when `cond` is false.
	 */
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

}
