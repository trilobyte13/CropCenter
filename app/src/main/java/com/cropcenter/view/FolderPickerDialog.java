package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
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

import com.cropcenter.SaveTempFiles;
import com.cropcenter.model.Format;
import com.cropcenter.util.DpToPx;
import com.cropcenter.util.ThemeColors;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Merged in-app save dialog — format chips + Export Grid checkbox + editable "Save as" filename + a filesystem browser
 * (delegated to FolderBrowser) for the target folder. Workaround for Samsung's One UI ACTION_CREATE_DOCUMENT picker,
 * which hides folder listings in internal-storage views.
 *
 * Layout: a single SURFACE0 DialogCards panel hosting the FolderBrowser panel (title + breadcrumb + thumbnail scroller)
 * + an options row (format chips + Export Grid) + a "Save as" filename row. File taps are tap-to-populate — tapping a
 * file fills the filename input (convenient for overwriting) but the dialog stays open; the save commits via "Save
 * here". Distinct from OpenPickerDialog's tap-to-dismiss.
 *
 * Transient-dialog tracking uses host.setActiveTransientDialog (not registerTransientDialog) + a hostDismissCallback
 * because the dialog installs its own composite OnDismissListener calling FolderBrowser.shutdown.
 */
public final class FolderPickerDialog
{
	/**
	 * Result bundle returned from the picker on "Save here". Null is passed instead of a SaveChoices instance on
	 * Cancel / back / external dismiss.
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

	// Filesystem filename-length cap, in UTF-8 bytes. Common Linux / Android filesystems (ext4, F2FS, FAT32 LFN)
	// cap individual filename components at 255 bytes, and the crash-safe save pipeline prepends
	// SaveTempFiles.tempName's hidden prefix to every validated name — ".cropcenter-tmp-" (16 bytes) + up to 19
	// nanoTime digits + "-" (1 byte) = 36 bytes worst case — so a validated name longer than 255 - 36 = 219
	// bytes deterministically fails the placeholder / write-temp creation with ENAMETOOLONG after validation
	// already passed. 210 leaves further headroom for the format-extension swap the caller may apply after
	// isValidFilename runs (`.jpeg` — longest 5 bytes) plus slack for filesystems that shave the component cap.
	private static final int MAX_FILENAME_UTF8_BYTES = 210;

	private final Consumer<SaveChoices> onPicked;
	private final Context ctx;
	// Use the Android-native DialogInterface.OnDismissListener (instead of Consumer<DialogInterface>) so the type
	// matches SettingsDialog's equivalent callback shape — the codebase chose the Android idiom for that role.
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
		// onFileTapped is `this::onFileTappedForSave` → file rows / grid cells populate the filename field with
		// the tapped file's name (a convenient way to overwrite an existing file in the same folder). The
		// dialog stays open; the user still confirms via the Save here button. This is intentionally different
		// from OpenPickerDialog's tap-to-dismiss behavior — save commits a typed name, not a tapped file.
		// jpegOnly=false — save flow accepts taps on PNG / JPEG alike (the tap just populates the filename
		// input; the actual save-format choice is independent).
		this.browser = new FolderBrowser(ctx, density, startDir, this::onFileTappedForSave, false);
	}

	/**
	 * Validate a user-typed filename for the in-app save dialog. Rejects empty input, traversal segments (".",
	 * ".."), any path separator ("/", "\"), names in the reserved crash-safe temp namespace
	 * (SaveTempFiles.isReservedName — the sweep's deletable shapes and everything one nonce short of them), and
	 * names whose UTF-8 byte length exceeds MAX_FILENAME_UTF8_BYTES (the filesystem-compatibility cap sized so
	 * the crash-safe temp prefix still fits the 255-byte component limit — see the constant; the filename
	 * field's own InputFilter shares the cap through editExceedsByteCap). Public so SaveController (in a
	 * different package) can route its merged-save and rename validators through this single chokepoint — and
	 * so the test class can pin the validation contract without spinning up the dialog. A false return
	 * surfaces "Invalid filename" via Toast and keeps the dialog open.
	 *
	 * @param typed user-typed filename, ALREADY trimmed by the caller. Untrimmed " " would pass
	 *              the isEmpty check but produce a useless filename — trim at the call site, not here
	 * @return true when the name is safe to use as a file-system filename; false otherwise
	 */
	public static boolean isValidFilename(String typed)
	{
		if (typed.isEmpty() || typed.equals(".") || typed.equals("..") || typed.contains("/")
			|| typed.contains("\\"))
		{
			return false;
		}
		// Reserved crash-safe temp namespace: a user file here would collide with an in-flight save's temp or
		// sit inside the startup sweep's deletable namespace. Both save entry points (the merged dialog's
		// Save here and SaveController.onRenameOkClicked) inherit the rejection through this predicate.
		if (SaveTempFiles.isReservedName(typed))
		{
			return false;
		}
		// UTF-8 byte-length cap. Encoding here is unavoidable on the UI thread because String.length()
		// (UTF-16 char count) and the on-disk byte cost diverge for any non-ASCII input — 200 chars of
		// 4-byte emoji is 800 bytes, too long for ext4 / FAT32.
		return typed.getBytes(StandardCharsets.UTF_8).length <= MAX_FILENAME_UTF8_BYTES;
	}

	/**
	 * Strip any trailing extension from `name` and append `format.extension()`. Used at "Save here" to normalise
	 * the typed filename's extension against the selected format chip — same rule syncFilenameExtension applies
	 * live during format-chip toggles, applied one more time at confirm so a user who typed a mismatched extension
	 * AFTER the last toggle gets the format-aligned name. Names with no dot or with a leading-dot stem
	 * (".gitignore" — dot at index 0) get the extension appended verbatim without stripping. Public so
	 * SaveController.showInAppRenameDialog can apply the same normalisation to the in-app Rename branch's
	 * typed-name path (gallery / file-manager type inference depends on the on-disk extension agreeing with the
	 * encoded bytes).
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
	 * Build and show the merged save dialog. Returns the AlertDialog so the caller can pass it to
	 * SaveHost.setActiveTransientDialog (NOT registerTransientDialog — the latter would overwrite the composite
	 * OnDismissListener installed here).
	 *
	 * @return the dialog after .show() has been called
	 */
	public AlertDialog show()
	{
		int padInner = DpToPx.toPx(12, density);

		// FolderBrowser's buildPanel returns the inlaid card as [titleBlock, browser scroller], where the
		// scroller is a weight=1 flex child of a height-capped panel. Append the save-specific rows (options +
		// filename) AFTER the scroller so they render below the grid. They stay on-screen even though the list
		// populates asynchronously (and can balloon post-layout) because the panel cap distributes any overflow
		// to the weighted scroller alone — the list shrinks and scrolls internally while these fixed rows keep
		// their full height. All rows share the card's SURFACE0 fill so no un-panelled region remains in the
		// dialog body.
		LinearLayout panel = browser.buildPanel();
		panel.addView(buildOptionsRow(padInner));
		panel.addView(buildFilenameRow(padInner));

		boolean[] decided = { false };
		// Install the positive button with a null handler so AlertDialog doesn't auto-dismiss after the click —
		// we override the button's OnClickListener in OnShowListener below so an "Invalid filename" validation
		// failure can toast and keep the dialog open rather than closing the whole flow on a correctable typo.
		// The negative button keeps the default null-handler dismiss behaviour (Cancel really should close).
		AlertDialog dialog = new AlertDialog.Builder(ctx)
			.setView(browser.wrapInDialogMargins(panel))
			.setPositiveButton("Save here", null)
			.setNegativeButton(DialogStrings.CANCEL, null)
			.create();
		dialog.setOnShowListener(shown ->
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				onSaveHereClicked(dialog, decided)));
		browser.attachToDialog(dialog, () -> decided[0], () -> onPicked.accept(null), hostDismissCallback);
		return dialog;
	}

	/**
	 * Decide whether a proposed edit to the filename field would push the field's text over
	 * MAX_FILENAME_UTF8_BYTES. Pure core of the field's InputFilter: source[start, end) replaces
	 * dest[dstart, dend), and the resulting text's UTF-8 byte length is measured exactly the way
	 * isValidFilename measures it, so the field and the Save-here validator can never disagree about length.
	 * An over-cap edit is rejected WHOLE (filterByteCapEdit returns the untouched dest range) rather
	 * than partially accepted: a truncated insertion could silently mutate a tapped file's name into a
	 * different valid name, and a char-boundary split could sever a surrogate pair mid-emoji — an edit either
	 * lands entirely or not at all. Deletions always proceed (an empty replacement only shrinks the text).
	 * Package-private static so the test class can pin the contract headlessly; onFileTappedForSave asks this
	 * same question before populating the field.
	 *
	 * @param source proposed replacement text (IME insertion, paste, or programmatic setText value)
	 * @param start  start of the relevant range in source (inclusive)
	 * @param end    end of the relevant range in source (exclusive)
	 * @param dest   the field's current text (empty for a programmatic setText pass)
	 * @param dstart start of the range in dest being replaced (inclusive)
	 * @param dend   end of the range in dest being replaced (exclusive)
	 * @return true when the resulting text would exceed MAX_FILENAME_UTF8_BYTES and the edit must be
	 *         rejected whole; false when the edit fits and may be applied verbatim
	 */
	static boolean editExceedsByteCap(CharSequence source, int start, int end, CharSequence dest, int dstart,
		int dend)
	{
		String result = dest.subSequence(0, dstart).toString() + source.subSequence(start, end)
			+ dest.subSequence(dend, dest.length());
		return result.getBytes(StandardCharsets.UTF_8).length > MAX_FILENAME_UTF8_BYTES;
	}

	/**
	 * The filename field's InputFilter body, modelling the framework contract faithfully: the returned
	 * CharSequence replaces dest[dstart, dend) regardless of acceptance, so rejection must return the
	 * UNTOUCHED dest range (the keep-original idiom), not the empty string — returning "" would convert an
	 * over-cap replace-selection edit into a silent deletion of the selection, mutating the field into a
	 * different valid name. A fitting edit returns null (accept source verbatim). On the programmatic setText
	 * shape (empty dest) the no-op result collapses to "" — the field clears rather than holding an over-cap
	 * value, which is why the setText callers pre-check with editExceedsByteCap. Package-private static so
	 * the test class can pin the dest-range return contract headlessly.
	 *
	 * @param source proposed replacement text (IME insertion, paste, or programmatic setText value)
	 * @param start  start of the relevant range in source (inclusive)
	 * @param end    end of the relevant range in source (exclusive)
	 * @param dest   the field's current text (empty for a programmatic setText pass)
	 * @param dstart start of the range in dest being replaced (inclusive)
	 * @param dend   end of the range in dest being replaced (exclusive)
	 * @return null to accept the edit verbatim; the untouched dest[dstart, dend) range when the edit would
	 *         exceed MAX_FILENAME_UTF8_BYTES, leaving the field byte-identical after the framework applies it
	 */
	static CharSequence filterByteCapEdit(CharSequence source, int start, int end, CharSequence dest,
		int dstart, int dend)
	{
		return editExceedsByteCap(source, start, end, dest, dstart, dend)
			? dest.subSequence(dstart, dend)
			: null;
	}

	/**
	 * Caret index for the tap-to-populate flow: at the stem-end (last dot) so the user can type a stem suffix
	 * without first skipping past the extension, clamped to the field's actual text length. The clamp is
	 * defense in depth: onFileTappedForSave populates the field only with names the byte-cap filter accepts
	 * whole, but a caret computed from the tapped name must never exceed whatever text the field actually
	 * holds after its filters ran — setSelection past the text length throws IndexOutOfBoundsException. Names
	 * without an extension, or with only a leading dot (".gitignore" — index 0), put the caret at end-of-text
	 * so typing can't corrupt the dotfile prefix. Package-private so the test class can pin the clamp.
	 *
	 * @param name            the tapped file's full name, independent of what survived the field's filters
	 * @param fieldTextLength length of the text the EditText actually holds after its filters ran
	 * @return caret index in [0, fieldTextLength], safe to pass to setSelection
	 */
	static int tapCaretIndex(String name, int fieldTextLength)
	{
		int dotIdx = name.lastIndexOf('.');
		int caret = dotIdx > 0 ? dotIdx : name.length();
		return Math.clamp(caret, 0, fieldTextLength);
	}

	/**
	 * Build the editable filename row at the bottom of the dialog (above the framework buttons). Pre-filled with
	 * `initialFilename`; the user can edit freely. Format-chip toggling syncs the extension automatically via
	 * syncFilenameExtension. Sanitisation (no path separators, no traversal segments) runs on the Save-here
	 * positive-button handler, not here, so the field itself accepts any IME input.
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
		// Suppress autocorrect / autosuggest — those can silently insert spaces or rewrite typed characters
		// into a different filename than the user sees. The IME action becomes DONE (collapses the keyboard)
		// rather than a newline, since setSingleLine already blocks newlines.
		filenameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		filenameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
		// Byte-aware input filter sharing MAX_FILENAME_UTF8_BYTES with isValidFilename: an edit that would
		// push the text over the cap is rejected whole as a true no-op (filterByteCapEdit returns the
		// untouched dest range — the keep-original idiom), never truncated into a silently-different valid
		// name and never allowed to delete a replaced selection. The two programmatic setText callers
		// installed after this point pre-check with the same seam (onFileTappedForSave toasts;
		// syncFilenameExtension skips the live swap) because a setText pass runs the filter against an empty
		// dest, where the no-op result is "" and an over-cap value would clear the field. The initial
		// pre-fill above deliberately precedes the install so an over-length rescued name stays visible and
		// shrinkable — deletions always pass the filter.
		filenameInput.setFilters(new InputFilter[] { FolderPickerDialog::filterByteCapEdit });
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

		// 10dp vertical padding for a comfortable tap target — the Samsung S25's 3x density renders this at
		// 30px, well clear of the 32px-minimum Material guideline for inline chips.
		TextView jpegChip = DialogCards.newToggleChip(ctx, "JPEG", density, 10);
		TextView pngChip = DialogCards.newToggleChip(ctx, "PNG", density, 10);
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

		chkBakeGrid = DialogCards.newMauveCheckBox(ctx, "Export Grid", initialBakeGrid);

		// Children order: [Export Grid] [JPEG] [PNG]. Export Grid leads at WRAP_CONTENT; the two format chips
		// occupy the remaining width with equal weight=1 splits.
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

	/**
	 * File-row / grid-cell tap handler — fired by FolderBrowser when the user taps an image in the browser.
	 * Populates the filename input with the tapped file's WHOLE name and parks the IME cursor at the stem-end
	 * (just before the trailing extension) so the user can fast-edit the stem without re-typing the extension. A
	 * name over the UTF-8 byte cap never populates: the field stays unchanged and the "Name is too long to
	 * reuse" toast says why — a truncated variant would silently aim Save here at a different file than the one
	 * tapped. The dialog stays open; the user still commits via the Save here button.
	 *
	 * Does NOT alter currentFormat — if the tapped file's extension implies a different format than the chip
	 * currently shows, the chip wins (the user explicitly picked the format before browsing); the
	 * normaliseExtension at confirm time will adjust the typed extension to match the format chip's encoder, so the
	 * on-disk bytes and the on-disk extension stay coherent.
	 *
	 * @param file the file the user tapped
	 */
	private void onFileTappedForSave(File file)
	{
		String name = file.getName();
		// Pre-check with the exact question setText's filter pass would ask (empty dest): an over-cap name
		// must leave the field unchanged — setText would run the byte-cap filter and clear it — and the
		// toast explains why the field did not populate.
		if (editExceedsByteCap(name, 0, name.length(), "", 0, 0))
		{
			Toast.makeText(ctx, DialogStrings.NAME_TOO_LONG_TO_REUSE, Toast.LENGTH_SHORT).show();
			return;
		}
		filenameInput.setText(name);
		// Caret at the stem-end so a user about to overwrite "IMG_0042.jpg" can immediately type a suffix like
		// "-edited". tapCaretIndex clamps against the field's actual post-filter text as defense in depth —
		// the pre-check above means the whole name normally survives setText.
		filenameInput.setSelection(tapCaretIndex(name, filenameInput.getText().length()));
	}

	/**
	 * Save here positive-button handler. Sanitises the typed filename — rejects path separators and traversal
	 * segments, toasts "Invalid filename" and keeps the dialog open on failure — then normalises the extension to
	 * match the selected format chip, fires onPicked with the SaveChoices, and dismisses. Sets `decided[0] = true`
	 * AFTER onPicked.accept returns so a thrown callback leaves decided=false and the subsequent OnDismiss fires
	 * the cancel signal that the SaveController contract expects.
	 *
	 * A named method so show()'s OnShow lambda stays a single-line method-reference adapter (3-line lambda cap).
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
		// 6dp corners give the chips a softer pill shape that visually pairs with the dialog's rounded
		// AlertDialog frame.
		DialogCards.styleChipPair(jpeg, png, currentFormat == Format.JPEG, density, 6);
	}

	/**
	 * Swap the filename's extension to match the current format chip (.jpg ↔ .png) without disturbing the
	 * user-typed stem. Called from each format chip's OnClickListener so toggling JPEG → PNG on "vacation.jpg"
	 * produces "vacation.png" instead of a mismatched filename. Cursor position is clamped to the new length to
	 * keep the IME caret in roughly the same logical place when the user was mid-edit. The live swap is skipped
	 * when the format-aligned name would exceed the byte cap (an at-cap stem gaining ".jpg") — setText would run
	 * the byte-cap filter against an empty dest and clear the field; Save here still aligns the extension at
	 * confirm time inside the cap's extension-swap headroom.
	 */
	private void syncFilenameExtension()
	{
		String typedText = filenameInput.getText().toString();
		String newName = normaliseExtension(typedText, currentFormat);
		if (!typedText.equals(newName) && !editExceedsByteCap(newName, 0, newName.length(), "", 0, 0))
		{
			int cursor = filenameInput.getSelectionStart();
			filenameInput.setText(newName);
			filenameInput.setSelection(Math.clamp(cursor, 0, newName.length()));
		}
	}
}
