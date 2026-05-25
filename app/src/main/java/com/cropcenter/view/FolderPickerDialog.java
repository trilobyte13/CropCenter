package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.exifinterface.media.ExifInterface;

import com.cropcenter.R;
import com.cropcenter.model.Format;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.DpToPx;
import com.cropcenter.util.ThemeColors;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Merged in-app save dialog — combines what used to be SaveDialog (format chips + Export Grid checkbox) with the
 * folder navigator and file thumbnail display into a single chooser. Used by the Save flow as a workaround for
 * Samsung's One UI ACTION_CREATE_DOCUMENT picker, which hides folder listings in internal-storage views.
 *
 * Layout top-to-bottom: a title block (bold folder name + clickable-breadcrumb header — current segment
 * highlighted, no "Up" affordance, user navigates up via breadcrumb taps — plus a grid/list toggle on the
 * right of the title row), then a vertically-scrolling content area listing all folders first then all files
 * (no section headers), then a format / Export-Grid options row (JPEG / PNG chip toggle and the bake-grid
 * checkbox), then an editable "Save as" filename row. Framework Save here / Cancel buttons sit below.
 * Files render either as a 3-column grid of square thumbnails OR as single-column list rows (filename +
 * 40dp thumbnail) depending on the toggle; toggle state persists across launches via SharedPreferences.
 * Thumbnails apply EXIF orientation and decode async via a generation-counter-cancelled executor.
 *
 * Transient-dialog tracking uses host.setActiveTransientDialog (not registerTransientDialog) plus a
 * hostDismissCallback because the dialog installs its own composite OnDismissListener.
 */
public final class FolderPickerDialog
{
	/**
	 * Result bundle returned from the picker on "Save here". Null is passed instead of a SaveChoices
	 * instance on Cancel / back / external dismiss.
	 *
	 * @param folder   target directory the user picked
	 * @param filename target filename the user picked, normalised by the picker so the trailing
	 *                 extension matches `format` ("photo.heic" with format=PNG normalises to
	 *                 "photo.png"). Sanitised: no path separators, no ".." segments
	 * @param format   JPEG or PNG output format; drives both the encoded bytes and the filename's
	 *                 extension after normalisation
	 * @param bakeGrid true to render the grid overlay into the saved image
	 */
	public record SaveChoices(File folder, String filename, Format format, boolean bakeGrid) {}

	private static final String TAG = "FolderPickerDialog";

	private static final String BREADCRUMB_SEPARATOR = " › ";
	private static final String KEY_GRID_MODE = "grid_mode";
	private static final String PREFS_NAME = "cropcenter_picker_view";
	private static final String ROOT_LABEL = "Internal storage";
	// UI input throttle, UTF-16 chars. Coarse cap to prevent copy-paste of multi-MB names into the
	// EditText; the authoritative filesystem-compatibility check is MAX_FILENAME_UTF8_BYTES below.
	// A 200-char string of single-byte ASCII fits easily under 255 bytes; a 200-char string of
	// 4-byte emoji codepoints would be ~800 bytes — that's caught by the byte-length check in
	// isValidFilename, not here.
	private static final int FILENAME_MAX_LENGTH = 200;
	// Filesystem filename-length cap, in UTF-8 bytes. Common Linux / Android filesystems (ext4, F2FS,
	// FAT32 LFN) cap individual filename components at 255 bytes. We use 250 to leave headroom for
	// the format-extension the caller may append after isValidFilename runs (`.jpg` / `.png` / `.jpeg`
	// — longest 5 bytes), so a typed-name validation can be done before extension normalisation and
	// the result still fits after the swap.
	private static final int MAX_FILENAME_UTF8_BYTES = 250;
	private static final int MAX_THUMBNAILS = 60;
	private static final int THUMBNAIL_GRID_COLUMNS = 3;

	private final AtomicInteger thumbnailGeneration = new AtomicInteger(0);
	private final Consumer<SaveChoices> onPicked;
	private final Context ctx;
	// Use the Android-native DialogInterface.OnDismissListener (instead of Consumer<DialogInterface>)
	// so the type matches SettingsDialog's equivalent callback shape — the codebase chose the Android
	// idiom for that role and this file's prior Consumer variant was an outlier.
	private final DialogInterface.OnDismissListener hostDismissCallback;
	private final File rootDir;
	private final Handler uiHandler = new Handler(Looper.getMainLooper());
	private final String initialFilename;
	private final boolean initialBakeGrid;
	private AppCompatImageView viewModeToggle;
	private CheckBox chkBakeGrid;
	private EditText filenameInput;
	private ExecutorService thumbnailExecutor;
	private File current;
	private Format currentFormat;
	private LinearLayout contentList;
	private TextView breadcrumbLabel;
	private TextView titleLabel;
	private boolean gridMode;
	private float density;
	private int folderRowRippleResId;

	/**
	 * Construct the merged-save dialog. Callers invoke show() to actually display it.
	 *
	 * @param ctx                 Context for inflation
	 * @param startDir            initial folder to display; clamped to rootDir if outside, null lands at rootDir
	 * @param initialFilename     initial filename to pre-populate the editable filename field with (e.g. the
	 *                            source's stem + extension); the user can edit before tapping Save here
	 * @param initialFormat       initial Format selection (JPEG/PNG); the user can toggle in-dialog
	 * @param initialBakeGrid     initial Export Grid checkbox state
	 * @param onPicked            called with the SaveChoices on Save here, or null on Cancel / external dismiss
	 * @param hostDismissCallback host's transient-dialog tracking cleanup
	 */
	public FolderPickerDialog(Context ctx, File startDir, String initialFilename, Format initialFormat,
		boolean initialBakeGrid, Consumer<SaveChoices> onPicked,
		DialogInterface.OnDismissListener hostDismissCallback)
	{
		this.ctx = ctx;
		this.onPicked = onPicked;
		this.hostDismissCallback = hostDismissCallback;
		this.rootDir = Environment.getExternalStorageDirectory();
		this.current = (startDir != null && isInsideRoot(startDir)) ? startDir : rootDir;
		this.currentFormat = initialFormat != null ? initialFormat : Format.JPEG;
		this.initialFilename = initialFilename != null ? initialFilename : "crop.jpg";
		this.initialBakeGrid = initialBakeGrid;
		this.gridMode = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			.getBoolean(KEY_GRID_MODE, true);
	}

	/**
	 * Validate a user-typed filename for the in-app save dialog. Rejects empty input, traversal
	 * segments (".", ".."), any path separator ("/", "\"), and names whose UTF-8 byte length exceeds
	 * MAX_FILENAME_UTF8_BYTES (the filesystem-compatibility cap — 200 UTF-16 chars of CJK / emoji can
	 * easily exceed the common 255-byte filesystem-component limit, which the LengthFilter doesn't
	 * catch). Public so SaveController (in a different package) can route its merged-save and rename
	 * validators through this single chokepoint — and so the test class can pin the validation
	 * contract without spinning up the dialog. A false return surfaces "Invalid filename" via Toast
	 * and keeps the dialog open.
	 *
	 * @param typed user-typed filename, ALREADY trimmed by the caller. Untrimmed " " would pass the
	 *              isEmpty check but produce a useless filename — trim at the call site, not here
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
		// UTF-8 byte-length cap. 200 chars of 4-byte emoji = 800 bytes — too long for ext4 / FAT32
		// even though LengthFilter accepted it. Encoding here is unavoidable on the UI thread because
		// String.length() (UTF-16 char count) and the on-disk byte cost diverge for any non-ASCII
		// input.
		return typed.getBytes(StandardCharsets.UTF_8).length <= MAX_FILENAME_UTF8_BYTES;
	}

	/**
	 * Strip any trailing extension from `name` and append `format.extension()`. Used at "Save here" to
	 * normalise the typed filename's extension against the selected format chip — same rule
	 * syncFilenameExtension applies live during format-chip toggles, applied one more time at confirm
	 * so a user who typed a mismatched extension AFTER the last toggle gets the format-aligned name.
	 * Names with no dot or with a leading-dot stem (".gitignore" — dot at index 0) get the extension
	 * appended verbatim without stripping. Public so SaveController.showInAppRenameDialog can apply the
	 * same normalisation to the in-app Rename branch's typed-name path (gallery / file-manager type
	 * inference depends on the on-disk extension agreeing with the encoded bytes).
	 *
	 * @param name   user-typed filename (already trimmed and validated for path-separator safety)
	 * @param format format whose extension to enforce
	 * @return the filename with the trailing extension swapped (or appended) to match format
	 */
	public static String normaliseExtension(String name, Format format)
	{
		return Format.stripExtension(name) + format.extension();
	}

	/**
	 * Build and show the merged save dialog. Returns the AlertDialog so the caller can pass it to
	 * SaveHost.setActiveTransientDialog (NOT registerTransientDialog — the latter would overwrite the
	 * composite OnDismissListener installed here).
	 *
	 * @return the dialog after .show() has been called
	 */
	public AlertDialog show()
	{
		density = ctx.getResources().getDisplayMetrics().density;
		int padInner = DpToPx.toPx(12, density);
		int padSmall = DpToPx.toPx(4, density);
		TypedValue rippleAttr = new TypedValue();
		ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, rippleAttr, true);
		this.folderRowRippleResId = rippleAttr.resourceId;

		LinearLayout content = new LinearLayout(ctx);
		content.setOrientation(LinearLayout.VERTICAL);

		// Single SURFACE0-backed DialogCards panel holding every visible element — title block on
		// top, thumbnail scroller in the middle (weight=1 so it stretches to consume the dialog's
		// fixed body height), then the save options + filename rows. All labels, icons, and the
		// scrolling browsing area sit inside the inlaid darker fill; no un-panelled region remains
		// in the dialog body.
		contentList = new LinearLayout(ctx);
		contentList.setOrientation(LinearLayout.VERTICAL);
		contentList.setPadding(0, padSmall, 0, padInner);

		// Scroller height is WRAP_CONTENT but clamped via the anonymous subclass below to a fraction
		// of screen height. This is the "natural variance" model: short list-mode contents make a
		// short dialog, denser thumbnail grids grow the dialog, BUT once the contents would push the
		// options row + filename row + Save/Cancel buttons off-screen the scroller caps and starts
		// scrolling instead. Without the clamp the panel's WRAP_CONTENT layout would let a long
		// folder of images grow the scroller without bound, hiding the controls below it. The
		// reservedPx budget approximates the non-scroller chrome:
		//   ~80dp status/nav bar (Android 15 edge-to-edge varies by device)
		//   ~24dp AlertDialog top frame
		//   ~60dp AlertDialog button row
		//   ~24dp panel outer margin (12dp top + 12dp bottom)
		//   ~20dp DialogCards card padding (10dp top + 10dp bottom)
		//   ~60dp title block (folder name 32dp + breadcrumb 24dp + 4dp gap)
		//   ~60dp options row (padInner 12 + chip 36 + padInner 12)
		//   ~60dp filename row (padInner 12 + EditText 36 + padInner 12)
		// Total ≈ 388dp; rounding up to 440dp leaves a 50dp buffer for device variance (notches,
		// gesture-nav handle, taller status bars). Prior 300dp budget was ~90dp too small — the Save
		// here / Cancel buttons would clip off the bottom edge on a full-grid folder.
		int reservedPx = DpToPx.toPx(440, density);
		int screenHeightPx = ctx.getResources().getDisplayMetrics().heightPixels;
		final int scrollerMaxPx = Math.max(DpToPx.toPx(240, density), screenHeightPx - reservedPx);
		ScrollView scroller = new ScrollView(ctx)
		{
			@Override
			protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
			{
				int heightMode = MeasureSpec.getMode(heightMeasureSpec);
				int heightSize = MeasureSpec.getSize(heightMeasureSpec);
				int targetMax = (heightMode == MeasureSpec.UNSPECIFIED)
					? scrollerMaxPx
					: Math.min(heightSize, scrollerMaxPx);
				super.onMeasure(widthMeasureSpec,
					MeasureSpec.makeMeasureSpec(targetMax, MeasureSpec.AT_MOST));
			}
		};
		scroller.addView(contentList);
		LinearLayout.LayoutParams scrollerLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

		LinearLayout panel = DialogCards.newCard(ctx, density);
		panel.addView(buildTitleBlock(padSmall));
		panel.addView(scroller, scrollerLp);
		panel.addView(buildOptionsRow(padInner));
		panel.addView(buildFilenameRow(padInner));

		// Symmetric gutters between the inlaid panel and the AlertDialog frame: 12dp on every side
		// so the visible "border" looks identical top/bottom/left/right. The dialog frame itself
		// adds a small native inset on top of this; padding here only governs the gap between the
		// dialog content area and the SURFACE0-backed panel edge.
		int cardMargin = DpToPx.toPx(12, density);
		LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		panelLp.setMargins(cardMargin, cardMargin, cardMargin, cardMargin);
		content.addView(panel, panelLp);

		thumbnailExecutor = Executors.newSingleThreadExecutor(task ->
		{
			Thread thread = new Thread(task, "FolderPicker-thumbnails");
			thread.setDaemon(true);
			return thread;
		});

		boolean[] decided = { false };
		// Install the positive button with a null handler so AlertDialog doesn't auto-dismiss after the
		// click — we override the button's OnClickListener in OnShowListener below so an "Invalid
		// filename" validation failure can toast and keep the dialog open rather than closing the whole
		// flow on a correctable typo. The negative button keeps the default null-handler dismiss
		// behaviour (Cancel really should close).
		AlertDialog dialog = new AlertDialog.Builder(ctx)
			.setView(content)
			.setPositiveButton("Save here", null)
			.setNegativeButton(DialogStrings.CANCEL, null)
			.create();
		dialog.setOnShowListener(shown ->
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
			{
				// Sanitise the typed filename — reject path separators and traversal segments. The
				// caller (SaveController.onMergedSaveConfirmed) re-sanitises defensively, but the
				// user-facing path fails loudly with a Toast here so the user can correct the typo
				// rather than the save silently aborting.
				String typed = filenameInput.getText().toString().trim();
				if (!isValidFilename(typed))
				{
					Toast.makeText(ctx, DialogStrings.INVALID_FILENAME, Toast.LENGTH_SHORT).show();
					return;
				}
				// Normalise the trailing extension to match the selected format. The format chip drives
				// the encoded bytes; if the user typed a different extension (or none) after toggling,
				// saving "photo.heic" with PNG bytes would mislead gallery / file-manager type
				// inference. Strip any existing extension and append currentFormat.extension().
				String normalised = normaliseExtension(typed, currentFormat);
				// Set `decided[0]` AFTER onPicked.accept returns — if the callback throws, decided
				// stays false so a subsequent dialog dismiss (back-press, OnCancel) still fires the
				// onPicked(null) "user cancelled" signal that the SaveController contract expects.
				onPicked.accept(new SaveChoices(current, normalised, currentFormat,
					chkBakeGrid.isChecked()));
				decided[0] = true;
				dialog.dismiss();
			}));
		dialog.setOnDismissListener(dismissed ->
		{
			// Param is `dismissed` (not `dialog`) to avoid shadowing the outer `AlertDialog dialog`
			// per CLAUDE.md's collision-avoidance rule. The DialogInterface passed in is the same
			// underlying instance as the outer `dialog`, just at the narrower interface type.
			//
			// Bump the generation FIRST so any decode task that's already finished and posted a UI
			// runnable to uiHandler sees the mismatch and recycles its bitmap via the else branch
			// (instead of calling setImageBitmap on a now-detached ImageView and pinning the bitmap
			// until GC). shutdownNow interrupts pending decodes but can't unposted-runnable a task that
			// already crossed the bg→UI boundary.
			thumbnailGeneration.incrementAndGet();
			thumbnailExecutor.shutdownNow();
			if (!decided[0])
			{
				onPicked.accept(null);
			}
			if (hostDismissCallback != null)
			{
				hostDismissCallback.onDismiss(dismissed);
			}
		});

		refresh();
		try
		{
			dialog.show();
		}
		catch (RuntimeException e)
		{
			// BadTokenException (config-change race between picker construction and show()) leaves the
			// OnDismissListener installed-but-never-fired, which means the thumbnailExecutor created
			// above would leak — its in-flight refresh() decode tasks would keep the worker thread
			// alive holding ImageView references through their closures. Shut it down explicitly here
			// and re-throw so the caller's try/catch surfaces the failure normally.
			thumbnailExecutor.shutdownNow();
			throw e;
		}
		return dialog;
	}

	/**
	 * Walk the path chain from rootDir to current (inclusive), one File per segment. The first element
	 * is always rootDir; if current equals rootDir, the chain has length 1. Otherwise the chain
	 * contains rootDir plus one File per "/"-separated segment of the path relative to rootDir.
	 * Package-private so the breadcrumb-rendering test can exercise the chain-building logic without
	 * spinning up an AlertDialog. buildBreadcrumb delegates to this for the segment enumeration; the
	 * spannable formatting (ClickableSpan / StyleSpan / ForegroundColorSpan) stays in the View
	 * layer's instance method where it can capture the dialog's mutable `current` field.
	 *
	 * Both paths are canonicalized before the relative-slice so a symlink in either input produces
	 * the same chain a direct path would. Without this, a current path like `/sdcard/Pictures` (the
	 * /sdcard symlink to /storage/emulated/0) under rootDir = `/storage/emulated/0` would pass
	 * isInsideRoot (which canonicalizes) but then crash here with
	 * StringIndexOutOfBoundsException because the absolute-path prefix doesn't match. The defensive
	 * startsWith check after canonicalization handles the residual case where a stale `current`
	 * from a concurrent refresh is no longer a descendant — degenerates to a root-only chain rather
	 * than throwing.
	 *
	 * @param rootDir absolute path representing the breadcrumb's leftmost ("Internal storage") segment
	 * @param current the folder currently displayed by the picker; expected to be a descendant of
	 *                rootDir (verified at navigation time by isInsideRoot)
	 * @return ordered list of File segments [rootDir, ..., current]; never empty (always contains
	 *         rootDir at minimum); collapses to [rootDir] when current is not a descendant or
	 *         either path can't be canonicalized
	 */
	static List<File> breadcrumbChain(File rootDir, File current)
	{
		List<File> chain = new ArrayList<>();
		chain.add(rootDir);
		String rootPath;
		String currentPath;
		try
		{
			rootPath = rootDir.getCanonicalPath();
			currentPath = current.getCanonicalPath();
		}
		catch (IOException ignored)
		{
			// Symlink loop / unreadable parent / hostile filesystem entry — degenerate to a
			// root-only chain rather than crashing the picker UI. The user can still navigate
			// from the root label.
			return chain;
		}
		if (currentPath.equals(rootPath))
		{
			return chain;
		}
		String prefix = rootPath + File.separator;
		if (!currentPath.startsWith(prefix))
		{
			// current is no longer a descendant of root (concurrent refresh raced this call, or
			// caller bypassed isInsideRoot). Without this guard the substring below would underflow
			// or return a path with leading separator that split into a bogus first segment.
			return chain;
		}
		String relative = currentPath.substring(prefix.length());
		File walker = rootDir;
		// Split on the platform's native File.separator. Android uses "/", JVM-on-Windows uses
		// "\" — using the platform constant keeps the helper testable on both. Pattern.quote
		// because "\" is a regex meta-character; without it the JVM throws PatternSyntaxException.
		for (String part : relative.split(Pattern.quote(File.separator)))
		{
			walker = new File(walker, part);
			chain.add(walker);
		}
		return chain;
	}

	/**
	 * Extension filter for the file-list pass in `refresh()` — used to decide which files in the picked
	 * folder are eligible for thumbnail decode + selection. Matches the same set the rest of the app
	 * decodes (JPEG / PNG via the load path, HEIC / WebP / HEIF via Android's BitmapFactory). Anything
	 * outside this set is hidden from the grid / list view. Package-private rather than private only so
	 * the test class can pin the accepted extension set directly.
	 *
	 * @param file candidate file (only the name's lowercase suffix is inspected; the File doesn't need
	 *             to exist)
	 * @return true when the filename's lowercase suffix matches an accepted extension; false otherwise
	 */
	static boolean isImageFile(File file)
	{
		String name = file.getName().toLowerCase(Locale.ROOT);
		return name.endsWith(".jpg") || name.endsWith(".jpeg")
			|| name.endsWith(".png")
			|| name.endsWith(".webp")
			|| name.endsWith(".heic") || name.endsWith(".heif");
	}

	/**
	 * Decode a thumbnail for `file` aimed at the target display size in pixels and apply EXIF orientation
	 * so a sideways-stored JPEG renders right-side-up. Tries ExifInterface.getThumbnail() first for JPEG
	 * sources (the embedded thumbnail decodes in milliseconds vs. a full-image read), then falls back to
	 * a subsampled BitmapFactory.decodeFile pass. Returns null on any IO / decoder failure.
	 *
	 * @param file       image file to thumbnail
	 * @param targetSize target display dimension in pixels
	 * @return EXIF-oriented thumbnail bitmap, or null if all decode paths failed
	 */
	private static Bitmap decodeThumbnail(File file, int targetSize)
	{
		String path = file.getAbsolutePath();
		String lower = file.getName().toLowerCase(Locale.ROOT);
		Bitmap raw = null;
		int orientation = ExifInterface.ORIENTATION_NORMAL;
		boolean isJpeg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
		if (isJpeg)
		{
			try
			{
				ExifInterface exif = new ExifInterface(path);
				orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
					ExifInterface.ORIENTATION_NORMAL);
				byte[] thumbBytes = exif.getThumbnail();
				if (thumbBytes != null)
				{
					// decodeByteArray can OOM on an adversarial JPEG with a multi-MB embedded
					// thumbnail (spec-legal for PNG eXIf which is u31-uncapped; JPEG IFD1 is
					// ~64 KB-capped but androidx.exifinterface returns the bytes verbatim with
					// no client-side upper bound). Wrap in OOM+RuntimeException catch so a
					// corrupt thumb falls through to the subsampled BitmapFactory.decodeFile
					// path rather than crashing the bg thumbnail executor.
					try
					{
						raw = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.length);
					}
					catch (RuntimeException | OutOfMemoryError e)
					{
						Log.w(TAG, "EXIF thumbnail decode failed for " + file.getName()
							+ ": " + e.getMessage());
					}
				}
			}
			catch (IOException ignored)
			{
				// EXIF read failed (corrupt header, partial file) — fall through to BitmapFactory.
			}
		}
		if (raw == null)
		{
			BitmapFactory.Options bounds = new BitmapFactory.Options();
			bounds.inJustDecodeBounds = true;
			BitmapFactory.decodeFile(path, bounds);
			if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
			{
				return null;
			}
			int sampleSize = 1;
			int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
			while (maxDim / sampleSize > targetSize * 2)
			{
				sampleSize *= 2;
			}
			BitmapFactory.Options opts = new BitmapFactory.Options();
			opts.inSampleSize = sampleSize;
			try
			{
				raw = BitmapFactory.decodeFile(path, opts);
			}
			catch (RuntimeException | OutOfMemoryError e)
			{
				Log.w(TAG, "decodeThumbnail failed for " + path + ": " + e.getMessage());
				return null;
			}
			if (raw == null)
			{
				return null;
			}
		}
		// Reject zero-dim decode (corrupt embedded thumbnail bytes that decode to a 0×0 Bitmap would
		// crash Bitmap.createBitmap inside applyOrientation, leaking `raw`).
		if (raw.getWidth() <= 0 || raw.getHeight() <= 0)
		{
			raw.recycle();
			return null;
		}
		// Identity-orientation predicate matches BitmapUtils.applyOrientation / UltraHdrCompat:
		// values ≤ 1 are upright (ExifInterface.ORIENTATION_NORMAL = 1; 0 is a malformed/missing
		// tag that all three sites also treat as identity).
		if (orientation <= ExifInterface.ORIENTATION_NORMAL)
		{
			return raw;
		}
		try
		{
			return BitmapUtils.applyOrientation(raw, orientation);
		}
		catch (RuntimeException | OutOfMemoryError e)
		{
			// applyOrientation calls Bitmap.createBitmap which can OOM on multi-MP intermediates.
			// Its Javadoc recycles `raw` only when rotation SUCCEEDS — on throw, the input survives,
			// so recycle here to avoid leaking the native pixel buffer until GC.
			Log.w(TAG, "applyOrientation failed for " + file.getName() + ": " + e.getMessage());
			raw.recycle();
			return null;
		}
	}

	/**
	 * Append a single 3-column grid row to the content list containing the next up-to-3 files starting
	 * at fromIndex. Final row pads with weighted placeholder Views so the cells size identically to a
	 * full row (otherwise 1 or 2 cells would expand to the full row width). The row spans the full
	 * content-list width (no left/right inset) so the thumbnails grow as large as the dialog allows
	 * and align with the options / filename rows above and below the scroller.
	 *
	 * @param files        file list being rendered
	 * @param fromIndex    start index within `files`; reads up to 3 sequential entries
	 * @param thumbnailGen current thumbnail generation; bg loads check this before posting
	 * @param gap          inter-cell margin in px (also used top/bottom for inter-row spacing so the
	 *                     grid view's row gap matches the list view's 4dp + 4dp internal padding)
	 */
	private void appendGridFileRow(List<File> files, int fromIndex, int thumbnailGen, int gap)
	{
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		// Visible spacing between adjacent rows: bottomMargin (gap) + topMargin (gap) = 2 * gap.
		// Inter-cell horizontal spacing is also 2 * gap (cellLp.leftMargin = 2 * gap below) so
		// vertical and horizontal gaps between thumbnails appear equal — previously horizontal was
		// half the vertical, which read as asymmetric to the eye.
		rowLp.topMargin = gap;
		rowLp.bottomMargin = gap;
		contentList.addView(row, rowLp);
		// Doubled target size now that the grid row spans the full card-content width: each of the
		// 3 columns gets ~33% of (cardWidth - 2 * gap) instead of the prior padOuter-inset width.
		int displayTarget = Math.max(1, DpToPx.toPx(220, density));
		int horizontalCellGap = gap * 2;
		for (int i = 0; i < THUMBNAIL_GRID_COLUMNS; i++)
		{
			LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0,
				LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
			if (i > 0)
			{
				cellLp.leftMargin = horizontalCellGap;
			}
			int fileIdx = fromIndex + i;
			if (fileIdx >= files.size())
			{
				View placeholder = new View(ctx);
				LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0,
					LinearLayout.LayoutParams.MATCH_PARENT, 1f);
				pLp.leftMargin = horizontalCellGap;
				row.addView(placeholder, pLp);
				continue;
			}
			File img = files.get(fileIdx);
			AppCompatImageView iv = new AppCompatImageView(ctx)
			{
				@Override
				protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
				{
					// Square cells: height tracks width so each thumbnail is 1:1 regardless of
					// dialog width. Without this, height stays at WRAP_CONTENT and the bitmap
					// renders into a zero-height row.
					super.onMeasure(widthMeasureSpec, widthMeasureSpec);
				}
			};
			iv.setBackgroundColor(ThemeColors.SURFACE0);
			iv.setScaleType(AppCompatImageView.ScaleType.CENTER_CROP);
			row.addView(iv, cellLp);
			thumbnailExecutor.submit(() ->
			{
				if (thumbnailGeneration.get() != thumbnailGen)
				{
					return;
				}
				Bitmap bmp = decodeThumbnail(img, displayTarget);
				if (bmp == null)
				{
					return;
				}
				uiHandler.post(() ->
				{
					if (thumbnailGeneration.get() == thumbnailGen)
					{
						iv.setImageBitmap(bmp);
					}
					else
					{
						// Gen moved on between decode and UI post (rapid navigation or
						// dismiss). Recycle to free the native pixel buffer; otherwise
						// it'd wait on GC and accumulate fast at MP-class thumbnails.
						bmp.recycle();
					}
				});
			});
		}
	}

	/**
	 * Build the breadcrumb spannable. Every segment is bold; intermediate segments are clickable mauve
	 * ClickableSpans that navigate to that level on tap; the current segment is TEXT-colored and NOT
	 * clickable. The chevron separator (U+203A) renders in the muted SUBTEXT0 the TextView is set to.
	 *
	 * @return spannable for breadcrumbLabel.setText; LinkMovementMethod handles span taps
	 */
	private SpannableStringBuilder buildBreadcrumb()
	{
		SpannableStringBuilder sb = new SpannableStringBuilder();
		List<File> chain = breadcrumbChain(rootDir, current);
		for (int i = 0; i < chain.size(); i++)
		{
			File segment = chain.get(i);
			boolean isCurrent = (i == chain.size() - 1);
			if (i > 0)
			{
				sb.append(BREADCRUMB_SEPARATOR);
			}
			int start = sb.length();
			sb.append(i == 0 ? ROOT_LABEL : segment.getName());
			int end = sb.length();
			sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			if (isCurrent)
			{
				sb.setSpan(new ForegroundColorSpan(ThemeColors.TEXT), start, end,
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
			else
			{
				sb.setSpan(makeBreadcrumbSpan(segment), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
		}
		return sb;
	}

	/**
	 * Build the editable filename row at the bottom of the dialog (above the framework buttons).
	 * Pre-filled with `initialFilename`; the user can edit freely. Format-chip toggling syncs the
	 * extension automatically via syncFilenameExtension. Sanitisation (no path separators, no
	 * traversal segments) runs on the Save-here positive-button handler, not here, so the field
	 * itself accepts any IME input. Horizontal padding lives on the enclosing DialogCards card; this
	 * row only supplies the vertical breathing room between options and filename.
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
		// Suppress autocorrect / autosuggest — those can silently insert spaces or rewrite typed
		// characters into a different filename than the user sees. The IME action becomes DONE
		// (collapses the keyboard) rather than a newline, since setSingleLine already blocks newlines.
		filenameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		filenameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
		// Cap input length to keep the typed name comfortably under common filesystem 255-byte limits
		// (allowing for multi-byte UTF-8 characters in the name).
		filenameInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(FILENAME_MAX_LENGTH) });
		LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0,
			LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
		inputLp.leftMargin = DpToPx.toPx(8, density);
		row.addView(filenameInput, inputLp);
		return row;
	}

	/**
	 * Build the format-chip + Export-Grid checkbox row that sits at the top of the save-options card.
	 * Horizontal padding lives on the enclosing DialogCards card; this row only supplies the vertical
	 * breathing room between the card edge and the format chips.
	 *
	 * @param padInner vertical padding inside the row
	 * @return single-row LinearLayout containing the JPEG / PNG chips and the Export Grid checkbox
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

		// Children order: [Export Grid] [JPEG] [PNG]. Export Grid leads the row at WRAP_CONTENT;
		// the two format chips occupy the remaining width with equal weight=1 splits so their
		// pill backgrounds visually balance regardless of dialog width.
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
	 * Build the title block — bold folder name + clickable breadcrumb on the left, grid/list view-mode
	 * toggle icon on the right. Horizontal padding lives on the enclosing DialogCards card; this block
	 * only supplies the small vertical gap between the title row and the breadcrumb below it.
	 *
	 * @param padSmall vertical gap between the title row and the breadcrumb label
	 * @return assembled title block ready to be added as the sole child of the title DialogCards card
	 */
	private LinearLayout buildTitleBlock(int padSmall)
	{
		LinearLayout titleBlock = new LinearLayout(ctx);
		titleBlock.setOrientation(LinearLayout.VERTICAL);

		LinearLayout titleRow = new LinearLayout(ctx);
		titleRow.setOrientation(LinearLayout.HORIZONTAL);
		titleRow.setGravity(Gravity.CENTER_VERTICAL);
		titleLabel = new TextView(ctx);
		titleLabel.setTextSize(20);
		titleLabel.setTypeface(titleLabel.getTypeface(), Typeface.BOLD);
		titleLabel.setSingleLine(true);
		titleLabel.setEllipsize(TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
			LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
		titleRow.addView(titleLabel, titleLp);

		// Grid/list toggle. Shows the icon for the OTHER mode — clicking switches into that mode.
		// Tap feedback via selectableItemBackground ripple; persists choice to SharedPreferences.
		viewModeToggle = new AppCompatImageView(ctx);
		viewModeToggle.setImageResource(gridMode ? R.drawable.ic_view_list : R.drawable.ic_view_grid);
		viewModeToggle.setBackgroundResource(folderRowRippleResId);
		int toggleSize = DpToPx.toPx(40, density);
		int togglePad = DpToPx.toPx(8, density);
		viewModeToggle.setPadding(togglePad, togglePad, togglePad, togglePad);
		viewModeToggle.setOnClickListener(view ->
		{
			gridMode = !gridMode;
			viewModeToggle.setImageResource(gridMode ? R.drawable.ic_view_list : R.drawable.ic_view_grid);
			ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putBoolean(KEY_GRID_MODE, gridMode).apply();
			refresh();
		});
		titleRow.addView(viewModeToggle, new LinearLayout.LayoutParams(toggleSize, toggleSize));
		titleBlock.addView(titleRow);

		breadcrumbLabel = new TextView(ctx);
		breadcrumbLabel.setTextSize(13);
		breadcrumbLabel.setTextColor(ThemeColors.SUBTEXT0);
		breadcrumbLabel.setSingleLine(true);
		// Ellipsize at START so a deep path (Internal storage › DCIM › Camera › … › Restaurants) keeps the
		// CURRENT segment — which sits at the end — visible, and clips the root prefix instead. Without
		// this, a long breadcrumb hides the current segment the user just navigated into; setSingleLine
		// alone just clips the right edge.
		breadcrumbLabel.setEllipsize(TextUtils.TruncateAt.START);
		breadcrumbLabel.setPadding(0, padSmall, 0, 0);
		breadcrumbLabel.setMovementMethod(LinkMovementMethod.getInstance());
		titleBlock.addView(breadcrumbLabel);
		return titleBlock;
	}

	private boolean isInsideRoot(File candidate)
	{
		try
		{
			String candidatePath = candidate.getCanonicalPath();
			String rootPath = rootDir.getCanonicalPath();
			// Exact match OR candidate sits at a true descendant path. The naive `startsWith(rootPath)`
			// would accept sibling paths sharing the same textual prefix — e.g. with rootPath
			// "/storage/emulated/0", a candidate "/storage/emulated/0abc" textually startsWith rootPath
			// but is a completely separate location. Requiring the path-separator boundary after the
			// prefix guarantees the candidate is genuinely nested under rootDir rather than a
			// coincidentally-named sibling.
			return candidatePath.equals(rootPath)
				|| candidatePath.startsWith(rootPath + File.separator);
		}
		catch (IOException ignored)
		{
			// Canonicalization fails on symlink loops or unreadable parents — reject for safety.
			return false;
		}
	}

	private ClickableSpan makeBreadcrumbSpan(File target)
	{
		return new ClickableSpan()
		{
			@Override
			public void onClick(View widget)
			{
				if (isInsideRoot(target))
				{
					current = target;
					refresh();
				}
			}

			@Override
			public void updateDrawState(TextPaint ds)
			{
				super.updateDrawState(ds);
				ds.setColor(ThemeColors.MAUVE);
				ds.setUnderlineText(false);
			}
		};
	}

	private View makeFileListRow(File file, int thumbnailGen, int padInner, int padSmall)
	{
		int thumbSize = DpToPx.toPx(40, density);
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		// No setMinimumHeight: previously the 56dp minimum added ~8dp of slack above and below the
		// 40dp thumbnail (CENTER_VERTICAL distributed half + half), making list-view thumb-to-thumb
		// distance read as ~16dp while grid-view thumb-to-thumb is ~8dp. Letting the row WRAP its
		// natural content height + setPadding equalises the two views' visible row density.
		// No horizontal padding — the row spans the full card-content width so list and grid views
		// share the same outer-bound width (matches the options / filename rows above and below).
		row.setPadding(0, padSmall, 0, padSmall);
		AppCompatImageView thumb = new AppCompatImageView(ctx);
		thumb.setBackgroundColor(ThemeColors.SURFACE0);
		thumb.setScaleType(AppCompatImageView.ScaleType.CENTER_CROP);
		row.addView(thumb, new LinearLayout.LayoutParams(thumbSize, thumbSize));
		TextView label = new TextView(ctx);
		label.setText(file.getName());
		label.setTextSize(15);
		label.setTextColor(ThemeColors.TEXT);
		label.setSingleLine(true);
		label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
		label.setPadding(padInner, 0, 0, 0);
		row.addView(label, new LinearLayout.LayoutParams(0,
			LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		thumbnailExecutor.submit(() ->
		{
			if (thumbnailGeneration.get() != thumbnailGen)
			{
				return;
			}
			Bitmap bmp = decodeThumbnail(file, thumbSize);
			if (bmp == null)
			{
				return;
			}
			uiHandler.post(() ->
			{
				if (thumbnailGeneration.get() == thumbnailGen)
				{
					thumb.setImageBitmap(bmp);
				}
				else
				{
					// Stale post — generation moved on; recycle to free native pixel buffer.
					bmp.recycle();
				}
			});
		});
		return row;
	}

	private LinearLayout makeFolderRow(File entry, int rowMinHeight, int iconSize, int padInner,
		int padSmall)
	{
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setMinimumHeight(rowMinHeight);
		// No horizontal padding — folder rows align with the file list / grid rows and the
		// options / filename rows below, spanning the full card-content width.
		row.setPadding(0, 0, 0, 0);
		row.setBackgroundResource(folderRowRippleResId);
		AppCompatImageView icon = new AppCompatImageView(ctx);
		icon.setImageResource(R.drawable.ic_folder);
		row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
		TextView label = new TextView(ctx);
		label.setTextSize(16);
		label.setTextColor(ThemeColors.TEXT);
		label.setPadding(padInner + padSmall, 0, 0, 0);
		label.setText(entry.getName());
		row.addView(label, new LinearLayout.LayoutParams(0,
			LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		row.setOnClickListener(view ->
		{
			current = entry;
			refresh();
		});
		return row;
	}

	private TextView makeFormatChip(String text)
	{
		TextView btn = new TextView(ctx);
		btn.setText(text);
		btn.setTextSize(13);
		btn.setGravity(Gravity.CENTER);
		btn.setTypeface(btn.getTypeface(), Typeface.BOLD);
		// 10dp vertical padding for a comfortable tap target — the Samsung S25's 3x density renders
		// this at 30px, well clear of the 32px-minimum Material guideline for inline chips.
		btn.setPadding(0, DpToPx.toPx(10, density), 0, DpToPx.toPx(10, density));
		btn.setSingleLine(true);
		return btn;
	}

	private void refresh()
	{
		titleLabel.setText(current.getAbsolutePath().equals(rootDir.getAbsolutePath())
			? ROOT_LABEL : current.getName());
		breadcrumbLabel.setText(buildBreadcrumb());

		int gen = thumbnailGeneration.incrementAndGet();
		contentList.removeAllViews();
		int rowMinHeight = DpToPx.toPx(48, density);
		int iconSize = DpToPx.toPx(24, density);
		int padInner = DpToPx.toPx(12, density);
		int padSmall = DpToPx.toPx(4, density);
		int gap = DpToPx.toPx(4, density);

		List<File> folders = new ArrayList<>();
		List<File> files = new ArrayList<>();
		File[] kids = current.listFiles();
		if (kids != null)
		{
			Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
			for (File f : kids)
			{
				if (f.isDirectory())
				{
					if (!f.getName().startsWith("."))
					{
						folders.add(f);
					}
				}
				else if (isImageFile(f))
				{
					files.add(f);
				}
			}
		}
		for (File folder : folders)
		{
			contentList.addView(makeFolderRow(folder, rowMinHeight, iconSize, padInner, padSmall));
		}
		int fileLimit = Math.min(files.size(), MAX_THUMBNAILS);
		List<File> shownFiles = files.subList(0, fileLimit);
		if (gridMode)
		{
			for (int i = 0; i < shownFiles.size(); i += THUMBNAIL_GRID_COLUMNS)
			{
				appendGridFileRow(shownFiles, i, gen, gap);
			}
		}
		else
		{
			for (File file : shownFiles)
			{
				contentList.addView(makeFileListRow(file, gen, padInner, padSmall));
			}
		}
	}

	private void styleFormatChips(TextView jpeg, TextView png)
	{
		boolean jpegSelected = currentFormat == Format.JPEG;
		// 6dp corners give the chips a softer pill shape that visually pairs with the dialog's
		// rounded AlertDialog frame; the prior 4dp read as a hard rectangle.
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
	 * Swap the filename's extension to match the current format chip (.jpg ↔ .png) without disturbing
	 * the user-typed stem. Called from each format chip's OnClickListener so toggling JPEG → PNG on
	 * "vacation.jpg" produces "vacation.png" instead of a mismatched filename. Cursor position is
	 * clamped to the new length to keep the IME caret in roughly the same logical place when the user
	 * was mid-edit.
	 */
	private void syncFilenameExtension()
	{
		// Local name avoids shadowing the `private File current` field that holds the picker's
		// current directory.
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
