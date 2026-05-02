package com.cropcenter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.Format;
import com.cropcenter.model.Graft;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.StoragePermissionHelper;
import com.cropcenter.view.CropEditorView;
import com.cropcenter.view.RotationRulerView;
import com.cropcenter.view.SettingsDialog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Top-level Activity for CropCenter. Owns the canonical CropState and orchestrates loading, editing, and exporting via
 * four host interfaces (ImageLoadHost, SaveHost, UiHost, ToolbarHost) implemented by this class. Heavy work runs on a
 * single-thread daemon executor; UI updates marshal back through runOnUiThread + isDestroyed checks. Most user actions
 * are handled by binders (ToolbarBinder, AutoRotateBinder) and controllers (ImageLoadController, GraftController,
 * SaveController) — this class wires them up and provides view accessors / state plumbing.
 */
public class MainActivity extends AppCompatActivity implements ImageLoadHost, SaveHost, UiHost, ToolbarHost
{
	private static final String TAG = "MainActivity";

	private final AtomicBoolean busy = new AtomicBoolean(false);
	private final CropState state = new CropState();
	// Single-thread executor with daemon-threaded worker — serialises load/export/horizon-detect so only one
	// heavyweight CropState-touching task runs at a time. Daemon thread doesn't prevent JVM exit; onDestroy shuts
	// the executor down gracefully so config-change rotation doesn't leak an orphaned worker per recreation.
	private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "CropCenter-bg");
		t.setDaemon(true);
		return t;
	});
	// safFiles / permissions declared before saveController and graftController because both constructors take them
	// — Java initialises fields in declaration order, so dependencies must come first regardless of strict
	// alphabetical ordering.
	private final SafFileHelper safFiles = new SafFileHelper(this);
	private final StoragePermissionHelper permissions = new StoragePermissionHelper(this);
	private final ImageLoadController imageLoader = new ImageLoadController(this, safFiles);
	private final SaveController saveController = new SaveController(this, safFiles, permissions);
	private final GraftController graftController = new GraftController(this, safFiles, this::applyGraftedBytes);
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
	public void installImageOnUi(Bitmap bmp, String sizeInfo, String metaInfo)
	{
		// Activity-destroyed guard: bg load tasks dispatched via runInBackground can outlive the Activity
		// (rotation, app backgrounded then killed). findViewById on a destroyed Activity returns null, and the
		// immediate CheckBox cast below would NPE the main thread, taking down the app. state.setListener(null)
		// in onDestroy suppresses listener-driven UI updates but not these inline findViewByIds, so the guard
		// has to live here. The leaked bmp from this branch is reclaimed by the GC finalizer once the destroyed
		// Activity itself is GC'd.
		if (isDestroyed())
		{
			return;
		}
		// CropState.reset() restored editorMode and centerMode to defaults (Select + Both) and cleared
		// centerLocked, but the Pan / Lock toolbar checkboxes are UI-driven state that doesn't auto-sync.
		// Uncheck them here so the visual state matches CropState, then call applyLockMode() to propagate the
		// unchecked Pan into centerMode (no-op since reset already set BOTH, but keeps the invariant that
		// state.centerMode follows chkPan + lock-axis pref).
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
	}

	@Override
	public boolean isPanning()
	{
		// findViewById can return null on a destroyed Activity (or, theoretically, before view inflation
		// completes if a listener fires very early). Without the null check the cast-and-isChecked NPEs the UI
		// thread. Pattern-match on CheckBox to dodge both the null and any unlikely class mismatch.
		View view = findViewById(R.id.chkPan);
		return view instanceof CheckBox checkBox && checkBox.isChecked();
	}

	@Override
	public boolean isRulerUpdating()
	{
		return rulerUpdating;
	}

	/**
	 * Recenter the crop on selection points without resizing (for Move mode axis switch). Uses
	 * CropEngine.rotatedSelectionMidpoint so the center matches exactly what Select mode's recompute would produce
	 * — on a rotated image the un-rotated AABB midpoint and the rotated AABB midpoint are different points, and
	 * this method has to match Select mode's framing to keep the crop's visual position stable across mode
	 * switches.
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
		// Move mode: user just moved the crop to the selection midpoint. Update the rotation anchor so
		// subsequent rotations start from here.
		state.setAnchor(mid[0], mid[1]);
		// Snap the display center to the pixel grid — in Move mode the crop borders must land on whole-pixel
		// boundaries, but a half-integer selection midpoint paired with even cropW (or vice versa) would
		// otherwise leave them mid-pixel.
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
				imageLoader.tryTakePersistable(uri, "(open)", true);
				imageLoader.load(uri);
			}
		});

		saveAsLauncher = registerForActivityResult(
			new ActivityResultContracts.CreateDocument(Format.JPEG.mimeType())
			{
				@Override
				public Intent createIntent(Context ctx, String input)
				{
					Intent intent = super.createIntent(ctx, input);
					if (input != null && input.endsWith(Format.PNG.extension()))
					{
						intent.setType(Format.PNG.mimeType());
					}
					return intent;
				}
			}, uri ->
			{
				// SAF result: URI (file picked) or null (cancelled). Clear the pending-save flag either
				// way so the Save button re-enables.
				if (uri != null)
				{
					// Persistable permission lets Replace's SAF fallbacks reopen the same document
					// later (re-read, re-rename). SAF grants write on creation but the grant
					// expires at process death without this; file-I/O fallback still works when
					// MANAGE_EXTERNAL_STORAGE is held, so failure is non-fatal but worth warning
					// about.
					imageLoader.tryTakePersistable(uri, "(save)", true);
					saveController.handleSaveAsResult(uri);
				}
				else
				{
					saveController.onSaveCancelled();
				}
			});

		graftPickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri ->
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
			openLauncher.launch(new String[] { Format.JPEG.mimeType(), Format.PNG.mimeType() }));
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
		// (showReplaceFailureDialog) offers the grant prompt only when an overwrite actually hits a
		// permission-bound failure. Most Save As flows never need this permission.
		imageLoader.handleIncomingIntent(getIntent());
	}

	@Override
	protected void onDestroy()
	{
		// Clear the state listener FIRST so any in-flight notifyChanged() from a background task doesn't fire
		// an Activity-destroyed callback.
		state.setListener(null);
		// Shut down the executor gracefully (NOT shutdownNow): in-flight tasks finish, no new tasks accepted,
		// the daemon worker thread exits cleanly when the queue drains. Without this, configuration changes
		// (rotation) accumulate orphaned executors — each new MainActivity creates a fresh executor while the
		// old one's worker thread idles forever, retaining the queue and any references the worker held.
		backgroundExecutor.shutdown();
		// Do NOT recycle the source bitmap here. A background thread may still be reading it (export encode or
		// horizon detection) and recycle() would crash those threads. On minSdk 35 the GC reclaims bitmap
		// memory once the last reference is released, so the trade-off — delayed reclaim vs. a hard crash —
		// favours not recycling.
		super.onDestroy();
	}

	@Override
	protected void onNewIntent(Intent intent)
	{
		super.onNewIntent(intent);
		imageLoader.handleIncomingIntent(intent);
	}

	/**
	 * Replace the in-memory image with pre-baked graft bytes — used by the graft flow once GraftController has
	 * produced the splice. The bytes carry the full file (original's identity metadata + edit's pixel content +
	 * original's HDR package + SEFT trailer if any); applyBytes treats them like any other freshly-loaded JPEG so
	 * the existing crop / save pipeline operates on them unchanged. Invoked on the UI thread via the
	 * GraftReadyHandler that GraftController fires from runOnUiThread.
	 *
	 * Busy ownership: GraftController.onEditPicked claims busy on the UI thread before dispatching its bg work, and
	 * that claim is held all the way through to here. We inherit it (no compareAndSet — racing here is impossible
	 * because Save / Open couldn't have started while busy was held) and release it in finally.
	 *
	 * Post-apply state writes flow through state.installGraft, which encapsulates the "graftApplied AND aiMask,
	 * both AFTER reset" invariant. Skipping the call would let canBypassEncode short-circuit the canvas pass and
	 * ship source's gain map verbatim over the spliced primary, plus leave the gain-map inpaint silent.
	 */
	private void applyGraftedBytes(Graft graft)
	{
		runInBackground(() -> applyGraftedBytesOnBg(graft));
	}

	/**
	 * Bg-thread body of applyGraftedBytes, extracted to satisfy CLAUDE.md's 3-line lambda cap. Runs the spliced
	 * bytes through applyBytes, installs graft state on success, fires the user-visible toast, and releases the
	 * busy flag in finally regardless of outcome.
	 *
	 * @param graft assembled graft (bytes + displayName + aiMask) from GraftController
	 */
	private void applyGraftedBytesOnBg(Graft graft)
	{
		try
		{
			// installGraft must only run on a successful apply — if the splice bytes fail to decode,
			// applyBytes posts the "Failed to decode" toast and the OLD image stays loaded. Installing
			// graftApplied + aiMask on top of that old state would force the next save through the canvas
			// re-encode path AND apply the failed graft's AI mask to the OLD image's gain map, which
			// produces a corrupted save with a different image's inpaint region. Gating on the boolean
			// return keeps the failure local. The "External edit applied" success toast also fires here,
			// not earlier in GraftController — firing it before applyBytes ran could lie about state for a
			// brief window if the apply failed afterward.
			if (imageLoader.applyBytes(graft.bytes(), graft.displayName()))
			{
				state.installGraft(graft);
				runOnUiThread(() -> toastIfAlive("External edit applied", Toast.LENGTH_SHORT));
			}
		}
		catch (Exception e)
		{
			Log.e(TAG, "Apply graft failed", e);
			runOnUiThread(() -> toastIfAlive("Apply failed: " + e.getMessage(), Toast.LENGTH_SHORT));
		}
		finally
		{
			busy.set(false);
			runOnUiThread(() -> setBusyUi(false));
		}
	}

	/**
	 * State-listener body. Reads CropState and fans out to the UI sync calls, optionally running
	 * CropEngine.recomputeCrop first when the crop size is marked dirty.
	 *
	 * Wrapped in state.beginBatch / endBatch so setters called by recomputeCrop have their notifyChanged buffered
	 * into a single post-batch fire. That post-batch fire otherwise triggers a recursive applyStateToUi run whose
	 * only work is re-running the idempotent UI updates this call already did — pure waste at high fling velocity.
	 * The applyingStateToUi flag short-circuits that recursive entry: any setter-driven notifyChanged that happens
	 * while the first call is still on the stack is absorbed (cropSizeDirty is already false by the time the
	 * re-entry would fire, so nothing is actually skipped).
	 */
	private void applyStateToUi()
	{
		// Listener may fire after onDestroy (background-thread setter + runOnUiThread posted before destroy but
		// dispatched after); drop it then.
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
				// Seeds center + rotation anchor to image midpoint on first ever recompute; subsequent
				// recomputes are no-op for that seed and just fall through.
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
			// Hold the guard through endBatch: the endBatch fire (the post-batch listener invocation that
			// triggers the recursive entry we want to suppress) happens inside endBatch itself. Releasing
			// the flag before endBatch would let the recursion through exactly when we're trying to stop
			// it.
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

}
