package com.cropcenter.model;

/**
 * Which axes of the crop box are locked symmetrically around the selection. In Select
 * mode the user picks one of BOTH / HORIZONTAL / VERTICAL to control how the crop frames
 * the points. In Move mode the state is LOCKED — the crop is fixed and drag events pan
 * the viewport instead.
 */
public enum CenterMode
{
	/**
	 * Select mode: crop sized symmetrically on both axes — selection at dead center.
	 */
	BOTH,

	/**
	 * Lock X axis symmetric about selection midpoint; Y can shift to fit image bounds.
	 */
	HORIZONTAL,

	/**
	 * Lock Y axis symmetric about selection midpoint; X can shift to fit image bounds.
	 */
	VERTICAL,

	/**
	 * Move mode: crop fully locked, drag pans the viewport.
	 */
	LOCKED
}
