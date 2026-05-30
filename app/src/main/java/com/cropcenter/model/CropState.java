package com.cropcenter.model;

import android.graphics.Bitmap;

import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.util.AiRegionDetector.AiMask;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.RotatedCropClamp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Central state object for the crop editor. Holds all parameters, source image, and extracted metadata.
 *
 * Threading: all cross-thread state lives in `volatile` fields written on the bg load thread and read on
 * the UI thread — see the volatile-field protocol block at the field declarations. Mutations dispatch
 * through StateBus; beginBatch / endBatch collapse setter cascades into one listener fire.
 */
public final class CropState
{
	/**
	 * Callback fired by StateBus when any state field changes (outside of a beginBatch / endBatch
	 * window). MainActivity installs a listener that posts applyStateToUi to the UI thread; the
	 * listener replays into recomputeCrop + UI sync. Implementations should be cheap and safe to
	 * call from any thread — StateBus dispatches synchronously from the setter that triggered the
	 * change, which can be either the UI thread (user gestures) or the bg thread (image load
	 * commit, horizon detection result). Marshal to the UI thread before touching Views.
	 */
	public interface OnStateChangedListener
	{
		/**
		 * Notification that state has changed. Called once per setter outside a batch; once at
		 * endBatch when one or more setters fired inside a beginBatch window.
		 */
		void onStateChanged();
	}

	// Listener dispatch + batch-suppression protocol. Setters call bus.notifyChanged() to fire the registered
	// listener; the Activity wraps recomputeCrop + UI updates in bus.beginBatch / bus.endBatch so inner setter
	// calls coalesce into one listener invocation.
	private final StateBus bus = new StateBus();

	// Volatile-field protocol: bg-thread writers (ImageLoadController.load, installGraft, extractMetadata) →
	// UI-thread readers (EditorRenderer, ViewportMath, MainActivity.applyStateToUi, gesture handlers, Save
	// pre-enqueue path). Without volatile, weak-memory devices can keep reading a stale reference across
	// multiple frames. List fields use replace-instead-of-clear in reset() so an in-place ArrayList.clear()
	// from bg doesn't CME the UI iterator.

	// Set by GraftController.onEditPicked from Apply External Edit; cleared by reset(). Read by UltraHdrCompat
	// at HDR re-encode time so the AI region's gain-map boost can be inpainted from surrounding values (avoids
	// the stale "boost the features that used to be there" artifact). Volatile is cheap insurance — both sides
	// are bg-serialised today, but a future UI-thread read (graft preview badge) would need the happens-before.
	private volatile AiMask aiMask;
	// Volatile because `reset()` and `setAspectRatio` run on the bg load thread while EditorRenderer +
	// ToolbarBinder read on the UI thread. Without volatile, a mid-reset UI frame could see a torn /
	// stale aspectRatio reference and render with the wrong crop dimensions.
	private volatile AspectRatio aspectRatio = AspectRatio.R4_5;
	// volatile + identical-publishing reasoning as sourceImage below — the proxy bitmap derived from the
	// source for editor-render and HorizonDetector consumption. May be the same reference as sourceImage
	// when the source fits within MAX_DISPLAY_PIXELS (BitmapUtils.createDisplayProxy aliases). Always
	// paired with sourceImage via setSourceImage(Bitmap, Bitmap) or reset() — never written by a
	// different writer. NOT a truly atomic pair: setSourceImage and reset() each write the two volatile
	// fields as two separate stores, so a UI-thread reader running between them can observe the
	// intermediate (sourceImage=old, displayImage=new) or (sourceImage=null, displayImage=old) state.
	// EditorRenderer.draw and gesture handlers all null-check via `state.getSourceImage() == null ||
	// state.getDisplayImage() == null` (logical OR) which tolerates either half-null state cleanly —
	// future readers that only check one half MUST add the second check to preserve this contract, or
	// the two writes need to move into a synchronized block / single Bitmaps record swap.
	private volatile Bitmap displayImage;
	private volatile Bitmap sourceImage;
	// Volatile because `reset()` writes both on bg while EditorRenderer / gesture handlers read on UI.
	private volatile CenterMode centerMode = CenterMode.BOTH;
	private volatile EditorMode editorMode = EditorMode.SELECT_FEATURE;
	// Mutated only via updateExportConfig / updateGridConfig — the record types are immutable, so observers see
	// a consistent snapshot and notifyChanged fires exactly once per logical transition. Volatile because
	// ImageLoadController.applyBytes (bg thread) calls installLoadedImage (which mutates exportConfig via the
	// inlined sourceFormat seed) and updateGridConfig is reachable from settings paths that may run before the
	// UI re-reads; UI-thread readers (ExportPipeline.canBypassEncode, SaveController.openSaveOptionsDialog)
	// need a happens-before edge to the bg-side write so they don't observe a stale record reference on
	// weak-memory ARM.
	private volatile ExportConfig exportConfig = ExportConfig.defaults();
	// Volatile because `reset()` and `installLoadedImage` run on the bg load thread while the UI-thread
	// save-flow paths (SaveController + SaveDialog / FolderPickerDialog) read `getSourceFormat()` to
	// drive format-picker defaults and extension validation. Without volatile, a UI tap immediately
	// after load could see a stale sourceFormat from the previous image — causing the format picker to
	// default to the wrong format.
	private volatile Format sourceFormat;
	private volatile GridConfig gridConfig = GridConfig.defaults();
	private volatile List<JpegSegment> jpegMeta = new ArrayList<>();
	// Mutated only via addSelectionPoint / removeSelectionPoint* / replaceSelectionPoints / clearSelectionPoints
	// (UI thread); getSelectionPoints returns an unmodifiable view.
	private volatile List<SelectionPoint> selectionPoints = new ArrayList<>();
	// originalFilename / cropSizeDirty / hasCenter / anchorX-Y / centerX-Y / rotationDegrees / cropH /
	// cropW are all written by `reset()` on the bg load thread (and by installLoadedImage for
	// originalFilename). UI-thread reads on every editor render frame, every tap / drag / pinch
	// handler, and every save-flow dispatch (canBypassEncode reads hasCenter, cropW/H). Without
	// volatile, a mid-load UI frame can see torn or stale primitive values — most visibly a
	// `hasCenter = true` paired with `cropW = 0` produces a degenerate crop overlay; less visibly a
	// stale `rotationDegrees` rotates the new image by the old image's angle for a frame or two.
	private volatile String originalFilename;
	private volatile boolean cropSizeDirty = true;
	// True when the in-memory image came from the Apply External Edit graft flow. Read by
	// ExportPipeline.canBypassEncode to refuse the verbatim-write bypass for graft saves — bypassing would
	// ship source's gain map verbatim over the spliced primary, so any user crop afterwards shifts the
	// gain-map HDR boost off the features it's meant for. The full encode regenerates the gain map from the
	// spliced primary.
	private volatile boolean graftApplied;
	private volatile boolean hasCenter;
	private volatile byte[] gainMap;
	// Volatile because GraftController.start (UI thread) reads `getOriginalFileBytes()` and
	// ExportPipeline.canBypassEncode reads it from both UI and bg dispatch paths, while `reset()` and
	// `installLoadedImage` run on the bg load thread. Without volatile, the UI can observe stale
	// null after a successful load — breaking graft-start and forcing canBypassEncode to false.
	private volatile byte[] originalFileBytes;
	// Raw TIFF bytes from the source PNG's eXIf chunk (PNG 1.6 spec). Set by extractMetadata on PNG sources;
	// CropExporter.exportPng prefers this over jpegMeta for PNG → PNG round-trips because the PNG eXIf chunk
	// has a u31 length field (JPEG APP1's u16 cap doesn't apply), so a PNG with > 64KB of EXIF keeps its full
	// metadata when re-saved as PNG. Null for JPEG sources or PNGs without eXIf.
	private volatile byte[] pngExifTiff;
	private volatile byte[] seftTrailer; // Samsung SEFT trailer (appended after gain map)
	// Cross-thread primitives — see the originalFilename block comment above for the rationale.
	private volatile float anchorX; // stable "intent" center for no-selection rotations
	private volatile float anchorY;
	private volatile float centerX;
	private volatile float centerY;
	private volatile float rotationDegrees = 0f; // precise rotation applied to source image
	private volatile int cropH;
	private volatile int cropW;

	/**
	 * Append a selection point. Fires the state listener once.
	 *
	 * Builds a fresh list and volatile-swaps the field rather than mutating the live list in
	 * place. The field's contract (see selectionPoints declaration) requires every writer to use
	 * volatile-swap so any concurrent reader's iterator — e.g. HorizonDetector running on the bg
	 * thread while the UI thread adds a point — keeps walking the old list unaffected rather than
	 * tripping ConcurrentModificationException. The previous in-place .add was a contract
	 * violation that replaceSelectionPoints / reset had already documented around.
	 *
	 * @param point selection point to append (consumed by reference; SelectionPoint is immutable)
	 */
	public void addSelectionPoint(SelectionPoint point)
	{
		List<SelectionPoint> updated = new ArrayList<>(selectionPoints.size() + 1);
		updated.addAll(selectionPoints);
		updated.add(point);
		selectionPoints = updated;
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
	 * Remove every selection point. No-op + no listener fire when already empty. Volatile-swaps a
	 * fresh empty list rather than .clear()-ing in place — same contract as addSelectionPoint /
	 * replaceSelectionPoints / reset (see selectionPoints field comment).
	 */
	public void clearSelectionPoints()
	{
		if (selectionPoints.isEmpty())
		{
			return;
		}
		selectionPoints = new ArrayList<>();
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

	public float getAnchorX()
	{
		return anchorX;
	}

	public float getAnchorY()
	{
		return anchorY;
	}

	public AspectRatio getAspectRatio()
	{
		return aspectRatio;
	}

	public CenterMode getCenterMode()
	{
		return centerMode;
	}

	/**
	 * Crop center X in screen-aligned image coords — the same coord system the editor's crop overlay draws
	 * into via `ViewportMath.imageToScreenX` (a linear, un-rotated map). At zero rotation this equals
	 * un-rotated bitmap-pixel X. At non-zero rotation, the value can lie outside [0, imgW] because the
	 * rotated bitmap's bounding box extends beyond the un-rotated bitmap rectangle. `CropEngine.recomputeCrop`
	 * uses `RotationMath.rotatedAabbDimensions` to clamp this to the rotated AABB. Continuous float.
	 *
	 * @return crop center X as a continuous float in screen-aligned image coords (may lie outside
	 *         [0, imgW] under non-zero rotation, clamped by CropEngine.recomputeCrop to the rotated AABB)
	 */
	public float getCenterX()
	{
		return centerX;
	}

	/**
	 * Crop center Y in screen-aligned image coords — see getCenterX. Continuous float.
	 *
	 * @return crop center Y as a continuous float in screen-aligned image coords (same rotated-AABB
	 *         contract as getCenterX)
	 */
	public float getCenterY()
	{
		return centerY;
	}

	public int getCropH()
	{
		return cropH;
	}

	/**
	 * Continuous-float crop left for the renderer: centerX − cropW / 2f. Sub-pixel precision so a smoothly rotating
	 * selection midpoint produces smooth crop motion on screen. Returns 0 when no crop is placed. Callers that need
	 * an integer pixel origin cast via Math.floor at the call site — the exporter absorbs the sub-pixel bias.
	 *
	 * @return continuous-float crop left (centerX − cropW / 2f); 0 when no crop is placed
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
	 *
	 * @return continuous-float crop top (centerY − cropH / 2f); 0 when no crop is placed
	 */
	public float getCropImageYFloat()
	{
		if (!hasCenter)
		{
			return 0f;
		}
		return centerY - cropH / 2f;
	}

	public int getCropW()
	{
		return cropW;
	}

	/**
	 * Display-proxy bitmap downsampled from the source to fit within BitmapUtils.MAX_DISPLAY_PIXELS. Used by
	 * EditorRenderer for per-frame rendering at zoom < 4 (above that, the renderer switches to
	 * getSourceImage() for pixel-grid accuracy) and by AutoRotateBinder for painted-region horizon detection.
	 * Returns null when no image is loaded; returns the same reference as getSourceImage() when the source
	 * was small enough that BitmapUtils.createDisplayProxy aliased rather than allocating a copy. Save
	 * paths must NOT route through this — they read getSourceImage() so output resolution matches source.
	 *
	 * @return display proxy (≤ MAX_DISPLAY_PIXELS), or null when no image is loaded
	 */
	public Bitmap getDisplayImage()
	{
		return displayImage;
	}

	public EditorMode getEditorMode()
	{
		return editorMode;
	}

	public ExportConfig getExportConfig()
	{
		return exportConfig;
	}

	/**
	 * Raw Ultra HDR gain-map bytes extracted at load time, or null for non-HDR sources. The exporter re-composes
	 * this onto the cropped primary for HDR-preserving saves.
	 *
	 * Caller must not mutate the returned array. The reference is shared with all consumers and with internal
	 * state — appending to or rewriting elements would silently corrupt the next save. A defensive clone is not
	 * done because the array is potentially multi-MB and every consumer treats it as read-only.
	 *
	 * @return raw Ultra HDR gain-map bytes (read-only; do not mutate), or null for non-HDR sources
	 */
	public byte[] getGainMap()
	{
		return gainMap;
	}

	public GridConfig getGridConfig()
	{
		return gridConfig;
	}

	public int getImageHeight()
	{
		return sourceImage != null ? sourceImage.getHeight() : 0;
	}

	public int getImageWidth()
	{
		return sourceImage != null ? sourceImage.getWidth() : 0;
	}

	/**
	 * Read-only view of the loaded JPEG's segment list. Returns an empty list before any image is loaded. Populated
	 * en-bloc by installLoadedImage during loadImage; the list is never mutated in place.
	 *
	 * @return unmodifiable view of the loaded JPEG's segment list; empty before any image is loaded
	 */
	public List<JpegSegment> getJpegMeta()
	{
		return Collections.unmodifiableList(jpegMeta);
	}

	/**
	 * Original file bytes captured at load. Used by ExportPipeline.canBypassEncode for the verbatim-write path and
	 * by GraftController.onEditPicked as the splice base. Null when the source was loaded via a SAF stream that
	 * wasn't buffered.
	 *
	 * Caller must not mutate the returned array. The reference is shared across consumers and feeds the
	 * verbatim-write bypass; appending or rewriting elements would silently corrupt the next save.
	 */
	public byte[] getOriginalFileBytes()
	{
		return originalFileBytes;
	}

	public String getOriginalFilename()
	{
		return originalFilename;
	}

	/**
	 * Raw TIFF bytes from the source PNG's eXIf chunk, or null when the source was JPEG / had no eXIf chunk.
	 * Used by CropExporter.exportPng for PNG → PNG round-trip where the JPEG APP1 u16 cap doesn't apply.
	 *
	 * @return raw TIFF bytes from the source PNG's eXIf chunk, or null for JPEG sources / PNGs without eXIf
	 */
	public byte[] getPngExifTiff()
	{
		return pngExifTiff;
	}

	public float getRotationDegrees()
	{
		return rotationDegrees;
	}

	/**
	 * Samsung Extended Format Trailer captured at load, or null for non-Samsung sources. Re-appended verbatim by
	 * CropExporter.appendSeftFileToFile on every save — see that method's Javadoc for the verbatim-preservation
	 * contract and why CropCenter cannot fabricate fresh trailers.
	 *
	 * Caller must not mutate the returned array. The reference is shared and gets re-appended verbatim by the
	 * exporter; mutation would silently corrupt the saved trailer.
	 *
	 * @return Samsung SEFT trailer bytes (read-only; do not mutate), or null for non-Samsung sources
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
	 *
	 * @return unmodifiable view of the selection points; mutation must go through the dedicated setters
	 */
	public List<SelectionPoint> getSelectionPoints()
	{
		return Collections.unmodifiableList(selectionPoints);
	}

	/**
	 * Format of the loaded source — JPEG or PNG. Independent of export format. Null when no image is loaded or when
	 * the source's signature doesn't match either supported format.
	 *
	 * @return detected source Format (JPEG or PNG), or null when no image is loaded or the signature is
	 *         unrecognized
	 */
	public Format getSourceFormat()
	{
		return sourceFormat;
	}

	/**
	 * The loaded bitmap in display orientation (EXIF orientation already applied), or null before any image loads.
	 * Callers must null-check.
	 *
	 * @return source bitmap with EXIF orientation already applied, or null before any image loads
	 */
	public Bitmap getSourceImage()
	{
		return sourceImage;
	}

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
	 *
	 * @param graft pre-baked splice from GraftController; aiMask is installed when present, graftApplied
	 *              is unconditionally set true
	 */
	public void installGraft(Graft graft)
	{
		// Order matters WHEN AN AI MASK IS PRESENT: setAiMask FIRST, then setGraftApplied. Mirrors
		// reset()'s clear order (aiMask=null first, graftApplied=false second) so the same "no
		// transient (graftApplied=true, aiMask=non-null-but-stale) window" invariant the reset comment
		// documents also holds on the install side. Without this order, a concurrent
		// UltraHdrCompat.compressWithGainmap (e.g. Save tapped immediately after Apply External Edit
		// completes) could catch graftApplied=true while aiMask is still the prior value, skip the
		// inpaint against the WRONG mask, and ship an HDR boost that still highlights the pre-graft
		// features — exactly the bug installGraft exists to prevent. Reference reads are atomic per
		// JLS §17.7 so aiMask is always either null or the new mask, never torn. The
		// (graftApplied=true, aiMask=null) pair IS the steady state for SDR grafts (hasAiMask()=false
		// path); UltraHdrCompat.inpaintGainmapIfMasked treats null aiMask as a no-op, so this is
		// behaviorally safe — the "concurrent stale-mask" concern only applies when a fresh mask is
		// being written.
		if (graft.hasAiMask())
		{
			setAiMask(graft.aiMask());
		}
		setGraftApplied(true);
	}

	/**
	 * Atomically install all load-time metadata from a successful image load. Single chokepoint:
	 * ImageLoadController.applyBytes builds the bundle (bytes / filename / format / metadata) on the bg
	 * thread, then calls this to publish them as one group — not a sequence of independent setters a caller
	 * could reorder or partially apply.
	 *
	 * Wrapped in beginBatch / endBatch even though no write below fires notifyChanged today — it makes the
	 * atomicity contract explicit for a future maintainer who adds one. The setSourceImage call that follows
	 * in the load path is the actual listener fire.
	 *
	 * sourceFormat also seeds exportConfig (so loading a PNG defaults the SaveDialog toggle to PNG), with a
	 * null guard so a format-detection miss doesn't clobber the prior session's exportConfig. A null jpegMeta
	 * is coerced to an empty list so the field's never-null contract (getJpegMeta's unmodifiableList) holds.
	 *
	 * @param fileBytes      raw image bytes — must be non-null on success path
	 * @param filename       display name from SAF; null tolerated (falls back at downstream readers)
	 * @param sourceFormat   detected Format (JPEG / PNG); null when bytes match neither — exportConfig.format
	 *                       is preserved in that case
	 * @param jpegMeta       parsed APP segments for a JPEG source; null tolerated (coerced to empty list)
	 * @param pngExifTiff    raw TIFF body from a PNG eXIf chunk, or null for JPEG / non-EXIF PNG
	 * @param gainMap        Ultra HDR gain-map bytes, or null for SDR sources
	 * @param seftTrailer    Samsung SEFT trailer bytes, or null for non-Samsung-edited sources
	 */
	public void installLoadedImage(byte[] fileBytes, String filename, Format sourceFormat,
		List<JpegSegment> jpegMeta, byte[] pngExifTiff, byte[] gainMap, byte[] seftTrailer)
	{
		beginBatch();
		try
		{
			this.originalFileBytes = fileBytes;
			this.originalFilename = filename;
			this.sourceFormat = sourceFormat;
			if (sourceFormat != null)
			{
				this.exportConfig = exportConfig.withFormat(sourceFormat);
			}
			// Null-coerce to match the field's "never null" contract — getJpegMeta wraps with
			// Collections.unmodifiableList which NPEs on a null delegate, so a null write here would
			// poison every downstream read.
			this.jpegMeta = jpegMeta != null ? jpegMeta : new ArrayList<>();
			this.pngExifTiff = pngExifTiff;
			this.gainMap = gainMap;
			this.seftTrailer = seftTrailer;
		}
		finally
		{
			endBatch();
		}
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
	 * Remove a selection point by equality. Fires the state listener only when something was actually
	 * removed. Volatile-swaps a copy with the point removed rather than mutating the live list —
	 * same contract as addSelectionPoint / clearSelectionPoints / replaceSelectionPoints / reset
	 * (see selectionPoints field comment for the bg-thread reader CME rationale).
	 *
	 * @param point selection point to remove (matched by equals)
	 * @return true when a matching point was found and removed; false when no equal point existed
	 */
	public boolean removeSelectionPoint(SelectionPoint point)
	{
		List<SelectionPoint> updated = new ArrayList<>(selectionPoints);
		if (!updated.remove(point))
		{
			return false;
		}
		selectionPoints = updated;
		notifyChanged();
		return true;
	}

	/**
	 * Replace all selection points atomically (used for undo/redo snapshot restores). Swaps the volatile
	 * field to a fresh list rather than mutating in place — a bg-thread caller (rare on this path, but
	 * the field's volatile-swap contract documents the guarantee for ALL writers) could otherwise CME a
	 * UI-thread iteration. Same pattern reset() uses on line below.
	 *
	 * @param newPoints replacement selection points (copied into a new backing list; caller retains
	 *                  ownership of the supplied collection)
	 */
	public void replaceSelectionPoints(Collection<SelectionPoint> newPoints)
	{
		selectionPoints = new ArrayList<>(newPoints);
		notifyChanged();
	}

	/**
	 * Reset everything for a new image.
	 */
	public void reset()
	{
		sourceImage = null;
		displayImage = null;
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
		// Restore the documented defaults (Select mode, Both lock-axis). Without this, a new image inherits the
		// previous session's editor/lock state — e.g. loading a photo into a still-active Move + Pin combo
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
		pngExifTiff = null;
		seftTrailer = null;
		// Order matters: clear aiMask BEFORE graftApplied so a concurrent UI read mid-reset never
		// observes the inconsistent (graftApplied=false, aiMask=stale-non-null) intermediate state.
		// The reverse intermediate (graftApplied=true, aiMask=null) IS briefly observable but is
		// benign — UltraHdrCompat.compressWithGainmap's inpaint gate handles null aiMask as a no-op,
		// so a reader that catches the transient pair just skips inpaint (which is also what an
		// already-reset state produces). Reference reads are atomic per JLS §17.7, so aiMask is
		// either the old reference or null — never torn — and the inpaint code never dereferences a
		// half-cleared object.
		aiMask = null;
		graftApplied = false;
	}

	public void setAnchor(float x, float y)
	{
		this.anchorX = x;
		this.anchorY = y;
	}

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
	 *
	 * @param x requested crop center X in screen-aligned image coords; clamped to keep the crop rect inside
	 *          the (possibly rotated) image
	 * @param y requested crop center Y; same clamp contract as x
	 */
	public void setCenter(float x, float y)
	{
		// Snapshot the volatile sourceImage reference once. A bg-thread reset() that writes
		// sourceImage = null between this read and the getWidth()/getHeight() reads below would NPE
		// the UI thread on setCenter calls reached from drag / clamp / programmatic recenter paths
		// (dragCropCenter, onPanRelease, recomputeCrop, etc.). The busy gate prevents most racing
		// loads, but Share/View intents can dismiss transient dialogs mid-drag and still race the
		// next pan callback. CropEditorView.onTap already snapshots for the same reason.
		Bitmap snapshot = sourceImage;
		// Clamp so crop rect stays fully inside the (possibly rotated) image.
		if (snapshot != null && cropW > 0 && cropH > 0)
		{
			int imgW = snapshot.getWidth();
			int imgH = snapshot.getHeight();
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
	 * Replace the lock mode. Does NOT mark the crop size dirty (see comment below) — the button handler explicitly
	 * calls recomputeForLockChange with the correct selection midpoint, avoiding a race between the listener-driven
	 * recompute and the handler-driven one.
	 *
	 * @param mode new lock mode (BOTH / HORIZONTAL / VERTICAL / LOCKED); fires the state listener but does not
	 *             mark the crop size dirty
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
	 *
	 * @param x crop center X in screen-aligned image coords; no clamp, no listener fire
	 * @param y crop center Y; same contract as x
	 */
	public void setCenterUnclamped(float x, float y)
	{
		this.centerX = x;
		this.centerY = y;
		this.hasCenter = true;
		// No notifyChanged — caller will trigger notify via recomputeCrop → setCenter
	}

	/**
	 * Set the cropSizeDirty flag directly. Public (rather than package-private) because
	 * MainActivity.applyRestoreBundle, CropEngine.recomputeCrop, and CropEditorView's measure-pass
	 * callback each clear it after applying a fresh size — the trio is across packages. Paired with
	 * markCropSizeDirty (no-arg true setter) for symmetry; the boolean variant exists for the
	 * explicit-false clear sites.
	 *
	 * @param dirty true to mark the cropW/cropH as needing recompute, false to clear after a
	 *              recompute has applied the new size
	 */
	public void setCropSizeDirty(boolean dirty)
	{
		this.cropSizeDirty = dirty;
	}

	/**
	 * Write cropW + cropH without firing the listener. Used by CropEngine's recompute pass (writes
	 * the snapped dims after computing them — the listener fire is deferred to the caller, which
	 * orchestrates the recompute as part of a larger state-mutation batch) AND by
	 * MainActivity.applyRestoreBundle (writes the restored dims inside a beginBatch wrapper, so
	 * the single endBatch notification covers everything). Always paired with a separate
	 * setCropSizeDirty / notifyChanged downstream so observers see the new size.
	 *
	 * @param width  new crop width in image pixels
	 * @param height new crop height in image pixels
	 */
	public void setCropSizeSilent(int width, int height)
	{
		this.cropW = width;
		this.cropH = height;
	}

	public void setEditorMode(EditorMode mode)
	{
		this.editorMode = mode;
		// Don't set cropSizeDirty — mode changes don't affect crop size
		notifyChanged();
	}

	/**
	 * Register (or clear via null) the single state-change listener. MainActivity wires itself as the listener in
	 * onCreate and unwires in onDestroy.
	 *
	 * @param listener state-change listener; null clears the registered listener
	 */
	public void setListener(OnStateChangedListener listener)
	{
		bus.setListener(listener);
	}

	/**
	 * Replace the rotation angle. Handles NaN / infinite inputs by treating them as 0, clamps to [−180, 180], snaps
	 * magnitudes below BitmapUtils.ROTATION_EPSILON (0.005°) to exactly 0, marks the crop size dirty (recompute
	 * needed to shrink the crop for the new rotation), and fires the listener.
	 *
	 * The sub-epsilon snap is the single chokepoint that keeps every rotation entry point — ruler, Reset chip,
	 * horizon detector, programmatic — aligned with what UiSync, CropEngine, ViewportMath,
	 * BitmapUtils.drawCropped, and ExportPipeline actually render. The 0.005° epsilon sits a half-step below the
	 * ruler's 0.01° finest tick (and the horizon detector's 0.01° rounding), so every value those entry points can
	 * produce is honored end-to-end. The snap exists for inputs strictly smaller than what the UI exposes — e.g.,
	 * float-precision residue near zero from RotationMath, or a programmatic caller passing 1e-6° — that would
	 * otherwise leave the model holding a non-zero value the renderer treats as zero (hidden readout + needless
	 * re-encode).
	 *
	 * @param deg requested rotation in degrees; NaN / infinite collapse to 0, magnitudes are clamped to
	 *            [−180, 180] and sub-ROTATION_EPSILON values snap to exactly 0. Marks the crop size dirty and
	 *            fires the listener on every call.
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
	 * Set the source bitmap and its derived display-proxy in lockstep, then fire the listener — triggers
	 * applyStateToUi which computes the initial crop center and fits the view. The proxy must be derived
	 * via BitmapUtils.createDisplayProxy(source) by the caller (typically ImageLoadController on the bg
	 * thread, so the proxy's bilinear-downscale pass doesn't block the UI thread). Passing display == source
	 * when the source already fits within MAX_DISPLAY_PIXELS is the documented and expected aliasing case.
	 *
	 * Both arguments are nullable — calling with (null, null) clears the loaded image. Calling with one
	 * null and one non-null is a contract violation (the EditorRenderer expects either both or neither to
	 * be live) and the implementation does not guard against it; the caller is responsible.
	 *
	 * @param source  full-resolution bitmap; pixel source for CropExporter / UltraHdrCompat save paths
	 * @param display display-proxy bitmap (≤ MAX_DISPLAY_PIXELS); EditorRenderer / HorizonDetector consumer
	 */
	public void setSourceImage(Bitmap source, Bitmap display)
	{
		this.sourceImage = source;
		this.displayImage = display;
		notifyChanged();
	}

	/**
	 * Replace the export config with the result of the given transformer and fire notifyChanged
	 * exactly once. Callers supply a withXxx chain on the current value.
	 *
	 * @param transformer applied to the current ExportConfig; the returned instance becomes the new
	 *                    value. Must not be null and must not return null
	 */
	public void updateExportConfig(UnaryOperator<ExportConfig> transformer)
	{
		this.exportConfig = transformer.apply(exportConfig);
		notifyChanged();
	}

	/**
	 * Replace the grid config with the result of the given transformer and fire notifyChanged exactly
	 * once. Callers supply a withXxx chain on the current value — multi-field updates fold into one
	 * transformer so the listener fires once.
	 *
	 * @param transformer applied to the current GridConfig; the returned instance becomes the new
	 *                    value. Must not be null and must not return null
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
