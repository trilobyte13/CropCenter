package com.cropcenter.model;

/**
 * Top-level interaction mode of the editor. MOVE lets the user drag the crop box around;
 * SELECT_FEATURE lets the user tap to place selection points that drive auto-centering
 * of the crop on the selected feature. The mode is toggled by the Move / Select buttons
 * in the toolbar below the editor view.
 */
public enum EditorMode
{
	/**
	 * Tap does nothing; drag pans the crop (when a center is placed) or the viewport.
	 */
	MOVE,

	/**
	 * Tap adds / removes selection points; crop auto-centers on the selection midpoint.
	 */
	SELECT_FEATURE
}
