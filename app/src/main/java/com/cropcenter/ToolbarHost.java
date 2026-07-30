package com.cropcenter;

import android.widget.TextView;

import com.cropcenter.model.CenterMode;
import com.cropcenter.view.CropEditorView;
import com.cropcenter.view.RotationRulerView;

/**
 * Host surface consumed by ToolbarBinder. Adds toolbar-specific operations (lock mode / ensureCropCenter /
 * recenterOnSelection / recomputeForLockChange / setCurrentPref / view accessors) on top of EditorHost's shared busy /
 * progress / toast / transient-dialog plumbing.
 */
interface ToolbarHost extends EditorHost
{
	/**
	 * Reapply the active lock mode to CropState.
	 */
	void applyLockMode();

	/**
	 * Seed a crop center at the image midpoint when none exists yet. Called from the AR popup / Custom AR dialog
	 * flows so an aspect-ratio change takes immediate effect.
	 */
	void ensureCropCenter();

	CropEditorView getEditorView();

	TextView getRotDegreesTextView();

	RotationRulerView getRotationRuler();

	boolean isPanning();

	boolean isRulerUpdating();

	/**
	 * Move-mode axis switch — re-anchor the crop at the current selection midpoint without resizing.
	 */
	void recenterOnSelection();

	/**
	 * After a lock mode / editor mode / pan toggle, recompute the crop rectangle so the new mode's constraints are
	 * applied to the existing state.
	 */
	void recomputeForLockChange();

	/**
	 * Update the lock-mode preference for the currently-active editor mode.
	 *
	 * @param pref new axis preference (BOTH / HORIZONTAL / VERTICAL). LOCKED is never passed — it is the
	 *             Pin sentinel on centerMode, not an axis preference, and writing it here would corrupt
	 *             the stored axis pref (ToolbarBinder.syncFromState explicitly skips it)
	 */
	void setCurrentPref(CenterMode pref);
}
