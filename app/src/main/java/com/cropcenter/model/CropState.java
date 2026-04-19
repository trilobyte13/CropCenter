package com.cropcenter.model;

import android.graphics.Bitmap;

import com.cropcenter.metadata.JpegSegment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

// Central state object for the crop editor. Holds all parameters, source image, and extracted
// metadata.
public class CropState
{
	public interface OnStateChangedListener
	{
		void onStateChanged();
	}

	private final ExportConfig exportConfig = new ExportConfig();
	private final GridConfig gridConfig = new GridConfig();
	// Mutated only via addSelectionPoint / removeSelectionPoint* / replaceSelectionPoints /
	// clearSelectionPoints. Callers never mutate directly — getSelectionPoints() returns an
	// unmodifiable view.
	private final List<SelectionPoint> selectionPoints = new ArrayList<>();

	private AspectRatio aspectRatio = AspectRatio.R4_5;
	private Bitmap sourceImage;
	private CenterMode centerMode = CenterMode.BOTH;
	private EditorMode editorMode = EditorMode.MOVE;
	private List<JpegSegment> jpegMeta = new ArrayList<>();
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
	private float centerX;
	private float centerY;
	private float rotationDegrees = 0f; // precise rotation applied to source image
	private int cropH;
	private int cropW;
	private long mediaStoreId = -1; // MediaStore _ID for Samsung Revert

	public void addSelectionPoint(SelectionPoint point)
	{
		selectionPoints.add(point);
		notifyChanged();
	}

	public void clearSelectionPoints()
	{
		if (selectionPoints.isEmpty())
		{
			return;
		}
		selectionPoints.clear();
		notifyChanged();
	}

	public AspectRatio getAspectRatio()
	{
		return aspectRatio;
	}

	public CenterMode getCenterMode()
	{
		return centerMode;
	}

	public float getCenterX()
	{
		return centerX;
	}

	public float getCenterY()
	{
		return centerY;
	}

	public int getCropH()
	{
		return cropH;
	}

	public int getCropW()
	{
		return cropW;
	}

	public EditorMode getEditorMode()
	{
		return editorMode;
	}

	public ExportConfig getExportConfig()
	{
		return exportConfig;
	}

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

	public List<JpegSegment> getJpegMeta()
	{
		return jpegMeta;
	}

	public long getMediaStoreId()
	{
		return mediaStoreId;
	}

	public byte[] getOriginalFileBytes()
	{
		return originalFileBytes;
	}

	public String getOriginalFilePath()
	{
		return originalFilePath;
	}

	public String getOriginalFilename()
	{
		return originalFilename;
	}

	public float getRotationDegrees()
	{
		return rotationDegrees;
	}

	public byte[] getSeftTrailer()
	{
		return seftTrailer;
	}

	// Unmodifiable view of the selection points. Callers that need to mutate must go through
	// addSelectionPoint / removeSelectionPoint / clearSelectionPoints / replaceSelectionPoints so
	// each change fires notifyChanged exactly once — previously callers mutated the backing list
	// directly and had to remember to trigger recomputes themselves.
	public List<SelectionPoint> getSelectionPoints()
	{
		return Collections.unmodifiableList(selectionPoints);
	}

	public String getSourceFormat()
	{
		return sourceFormat;
	}

	public Bitmap getSourceImage()
	{
		return sourceImage;
	}

	public boolean hasCenter()
	{
		return hasCenter;
	}

	public boolean isCenterLocked()
	{
		return centerLocked;
	}

	public boolean isCropSizeDirty()
	{
		return cropSizeDirty;
	}

	public void markCropSizeDirty()
	{
		this.cropSizeDirty = true;
	}

	public boolean removeSelectionPoint(SelectionPoint point)
	{
		boolean removed = selectionPoints.remove(point);
		if (removed)
		{
			notifyChanged();
		}
		return removed;
	}

	public SelectionPoint removeSelectionPointAt(int index)
	{
		SelectionPoint removed = selectionPoints.remove(index);
		notifyChanged();
		return removed;
	}

	// Replace all selection points atomically (used for undo/redo snapshot restores).
	public void replaceSelectionPoints(Collection<SelectionPoint> newPoints)
	{
		selectionPoints.clear();
		selectionPoints.addAll(newPoints);
		notifyChanged();
	}

	// Reset everything for a new image.
	public void reset()
	{
		sourceImage = null;
		originalFileBytes = null;
		sourceFormat = null;
		centerX = 0;
		centerY = 0;
		cropW = 0;
		cropH = 0;
		hasCenter = false;
		cropSizeDirty = true;
		rotationDegrees = 0f;
		centerLocked = false;
		// aspectRatio preserved — it's a user preference, not image data
		originalFilename = null;
		originalFilePath = null;
		mediaStoreId = -1;
		selectionPoints.clear();
		jpegMeta.clear();
		gainMap = null;
		seftTrailer = null;
	}

	public void setAspectRatio(AspectRatio ar)
	{
		this.aspectRatio = ar;
		cropSizeDirty = true;
		notifyChanged();
	}

	public void setCenter(float x, float y)
	{
		// Clamp so crop rect stays fully inside the (possibly rotated) image.
		if (sourceImage != null && cropW > 0 && cropH > 0)
		{
			int imgW = sourceImage.getWidth();
			int imgH = sourceImage.getHeight();

			if (rotationDegrees == 0f)
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
			}
			else
			{
				// For rotated images: clamp each axis independently via binary search. This
				// prevents clamping X from affecting Y and vice versa.
				float imageMidX = imgW / 2f;
				float imageMidY = imgH / 2f;
				double rad = Math.toRadians(-rotationDegrees);
				double cosR = Math.cos(rad);
				double sinR = Math.sin(rad);
				float halfWidth = cropW / 2f;
				float halfHeight = cropH / 2f;

				// First clamp X (keeping Y fixed)
				if (!cornersInside(x, y, halfWidth, halfHeight,
					imageMidX, imageMidY, cosR, sinR, imgW, imgH))
				{
					float loFraction = 0f;
					float hiFraction = 1f;
					float validX = imageMidX;
					for (int i = 0; i < 25; i++)
					{
						float midFraction = (loFraction + hiFraction) / 2f;
						float testX = imageMidX + (x - imageMidX) * midFraction;
						if (cornersInside(testX, y, halfWidth, halfHeight,
							imageMidX, imageMidY, cosR, sinR, imgW, imgH))
						{
							validX = testX;
							loFraction = midFraction;
						}
						else
						{
							hiFraction = midFraction;
						}
					}
					x = validX;
				}

				// Then clamp Y (keeping clamped X fixed)
				if (!cornersInside(x, y, halfWidth, halfHeight,
					imageMidX, imageMidY, cosR, sinR, imgW, imgH))
				{
					float loFraction = 0f;
					float hiFraction = 1f;
					float validY = imageMidY;
					for (int i = 0; i < 25; i++)
					{
						float midFraction = (loFraction + hiFraction) / 2f;
						float testY = imageMidY + (y - imageMidY) * midFraction;
						if (cornersInside(x, testY, halfWidth, halfHeight,
							imageMidX, imageMidY, cosR, sinR, imgW, imgH))
						{
							validY = testY;
							loFraction = midFraction;
						}
						else
						{
							hiFraction = midFraction;
						}
					}
					y = validY;
				}
			}
		}
		this.centerX = x;
		this.centerY = y;
		this.hasCenter = true;
		notifyChanged();
	}

	public void setCenterLocked(boolean locked)
	{
		this.centerLocked = locked;
	}

	public void setCenterMode(CenterMode mode)
	{
		this.centerMode = mode;
		// Don't set cropSizeDirty here — the button handler calls recomputeForLockChange()
		// explicitly. Setting dirty here causes the listener to recompute IMMEDIATELY
		// (runOnUiThread runs inline on UI thread) with the wrong center, racing with
		// the handler's recomputeForLockChange that uses the correct selection midpoint.
		notifyChanged();
	}

	// Set center without bounds clamping or notification — used before recomputeCrop.
	public void setCenterUnclamped(float x, float y)
	{
		this.centerX = x;
		this.centerY = y;
		this.hasCenter = true;
		// No notifyChanged — caller will trigger notify via recomputeCrop → setCenter
	}

	public void setCropSize(int width, int height)
	{
		this.cropW = width;
		this.cropH = height;
		notifyChanged();
	}

	public void setCropSizeDirty(boolean dirty)
	{
		this.cropSizeDirty = dirty;
	}

	// Set crop size without triggering listener — used during batch updates in recomputeCrop.
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

	public void setGainMap(byte[] gm)
	{
		this.gainMap = gm;
	}

	public void setJpegMeta(List<JpegSegment> meta)
	{
		this.jpegMeta = meta;
	}

	public void setListener(OnStateChangedListener listener)
	{
		this.listener = listener;
	}

	public void setMediaStoreId(long id)
	{
		this.mediaStoreId = id;
	}

	public void setOriginalFileBytes(byte[] bytes)
	{
		this.originalFileBytes = bytes;
	}

	public void setOriginalFilePath(String path)
	{
		this.originalFilePath = path;
	}

	public void setOriginalFilename(String name)
	{
		this.originalFilename = name;
	}

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

	public void setSeftTrailer(byte[] seft)
	{
		this.seftTrailer = seft;
	}

	public void setSourceFormat(String fmt)
	{
		this.sourceFormat = fmt;
	}

	public void setSourceImage(Bitmap bmp)
	{
		this.sourceImage = bmp;
		notifyChanged();
	}

	private void notifyChanged()
	{
		if (listener != null)
		{
			listener.onStateChanged();
		}
	}

	// Check if all 4 corners of the crop rect, when un-rotated, are inside the image.
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
