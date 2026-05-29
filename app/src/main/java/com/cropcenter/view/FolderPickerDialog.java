package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.cropcenter.model.Format;
import com.cropcenter.util.DpToPx;
import com.cropcenter.util.ThemeColors;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Merged in-app save dialog — combines the format chips + Export Grid checkbox + editable
 * "Save as" filename field with a filesystem browser (delegated to FolderBrowser) for picking
 * the target folder. Used by the Save flow as a workaround for Samsung's One UI
 * ACTION_CREATE_DOCUMENT picker, which hides folder listings in internal-storage views.
 *
 * Layout top-to-bottom: a single SURFACE0-backed DialogCards panel hosting the FolderBrowser
 * panel (title block + breadcrumb + thumbnail scroller) plus an options row (format chips +
 * Export Grid checkbox) plus a "Save as" filename row. The browser panel's file rows / grid
 * cells are tap-to-populate: tapping an existing file fills the "Save as" filename input with
 * that file's name (convenient for overwriting), but the dialog stays open and the actual
 * save commit happens via the Save here button. Distinct from OpenPickerDialog's
 * tap-to-dismiss semantics where a tap commits the choice directly.
 *
 * Transient-dialog tracking uses host.setActiveTransientDialog (not registerTransientDialog)
 * plus a hostDismissCallback because the dialog installs its own composite OnDismissListener
 * that calls FolderBrowser.shutdown.
 */
public final class FolderPickerDialog
{
	/**
	 * Result bundle returned from the picker on "Save here". Null is passed instead of a
	 * SaveChoices instance on Cancel / back / external dismiss.
	 *
	 * @param folder   target directory the user picked
	 * @param filename target filename the user picked, normalised by the picker so the trailing
	 *                 extension matches `format` ("photo.heic" with format=PNG normalises to
	 *                 "photo.png"). Sanitised: no path separators, no ".." segments
	 * @param format   JPEG or PNG output format; drives both the encoded bytes and the
	 *                 filename's extension after normalisation
	 * @param bakeGrid true to render the grid overlay into the saved image
	 */
	public record SaveChoices(File folder, String filename, Format format, boolean bakeGrid) {}

	// UI input throttle, UTF-16 chars. Coarse cap to prevent copy-paste of multi-MB names into
	// the EditText; the authoritative filesystem-compatibility check is
	// MAX_FILENAME_UTF8_BYTES below. A 200-char string of single-byte ASCII fits easily under
	// 255 bytes; a 200-char string of 4-byte emoji codepoints would be ~800 bytes — that's
	// caught by the byte-length check in isValidFilename, not here.
	private static final int FILENAME_MAX_LENGTH = 200;
	// Filesystem filename-length cap, in UTF-8 bytes. Common Linux / Android filesystems
	// (ext4, F2FS, FAT32 LFN) cap individual filename components at 255 bytes. We use 250 to
	// leave headroom for the format-extension the caller may append after isValidFilename
	// runs (`.jpg` / `.png` / `.jpeg` — longest 5 bytes), so a typed-name validation can be
	// done before extension normalisation and the result still fits after the swap.
	private static final int MAX_FILENAME_UTF8_BYTES = 250;

	private final Consumer<SaveChoices> onPicked;
	private final Context ctx;
	// Use the Android-native DialogInterface.OnDismissListener (instead of
	// Consumer<DialogInterface>) so the type matches SettingsDialog's equivalent callback
	// shape — the codebase chose the Android idiom for that role.
	private final DialogInterface.OnDismissListener hostDismissCallback;
	private final FolderBrowser browser;
	private final String initialFilename;
	private final boolean initialBakeGrid;
	private CheckBox chkBakeGrid;
	private EditText filenameInput;
	private Format currentFormat;
	private float density;

	/**
	 * Construct the merged-save dialog. Callers invoke show() to actually display it.
	 *
	 * @param ctx                 Context for inflation
	 * @param startDir            initial folder to display; FolderBrowser clamps to its rootDir
	 *                            if outside, null lands at rootDir
	 * @param initialFilename     initial filename to pre-populate the editable filename field
	 *                            with (e.g. the source's stem + extension); the user can edit
	 *                            before tapping Save here
	 * @param initialFormat       initial Format selection (JPEG/PNG); the user can toggle
	 *                            in-dialog
	 * @param initialBakeGrid     initial Export Grid checkbox state
	 * @param onPicked            called with the SaveChoices on Save here, or null on Cancel /
	 *                            external dismiss
	 * @param hostDismissCallback host's transient-dialog tracking cleanup
	 */
	public FolderPickerDialog(Context ctx, File startDir, String initialFilename, Format initialFormat,
		boolean initialBakeGrid, Consumer<SaveChoices> onPicked,
		DialogInterface.OnDismissListener hostDismissCallback)
	{
		this.ctx = ctx;
		this.onPicked = onPicked;
		this.hostDismissCallback = hostDismissCallback;
		this.currentFormat = initialFormat != null ? initialFormat : Format.JPEG;
		this.initialFilename = initialFilename != null ? initialFilename : "crop.jpg";
		this.initialBakeGrid = initialBakeGrid;
		this.density = ctx.getResources().getDisplayMetrics().density;
		// onFileTapped is `this::onFileTappedForSave` → file rows / grid cells populate the
		// filename field with the tapped file's name (a convenient way to overwrite an existing
		// file in the same folder). The dialog stays open; the user still confirms via the
		// Save here button. This is intentionally different from OpenPickerDialog's tap-to-
		// dismiss behavior — save commits a typed name, not a tapped file.
		// jpegOnly=false — save flow accepts taps on PNG / JPEG alike (the tap just populates the
		// filename input; the actual save-format choice is independent).
		this.browser = new FolderBrowser(ctx, density, startDir, this::onFileTappedForSave, false);
	}

	/**
	 * Validate a user-typed filename for the in-app save dialog. Rejects empty input, traversal
	 * segments (".", ".."), any path separator ("/", "\"), and names whose UTF-8 byte length
	 * exceeds MAX_FILENAME_UTF8_BYTES (the filesystem-compatibility cap — 200 UTF-16 chars of
	 * CJK / emoji can easily exceed the common 255-byte filesystem-component limit, which the
	 * LengthFilter doesn't catch). Public so SaveController (in a different package) can route
	 * its merged-save and rename validators through this single chokepoint — and so the test
	 * class can pin the validation contract without spinning up the dialog. A false return
	 * surfaces "Invalid filename" via Toast and keeps the dialog open.
	 *
	 * @param typed user-typed filename, ALREADY trimmed by the caller. Untrimmed " " would pass
	 *              the isEmpty check but produce a useless filename — trim at the call site,
	 *              not here
	 * @return true when the name is safe to use as a file-system filename; false otherwise
	 */
	public static boolean isValidFilename(String typed)
	{
		if (typed.isEmpty()
			|| typed.equals(".")
			|| typed.equals("..")
			|| typed.contains("/")
			|| typed.contains("\\"))
		{
			return false;
		}
		// UTF-8 byte-length cap. 200 chars of 4-byte emoji = 800 bytes — too long for ext4 /
		// FAT32 even though LengthFilter accepted it. Encoding here is unavoidable on the UI
		// thread because String.length() (UTF-16 char count) and the on-disk byte cost diverge
		// for any non-ASCII input.
		return typed.getBytes(StandardCharsets.UTF_8).length <= MAX_FILENAME_UTF8_BYTES;
	}

	/**
	 * Strip any trailing extension from `name` and append `format.extension()`. Used at "Save
	 * here" to normalise the typed filename's extension against the selected format chip — same
	 * rule syncFilenameExtension applies live during format-chip toggles, applied one more time
	 * at confirm so a user who typed a mismatched extension AFTER the last toggle gets the
	 * format-aligned name. Names with no dot or with a leading-dot stem (".gitignore" — dot at
	 * index 0) get the extension appended verbatim without stripping. Public so
	 * SaveController.showInAppRenameDialog can apply the same normalisation to the in-app
	 * Rename branch's typed-name path (gallery / file-manager type inference depends on the
	 * on-disk extension agreeing with the encoded bytes).
	 *
	 * @param name   user-typed filename (already trimmed and validated for path-separator
	 *               safety)
	 * @param format format whose extension to enforce
	 * @return the filename with the trailing extension swapped (or appended) to match format
	 */
	public static String normaliseExtension(String name, Format format)
	{
		return Format.stripExtension(name) + format.extension();
	}

	/**
	 * Build and show the merged save dialog. Returns the AlertDialog so the caller can pass it
	 * to SaveHost.setActiveTransientDialog (NOT registerTransientDialog — the latter would
	 * overwrite the composite OnDismissListener installed here).
	 *
	 * @return the dialog after .show() has been called
	 */
	public AlertDialog show()
	{
		int padInner = DpToPx.toPx(12, density);

		// FolderBrowser's buildPanel returns the inlaid card as [titleBlock, browser scroller], where
		// the scroller is a weight=1 flex child of a height-capped panel. Append the save-specific
		// rows (options + filename) AFTER the scroller so they render below the grid. They stay
		// on-screen even though the list populates asynchronously (and can balloon post-layout)
		// because the panel cap distributes any overflow to the weighted scroller alone — the list
		// shrinks and scrolls internally while these fixed rows keep their full height. All rows
		// share the card's SURFACE0 fill so no un-panelled region remains in the dialog body.
		LinearLayout panel = browser.buildPanel();
		panel.addView(buildOptionsRow(padInner));
		panel.addView(buildFilenameRow(padInner));

		boolean[] decided = { false };
		// Install the positive button with a null handler so AlertDialog doesn't auto-dismiss
		// after the click — we override the button's OnClickListener in OnShowListener below so
		// an "Invalid filename" validation failure can toast and keep the dialog open rather
		// than closing the whole flow on a correctable typo. The negative button keeps the
		// default null-handler dismiss behaviour (Cancel really should close).
		AlertDialog dialog = new AlertDialog.Builder(ctx)
			.setView(browser.wrapInDialogMargins(panel))
			.setPositiveButton("Save here", null)
			.setNegativeButton(DialogStrings.CANCEL, null)
			.create();
		dialog.setOnShowListener(shown ->
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				onSaveHereClicked(dialog, decided)));
		browser.attachToDialog(dialog, () -> decided[0], () -> onPicked.accept(null),
			hostDismissCallback);
		return dialog;
	}

	/**
	 * Build the editable filename row at the bottom of the dialog (above the framework
	 * buttons). Pre-filled with `initialFilename`; the user can edit freely. Format-chip
	 * toggling syncs the extension automatically via syncFilenameExtension. Sanitisation (no
	 * path separators, no traversal segments) runs on the Save-here positive-button handler,
	 * not here, so the field itself accepts any IME input.
	 *
	 * @param padInner vertical padding inside the row
	 * @return single-row LinearLayout containing the filename EditText
	 */
	private LinearLayout buildFilenameRow(int padInner)
	{
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(0, padInner, 0, padInner);

		TextView label = new TextView(ctx);
		label.setText("Save as");
		label.setTextSize(13);
		label.setTextColor(ThemeColors.SUBTEXT0);
		row.addView(label, new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

		filenameInput = new EditText(ctx);
		filenameInput.setText(initialFilename);
		filenameInput.setTextSize(14);
		filenameInput.setTextColor(ThemeColors.TEXT);
		filenameInput.setSingleLine(true);
		filenameInput.setEllipsize(TextUtils.TruncateAt.END);
		// Suppress autocorrect / autosuggest — those can silently insert spaces or rewrite
		// typed characters into a different filename than the user sees. The IME action becomes
		// DONE (collapses the keyboard) rather than a newline, since setSingleLine already
		// blocks newlines.
		filenameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		filenameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
		// Cap input length to keep the typed name comfortably under common filesystem 255-byte
		// limits (allowing for multi-byte UTF-8 characters in the name).
		filenameInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(FILENAME_MAX_LENGTH) });
		LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0,
			LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
		inputLp.leftMargin = DpToPx.toPx(8, density);
		row.addView(filenameInput, inputLp);
		return row;
	}

	/**
	 * Build the format-chip + Export-Grid checkbox row.
	 *
	 * @param padInner vertical padding inside the row
	 * @return single-row LinearLayout containing the JPEG / PNG chips and the Export Grid
	 *         checkbox
	 */
	private LinearLayout buildOptionsRow(int padInner)
	{
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(0, padInner, 0, padInner);

		TextView jpegChip = makeFormatChip("JPEG");
		TextView pngChip = makeFormatChip("PNG");
		Runnable updateChipStyle = () -> styleFormatChips(jpegChip, pngChip);
		updateChipStyle.run();
		jpegChip.setOnClickListener(view ->
		{
			currentFormat = Format.JPEG;
			updateChipStyle.run();
			syncFilenameExtension();
		});
		pngChip.setOnClickListener(view ->
		{
			currentFormat = Format.PNG;
			updateChipStyle.run();
			syncFilenameExtension();
		});

		chkBakeGrid = new CheckBox(ctx);
		chkBakeGrid.setText("Export Grid");
		chkBakeGrid.setTextSize(12);
		chkBakeGrid.setTextColor(ThemeColors.TEXT);
		chkBakeGrid.setButtonTintList(ColorStateList.valueOf(ThemeColors.MAUVE));
		chkBakeGrid.setChecked(initialBakeGrid);

		// Children order: [Export Grid] [JPEG] [PNG]. Export Grid leads at WRAP_CONTENT; the
		// two format chips occupy the remaining width with equal weight=1 splits.
		int chipGap = DpToPx.toPx(8, density);
		LinearLayout.LayoutParams chkLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		row.addView(chkBakeGrid, chkLp);

		LinearLayout.LayoutParams jpegLp = new LinearLayout.LayoutParams(0,
			LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
		jpegLp.leftMargin = chipGap * 2;
		row.addView(jpegChip, jpegLp);

		LinearLayout.LayoutParams pngLp = new LinearLayout.LayoutParams(0,
			LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
		pngLp.leftMargin = chipGap;
		row.addView(pngChip, pngLp);
		return row;
	}

	private TextView makeFormatChip(String text)
	{
		TextView btn = new TextView(ctx);
		btn.setText(text);
		btn.setTextSize(13);
		btn.setGravity(Gravity.CENTER);
		btn.setTypeface(btn.getTypeface(), Typeface.BOLD);
		// 10dp vertical padding for a comfortable tap target — the Samsung S25's 3x density
		// renders this at 30px, well clear of the 32px-minimum Material guideline for inline
		// chips.
		btn.setPadding(0, DpToPx.toPx(10, density), 0, DpToPx.toPx(10, density));
		btn.setSingleLine(true);
		return btn;
	}

	/**
	 * File-row / grid-cell tap handler — fired by FolderBrowser when the user taps an image in
	 * the browser. Populates the filename input with the tapped file's name and parks the IME
	 * cursor at the stem-end (just before the trailing extension) so the user can fast-edit the
	 * stem without re-typing the extension. The dialog stays open; the user still commits via
	 * the Save here button.
	 *
	 * Does NOT alter currentFormat — if the tapped file's extension implies a different format
	 * than the chip currently shows, the chip wins (the user explicitly picked the format
	 * before browsing); the normaliseExtension at confirm time will adjust the typed extension
	 * to match the format chip's encoder, so the on-disk bytes and the on-disk extension stay
	 * coherent.
	 *
	 * @param file the file the user tapped
	 */
	private void onFileTappedForSave(File file)
	{
		String name = file.getName();
		filenameInput.setText(name);
		// Move the IME caret to the dot (stem-end) so a user about to overwrite "IMG_0042.jpg"
		// can immediately type a suffix like "-edited" without first having to skip past the
		// extension. Names without an extension get the caret at end-of-text. The leading-dot
		// case ("." at index 0, ".gitignore") is treated as no extension to avoid landing the
		// caret at position 0 where typing would corrupt the dotfile prefix.
		int dotIdx = name.lastIndexOf('.');
		int caret = dotIdx > 0 ? dotIdx : name.length();
		filenameInput.setSelection(caret);
	}

	/**
	 * Save here positive-button handler. Sanitises the typed filename — rejects path separators
	 * and traversal segments, toasts "Invalid filename" and keeps the dialog open on failure —
	 * then normalises the extension to match the selected format chip, fires onPicked with the
	 * SaveChoices, and dismisses. Sets `decided[0] = true` AFTER onPicked.accept returns so a
	 * thrown callback leaves decided=false and the subsequent OnDismiss fires the cancel
	 * signal that the SaveController contract expects.
	 *
	 * Extracted from a previous inline lambda inside show()'s setOnShowListener so the OnShow
	 * lambda stays a single-line method-reference adapter (3-line lambda cap).
	 *
	 * @param dialog  the dialog to dismiss on success
	 * @param decided one-element array; flipped to true on a successful filename-validated
	 *                Save so the OnDismissListener skips the cancel callback
	 */
	private void onSaveHereClicked(AlertDialog dialog, boolean[] decided)
	{
		String typed = filenameInput.getText().toString().trim();
		if (!isValidFilename(typed))
		{
			Toast.makeText(ctx, DialogStrings.INVALID_FILENAME, Toast.LENGTH_SHORT).show();
			return;
		}
		String normalised = normaliseExtension(typed, currentFormat);
		onPicked.accept(new SaveChoices(browser.getCurrent(), normalised, currentFormat,
			chkBakeGrid.isChecked()));
		decided[0] = true;
		dialog.dismiss();
	}

	private void styleFormatChips(TextView jpeg, TextView png)
	{
		boolean jpegSelected = currentFormat == Format.JPEG;
		// 6dp corners give the chips a softer pill shape that visually pairs with the dialog's
		// rounded AlertDialog frame.
		float cornerPx = DpToPx.toPx(6, density);
		GradientDrawable jpegBg = new GradientDrawable();
		jpegBg.setColor(jpegSelected ? ThemeColors.MAUVE : ThemeColors.SURFACE1);
		jpegBg.setCornerRadius(cornerPx);
		jpeg.setBackground(jpegBg);
		jpeg.setTextColor(jpegSelected ? ThemeColors.CRUST : ThemeColors.TEXT);

		GradientDrawable pngBg = new GradientDrawable();
		pngBg.setColor(!jpegSelected ? ThemeColors.MAUVE : ThemeColors.SURFACE1);
		pngBg.setCornerRadius(cornerPx);
		png.setBackground(pngBg);
		png.setTextColor(!jpegSelected ? ThemeColors.CRUST : ThemeColors.TEXT);
	}

	/**
	 * Swap the filename's extension to match the current format chip (.jpg ↔ .png) without
	 * disturbing the user-typed stem. Called from each format chip's OnClickListener so
	 * toggling JPEG → PNG on "vacation.jpg" produces "vacation.png" instead of a mismatched
	 * filename. Cursor position is clamped to the new length to keep the IME caret in roughly
	 * the same logical place when the user was mid-edit.
	 */
	private void syncFilenameExtension()
	{
		String typedText = filenameInput.getText().toString();
		String newName = normaliseExtension(typedText, currentFormat);
		if (!typedText.equals(newName))
		{
			int cursor = filenameInput.getSelectionStart();
			filenameInput.setText(newName);
			filenameInput.setSelection(Math.clamp(cursor, 0, newName.length()));
		}
	}
}
