package com.cropcenter.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View.MeasureSpec;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView subclass with an optional intrinsic-height cap. Used by FolderBrowser's content
 * list so the dialog grows naturally with the folder's item count up to a screen-fraction cap,
 * then starts scrolling — short folders make a compact dialog while long ones stay bounded so
 * the picker's bottom controls (Save/Cancel buttons, filename input, options row) stay reachable
 * without scrolling them.
 *
 * Subclass (rather than an anonymous-subclass closure inside FolderBrowser.buildPanel) so the
 * picker's content RecyclerView can be inflated from XML alongside the BrowserFastScroller
 * sibling that overlays its right edge. XML inflation requires a concrete public class.
 *
 * The height cap is set imperatively via setMaxHeightPx because the value depends on screen
 * height minus a dialog-specific reserved-chrome budget (440dp for save, 300dp for open) that
 * isn't available from layout XML alone.
 */
public final class BrowserRecyclerView extends RecyclerView
{
	private int maxHeightPx;

	public BrowserRecyclerView(@NonNull Context context)
	{
		super(context);
	}

	public BrowserRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs)
	{
		super(context, attrs);
	}

	public BrowserRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle)
	{
		super(context, attrs, defStyle);
	}

	/**
	 * Update the height cap and request a relayout. A value of 0 disables the cap (the
	 * RecyclerView measures naturally). Negative values are treated as 0 — the cap can't go
	 * below the minimum-height contract a measure pass implies.
	 *
	 * @param px desired maximum measured height in pixels; 0 or negative disables capping
	 */
	public void setMaxHeightPx(int px)
	{
		this.maxHeightPx = Math.max(0, px);
		requestLayout();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
	{
		if (maxHeightPx <= 0)
		{
			super.onMeasure(widthMeasureSpec, heightMeasureSpec);
			return;
		}
		int heightMode = MeasureSpec.getMode(heightMeasureSpec);
		int heightSize = MeasureSpec.getSize(heightMeasureSpec);
		int targetMax = (heightMode == MeasureSpec.UNSPECIFIED)
			? maxHeightPx
			: Math.min(heightSize, maxHeightPx);
		super.onMeasure(widthMeasureSpec,
			MeasureSpec.makeMeasureSpec(targetMax, MeasureSpec.AT_MOST));
	}
}
