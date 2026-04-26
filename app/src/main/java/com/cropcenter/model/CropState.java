package com.cropcenter.model;

import android.graphics.Bitmap;

import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.util.BitmapUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Central state object for the crop editor. Holds all parameters, source image, and extracted
 * metadata.
 */
public class CropState
{
	public interface OnStateChangedListener
	{
		void onStateChanged();
	}

	// Mutated only via updateExportConfig / updateGridConfig — the record types themselves are
	// immutable, so state observers see a consistent snapshot and notifyChanged fires exactly
	// once per logical transition.
	private ExportConfig exportConfig = ExportConfig.defaults();
	private GridConfig gridConfig = GridConfig.defaults();
	// Mutated only via addSelectionPoint / removeSelectionPoint* / replaceSelectionPoints /
	// clearSelectionPoints. Callers never mutate directly — getSelectionPoints() returns an
	// unmodifiable view. Volatile + replace-instead-of-clear in reset() so the background-
	// thread loadImage path can safely null-swap the list while the UI thread iterates it —
	// an in-place ArrayList.clear() from bg would CME the UI iterator. Non-volatile mutation
	// via addSelectionPoint / etc. runs only on the UI thread, so those don't need synchronisation.
	private volatile List<SelectionPoint> selectionPoints = new ArrayList<>();

	private AspectRatio aspectRatio = AspectRatio.R4_5;
	// Batch mechanism used by the UI listener: notifyChanged during an open batch sets the
	// dirty flag instead of firing. endBatch then fires the listener at most once per batch,
	// and only if at least one setter actually called notifyChanged. This lets the listener
	// body freely call CropEngine.recomputeCrop (whose setter calls would otherwise recurse)
	// without needing a reentrancy guard.
	private int batchDepth;
	private boolean batchDirty;
	private Bitmap sourceImage;
	private CenterMode centerMode = CenterMode.BOTH;
	private EditorMode editorMode = EditorMode.SELECT_FEATURE;
	// Volatile for the same reason as selectionPoints — reset() may run on the bg executor
	// while UI readers iterate getJpegMeta().
	private volatile List<JpegSegment> jpegMeta = new ArrayList<>();
	private OnStateChangedListener listener;
	private String originalFilePath; // absolute path for Samsung Revert
	private String originalFilename;
	private String sourceFormat; // "jpeg" or "png"
	private boolean centerLocked = false; // when true, auto-recompute from points is suppressed
	private boolean cropSizeDirty = true;
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
	private long mediaStoreId = -1; // MediaStore _ID for Samsung Revert

	/**
	 * Append a selection point. Fires the state listener once.
	 */
	public void addSelectionPoint(SelectionPoint point)
	{
		selectionPoints.add(point);
		notifyChanged();
	}

	/**
	 * Start a batch: any notifyChanged calls until the matching endBatch record a dirty flag
	 * instead of firing the listener. Nested batches are supported — only the outermost
	 * endBatch fires. Used by the Activity's state listener to wrap recomputeCrop + UI
	 * updates so the recompute's inner setters don't re-enter the listener.
	 */
	public void beginBatch()
	{
		batchDepth++;
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
	 * End a batch started by beginBatch. Fires the listener once if any setter called
	 * notifyChanged during the batch; otherwise silent.
	 */
	public void endBatch()
	{
		if (batchDepth <= 0)
		{
			return;
		}
		if (--batchDepth == 0 && batchDirty)
		{
			batchDirty = false;
			fireListener();
		}
	}

	/**
	 * Stable rotation / drag anchor X — the user's intended (unclamped) crop center.
	 * See setAnchor for why this is distinct from centerX.
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
	 * Continuous-float crop left for the renderer: centerX − cropW / 2f. Sub-pixel precision
	 * so a smoothly rotating selection midpoint produces smooth crop motion on screen.
	 * Returns 0 when no crop is placed. Callers that need an integer pixel origin cast via
	 * Math.floor at the call site — the exporter absorbs the sub-pixel bias.
	 */
	public float getCropImgXFloat()
	{
		if (!hasCenter)
		{
			return 0f;
		}
		return centerX - cropW / 2f;
	}

	/**
	 * Continuous-float crop top for the renderer — see getCropImgXFloat.
	 */
	public float getCropImgYFloat()
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
	 * Current ExportConfig snapshot. Immutable — writes go through updateExportConfig, which
	 * replaces this field with a new instance and fires notifyChanged.
	 */
	public ExportConfig getExportConfig()
	{
		return exportConfig;
	}

	/**
	 * Raw Ultra HDR gain-map bytes extracted at load time, or null for non-HDR sources.
	 * The exporter re-composes this onto the cropped primary for HDR-preserving saves.
	 */
	public byte[] getGainMap()
	{
		return gainMap;
	}

	/**
	 * Current GridConfig snapshot. Immutable — writes go through updateGridConfig, which
	 * replaces this field with a new instance and fires notifyChanged.
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
	 * Read-only view of the loaded JPEG's segment list. Returns an empty list before any
	 * image is loaded. Populated en-bloc by setJpegMeta during loadImage; the list is
	 * never mutated in place.
	 */
	public List<JpegSegment> getJpegMeta()
	{
		return Collections.unmodifiableList(jpegMeta);
	}

	/**
	 * MediaStore _ID of the loaded image, or −1 when the source isn't a MediaStore file.
	 * Used by ReplaceStrategy to locate the original for Samsung Gallery Revert backup.
	 */
	public long getMediaStoreId()
	{
		return mediaStoreId;
	}

	/**
	 * Original file bytes captured at load. Used by saveOriginalBackup (which reads from
	 * memory, not disk, so backup is safe to call even after an overwrite has started).
	 * Null when the source was loaded via SAF stream that wasn't buffered.
	 */
	public byte[] getOriginalFileBytes()
	{
		return originalFileBytes;
	}

	/**
	 * Absolute on-disk path of the loaded image, or null when unknown (SAF-only source).
	 * Used for Samsung Revert backup path derivation.
	 */
	public String getOriginalFilePath()
	{
		return originalFilePath;
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
	 * Samsung Extended Format Trailer captured at load, or null for non-Samsung sources.
	 * Appended to the exported JPEG so Samsung Gallery's Revert feature can find and use
	 * the backup written by saveOriginalBackup.
	 */
	public byte[] getSeftTrailer()
	{
		return seftTrailer;
	}

	/**
	 * Unmodifiable view of the selection points. Callers that need to mutate must go through
	 * addSelectionPoint / removeSelectionPoint / clearSelectionPoints / replaceSelectionPoints so
	 * each change fires notifyChanged exactly once — previously callers mutated the backing list
	 * directly and had to remember to trigger recomputes themselves.
	 */
	public List<SelectionPoint> getSelectionPoints()
	{
		return Collections.unmodifiableList(selectionPoints);
	}

	/**
	 * "jpeg" or "png" — the format of the loaded source. Independent of export format.
	 */
	public String getSourceFormat()
	{
		return sourceFormat;
	}

	/**
	 * The loaded bitmap in display orientation (EXIF orientation already applied), or
	 * null before any image loads. Callers must null-check.
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
	 * True while Pan mode is active — suppresses auto-recompute so the crop stays put
	 * while the user drags the viewport.
	 */
	public boolean isCenterLocked()
	{
		return centerLocked;
	}

	/**
	 * True when cropW / cropH need a fresh recompute. Set by setAspectRatio,
	 * setRotationDegrees, markCropSizeDirty, and CropEngine.autoComputeFromPoints;
	 * cleared by CropEngine.recomputeCrop on completion.
	 */
	public boolean isCropSizeDirty()
	{
		return cropSizeDirty;
	}

	/**
	 * Flag cropW / cropH for recompute on the next listener cycle. Does not fire the
	 * listener itself — callers that also want immediate recompute call notifyChanged
	 * via a setter or invoke recomputeCrop directly.
	 */
	public void markCropSizeDirty()
	{
		this.cropSizeDirty = true;
	}

	/**
	 * Remove a selection point by equality. Returns true if anything was removed. Fires
	 * the state listener only when something was actually removed.
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
	 * Remove the selection point at the given index. Throws IndexOutOfBoundsException
	 * on an invalid index; always fires the listener when it does return.
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
		// Restore the documented defaults (Select mode, Both lock-axis). Without this,
		// a new image inherits the previous session's editor/lock state — e.g. loading
		// a photo into a still-active Move + Pan combo jumps straight to viewport-pan
		// gestures when the spec says new loads start in Select mode centered on the
		// image. MainActivity's loadImage UI runnable resyncs the toolbar widgets to
		// match these reset values.
		editorMode = EditorMode.SELECT_FEATURE;
		centerMode = CenterMode.BOTH;
		// aspectRatio preserved — it's a user preference, not image data.
		// exportConfig reset to defaults (JPEG) — the prior image's save-time format
		// choice was transient. The load-time extractMetadata path overrides this to
		// match the source's actual format; starting from JPEG matches the common case.
		exportConfig = ExportConfig.defaults();
		// gridConfig: preserve user preferences (colors, cols/rows, line width, pixel
		// grid) but clear includeInExport. Baking the grid into output is a per-save
		// choice; having it bleed into the next image is a footgun (user saved image A
		// with grid baked in, loads image B, saves B — and B silently gets a grid baked
		// in too unless they remembered to untick). Prefer the safe default.
		if (gridConfig.includeInExport())
		{
			gridConfig = gridConfig.withIncludeInExport(false);
		}
		originalFilename = null;
		originalFilePath = null;
		mediaStoreId = -1;
		// Replace rather than clear-in-place. reset() runs on the background loadImage
		// executor, and an in-place ArrayList.clear() would CME a UI-thread iterator
		// (onTap / draw / auto-rotate metadata read). Volatile reference swap publishes
		// the fresh empty list; any iterator already mid-walk keeps working on the old
		// list (now orphaned, GC'd once the iteration completes).
		selectionPoints = new ArrayList<>();
		jpegMeta = new ArrayList<>();
		gainMap = null;
		seftTrailer = null;
	}

	/**
	 * Stable rotation anchor for the no-selection case — setCenter's rotation clamp can
	 * pull centerX inward to keep the crop inside the rotated image, so reading centerX back
	 * on the next recompute would accumulate inward drift. The anchor holds the user's
	 * intended (unclamped) position. Callers that move the crop (user drag, image load)
	 * also call setAnchor so the next recompute uses a fresh starting position; rotation
	 * and AR changes leave the anchor alone.
	 */
	public void setAnchor(float x, float y)
	{
		this.anchorX = x;
		this.anchorY = y;
	}

	/**
	 * Replace the aspect-ratio constraint. Marks the crop size dirty so the next
	 * recompute resizes to fit, then fires the listener.
	 */
	public void setAspectRatio(AspectRatio ar)
	{
		this.aspectRatio = ar;
		cropSizeDirty = true;
		notifyChanged();
	}

	/**
	 * Set the crop center, clamping to keep the crop rectangle fully inside the
	 * (possibly rotated) image. Under rotation the clamp does an independent per-axis
	 * binary search to avoid one axis's clamp influencing the other. Fires the listener
	 * on every call, even if the clamp moves the target.
	 */
	public void setCenter(float x, float y)
	{
		// Clamp so crop rect stays fully inside the (possibly rotated) image.
		if (sourceImage != null && cropW > 0 && cropH > 0)
		{
			int imgW = sourceImage.getWidth();
			int imgH = sourceImage.getHeight();
			if (Math.abs(rotationDegrees) < BitmapUtils.ROTATION_EPSILON)
			{
				float[] clamped = clampCenterAxisAligned(x, y, imgW, imgH);
				x = clamped[0];
				y = clamped[1];
			}
			else
			{
				float[] clamped = clampCenterRotated(x, y, imgW, imgH);
				x = clamped[0];
				y = clamped[1];
			}
		}
		this.centerX = x;
		this.centerY = y;
		this.hasCenter = true;
		notifyChanged();
	}

	/**
	 * Axis-aligned clamp — sub-epsilon rotation is rendered as unrotated by the rest
	 * of the stack, so the cheap axis clamp suffices. Each axis is clamped
	 * independently; when cropW ≥ imgW (crop exceeds image) snap to the image mid
	 * rather than letting Math.clamp throw on inverted bounds.
	 */
	private float[] clampCenterAxisAligned(float x, float y, int imgW, int imgH)
	{
		if (cropW < imgW)
		{
			x = Math.clamp(x, cropW / 2f, imgW - cropW / 2f);
		}
		else
		{
			x = imgW / 2f;
		}
		if (cropH < imgH)
		{
			y = Math.clamp(y, cropH / 2f, imgH - cropH / 2f);
		}
		else
		{
			y = imgH / 2f;
		}
		return new float[] { x, y };
	}

	/**
	 * Rotated-image clamp — binary search along each axis independently so clamping X
	 * doesn't affect Y and vice versa. Each pass walks from the requested position
	 * toward image center, picking the farthest-out fraction whose four crop corners
	 * all land inside the source image bounds when un-rotated. When the crop is
	 * larger than the image under the current rotation (no valid position exists),
	 * falls back to image-center so the caller gets a stable, finite result.
	 */
	private float[] clampCenterRotated(float x, float y, int imgW, int imgH)
	{
		float imageMidX = imgW / 2f;
		float imageMidY = imgH / 2f;
		double rad = Math.toRadians(-rotationDegrees);
		double cosR = Math.cos(rad);
		double sinR = Math.sin(rad);
		float halfWidth = cropW / 2f;
		float halfHeight = cropH / 2f;

		// Fallback: when even image-center can't hold the crop (cropW / cropH exceed the
		// rotated image's inscribed axis-aligned rectangle), the binary searches below
		// would converge to image-center with corners still outside bounds. Snap to
		// image-center directly — the rotation-fit shrink in CropEngine.recomputeCrop's
		// recheck pass will catch up and size the crop down to fit on the next recompute.
		if (!cornersInside(imageMidX, imageMidY, halfWidth, halfHeight,
			imageMidX, imageMidY, cosR, sinR, imgW, imgH))
		{
			return new float[] { imageMidX, imageMidY };
		}

		if (!cornersInside(x, y, halfWidth, halfHeight,
			imageMidX, imageMidY, cosR, sinR, imgW, imgH))
		{
			x = binarySearchAxis(x, y, halfWidth, halfHeight,
				imageMidX, imageMidY, cosR, sinR, imgW, imgH, true);
		}
		if (!cornersInside(x, y, halfWidth, halfHeight,
			imageMidX, imageMidY, cosR, sinR, imgW, imgH))
		{
			y = binarySearchAxis(x, y, halfWidth, halfHeight,
				imageMidX, imageMidY, cosR, sinR, imgW, imgH, false);
		}
		return new float[] { x, y };
	}

	/**
	 * 25-iteration binary search for the farthest valid center position along one axis.
	 * The search range is fixed at both ends — `fraction = 0` gives the image center
	 * (always valid), `fraction = 1` gives the requested position. Each iteration
	 * tests the midpoint fraction; valid positions advance `loFraction` and update the
	 * best-so-far, invalid positions pull `hiFraction` in. Result: the largest valid
	 * fraction's candidate position. `clampX` true varies X while holding Y fixed;
	 * false varies Y.
	 */
	private static float binarySearchAxis(float x, float y, float halfWidth, float halfHeight,
		float imageMidX, float imageMidY, double cosR, double sinR, int imgW, int imgH,
		boolean clampX)
	{
		float loFraction = 0f;
		float hiFraction = 1f;
		float safeEndpoint = clampX ? imageMidX : imageMidY;
		float requestedEndpoint = clampX ? x : y;
		float validPosition = safeEndpoint;
		for (int i = 0; i < 25; i++)
		{
			float midFraction = (loFraction + hiFraction) / 2f;
			float candidate = safeEndpoint + (requestedEndpoint - safeEndpoint) * midFraction;
			float testX = clampX ? candidate : x;
			float testY = clampX ? y : candidate;
			if (cornersInside(testX, testY, halfWidth, halfHeight,
				imageMidX, imageMidY, cosR, sinR, imgW, imgH))
			{
				validPosition = candidate;
				loFraction = midFraction;
			}
			else
			{
				hiFraction = midFraction;
			}
		}
		return validPosition;
	}

	/**
	 * Lock / unlock auto-recompute. Used by Pan mode to freeze the crop while the user
	 * drags the viewport. Does not fire the listener — caller controls redraw cadence.
	 */
	public void setCenterLocked(boolean locked)
	{
		this.centerLocked = locked;
	}

	/**
	 * Replace the lock mode. Does NOT mark the crop size dirty (see comment below) —
	 * the button handler explicitly calls recomputeForLockChange with the correct
	 * selection midpoint, avoiding a race between the listener-driven recompute and
	 * the handler-driven one.
	 */
	public void setCenterMode(CenterMode mode)
	{
		this.centerMode = mode;
		// Don't set cropSizeDirty here — the button handler calls recomputeForLockChange()
		// explicitly. Setting dirty here causes the listener to recompute IMMEDIATELY
		// (runOnUiThread runs inline on UI thread) with the wrong center, racing with
		// the handler's recomputeForLockChange that uses the correct selection midpoint.
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
	 * Set the dirty flag explicitly. Primarily used to clear dirty AFTER recompute
	 * completes; setting dirty=true is usually expressed via markCropSizeDirty.
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
	 * Switch between Move and Select-Feature mode. Fires the listener; does not mark
	 * crop size dirty (mode switches preserve the crop rectangle).
	 */
	public void setEditorMode(EditorMode mode)
	{
		this.editorMode = mode;
		// Don't set cropSizeDirty — mode changes don't affect crop size
		notifyChanged();
	}

	/**
	 * Store the raw Ultra HDR gain-map bytes for later export. No listener fire —
	 * gain map never drives UI changes directly.
	 */
	public void setGainMap(byte[] gm)
	{
		this.gainMap = gm;
	}

	/**
	 * Replace the JPEG segment list en-bloc. No listener fire — the segment list is
	 * consulted by the exporter, not rendered.
	 */
	public void setJpegMeta(List<JpegSegment> meta)
	{
		this.jpegMeta = meta;
	}

	/**
	 * Register (or clear via null) the single state-change listener. MainActivity wires
	 * itself as the listener in onCreate and unwires in onDestroy.
	 */
	public void setListener(OnStateChangedListener listener)
	{
		this.listener = listener;
	}

	/**
	 * Record the MediaStore _ID of the loaded image (for Samsung Revert support). No
	 * listener fire — the ID is plumbing, not user-visible state.
	 */
	public void setMediaStoreId(long id)
	{
		this.mediaStoreId = id;
	}

	/**
	 * Record the original file bytes for in-memory backup use. No listener fire.
	 */
	public void setOriginalFileBytes(byte[] bytes)
	{
		this.originalFileBytes = bytes;
	}

	/**
	 * Record the original file's absolute path (may be null for SAF sources).
	 */
	public void setOriginalFilePath(String path)
	{
		this.originalFilePath = path;
	}

	/**
	 * Record the original file's display name (used in the Save-As default filename).
	 */
	public void setOriginalFilename(String name)
	{
		this.originalFilename = name;
	}

	/**
	 * Replace the rotation angle. Handles NaN / infinite inputs by treating them as 0,
	 * clamps to [−180, 180], marks the crop size dirty (recompute needed to shrink the
	 * crop for the new rotation), and fires the listener.
	 */
	public void setRotationDegrees(float deg)
	{
		if (Float.isNaN(deg) || Float.isInfinite(deg))
		{
			deg = 0f;
		}
		deg = Math.clamp(deg, -180f, 180f);
		this.rotationDegrees = deg;
		this.cropSizeDirty = true;
		notifyChanged();
	}

	/**
	 * Store the Samsung Extended Format Trailer for later re-appending to the export.
	 * No listener fire — trailer data doesn't drive UI.
	 */
	public void setSeftTrailer(byte[] seft)
	{
		this.seftTrailer = seft;
	}

	/**
	 * Record the source format ("jpeg" or "png"). No listener fire.
	 */
	public void setSourceFormat(String fmt)
	{
		this.sourceFormat = fmt;
	}

	/**
	 * Set the source bitmap and fire the listener — triggers applyStateToUi which
	 * computes the initial crop center and fits the view.
	 */
	public void setSourceImage(Bitmap bmp)
	{
		this.sourceImage = bmp;
		notifyChanged();
	}

	/**
	 * Replace the export config with the result of the given transformer and fire
	 * notifyChanged exactly once. Callers supply a withXxx chain on the current value.
	 */
	public void updateExportConfig(UnaryOperator<ExportConfig> transformer)
	{
		this.exportConfig = transformer.apply(exportConfig);
		notifyChanged();
	}

	/**
	 * Replace the grid config with the result of the given transformer and fire
	 * notifyChanged exactly once. Callers supply a withXxx chain on the current value —
	 * multi-field updates fold into one transformer so the listener fires once.
	 */
	public void updateGridConfig(UnaryOperator<GridConfig> transformer)
	{
		this.gridConfig = transformer.apply(gridConfig);
		notifyChanged();
	}

	private void fireListener()
	{
		if (listener != null)
		{
			listener.onStateChanged();
		}
	}

	private void notifyChanged()
	{
		if (batchDepth > 0)
		{
			batchDirty = true;
			return;
		}
		fireListener();
	}

	/**
	 * Check if all 4 corners of the crop rect, when un-rotated, are inside the image.
	 */
	private static boolean cornersInside(float centerX, float centerY, float halfWidth, float halfHeight,
		float imageMidX, float imageMidY, double cosR, double sinR,
		int imgW, int imgH)
	{
		float[] cornerDx = { -halfWidth, halfWidth, -halfWidth, halfWidth };
		float[] cornerDy = { -halfHeight, -halfHeight, halfHeight, halfHeight };
		for (int i = 0; i < 4; i++)
		{
			double deltaX = centerX + cornerDx[i] - imageMidX;
			double deltaY = centerY + cornerDy[i] - imageMidY;
			double unrotatedX = deltaX * cosR - deltaY * sinR + imageMidX;
			double unrotatedY = deltaX * sinR + deltaY * cosR + imageMidY;
			if (unrotatedX < -0.5 || unrotatedX > imgW + 0.5
				|| unrotatedY < -0.5 || unrotatedY > imgH + 0.5)
			{
				return false;
			}
		}
		return true;
	}
}
