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
	// Shared toast text for filename validation failures (path-separator inserted, empty / ".." traversal
	// segment, or UTF-8 byte length exceeds the filesystem-component cap). Used by FolderPickerDialog's
	// in-dialog typed-name check AND SaveController's defensive re-validation when the picker callback
	// fires. Centralised here per CLAUDE.md's "3-site duplication" extraction threshold.
	public static final String INVALID_FILENAME = "Invalid filename";
	// Neutral / informational dismiss label.
	public static final String OK = "OK";

	private DialogStrings() {}
}
