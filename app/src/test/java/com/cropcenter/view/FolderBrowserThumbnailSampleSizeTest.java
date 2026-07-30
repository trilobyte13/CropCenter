package com.cropcenter.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for FolderBrowser.thumbnailSampleSize — the single subsample policy behind BOTH thumbnail decode paths
 * (the EXIF-embedded thumbnail bytes and the full-file BitmapFactory.decodeFile fallback). The shared seam is the
 * fix for the unbounded EXIF-thumbnail decode: androidx.exifinterface hands back IFD1 thumbnail bytes verbatim,
 * and an adversarial ~64 KB APP1 can decode to a ~2500 px square (~25 MB ARGB) that thrashes the 64 MB
 * THUMBNAIL_CACHE — the bounds pass + this subsample factor cap the decoded bitmap near the display size on both
 * paths, so neither can regress independently.
 */
public final class FolderBrowserThumbnailSampleSizeTest
{
	@Test
	public void thumbnailSampleSizeCapsAdversarialEmbeddedThumbnailNearTarget()
	{
		// The C2 attack shape: a 2500 px square embedded thumbnail against a ~160 px cell. The factor must
		// bring the subsampled max dimension to at most targetSize * 2 (2500 / 8 = 312 <= 320), landing the
		// decode at ~0.4 MB ARGB instead of ~25 MB. A factor of 1 here is exactly the unbounded-decode
		// regression.
		assertEquals(8, FolderBrowser.thumbnailSampleSize(2500, 160));
		// Post-subsample invariant, stated directly: the decoded max dimension respects the cap.
		assertEquals(312, 2500 / FolderBrowser.thumbnailSampleSize(2500, 160));
	}

	@Test
	public void thumbnailSampleSizeIsPowerOfTwoBoundary()
	{
		// BitmapFactory rounds inSampleSize down to a power of 2 — the policy must emit exact powers so the
		// requested and effective factors agree. Boundary pins around targetSize * 2 = 320: at exactly the
		// cap no subsampling, one past it doubles. The cap comparison uses integer division, so 641 stays at
		// factor 2 (641 / 2 = 320, exactly at the cap) and 642 is the first to need factor 4.
		assertEquals(1, FolderBrowser.thumbnailSampleSize(320, 160));
		assertEquals(2, FolderBrowser.thumbnailSampleSize(321, 160));
		assertEquals(2, FolderBrowser.thumbnailSampleSize(641, 160));
		assertEquals(4, FolderBrowser.thumbnailSampleSize(642, 160));
	}

	@Test
	public void thumbnailSampleSizeReturnsOneForSmallAndDegenerateInputs()
	{
		// Typical honest embedded thumbnails (160-512 px) against the same cell need no subsampling, and
		// non-positive dims (corrupt bounds pass) fall through as factor 1 — the caller rejects those before
		// decoding.
		assertEquals(1, FolderBrowser.thumbnailSampleSize(160, 160));
		assertEquals(1, FolderBrowser.thumbnailSampleSize(1, 160));
		assertEquals(1, FolderBrowser.thumbnailSampleSize(0, 160));
	}
}
