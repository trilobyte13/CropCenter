package com.cropcenter.view;

/**
 * Shared user-facing button labels and short prompts for AlertDialog buttons across the app. Centralised so a
 * style change ("Apply" → "Apply edit", capitalisation tweak, etc.) lands in one place rather than
 * shotgun-edits across 9+ call sites. Matches the existing `ThemeColors` / `JpegMarker` / `TiffTag`
 * chokepoint pattern — "one literal per concept, used everywhere".
 *
 * Strings live as Java constants rather than `R.string.*` because the project's convention is hardcoded
 * literals at the call site; this class consolidates the most-duplicated ones without forcing the
 * strings.xml + getString plumbing the rest of the codebase has explicitly avoided.
 */
public final class DialogStrings
{
	// Positive-button label for "apply this edit / change".
	public static final String APPLY = "Apply";
	// Negative-button label for cancel / dismiss.
	public static final String CANCEL = "Cancel";
	// Neutral / informational dismiss label.
	public static final String OK = "OK";

	private DialogStrings() {}
}
