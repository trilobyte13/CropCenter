package com.cropcenter.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for BrowserFastScroller.isDragAtTrackEnd — the decision seam that routes a maxed-out drag to the O(1) last-item
 * anchor (scrollToPositionWithOffset on the LayoutManager) instead of the proportional row math. The boundary is
 * load-bearing in both directions: a false negative at the track bottom makes the last row unreachable by drag, while a
 * false positive mid-track teleports the list to the end; and the end anchor must never fire for a degenerate track
 * (thumb fills the strip), which belongs to the proportional path's ratio-0 handling.
 */
public final class BrowserFastScrollerTest
{
	@Test
	public void degenerateTrackIsNeverEndOfTrack()
	{
		// scrollableTrack <= 0 — the thumb fills (or overfills) the strip, so there is no drag travel at all.
		// The proportional path maps this to ratio 0 / row 0; taking the end anchor here would jump a
		// barely-overflowing folder to its last row on any grab.
		assertFalse(BrowserFastScroller.isDragAtTrackEnd(0f, 0f));
		assertFalse(BrowserFastScroller.isDragAtTrackEnd(0f, -10f));
	}

	@Test
	public void dragAtExactTrackBottomIsEndOfTrack()
	{
		// clampedTop == scrollableTrack is exactly what the scroll runnable produces after clamping a maxed (or
		// overshot) finger position — must take the anchored end path so the last row lands bottom-flush
		// without binding every row on the way.
		assertTrue(BrowserFastScroller.isDragAtTrackEnd(500f, 500f));
	}

	@Test
	public void dragJustAboveTrackBottomStaysProportional()
	{
		// Any drag short of the full travel stays on the row-proportional path.
		assertFalse(BrowserFastScroller.isDragAtTrackEnd(499.5f, 500f));
		assertFalse(BrowserFastScroller.isDragAtTrackEnd(0f, 500f));
	}
}
