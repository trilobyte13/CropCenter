package com.cropcenter.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cropcenter.model.CenterMode;

import org.junit.Test;

/**
 * Tests for the package-private CropEditorView.crossAxisDrifted gate that decides whether a single-axis drag
 * (HORIZONTAL or VERTICAL lock mode) should be rejected because setCenter's joint clamp pushed the locked axis. Pin the
 * lock-axis selection (HORIZONTAL locks Y, VERTICAL locks X — the counter-intuitive naming the production callers
 * depend on), the strict 0.5px threshold, and the fall-through behaviour for BOTH / LOCKED / null lock modes. A
 * regression that flipped which axis is locked would let single-axis drags drift the wrong axis on rotated images; a
 * regression to >= 0.5 would falsely reject sub-pixel jitter from setCenter's clamp.
 */
public final class CropEditorViewCrossAxisDriftedTest
{
	@Test
	public void crossAxisDriftedBothLockModeReturnsFalse()
	{
		// CenterMode.BOTH means both axes free — no rejection. Any drift on either axis returns false.
		assertFalse("BOTH lock with X drift", CropEditorView.crossAxisDrifted(
			CenterMode.BOTH, 100f, 100f, 200f, 100f));
		assertFalse("BOTH lock with Y drift", CropEditorView.crossAxisDrifted(
			CenterMode.BOTH, 100f, 100f, 100f, 200f));
		assertFalse("BOTH lock with both axes drifted", CropEditorView.crossAxisDrifted(
			CenterMode.BOTH, 100f, 100f, 200f, 200f));
	}

	@Test
	public void crossAxisDriftedExactlyHalfPixelDoesNotTrip()
	{
		// Strict greater-than: drift of exactly 0.5 px is NOT a violation. Pin so a regression to >= 0.5 would
		// surface here as a false-positive that rejects sub-pixel drags.
		assertFalse("HORIZONTAL lock with exactly 0.5px Y drift",
			CropEditorView.crossAxisDrifted(CenterMode.HORIZONTAL, 100f, 100f, 999f, 100.5f));
		assertFalse("VERTICAL lock with exactly 0.5px X drift",
			CropEditorView.crossAxisDrifted(CenterMode.VERTICAL, 100f, 100f, 100.5f, 999f));
	}

	@Test
	public void crossAxisDriftedHorizontalLockTripsOnYDrift()
	{
		// HORIZONTAL lock = "drag locked to horizontal axis" = Y is the LOCKED axis (only X can move). A Y
		// movement of more than 0.5 px from the pre-drag position trips the drift check. The post-X value is
		// irrelevant — only Y is checked under HORIZONTAL lock.
		assertTrue("Y drifted 1px > 0.5 threshold",
			CropEditorView.crossAxisDrifted(CenterMode.HORIZONTAL, 100f, 100f, 200f, 101f));
		assertFalse("Y stayed put (X drifted is fine under HORIZONTAL)",
			CropEditorView.crossAxisDrifted(CenterMode.HORIZONTAL, 100f, 100f, 999f, 100f));
		assertFalse("Y drifted 0.3px (under threshold)",
			CropEditorView.crossAxisDrifted(CenterMode.HORIZONTAL, 100f, 100f, 999f, 100.3f));
	}

	@Test
	public void crossAxisDriftedLockedModeReturnsFalse()
	{
		// CenterMode.LOCKED corresponds to Pan mode (the entire crop is fixed). The drift check is irrelevant
		// in Pan mode (no drag happens), but the function defends with false rather than NPE.
		assertFalse(CropEditorView.crossAxisDrifted(CenterMode.LOCKED, 100f, 100f, 200f, 200f));
	}

	@Test
	public void crossAxisDriftedNullLockReturnsFalse()
	{
		// Defense-in-depth: null lock returns false rather than NPE. Production callers shouldn't pass null but
		// pin the contract.
		assertFalse(CropEditorView.crossAxisDrifted(null, 100f, 100f, 200f, 200f));
	}

	@Test
	public void crossAxisDriftedUsesAbsoluteDistanceSymmetric()
	{
		// The threshold check uses Math.abs — drift of -1.0 (post < pre) trips as much as +1.0 (post > pre).
		// Pin symmetry; a regression that dropped Math.abs would only catch positive drifts.
		assertTrue("HORIZONTAL lock with Y drifted -1px (post < pre)",
			CropEditorView.crossAxisDrifted(CenterMode.HORIZONTAL, 100f, 100f, 100f, 99f));
		assertTrue("VERTICAL lock with X drifted -1px",
			CropEditorView.crossAxisDrifted(CenterMode.VERTICAL, 100f, 100f, 99f, 100f));
	}

	@Test
	public void crossAxisDriftedVerticalLockTripsOnXDrift()
	{
		// VERTICAL lock = "drag locked to vertical axis" = X is the LOCKED axis (only Y can move). A post-X
		// value drifting more than 0.5 px from pre-X is a violation.
		assertTrue("X drifted 1px",
			CropEditorView.crossAxisDrifted(CenterMode.VERTICAL, 100f, 100f, 101f, 200f));
		assertFalse("X stayed put (Y drifted is fine under VERTICAL)",
			CropEditorView.crossAxisDrifted(CenterMode.VERTICAL, 100f, 100f, 100f, 999f));
		assertFalse("X drifted 0.4px (under threshold)",
			CropEditorView.crossAxisDrifted(CenterMode.VERTICAL, 100f, 100f, 100.4f, 999f));
	}
}
