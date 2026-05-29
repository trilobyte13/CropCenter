package com.cropcenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.cropcenter.crop.CropEngine;
import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.Format;
import com.cropcenter.model.Graft;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.StoragePermissionHelper;
import com.cropcenter.util.UltraHdrCompat;
import com.cropcenter.view.CropEditorView;
import com.cropcenter.view.OpenPickerDialog;
import com.cropcenter.view.RotationRulerView;
import com.cropcenter.view.SettingsDialog;

import java.io.File;
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
public final class MainActivity extends AppCompatActivity implements ImageLoadHost, SaveHost, UiHost, ToolbarHost
{
	private static final String TAG = "MainActivity";

	private final AtomicBoolean busy = new AtomicBoolean(false);
	private final CropState state = new CropState();
	// Single-thread executor with daemon-threaded worker — serialises load/export/horizon-detect so only one
	// heavyweight CropState-touching task runs at a time. Daemon thread doesn't prevent JVM exit; onDestroy shuts
	// the executor down gracefully so config-change rotation doesn't leak an orphaned worker per recreation.
	private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(task ->
	{
		Thread thread = new Thread(task, "CropCenter-bg");
		thread.setDaemon(true);
		return thread;
	});
	// safFiles / permissions / ui declared before their consumers (saveController, graftController, toolbar)
	// because Java initialises fields in declaration order, so dependencies must come first regardless of
	// strict alphabetical ordering.
	private final SafFileHelper safFiles = new SafFileHelper(this);
	private final StoragePermissionHelper permissions = new StoragePermissionHelper(this);
	private final ImageLoadController imageLoader = new ImageLoadController(this, safFiles);
	private final RestoreController restoreController = new RestoreController(state);
	private final SaveController saveController = new SaveController(this, safFiles, permissions);
	private final GraftController graftController = new GraftController(this, safFiles, this::applyGraftedBytes);
	private final UiSync ui = new UiSync(this);
	private final ToolbarBinder toolbar = new ToolbarBinder(this, ui);

	private ActivityResultLauncher<String> saveAsLauncher;
	// Currently-open state-mutating transient dialog (Settings, merged save, in-app collision / rename /
	// overwrite-confirm, Replace, Custom AR, All-files-access prompt, extension-mismatch —
	// every producer that calls registerTransientDialog or setActiveTransientDialog) tracked
	// so an inbound load can dismiss it before its own bg dispatch starts. Cleared via the dialog's
	// OnDismissListener so the field doesn't outlive the dialog. Touched only from the UI thread — see
	// registerTransientDialog / setActiveTransientDialog / clearTransientDialog / dismissTransientDialogs
	// and their callers.
	private AlertDialog activeTransientDialog;
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
	public void clearTransientDialog(DialogInterface dialog)
	{
		clearActiveTransientDialogIfMatches(dialog);
	}

	@Override
	public void dismissTransientDialogs()
	{
		AlertDialog dialog = activeTransientDialog;
		// Use cancel() not dismiss() so the dialog's OnCancelListener fires too — Custom AR's
		// spinner-position restore, Replace dialog's placeholder cleanup, and SaveDialog's priorSnapshot
		// clear all live in OnCancelListener. dismiss() would only fire
		// OnDismissListener and skip these. cancel() also fires OnDismissListener afterward, so
		// activeTransientDialog still gets cleared via the registerTransientDialog listener.
		if (dialog != null && dialog.isShowing())
		{
			dialog.cancel();
		}
		activeTransientDialog = null;
	}

	@Override
	public void ensureCropCenter()
	{
		if (!state.hasCenter() && state.getSourceImage() != null)
		{
			// markCropSizeDirty would be redundant: every current caller (applyStateToUi's
			// isCropSizeDirty branch, ToolbarBinder's AR-change handler, CustomAR commit) has
			// already flipped the dirty flag via setAspectRatio or has entered through the
			// already-dirty branch in applyStateToUi. Re-marking here was load-bearing on no
			// path; pruned to keep the seam minimal. setCenter's notifyChanged + the next
			// applyStateToUi pass will pick up the dirty flag from the upstream setter and
			// run recomputeCrop's full branch.
			float imageMidX = state.getImageWidth() / 2f;
			float imageMidY = state.getImageHeight() / 2f;
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
		runOnUiThread(this::hideProgressOnUi);
	}

	@Override
	public void installImageOnUi(Bitmap source, Bitmap display, String sizeInfo, String metaInfo)
	{
		// Activity-destroyed guard: bg load tasks dispatched via runInBackground can outlive the Activity
		// (rotation, app backgrounded then killed). findViewById on a destroyed Activity returns null, and
		// the immediate setSelected call below would NPE the main thread, taking down the app.
		// state.setListener(null) in onDestroy suppresses listener-driven UI updates but not these inline
		// findViewByIds, so the guard has to live here. ImageLoadController.applyBytes flips its handedOff
		// flag BEFORE posting this UI runnable so the bg-side cleanup won't recycle the bitmaps on its way
		// out — without an explicit recycle here, the native pixel buffer would only be reclaimed by the GC
		// finalizer once the destroyed Activity itself was GC'd, which on a config-change with a multi-MB
		// source means a real (if short-lived) memory pressure spike. display may alias source — recycle
		// source first, then only recycle display if it's a separate allocation (Bitmap.recycle is
		// idempotent but the identity check makes the intent explicit).
		if (isDestroyed())
		{
			// state.reset() runs FIRST so any state field still holding a Bitmap reference (today only the
			// previous load's sourceImage / displayImage, which the bg-side reset() at applyBytes already
			// nulled before this post — but a future field that pins a Bitmap would land here too) is
			// nulled out before the recycle frees the underlying native pixel buffer. Reset-then-recycle
			// is also the cleaner teardown order: tear down state references first, then free the
			// resources they were referencing.
			//
			// ImageLoadController.applyBytes committed originalFileBytes (~5-50 MB on a multi-MP JPEG),
			// gainMap (~1-10 MB on HDR), jpegMeta, pngExifTiff, and seftTrailer to state BEFORE posting
			// this UI runnable. Without an explicit reset here, those heavy fields stay reachable through
			// the destroyed Activity's CropState until the whole Activity is GC'd — on a config-change
			// race with a 200 MP HDR source that's 30-50 MB pinned in a Bitmap-detached state.
			// state.reset() from the UI thread is safe here because applyBytes has already returned (the
			// runnable is only posted after the bg-side commits complete) and the busy gate prevents
			// another bg load from racing this UI-thread reset.
			state.reset();
			source.recycle();
			if (display != null && display != source)
			{
				display.recycle();
			}
			return;
		}
		// CropState.reset() restored editorMode and centerMode to defaults (Select + Both), but the Pin
		// chip's selected state AND the per-mode lock-axis prefs are UI-driven state that doesn't auto-sync.
		// Deselect Pin AND reset the lock-axis prefs to their initial defaults (Select=BOTH, Move=VERTICAL)
		// so a previous image's H/V Select-lock doesn't bleed into the new load — without this reset,
		// applyLockMode() below would re-apply the persisted selectLockPref and overwrite reset()'s BOTH,
		// contradicting the documented "new loads start Select+Both" invariant. Then call applyLockMode()
		// to propagate the deselected Pin into centerMode (now genuinely a no-op since both reset() and the
		// pref reset agree on BOTH).
		// Wrap the UI-side commit in try/catch so a throw between here and `setSourceImage` (any
		// findViewById returning null on an unusually-laid-out view tree, applyLockMode hitting a
		// stale listener that throws, ui.updateModeHighlight reaching a recycled view) recycles the
		// orphan bitmaps. Without the catch the source / display bitmaps would only be reclaimed
		// when the GC finalizer ran on the orphan references — on a 200 MP source that's ~800 MB
		// of native pixel buffer stranded for an indeterminate window. ImageLoadController's bg-side
		// finally has already flipped `handedOff = true` by the time we run, so it won't recycle on
		// our throw; ownership transfer is complete and the UI side now owns the cleanup contract.
		try
		{
			findViewById(R.id.btnPin).setSelected(false);
			selectLockPref = CenterMode.BOTH;
			moveLockPref = CenterMode.VERTICAL;
			applyLockMode();
			ui.updateModeHighlight();
			ui.updateLockHighlight();
			// Exit horizon paint mode if it was active when this load started. Auto-rotate paint mode
			// never holds the busy flag (it's acquired later, after the user commits the stroke), so
			// Open remains reachable while paint mode is up — without this reset, the new image's
			// first touch would route to horizon painting instead of the expected Select / Move
			// behavior, AND the Auto button would stay stuck on its "Cancel" label / red color from
			// the previous load.
			toolbar.cancelHorizonPaintMode();

			state.setSourceImage(source, display);
			editorView.setState(state);
			editorView.clearUndoHistory();
			txtImageInfo.setText(sizeInfo);
			txtImageFormats.setText(metaInfo);
			// Process-death restore: apply the saved geometry now that the bitmap is in place.
			// RestoreController consumes + nulls the bundle so a subsequent normal load (user
			// taps Open) doesn't re-apply the stale snapshot from the prior session. The state
			// listener fires once at the batch end inside applyIfPending, producing one
			// consolidated re-render.
			//
			// Outcome.consumed is true ONLY when a bundle was actually consumed; in that case the
			// toolbar UI reset earlier in this method (Pin chip deselected, selectLockPref = BOTH,
			// moveLockPref = VERTICAL, AR chip text on the default ratio) is now stale against the
			// restored model, so re-derive the toolbar widgets from the freshly-restored CropState.
			// The outcome also carries the per-mode lock-axis
			// prefs that were stashed alongside the bundle — re-seed them BEFORE syncFromState so
			// the toolbar resync's `host.setCurrentPref(state.getCenterMode())` writes against
			// correct baseline values for the inactive mode. Without this, a user who had Move+H
			// at kill time would see their Select-mode pref correctly restored but Move-mode's
			// pref fall back to VERTICAL (and vice versa). When Pin was on at kill time
			// (centerMode == LOCKED), BOTH prefs come from the bundle since syncFromState skips
			// the setCurrentPref write in the LOCKED case — without this re-seed, the hidden axis
			// preference would silently revert to defaults on Pin toggle-off.
			RestoreController.Outcome outcome = restoreController.applyIfPending();
			if (outcome.consumed())
			{
				if (outcome.restoredSelectPref() != null)
				{
					selectLockPref = outcome.restoredSelectPref();
				}
				if (outcome.restoredMovePref() != null)
				{
					moveLockPref = outcome.restoredMovePref();
				}
			}
			else
			{
				// Fresh load (no restore bundle): seed the aspect ratio from the image's
				// orientation. Landscape (width > height) defaults to R5_4, portrait
				// (height > width) defaults to R4_5, and a true square (width == height)
				// defaults to R1_1 — picking a non-square AR on a square source would crop
				// off part of the image immediately for no obvious benefit. The model-level
				// default of R4_5 covered the portrait-phone case but produced a tall-narrow
				// crop on landscape captures, which was almost always wrong as a starting
				// point. Restored sessions skip this branch so the user's prior AR choice
				// (stashed in the bundle) takes precedence over the orientation default.
				int srcW = source.getWidth();
				int srcH = source.getHeight();
				AspectRatio orientationDefault;
				if (srcW > srcH)
				{
					orientationDefault = AspectRatio.R5_4;
				}
				else if (srcW < srcH)
				{
					orientationDefault = AspectRatio.R4_5;
				}
				else
				{
					orientationDefault = AspectRatio.R1_1;
				}
				state.setAspectRatio(orientationDefault);
			}
			// Always resync the toolbar from state so the AR spinner reflects the orientation
			// default (fresh load) or the restored AR (restore path). Previously this only fired
			// on the restore branch because fresh loads were guaranteed to land on the same R4_5
			// the spinner was already showing; the orientation-aware default breaks that
			// invariant.
			toolbar.syncFromState();
		}
		catch (RuntimeException e)
		{
			Log.e(TAG, "installImageOnUi UI commit threw; recycling orphaned bitmaps", e);
			// state.reset() unconditionally: the bg-side applyBytes already committed originalFileBytes,
			// jpegMeta, gainMap, seftTrailer, pngExifTiff, and sourceFormat to state BEFORE this UI
			// runnable posts. A throw BEFORE setSourceImage (e.g. findViewById null, ClassCastException
			// on a checkbox lookup) would otherwise leave those metadata fields set without a matching
			// sourceImage — inconsistent state that the next save / settings dialog could misread.
			// reset() unwires everything atomically, matching the next-load contract.
			state.reset();
			source.recycle();
			if (display != null && display != source)
			{
				display.recycle();
			}
			// Don't propagate — the user is left with no image; a thrown exception out of the UI
			// thread would crash the Activity.
		}
	}

	@Override
	public boolean isPanning()
	{
		// findViewById can return null on a destroyed Activity (or, theoretically, before view inflation
		// completes if a listener fires very early). Without the null check the isSelected call NPEs the UI
		// thread. The btnPin chip's selected state IS the source of truth for Pin / panning — the model's
		// centerMode is derived from it via applyLockMode, not vice versa.
		View view = findViewById(R.id.btnPin);
		return view != null && view.isSelected();
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
		// Snap the display center to the pixel grid — in Move mode the crop borders must land on whole-pixel
		// boundaries, but a half-integer selection midpoint paired with even cropW (or vice versa) would
		// otherwise leave them mid-pixel.
		CropEngine.recomputeCrop(state);
		// Sync the rotation/drag anchor to the POST-clamp/post-recompute center, not the raw pre-clamp
		// selection midpoint. setCenter clamps when the requested point is near an image/rotation edge
		// or when the current crop dims can't fit there; recomputeCrop may further adjust. Without this
		// post-sync, a later Move-mode rotation or AR change reads the stale anchor (the unreachable
		// pre-clamp midpoint) via findCropCenter's non-Select path and pulls / shrinks the crop back
		// toward an off-image center. Mirrors CropEngine.autoComputeFromPoints' anchor sync.
		state.setAnchor(state.getCenterX(), state.getCenterY());
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
	public AlertDialog registerTransientDialog(AlertDialog newDialog)
	{
		activeTransientDialog = newDialog;
		newDialog.setOnDismissListener(dialog ->
		{
			if (activeTransientDialog == dialog)
			{
				activeTransientDialog = null;
			}
		});
		return newDialog;
	}

	@Override
	public void runInBackground(Runnable task)
	{
		backgroundExecutor.execute(task);
	}

	@Override
	public void setActiveTransientDialog(AlertDialog dialog)
	{
		activeTransientDialog = dialog;
	}

	/**
	 * Disable Save/Open while busy so rapid taps can't stack up. UI thread only. Also acts as the
	 * "load complete" hook for process-death restore: if a load finishes (isBusy=false) AND
	 * pendingRestoreBundle is still non-null, the restore load must have failed (decode error,
	 * unsupported format, revoked URI permission, file deleted) — installImageOnUi would have
	 * consumed the bundle on success. Clear it here so a subsequent manual Open doesn't apply
	 * the stale geometry from the failed-to-restore source.
	 *
	 * @param busy true to disable Save/Open while a background task is running; false to restore
	 *             their normal enabled state (Save additionally requires a loaded image)
	 */
	@Override
	public void setBusyUi(boolean busy)
	{
		View btnSave = findViewById(R.id.btnSave);
		View btnOpen = findViewById(R.id.btnOpen);
		boolean hasImage = state.getSourceImage() != null;
		if (btnSave != null)
		{
			// ImageButton with app:tint doesn't auto-dim on setEnabled — the tint stays mauve
			// regardless of state. Pair setEnabled with alpha so the disabled state reads at a
			// glance, matching the disabled-controls-stay-visible-but-dim contract the rest of
			// the toolbar follows (see UiSync.updateImageDependentToolbar).
			boolean saveEnabled = !busy && hasImage;
			btnSave.setEnabled(saveEnabled);
			btnSave.setAlpha(saveEnabled ? 1f : 0.4f);
		}
		if (btnOpen != null)
		{
			boolean openEnabled = !busy;
			btnOpen.setEnabled(openEnabled);
			btnOpen.setAlpha(openEnabled ? 1f : 0.4f);
		}
		View btnGraft = findViewById(R.id.btnGraft);
		if (btnGraft != null)
		{
			// Mirror UiSync.updateImageDependentToolbar's isJpeg gate plus the busy factor so the graft
			// icon dims during background work like Save / Open do (it used to live on btnOpen, which
			// dimmed). On busy=false this lands on the same value UiSync sets, so there's no conflict.
			boolean graftEnabled = !busy && hasImage && state.getSourceFormat() == Format.JPEG;
			btnGraft.setEnabled(graftEnabled);
			btnGraft.setAlpha(graftEnabled ? 1f : 0.4f);
		}
		// Restore-bundle leak guard: on a successful load installImageOnUi runs BEFORE this
		// setBusyUi(false) (both post via runOnUiThread; install is posted from inside applyBytes
		// before the bg-side finally posts setBusyUi). A non-null bundle here means installImageOnUi
		// never fired — the load failed. Drop the bundle silently rather than letting it apply to
		// whichever image the user opens next.
		if (!busy)
		{
			restoreController.clearPendingIfUnconsumed();
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
		toastIfAlive("Busy — try again", Toast.LENGTH_SHORT);
	}

	@Override
	public void showProgress(String message)
	{
		runOnUiThread(() -> showProgressOnUi(message));
	}

	/**
	 * UI-thread-safe toast helper — noop if Activity is destroyed.
	 *
	 * @param msg    user-facing toast text
	 * @param length Toast duration constant (e.g. Toast.LENGTH_SHORT, Toast.LENGTH_LONG)
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

		// Reclaim cache space from any prior session whose HDR re-decode was interrupted by a process
		// kill (OOM killer, force-stop, low-memory eviction). Each abandoned `hdr_src_<pid>_<nanos>.jpg`
		// can be 5-50 MB on Samsung HDR captures (200+ MB on 200 MP HDR); without a sweep they'd
		// accumulate across launches until scoped storage filled. Cheap call (single directory listing).
		UltraHdrCompat.sweepStaleCacheFiles(getCacheDir());

		// Handle edge-to-edge: apply system bar insets as padding to root layout. Direct framework
		// View.setOnApplyWindowInsetsListener + WindowInsets.Type.systemBars() — no ViewCompat /
		// WindowInsetsCompat wrapper. The listener returns `insets` unmodified so child views still
		// see the unconsumed insets for their own dispatch.
		View root = findViewById(android.R.id.content);
		root.setOnApplyWindowInsetsListener((view, insets) ->
		{
			Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
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

		// Plain SAF DocumentsUI contract for Save As — the only remaining ACTION_*_DOCUMENT site
		// in this Activity. The CreateDocument override only swaps JPEG → PNG mime type when the
		// pre-filled filename ends in .png so the picker shows the right extension. No
		// EXTRA_INITIAL_URI seeding — SAF DocumentsUI tracks the user's last-navigated location
		// across launches itself. Open and Graft both route through the in-app OpenPickerDialog
		// instead; the prior SAF-based graft picker was removed when graft was unified with the
		// load flow's picker (see onGraftClicked / launchGraftPicker below).
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
					// Undocumented DocumentsUI "advanced" storage extra (device-internal volumes,
					// USB, hidden providers). AOSP honors it; Samsung One UI sometimes does.
					// Harmless when ignored.
					intent.putExtra("android.provider.extra.SHOW_ADVANCED", true);
					return intent;
				}
			}, this::onSaveAsLauncherResult);

		View btnOpen = findViewById(R.id.btnOpen);
		// In-app OpenPickerDialog replaces the system SAF picker for both the Open flow and the
		// Graft flow — Samsung One UI's DocumentsUI doesn't persist user sort preferences across
		// launches, and the in-app picker fixes that. Trade-off: loses cross-app access to Drive
		// / Dropbox / Gallery — users must copy files into a filesystem-visible folder first.
		btnOpen.setOnClickListener(view -> showOpenPickerDialog());
		findViewById(R.id.btnGraft).setOnClickListener(view -> onGraftClicked());
		findViewById(R.id.btnSave).setOnClickListener(view -> saveController.showSaveDialog());
		setBusyUi(false); // Save stays disabled until an image is loaded
		findViewById(R.id.btnSettings).setOnClickListener(view -> showSettingsDialog());

		// Grid / Pin / AR chip wire-up lives entirely inside ToolbarBinder.bindAll's per-chip setup
		// methods (setupGridToggle / setupPinToggle / setupArButton) rather than as one-off
		// findViewById blocks here. setupArButton additionally seeds the chip text via
		// ui.updateAspectRatioButton, so the post-bindAll fan-out below skips it.
		toolbar.bindAll();

		ui.updateGridToggle();
		ui.updateModeHighlight();
		ui.updateLockHighlight();
		ui.updateImageDependentToolbar();
		ui.updateAutoRotateChips();
		ui.updatePointButtonStates();
		// MANAGE_EXTERNAL_STORAGE proactive prompt. The Replace-on-save flow needs MES to overwrite a
		// colliding file in public storage (without it, the SAF rename refuses to rename-to-existing
		// on strict providers and the user sees auto-renamed "(N)" files accumulate). Samsung's
		// "Special access → All files access" Settings page is buried three levels deep and many
		// users won't find it from a generic "needs permission" toast. Prompt at startup with the
		// canonical deep-link Intent so the grant flow is one tap away. Suppressed when MES is
		// already granted; suppressed across config-change recreate by gating on savedInstanceState
		// so a rotation / dark-mode toggle doesn't re-prompt. The prompt is dismissable — users who
		// don't want Replace-on-save can skip and continue using auto-rename behavior.
		if (savedInstanceState == null && !permissions.hasStoragePermission())
		{
			showAllFilesAccessPrompt();
		}
		// Process-death restore. Distinct from the Intent-handling path below: if the user was
		// editing an image and Android killed the process for memory, savedInstanceState carries
		// the source URI + every restore-able CropState field. Stash the bundle in the
		// RestoreController, fire the load, and let installImageOnUi consume the bundle once the
		// bg-side decode completes. A missing STATE_SOURCE_URI means there was nothing loaded at
		// kill time, so falls through to the normal handleIncomingIntent path (which is also a
		// no-op for non-Share intents).
		Uri restoreUri = RestoreController.readSourceUri(savedInstanceState);
		if (restoreUri != null)
		{
			restoreController.stash(savedInstanceState);
			// Re-take persistable read+write permission before loading. Share / View intents only
			// ever held SESSION-scoped grants (ImageLoadController.tryTakePersistable called with
			// warnOnFailure=false on the Share path) — after process death the session grant is
			// gone, so the load would fail with SecurityException without this retry. Open / Save
			// As paths already hold the persistable grant from their tryTakePersistable calls;
			// re-taking is idempotent and cheap. warnOnFailure=false because a restored URI whose
			// grant was explicitly revoked by the user isn't actionable from a log line.
			imageLoader.tryTakePersistable(restoreUri, "(restore)", false);
			imageLoader.load(restoreUri);
		}
		else
		{
			imageLoader.handleIncomingIntent(getIntent());
		}
	}

	@Override
	protected void onDestroy()
	{
		// Clear the state listener FIRST so any in-flight notifyChanged() from a background task doesn't fire
		// an Activity-destroyed callback — also so dialog-dismiss-driven setter calls (e.g. SaveDialog's
		// snapshot rollback writing back to ExportConfig) are absorbed as silent no-ops rather than
		// triggering applyStateToUi against the half-destroyed Activity.
		state.setListener(null);
		// Dismiss any tracked transient dialog (FolderPickerDialog, OpenPickerDialog, Settings, in-app
		// collision / rename, Custom AR, etc.) so each dialog's own OnDismissListener runs its cleanup —
		// most critically FolderBrowser.shutdown(), which terminates the picker's bg enumeration / decode
		// executor. Without this, a config-change destroy that fires while the picker is open leaves both
		// the dialog window pinned to the destroyed Activity (via the AlertDialog → Context chain) AND
		// the picker's 3-thread executor running for the lifetime of the worker threads. Runs AFTER
		// setListener(null) by design — see the listener-clearing comment above.
		dismissTransientDialogs();
		// Cancel any open ColorPicker tracked via SettingsDialog's static field. The field is process-wide
		// so a config-change destroy that fires while the picker is open would otherwise pin the destroyed
		// Activity's window through the AlertDialog → Context chain until the next SettingsDialog.show()
		// dismisses the stale picker on entry (which may never happen if the user never reopens Settings).
		SettingsDialog.cancelActivePicker();
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

	@Override
	protected void onSaveInstanceState(Bundle outState)
	{
		super.onSaveInstanceState(outState);
		// Process-death persistence: write the source URI plus the user's editing geometry so a
		// memory-pressure kill doesn't lose 5 minutes of precise alignment work. The bitmap,
		// source bytes, gain map, SEFT trailer, and PNG/EXIF metadata are NOT persisted —
		// those reload from the source URI on restore. Persistability is best-effort: Open and
		// Save As paths call tryTakePersistable with warnOnFailure=true and almost always
		// succeed; the Share / View intent path calls it with warnOnFailure=false because
		// external intents routinely deliver SESSION-only grants that expire at process death.
		// The restore path in onCreate handles both cases — re-takes persistable permission
		// (idempotent for already-persistable URIs, no-op-fails for session-only) and then
		// fires imageLoader.load(), which surfaces a SecurityException as a load failure if
		// the session grant is gone. RestoreController.writeTo handles the actual
		// serialisation; this method is now just the lifecycle hook. The per-mode lock prefs
		// (selectLockPref / moveLockPref) are passed in alongside CropState because they're
		// MainActivity-private fields, not CropState fields — and they must be persisted
		// independently of state.centerMode because Pin collapses centerMode to LOCKED, hiding
		// the underlying axis preference. Note: RestoreController.writeTo writes NOTHING when
		// state.isGraftApplied() is true — graft bytes are in-memory only and a restore would
		// silently reload the pre-graft source. The session loss on a graft-mid-kill is
		// preferable to saving a crop of the wrong image. See REQUIREMENTS.md §8.
		RestoreController.writeTo(outState, imageLoader.getLastLoadedUri(), state,
			selectLockPref, moveLockPref);
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
	 * because Save / Open couldn't have started while busy was held); the bg body's finally block releases on the
	 * normal path, and GraftController.applyConfirmedGraft's catch (which runs releaseBusy then rethrows) handles
	 * the throw-before-bg-runs path.
	 *
	 * Post-apply state writes flow through state.installGraft, which encapsulates the "graftApplied AND aiMask,
	 * both AFTER reset" invariant. Skipping the call would let canBypassEncode short-circuit the canvas pass and
	 * ship source's gain map verbatim over the spliced primary, plus leave the gain-map inpaint silent.
	 *
	 * @param graft pre-baked splice from GraftController (bytes + displayName + aiMask); applyBytes
	 *              treats `bytes` as a freshly-loaded JPEG, installGraft persists `aiMask` for the HDR
	 *              re-encode's inpaint step
	 */
	private void applyGraftedBytes(Graft graft)
	{
		// Unlike the other 4 runInBackground entry points (ImageLoadController.load, ExportPipeline.exportTo,
		// GraftController.onEditPicked, AutoRotateBinder.onHorizonPaintComplete), this enqueue site does NOT
		// acquire busy and does NOT own pre-enqueue cleanup. Two distinct cleanup paths:
		//   - bg body completes / throws → applyGraftedBytesOnBg's finally releases busy.
		//   - runInBackground itself throws (RejectedExecutionException after executor shutdown) → the throw
		//     propagates synchronously to GraftController.applyConfirmedGraft.catch, which runs releaseBusy
		//     and rethrows.
		// Dismiss any open transient dialog BEFORE the bg dispatch — applyBytes runs state.reset() on bg,
		// and an open dialog with in-flight commits to CropState (e.g. a SettingsDialog updateGridConfig)
		// would race the reset.
		dismissTransientDialogs();
		runInBackground(() -> applyGraftedBytesOnBg(graft));
	}

	/**
	 * Bg-thread body of applyGraftedBytes. Runs the spliced bytes through applyBytes, installs graft state on
	 * success, fires the user-visible toast, and releases the busy flag in finally regardless of outcome.
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
			else
			{
				// applyBytes returns false when BitmapFactory rejected the spliced bytes — the user
				// already saw "Failed to decode" but has no signal that graft assembly itself succeeded
				// and the result wouldn't decode (which is the actionable bug — likely a GraftWriter
				// corner case). Without this branch the user concludes their image is corrupt rather
				// than the graft pipeline. Log.w fires a developer-facing breadcrumb on logcat with the
				// MainActivity tag; the toast names the failure mode without asking the user to file a
				// bug they have no in-app affordance to file.
				Log.w(TAG, "graft splice produced undecodable bytes — likely a GraftWriter regression");
				runOnUiThread(() -> toastIfAlive(
					"Graft produced an undecodable result — apply aborted", Toast.LENGTH_LONG));
			}
		}
		catch (Exception | OutOfMemoryError e)
		{
			// Widened to include OutOfMemoryError — graft bytes carry the source's HDR + SEFT, so the
			// applyBytes decode + applyOrientation pass is the second-largest allocation peak in the
			// app (after primary export). Without OOM in the catch, a low-memory device would see the
			// progress overlay vanish with no signal that the graft apply died.
			Log.e(TAG, "Apply graft failed", e);
			runOnUiThread(() -> toastIfAlive("Apply failed: " + e.getMessage(), Toast.LENGTH_SHORT));
		}
		finally
		{
			busy.set(false);
			runOnUiThread(() ->
			{
				setBusyUi(false);
				hideProgress();
			});
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
			ui.updateAspectRatioButton();
			ui.updateCropInfo();
			ui.updateGridToggle();
			ui.updateZoomBadge();
			ui.updatePointButtonStates();
			ui.updateAutoRotateChips();
			ui.updateImageDependentToolbar();
			ui.updateModeHighlight();
			ui.updateLockHighlight();
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

	/**
	 * Dismiss-listener callback for dialogs that install their own composite OnDismissListener — currently
	 * SettingsDialog.show (cancelActivePicker + this) and FolderPickerDialog (executor shutdown + this).
	 * Clears `activeTransientDialog` only if it still references the dialog that just dismissed. The
	 * reference-equality guard prevents a stale dismiss from a now-superseded dialog from clearing a
	 * tracking handle that already points at a different dialog.
	 *
	 * @param dismissed the dialog reporting its dismiss; cleared from tracking only when it still
	 *                  matches activeTransientDialog (reference equality, not value equality —
	 *                  AlertDialog has no equals override)
	 */
	private void clearActiveTransientDialogIfMatches(DialogInterface dismissed)
	{
		if (activeTransientDialog == dismissed)
		{
			activeTransientDialog = null;
		}
	}

	/**
	 * UI-thread body of hideProgress. Extracted so the runOnUiThread call site stays a method-reference one-liner
	 * (isDestroyed guard + view-tree mutation push the body past 3 lines). Symmetric to showProgressOnUi.
	 */
	private void hideProgressOnUi()
	{
		if (isDestroyed())
		{
			return;
		}
		findViewById(R.id.progressOverlay).setVisibility(View.GONE);
	}

	/**
	 * Picker-launch Runnable handed to GraftController.start. Opens the same in-app
	 * OpenPickerDialog the Open flow uses, but with the graft-specific result callback
	 * (onGraftFilePicked). Re-throws on dialog-show failure so GraftController.start's catch
	 * can roll back graftPending — silent swallowing would leak the state and block future
	 * graft attempts.
	 */
	private void launchGraftPicker()
	{
		File startDir = SaveController.loadInitialPickerFolder(this);
		try
		{
			// jpegOnly=true: graft splices JPEG bytes via EditAligner, so PNG taps would only fail
			// downstream with "Selected file is not a JPEG". Gating at the picker greys PNGs out so
			// the user can see they're not selectable, matching the way WebP / HEIC / HEIF are
			// already handled for the load flow.
			activeTransientDialog = new OpenPickerDialog(this, startDir,
				this::onGraftFilePicked, this::clearTransientDialog, true).show();
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "graft picker dialog failed to show", e);
			throw e;
		}
	}

	/**
	 * btnGraft click handler. Gates on isDestroyed (config-change race) and
	 * MANAGE_EXTERNAL_STORAGE BEFORE calling GraftController.start so the controller's state
	 * machine (graftPending + pendingSource snapshot) doesn't get half-set when MES is missing
	 * — the in-app picker needs MES to enumerate the filesystem, and a partial state would
	 * block future graft attempts after the user grants the permission. Mirrors the gate ordering
	 * in showOpenPickerDialog.
	 */
	private void onGraftClicked()
	{
		if (isDestroyed())
		{
			return;
		}
		if (!requireStoragePermission("Apply External Edit"))
		{
			return;
		}
		graftController.start(this::launchGraftPicker);
	}

	/**
	 * Result handler for the in-app picker when launched for the graft flow. Picked File is
	 * converted to a SAF externalstorage URI (same round-trip the Open flow uses) and handed
	 * to GraftController.onEditPicked. Null file (Cancel / external dismiss) routes through
	 * onEditPickerCancelled so the controller can clear graftPending + pendingSource.
	 *
	 * @param file file the user tapped in the picker; null on Cancel / dismiss
	 */
	private void onGraftFilePicked(File file)
	{
		if (file == null)
		{
			graftController.onEditPickerCancelled();
			return;
		}
		Uri uri = SafFileHelper.buildExternalStorageDocumentUri(file);
		if (uri == null)
		{
			toastIfAlive("Couldn't resolve picked file to a graft URI", Toast.LENGTH_SHORT);
			graftController.onEditPickerCancelled();
			return;
		}
		graftController.onEditPicked(uri);
	}

	/**
	 * Result handler for the in-app OpenPickerDialog. Picked file gets converted to a SAF
	 * externalstorage document URI so the existing ImageLoadController.load path applies unchanged —
	 * the URI resolves back to the same File via SafFileHelper.fileFromSafUri's primary: branch
	 * (pure string parsing), and tryReadDirectlyFromPath reads from disk directly. No
	 * tryTakePersistable call: the URI is locally constructed from the file path (not granted by a
	 * SAF picker), so there's no SAF permission grant to persist — MES covers the read.
	 *
	 * @param file file the user tapped in the picker; null on Cancel / dismiss
	 */
	private void onOpenPickerResult(File file)
	{
		if (file == null)
		{
			return;
		}
		Uri uri = SafFileHelper.buildExternalStorageDocumentUri(file);
		if (uri == null)
		{
			toastIfAlive("Couldn't resolve picked file to a load URI", Toast.LENGTH_SHORT);
			return;
		}
		imageLoader.load(uri);
	}

	/**
	 * Result handler for the Save As SAF picker. Extracted from the registerForActivityResult call-site
	 * lambda because the success path must run tryTakePersistable BEFORE handleSaveAsResult so a
	 * busy-thrown SAF re-read of the just-created document can succeed — that ordering is hard to
	 * read inline, and a named method gives the dispatch a stack-trace anchor.
	 *
	 * @param uri SAF document URI committed by the user; null when the picker was cancelled
	 */
	private void onSaveAsLauncherResult(Uri uri)
	{
		if (uri != null)
		{
			// Persistable permission lets Replace's SAF fallbacks reopen the same document later
			// (re-read, re-rename). SAF grants write on creation but the grant expires at process
			// death without this; file-I/O fallback still works when MANAGE_EXTERNAL_STORAGE is
			// held, so failure is non-fatal but worth warning about.
			imageLoader.tryTakePersistable(uri, "(save)", true);
			saveController.handleSaveAsResult(uri);
		}
		else
		{
			saveController.onSaveCancelled();
		}
	}

	/**
	 * Common MES (MANAGE_EXTERNAL_STORAGE) gate for the toolbar's filesystem-using actions (Open,
	 * Apply External Edit). Returns true when the permission is held; otherwise surfaces a
	 * directed toast naming the feature so the user knows which action was blocked, and returns
	 * false. Shared by btnOpen's click handler and onGraftClicked so the gate wording stays in
	 * one place.
	 *
	 * @param featureNoun user-facing label of the action being attempted (e.g. "Open",
	 *                    "Apply External Edit") — interpolated into the toast
	 * @return true when MES is currently granted; false after the toast fires
	 */
	private boolean requireStoragePermission(String featureNoun)
	{
		if (permissions.hasStoragePermission())
		{
			return true;
		}
		toastIfAlive(featureNoun + " requires \"All files access\" — grant it from "
			+ "Settings → Permissions", Toast.LENGTH_LONG);
		return false;
	}

	/**
	 * One-time-per-launch dialog that offers a direct deep-link to the system "All files access"
	 * Settings page when MANAGE_EXTERNAL_STORAGE isn't granted. Without this, users have to dig
	 * three levels deep into Settings (Apps → Special access → All files access → CropCenter) to
	 * find the toggle — most won't.
	 *
	 * Registered as a transient dialog so a Share/View intent arriving mid-prompt dismisses it
	 * cleanly. BadTokenException guard around .show() covers the config-change race window between
	 * onCreate and the first frame.
	 */
	private void showAllFilesAccessPrompt()
	{
		String message = "CropCenter uses \"All files access\" for three things:"
			+ "\n\n• Open — the in-app picker needs it to browse your photo folders."
			+ "\n• Apply External Edit — same picker, same requirement."
			+ "\n• Save → Replace — overwrites the original file directly. Without it, every"
			+ " Replace falls back to an auto-renamed copy (\"foo (1).jpg\")."
			+ "\n\nWithout the grant, Open and Apply External Edit show a \"requires All files"
			+ " access\" toast. You can grant or revoke it anytime from Settings."
			+ "\n\nGrant access now?";
		try
		{
			registerTransientDialog(new AlertDialog.Builder(this)
				.setTitle("Allow All files access?")
				.setMessage(message)
				.setPositiveButton("Grant access",
					(dialog, which) -> permissions.openStoragePermissionSettings())
				.setNegativeButton("Skip", null)
				.show());
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "All-files-access prompt failed to show", e);
		}
	}

	/**
	 * Open the in-app image picker — replaces the system SAF ACTION_OPEN_DOCUMENT picker for the
	 * Open flow. Computes the initial folder via SaveController.loadInitialPickerFolder (last-saved
	 * or last-loaded folder, most recent wins; falls back to primary external storage). On pick,
	 * resolves the File to a SAF externalstorage URI via SafFileHelper.buildExternalStorageDocumentUri
	 * (which keeps the load path's tryReadDirectlyFromPath fast-path engaged so the Samsung MediaStore
	 * EXIF mangling stays bypassed) and routes through imageLoader.load — same path the Share / View
	 * intents take, so the busy-gating, dialog dismissal, and progress-overlay contract is shared.
	 *
	 * Gated on busy so a tap during an in-flight load / save / detect / graft surfaces the busy toast
	 * rather than opening a picker that would no-op on file tap. Also gated on MES: the in-app picker
	 * uses File.listFiles() to enumerate folders, which returns null on most directories under
	 * primary external storage without MANAGE_EXTERNAL_STORAGE on Android 11+ scoped storage. Without
	 * MES the picker would open showing partial / empty contents with no signal to the user, so we
	 * surface a directed toast pointing at the Settings card's Permissions affordance rather than
	 * silently failing. Mirrors SaveController.showSaveDialog's MES branch (which falls back to the
	 * legacy SAF picker for the save flow); for Open there is no equivalent fallback because the
	 * user chose "in-app only" — the system SAF picker is gone by design. Wrapped in try/catch
	 * (BadTokenException) for the config-change race window between the isDestroyed pre-check and
	 * the .show() call.
	 */
	private void showOpenPickerDialog()
	{
		if (busy.get())
		{
			showBusyToast();
			return;
		}
		if (isDestroyed())
		{
			return;
		}
		if (!requireStoragePermission("Open"))
		{
			return;
		}
		File startDir = SaveController.loadInitialPickerFolder(this);
		try
		{
			// jpegOnly=false: the load pipeline accepts both JPEG and PNG.
			activeTransientDialog = new OpenPickerDialog(this, startDir,
				this::onOpenPickerResult, this::clearTransientDialog, false).show();
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "open picker dialog failed to show", e);
		}
	}

	/**
	 * UI-thread body of showProgress. Extracted to keep the runOnUiThread call-site lambda within the
	 * 3-line cap. Same isDestroyed-guard pattern as hideProgressOnUi / installImageOnUi.
	 *
	 * @param message progress-text label
	 */
	private void showProgressOnUi(String message)
	{
		if (isDestroyed())
		{
			return;
		}
		View overlay = findViewById(R.id.progressOverlay);
		((TextView) findViewById(R.id.progressText)).setText(message);
		overlay.setVisibility(View.VISIBLE);
	}

	/**
	 * Open the Settings dialog, or show the busy toast if a save / load / detect / graft is in flight.
	 * The gate matters because SettingsDialog mutates state.gridConfig (color picker, line width,
	 * presets) on the UI thread while a concurrent load on the bg executor runs state.reset() — which
	 * read-modify-writes the same non-volatile gridConfig field. Without the gate, tapping Settings
	 * during a Share/View load can lose either the user's preference change or the reset's
	 * includeInExport=false clear.
	 *
	 * The complementary already-open-dialog race is closed by dismissTransientDialogs(): the dialog
	 * reference is tracked here and dismissed from ImageLoadController.load's UI-thread entry before any
	 * bg work begins. The OnDismissListener clears the tracked reference so a normal "Done" dismissal
	 * doesn't leave a stale handle behind.
	 */
	private void showSettingsDialog()
	{
		if (busy.get())
		{
			showBusyToast();
			return;
		}
		// BadTokenException guard: config-change races can land between the Settings tap and the
		// dialog show. isDestroyed is the first line of defense; the try/catch is the second (the
		// race window between the check and the actual show is still open).
		if (isDestroyed())
		{
			return;
		}
		try
		{
			// Can't use `registerTransientDialog(SettingsDialog.show(...))` here because that wraps
			// with `setOnDismissListener` which REPLACES SettingsDialog's own cancelActivePicker
			// cleanup. Pass the activity-tracking clear as SettingsDialog.show's hostDismissListener
			// parameter so the dialog installs ONE composed listener that runs both cleanups.
			activeTransientDialog = SettingsDialog.show(this, state, permissions,
				this::clearTransientDialog);
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "settings dialog failed to show", e);
		}
	}
}
