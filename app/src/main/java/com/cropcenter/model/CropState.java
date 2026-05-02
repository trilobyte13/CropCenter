package com.cropcenter.model;

import android.graphics.Bitmap;

import com.cropcenter.crop.RotatedCropClamp;
import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.util.AiRegionDetector.AiMask;
import com.cropcenter.util.BitmapUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Central state object for the crop editor. Holds all parameters, source image, and extracted metadata.
 */
public class CropState
{
	public interface OnStateChangedListener
	{
		void onStateChanged();
	}

	// Listener dispatch + batch-suppression protocol extracted into a dedicated helper. Setters call
	// bus.notifyChanged() to fire the registered listener; the Activity wraps recomputeCrop + UI updates in
	// bus.beginBatch / bus.endBatch so inner setter calls coalesce into one listener invocation.
	private final StateBus bus = new StateBus();

	// Set by GraftController.onEditPicked when an Apply External Edit produces an AI region; cleared by reset() on
	// the next image load. Read by UltraHdrCompat at HDR re-encode time so the gain map's HDR boost in the AI
	// region can be inpainted from surrounding values (matches the visual intent of Generative Remove without the
	// stale "boost the features that used to be there" artifact).
	private AiMask aiMask;
	private AspectRatio aspectRatio = AspectRatio.R4_5;
	private Bitmap sourceImage;
	private CenterMode centerMode = CenterMode.BOTH;
	private EditorMode editorMode = EditorMode.SELECT_FEATURE;
	// Mutated only via updateExportConfig / updateGridConfig — the record types themselves are immutable, so state
	// observers see a consistent snapshot and notifyChanged fires exactly once per logical transition. (Same
	// applies to gridConfig below.)
	private ExportConfig exportConfig = ExportConfig.defaults();
	private Format sourceFormat;
	private GridConfig gridConfig = GridConfig.defaults();
	// Volatile + replace-instead-of-clear in reset() so the background-thread loadImage path can safely null-swap
	// the list while the UI thread iterates it — an in-place ArrayList.clear() from bg would CME the UI iterator.
	// Same applies to selectionPoints below.
	private volatile List<JpegSegment> jpegMeta = new ArrayList<>();
	// Mutated only via addSelectionPoint / removeSelectionPoint* / replaceSelectionPoints / clearSelectionPoints.
	// Callers never mutate directly — getSelectionPoints() returns an unmodifiable view. Volatile rationale matches
	// jpegMeta above. Non-volatile mutation via addSelectionPoint / etc. runs only on the UI thread, so those don't
	// need synchronisation.
	private volatile List<SelectionPoint> selectionPoints = new ArrayList<>();
	private String originalFilename;
	private boolean centerLocked = false; // when true, auto-recompute from points is suppressed
	private boolean cropSizeDirty = true;
	// True when the in-memory image came from the Apply External Edit graft flow rather than a direct file load.
	// Set by MainActivity after applyBytes installs the spliced bytes. Read by ExportPipeline.canBypassEncode to
	// refuse the verbatim-write bypass for graft saves — bypassing would ship source's gain map verbatim over the
	// spliced primary, so any user crop afterwards shifts the gain map's HDR boost off the features it's meant for.
	// The full encode regenerates the gain map from the spliced primary.
	private boolean graftApplied;
	private boolean hasCenter;
	private byte[] gainMap;
	private byte[] originalFileBytes;
	private byte[] seftTrailer; // Samsung SEFT trailer (appended after gain map)
	private float anchorX; // stable "intent" center for no-selection rotations
	private float anchorY;
	private float centerX;
	private float centerY;
	private float rotationDegrees = 0f; // precise rotation applied to source image
	private int cropH;
	private int cropW;

	/**
	 * Append a selection point. Fires the state listener once.
	 */
	public void addSelectionPoint(SelectionPoint point)
	{
		selectionPoints.add(point);
		notifyChanged();
	}

	/**
	 * Start a batch: any notifyChanged calls until the matching endBatch record a dirty flag instead of firing the
	 * listener. Nested batches are supported — only the outermost endBatch fires. Used by the Activity's state
	 * listener to wrap recomputeCrop + UI updates so the recompute's inner setters don't re-enter the listener.
	 */
	public void beginBatch()
	{
		bus.beginBatch();
	}

	/**
	 * Remove every selection point. No-op + no listener fire when already empty.
	 */
	public void clearSelectionPoints()
	{
		if (selectionPoints.isEmpty())
		{
			return;
		}
		selectionPoints.clear();
		notifyChanged();
	}

	/**
	 * End a batch started by beginBatch. Fires the listener once if any setter called notifyChanged during the
	 * batch; otherwise silent.
	 */
	public void endBatch()
	{
		bus.endBatch();
	}

	/**
	 * Mask of AI-modified pixels from the most recent Apply External Edit graft, or null when no graft is active.
	 * UltraHdrCompat reads this at HDR re-encode time to inpaint the gain map's boost values inside the AI region
	 * (matching Generative Remove's intent of "this region looks like its neighbors" without the gain map's stale
	 * boost-the-removed-features artifact).
	 */
	public AiMask getAiMask()
	{
		return aiMask;
	}

	/**
	 * Stable rotation / drag anchor X — the user's intended (unclamped) crop center. See setAnchor for why this is
	 * distinct from centerX.
	 */
	public float getAnchorX()
	{
		return anchorX;
	}

	/**
	 * Stable rotation / drag anchor Y — see getAnchorX.
	 */
	public float getAnchorY()
	{
		return anchorY;
	}

	/**
	 * Current aspect-ratio constraint for the crop box. FREE means no constraint.
	 */
	public AspectRatio getAspectRatio()
	{
		return aspectRatio;
	}

	/**
	 * Which axes of the crop are locked symmetrically about the selection (or LOCKED).
	 */
	public CenterMode getCenterMode()
	{
		return centerMode;
	}

	/**
	 * Crop center X in un-rotated image coords. Continuous float.
	 */
	public float getCenterX()
	{
		return centerX;
	}

	/**
	 * Crop center Y in un-rotated image coords. Continuous float.
	 */
	public float getCenterY()
	{
		return centerY;
	}

	/**
	 * Crop height in integer image pixels.
	 */
	public int getCropH()
	{
		return cropH;
	}

	/**
	 * Continuous-float crop left for the renderer: centerX − cropW / 2f. Sub-pixel precision so a smoothly rotating
	 * selection midpoint produces smooth crop motion on screen. Returns 0 when no crop is placed. Callers that need
	 * an integer pixel origin cast via Math.floor at the call site — the exporter absorbs the sub-pixel bias.
	 */
	public float getCropImageXFloat()
	{
		if (!hasCenter)
		{
			return 0f;
		}
		return centerX - cropW / 2f;
	}

	/**
	 * Continuous-float crop top for the renderer — see getCropImageXFloat.
	 */
	public float getCropImageYFloat()
	{
		if (!hasCenter)
		{
			return 0f;
		}
		return centerY - cropH / 2f;
	}

	/**
	 * Crop width in integer image pixels.
	 */
	public int getCropW()
	{
		return cropW;
	}

	/**
	 * Current editor interaction mode (Move or Select-Feature).
	 */
	public EditorMode getEditorMode()
	{
		return editorMode;
	}

	/**
	 * Current ExportConfig snapshot. Immutable — writes go through updateExportConfig, which replaces this field
	 * with a new instance and fires notifyChanged.
	 */
	public ExportConfig getExportConfig()
	{
		return exportConfig;
	}

	/**
	 * Raw Ultra HDR gain-map bytes extracted at load time, or null for non-HDR sources. The exporter re-composes
	 * this onto the cropped primary for HDR-preserving saves.
	 */
	public byte[] getGainMap()
	{
		return gainMap;
	}

	/**
	 * Current GridConfig snapshot. Immutable — writes go through updateGridConfig, which replaces this field with a
	 * new instance and fires notifyChanged.
	 */
	public GridConfig getGridConfig()
	{
		return gridConfig;
	}

	/**
	 * Source bitmap height, or 0 before any image loads.
	 */
	public int getImageHeight()
	{
		return sourceImage != null ? sourceImage.getHeight() : 0;
	}

	/**
	 * Source bitmap width, or 0 before any image loads.
	 */
	public int getImageWidth()
	{
		return sourceImage != null ? sourceImage.getWidth() : 0;
	}

	/**
	 * Read-only view of the loaded JPEG's segment list. Returns an empty list before any image is loaded. Populated
	 * en-bloc by setJpegMeta during loadImage; the list is never mutated in place.
	 */
	public List<JpegSegment> getJpegMeta()
	{
		return Collections.unmodifiableList(jpegMeta);
	}

	/**
	 * Original file bytes captured at load. Used by ExportPipeline.canBypassEncode for the verbatim-write path and
	 * by GraftController.onEditPicked as the splice base. Null when the source was loaded via a SAF stream that
	 * wasn't buffered.
	 */
	public byte[] getOriginalFileBytes()
	{
		return originalFileBytes;
	}

	/**
	 * Display filename of the loaded image — used in Save-As default naming and info bar.
	 */
	public String getOriginalFilename()
	{
		return originalFilename;
	}

	/**
	 * Precise rotation angle applied to the source at draw time. Clamped to [−180, 180].
	 */
	public float getRotationDegrees()
	{
		return rotationDegrees;
	}

	/**
	 * Samsung Extended Format Trailer captured at load, or null for non-Samsung sources. Re-appended verbatim to
	 * the exported JPEG so a Gallery-edited source's existing Revert chain stays intact across CropCenter re-edits
	 * — the trailer's backup-path reference still points at Gallery's own `/data/sec/photoeditor/` backup, which we
	 * never touched. CropCenter does not generate fresh trailers for new edits (Gallery rejects backup paths
	 * outside its blessed locations).
	 */
	public byte[] getSeftTrailer()
	{
		return seftTrailer;
	}

	/**
	 * Unmodifiable view of the selection points. Callers that need to mutate must go through addSelectionPoint /
	 * removeSelectionPoint / clearSelectionPoints / replaceSelectionPoints so each change fires notifyChanged
	 * exactly once — previously callers mutated the backing list directly and had to remember to trigger recomputes
	 * themselves.
	 */
	public List<SelectionPoint> getSelectionPoints()
	{
		return Collections.unmodifiableList(selectionPoints);
	}

	/**
	 * Format of the loaded source — JPEG or PNG. Independent of export format. Null when no image is loaded or when
	 * the source's signature doesn't match either supported format.
	 */
	public Format getSourceFormat()
	{
		return sourceFormat;
	}

	/**
	 * The loaded bitmap in display orientation (EXIF orientation already applied), or null before any image loads.
	 * Callers must null-check.
	 */
	public Bitmap getSourceImage()
	{
		return sourceImage;
	}

	/**
	 * True once the crop center has been placed (via tap, drag, or auto-compute).
	 */
	public boolean hasCenter()
	{
		return hasCenter;
	}

	/**
	 * Apply the post-applyBytes side-effects for a successful graft: set graftApplied (which forces ExportPipeline
	 * through the canvas-encode path so the gain map gets regenerated from the spliced primary, keeping HDR boost
	 * spatially aligned with the edit's pixels) and stash the AI-region mask (which UltraHdrCompat then reads at
	 * HDR encode time to drive GainMapInpainter inside the Generative Remove fill).
	 *
	 * MUST be called only after applyBytes returns true — that path runs reset() which clears both fields, so
	 * calling installGraft before applyBytes leaves the fields cleared, and calling it on a decode failure
	 * (applyBytes returned false) would install graft state onto the previously-loaded image. Encapsulates the "two
	 * state writes, both after reset, both required" rule in a single call site so a future refactor of the apply
	 * flow can't silently skip one. Without graftApplied, canBypassEncode short-circuits the canvas pass and ships
	 * source's gain map verbatim over the spliced primary (boost lands on the wrong features). Without aiMask, the
	 * inpaint step is a no-op and the gain map's stale boost-the-removed-features artifact survives in the output.
	 */
	public void installGraft(Graft graft)
	{
		setGraftApplied(true);
		if (graft.hasAiMask())
		{
			setAiMask(graft.aiMask());
		}
	}

	/**
	 * True while Pan mode is active — suppresses auto-recompute so the crop stays put while the user drags the
	 * viewport.
	 */
	public boolean isCenterLocked()
	{
		return centerLocked;
	}

	/**
	 * True when cropW / cropH need a fresh recompute. Set by setAspectRatio, setRotationDegrees, markCropSizeDirty,
	 * and CropEngine.autoComputeFromPoints; cleared by CropEngine.recomputeCrop on completion.
	 */
	public boolean isCropSizeDirty()
	{
		return cropSizeDirty;
	}

	/**
	 * True when the in-memory image is the result of an Apply External Edit graft. ExportPipeline reads this to
	 * disable the verbatim-write bypass, so graft saves always go through CropExporter.export — that's the path
	 * that runs canvas-based P3 colour management (the bypass would write the splice's foreign-ICC bytes verbatim
	 * and produce a slightly different output structure than the cropped graft path). Cleared by reset() when a
	 * fresh image loads.
	 */
	public boolean isGraftApplied()
	{
		return graftApplied;
	}

	/**
	 * Flag cropW / cropH for recompute on the next listener cycle. Does not fire the listener itself — callers that
	 * also want immediate recompute call notifyChanged via a setter or invoke recomputeCrop directly.
	 */
	public void markCropSizeDirty()
	{
		this.cropSizeDirty = true;
	}

	/**
	 * Remove a selection point by equality. Returns true if anything was removed. Fires the state listener only
	 * when something was actually removed.
	 */
	public boolean removeSelectionPoint(SelectionPoint point)
	{
		boolean removed = selectionPoints.remove(point);
		if (removed)
		{
			notifyChanged();
		}
		return removed;
	}

	/**
	 * Remove the selection point at the given index. Throws IndexOutOfBoundsException on an invalid index; always
	 * fires the listener when it does return.
	 */
	public SelectionPoint removeSelectionPointAt(int index)
	{
		SelectionPoint removed = selectionPoints.remove(index);
		notifyChanged();
		return removed;
	}

	/**
	 * Replace all selection points atomically (used for undo/redo snapshot restores).
	 */
	public void replaceSelectionPoints(Collection<SelectionPoint> newPoints)
	{
		selectionPoints.clear();
		selectionPoints.addAll(newPoints);
		notifyChanged();
	}

	/**
	 * Reset everything for a new image.
	 */
	public void reset()
	{
		sourceImage = null;
		originalFileBytes = null;
		sourceFormat = null;
		anchorX = 0;
		anchorY = 0;
		centerX = 0;
		centerY = 0;
		cropW = 0;
		cropH = 0;
		hasCenter = false;
		cropSizeDirty = true;
		rotationDegrees = 0f;
		centerLocked = false;
		graftApplied = false;
		// Restore the documented defaults (Select mode, Both lock-axis). Without this, a new image inherits the
		// previous session's editor/lock state — e.g. loading a photo into a still-active Move + Pan combo
		// jumps straight to viewport-pan gestures when the spec says new loads start in Select mode centered on
		// the image. MainActivity's loadImage UI runnable resyncs the toolbar widgets to match these reset
		// values.
		editorMode = EditorMode.SELECT_FEATURE;
		centerMode = CenterMode.BOTH;
		// aspectRatio preserved — it's a user preference, not image data. exportConfig reset to defaults (JPEG)
		// — the prior image's save-time format choice was transient. The load-time extractMetadata path
		// overrides this to match the source's actual format; starting from JPEG matches the common case.
		exportConfig = ExportConfig.defaults();
		// gridConfig: preserve user preferences (colors, cols/rows, line width, pixel grid) but clear
		// includeInExport. Baking the grid into output is a per-save choice; having it bleed into the next
		// image is a footgun (user saved image A with grid baked in, loads image B, saves B — and B silently
		// gets a grid baked in too unless they remembered to untick). Prefer the safe default.
		if (gridConfig.includeInExport())
		{
			gridConfig = gridConfig.withIncludeInExport(false);
		}
		originalFilename = null;
		// Replace rather than clear-in-place. reset() runs on the background loadImage executor, and an
		// in-place ArrayList.clear() would CME a UI-thread iterator (onTap / draw / auto-rotate metadata read).
		// Volatile reference swap publishes the fresh empty list; any iterator already mid-walk keeps working
		// on the old list (now orphaned, GC'd once the iteration completes).
		selectionPoints = new ArrayList<>();
		jpegMeta = new ArrayList<>();
		gainMap = null;
		seftTrailer = null;
		aiMask = null;
	}

	/**
	 * Stable rotation anchor for the no-selection case — setCenter's rotation clamp can pull centerX inward to keep
	 * the crop inside the rotated image, so reading centerX back on the next recompute would accumulate inward
	 * drift. The anchor holds the user's intended (unclamped) position. Callers that move the crop (user drag,
	 * image load) also call setAnchor so the next recompute uses a fresh starting position; rotation and AR changes
	 * leave the anchor alone.
	 */
	public void setAnchor(float x, float y)
	{
		this.anchorX = x;
		this.anchorY = y;
	}

	/**
	 * Replace the aspect-ratio constraint. Marks the crop size dirty so the next recompute resizes to fit, then
	 * fires the listener.
	 */
	public void setAspectRatio(AspectRatio ar)
	{
		this.aspectRatio = ar;
		cropSizeDirty = true;
		notifyChanged();
	}

	/**
	 * Set the crop center, clamping to keep the crop rectangle fully inside the (possibly rotated) image. Under
	 * rotation the clamp does an independent per-axis binary search to avoid one axis's clamp influencing the
	 * other. Fires the listener on every call, even if the clamp moves the target.
	 */
	public void setCenter(float x, float y)
	{
		// Clamp so crop rect stays fully inside the (possibly rotated) image.
		if (sourceImage != null && cropW > 0 && cropH > 0)
		{
			int imgW = sourceImage.getWidth();
			int imgH = sourceImage.getHeight();
			float[] clamped = Math.abs(rotationDegrees) < BitmapUtils.ROTATION_EPSILON
				? RotatedCropClamp.clampAxisAligned(x, y, cropW, cropH, imgW, imgH)
				: RotatedCropClamp.clampRotated(x, y, cropW, cropH, rotationDegrees, imgW, imgH);
			x = clamped[0];
			y = clamped[1];
		}
		this.centerX = x;
		this.centerY = y;
		this.hasCenter = true;
		notifyChanged();
	}

	/**
	 * Lock / unlock auto-recompute. Used by Pan mode to freeze the crop while the user drags the viewport. Does not
	 * fire the listener — caller controls redraw cadence.
	 */
	public void setCenterLocked(boolean locked)
	{
		this.centerLocked = locked;
	}

	/**
	 * Replace the lock mode. Does NOT mark the crop size dirty (see comment below) — the button handler explicitly
	 * calls recomputeForLockChange with the correct selection midpoint, avoiding a race between the listener-driven
	 * recompute and the handler-driven one.
	 */
	public void setCenterMode(CenterMode mode)
	{
		this.centerMode = mode;
		// Don't set cropSizeDirty here — the button handler calls recomputeForLockChange() explicitly. Setting
		// dirty here causes the listener to recompute IMMEDIATELY (runOnUiThread runs inline on UI thread) with
		// the wrong center, racing with the handler's recomputeForLockChange that uses the correct selection
		// midpoint.
		notifyChanged();
	}

	/**
	 * Set center without bounds clamping or notification — used before recomputeCrop.
	 */
	public void setCenterUnclamped(float x, float y)
	{
		this.centerX = x;
		this.centerY = y;
		this.hasCenter = true;
		// No notifyChanged — caller will trigger notify via recomputeCrop → setCenter
	}

	/**
	 * Set the dirty flag explicitly. Primarily used to clear dirty AFTER recompute completes; setting dirty=true is
	 * usually expressed via markCropSizeDirty.
	 */
	public void setCropSizeDirty(boolean dirty)
	{
		this.cropSizeDirty = dirty;
	}

	/**
	 * Set crop size without triggering listener — used during batch updates in recomputeCrop.
	 */
	public void setCropSizeSilent(int width, int height)
	{
		this.cropW = width;
		this.cropH = height;
	}

	/**
	 * Switch between Move and Select-Feature mode. Fires the listener; does not mark crop size dirty (mode switches
	 * preserve the crop rectangle).
	 */
	public void setEditorMode(EditorMode mode)
	{
		this.editorMode = mode;
		// Don't set cropSizeDirty — mode changes don't affect crop size
		notifyChanged();
	}

	/**
	 * Store the raw Ultra HDR gain-map bytes for later export. No listener fire — gain map never drives UI changes
	 * directly.
	 */
	public void setGainMap(byte[] gm)
	{
		this.gainMap = gm;
	}

	/**
	 * Replace the JPEG segment list en-bloc. No listener fire — the segment list is consulted by the exporter, not
	 * rendered.
	 */
	public void setJpegMeta(List<JpegSegment> meta)
	{
		this.jpegMeta = meta;
	}

	/**
	 * Register (or clear via null) the single state-change listener. MainActivity wires itself as the listener in
	 * onCreate and unwires in onDestroy.
	 */
	public void setListener(OnStateChangedListener listener)
	{
		bus.setListener(listener);
	}

	/**
	 * Record the original file bytes for in-memory backup use. No listener fire.
	 */
	public void setOriginalFileBytes(byte[] bytes)
	{
		this.originalFileBytes = bytes;
	}

	/**
	 * Record the original file's display name (used in the Save-As default filename).
	 */
	public void setOriginalFilename(String name)
	{
		this.originalFilename = name;
	}

	/**
	 * Replace the rotation angle. Handles NaN / infinite inputs by treating them as 0, clamps to [−180, 180], snaps
	 * sub-epsilon magnitudes to exactly 0, marks the crop size dirty (recompute needed to shrink the crop for the
	 * new rotation), and fires the listener.
	 *
	 * The sub-epsilon snap is the single chokepoint that keeps every rotation entry point — ruler, precise-rotation
	 * dialog, horizon detector, programmatic — aligned with what UiSync, CropEngine, ViewportMath,
	 * BitmapUtils.drawCropped, and ExportPipeline actually render. Without it, those callers can store 0.01°-0.04°
	 * values that the renderer collapses to zero, producing a hidden readout, no visible rotation, and (until
	 * ExportPipeline's epsilon check was added) a needless re-encode of an unchanged image.
	 */
	public void setRotationDegrees(float deg)
	{
		if (Float.isNaN(deg) || Float.isInfinite(deg))
		{
			deg = 0f;
		}
		deg = Math.clamp(deg, -180f, 180f);
		if (Math.abs(deg) < BitmapUtils.ROTATION_EPSILON)
		{
			deg = 0f;
		}
		this.rotationDegrees = deg;
		this.cropSizeDirty = true;
		notifyChanged();
	}

	/**
	 * Store the Samsung Extended Format Trailer for later re-appending to the export. No listener fire — trailer
	 * data doesn't drive UI.
	 */
	public void setSeftTrailer(byte[] seft)
	{
		this.seftTrailer = seft;
	}

	/**
	 * Record the source format (JPEG or PNG) and seed exportConfig to match — load a PNG and the SaveDialog's
	 * format toggle and default filename should arrive on PNG, not silently default back to JPEG and drop alpha on
	 * the next save. Callers can still override the format afterwards via updateExportConfig (e.g., the user
	 * explicitly picks JPEG in the SaveDialog). No listener fire — listeners observe the resulting setSourceImage
	 * call that always follows in the load path.
	 *
	 * @param fmt detected source format, or null when the loaded bytes don't match either supported format
	 */
	public void setSourceFormat(Format fmt)
	{
		this.sourceFormat = fmt;
		if (fmt != null)
		{
			this.exportConfig = exportConfig.withFormat(fmt);
		}
	}

	/**
	 * Set the source bitmap and fire the listener — triggers applyStateToUi which computes the initial crop center
	 * and fits the view.
	 */
	public void setSourceImage(Bitmap bmp)
	{
		this.sourceImage = bmp;
		notifyChanged();
	}

	/**
	 * Replace the export config with the result of the given transformer and fire notifyChanged exactly once.
	 * Callers supply a withXxx chain on the current value.
	 */
	public void updateExportConfig(UnaryOperator<ExportConfig> transformer)
	{
		this.exportConfig = transformer.apply(exportConfig);
		notifyChanged();
	}

	/**
	 * Replace the grid config with the result of the given transformer and fire notifyChanged exactly once. Callers
	 * supply a withXxx chain on the current value — multi-field updates fold into one transformer so the listener
	 * fires once.
	 */
	public void updateGridConfig(UnaryOperator<GridConfig> transformer)
	{
		this.gridConfig = transformer.apply(gridConfig);
		notifyChanged();
	}

	/**
	 * Forward to the bus's batch-aware dispatcher. Setters call this so a single user action that touches multiple
	 * fields produces one listener call instead of N when the caller has an open batch.
	 */
	private void notifyChanged()
	{
		bus.notifyChanged();
	}

	/**
	 * Stash the AI-region mask produced by the graft pipeline. Private because the only legitimate call path is
	 * through installGraft, which atomically pairs this with the graftApplied flag write — calling setAiMask on its
	 * own would leave the export bypass still enabled and the inpaint silently inactive.
	 */
	private void setAiMask(AiMask mask)
	{
		this.aiMask = mask;
	}

	/**
	 * Mark the in-memory image as a graft result so ExportPipeline.canBypassEncode forces
	 * a full canvas re-encode on the next save. Private for the same reason setAiMask is:
	 * installGraft is the single chokepoint that pairs this with the AI mask write so the
	 * gain-map regeneration and inpaint stay in lockstep.
	 */
	private void setGraftApplied(boolean grafted)
	{
		this.graftApplied = grafted;
	}

}
