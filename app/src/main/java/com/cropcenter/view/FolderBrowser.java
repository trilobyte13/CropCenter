package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.exifinterface.media.ExifInterface;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cropcenter.R;
import com.cropcenter.metadata.PngMetadataExtractor;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.DpToPx;
import com.cropcenter.util.ThemeColors;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Shared filesystem-browser UI used by both FolderPickerDialog (save flow) and OpenPickerDialog
 * (load flow). Owns the inlaid panel (title block + breadcrumb + grid/list view-mode toggle +
 * sort-direction toggle + a RecyclerView-backed content list with a draggable fast-scroll thumb),
 * the navigation state (rootDir + current folder), the bg work executor (folder enumeration +
 * thumbnail decodes), and SharedPreference-backed UI state (grid/list view mode, sort
 * direction).
 *
 * The RecyclerView recycles off-screen cells back to a pool, so even a 50,000-photo Camera folder
 * only materialises ~visible-window cells (~10-30) at a time. Eager-inflation of all rows is the
 * previous architecture that OOM-crashed at large folder counts.
 *
 * Folder enumeration (listFiles + sort) runs on the bg executor, not the UI thread — for the
 * Camera album (50k+ entries) the sort alone was ~200ms and listFiles another ~500ms, freezing
 * the dialog while it loaded. The refresh() flow now clears the visible list immediately,
 * posts an enumeration task to the bg executor (its queue is cleared first so a rapid folder
 * tap pre-empts whatever decodes were pending for the previous folder), then re-populates the
 * adapter on the UI thread when the bg result arrives. A generation counter discards stale
 * results so a user who taps folder A then folder B before A's enumeration finishes only sees
 * B's content.
 *
 * Sort direction (newest first vs oldest first) applies to FILES only — folders are always
 * alphabetical regardless of sort mode, matching the convention of file managers where
 * navigation targets are name-ordered but content can be re-sorted.
 *
 * Fast scroller: BrowserFastScroller is a custom overlay View on the right edge of the
 * RecyclerView (FrameLayout z-order) with an always-visible draggable thumb, 32dp minimum
 * height, and position-based scroll math (findFirstVisibleItemPosition +
 * scrollToPositionWithOffset, both O(1) on GridLayoutManager). Replaces AndroidX's built-in
 * initFastScroller because the built-in thumb has no usable minimum height (collapses to a
 * sliver on 50k folders) and its drag math is O(N) on GridLayoutManager.
 *
 * File-tap callback: both flows wire taps via `onFileTapped`. The Open flow passes a callback
 * that selects-and-dismisses; the Save flow passes a callback that populates the filename input
 * (dialog stays open). The only difference between the two flows is what each callback does
 * with the picked File.
 *
 * buildPanel sizes the whole card to a fixed height (screenHeight − `CARD_RESERVED_DP`, floored at
 * 240dp), measured EXACTLY and clamped to the dialog's available height, and makes the file-list
 * container a weight=1 / height=0 flex child, so any footer rows the caller appends (Save's options +
 * filename rows) keep their measured height while the list fills the remainder and scrolls. EXACTLY
 * (not wrap-to-content) because the adapter populates asynchronously after show() — a wrap panel
 * would measure an empty list and open squished. `CARD_RESERVED_DP` is shared by all three pickers
 * (Load / Graft / Save) so they open at the same maximum size; it's headroom for the dialog frame +
 * buttons + system bars below the card, not the footer rows themselves (those live inside the sized
 * card and are measured normally).
 *
 * Lifecycle: caller calls buildPanel() to assemble the view tree, adds it to its dialog content,
 * then funnels through attachToDialog(...) — that single method installs the composite dismiss
 * listener (which calls shutdown() so the executor's worker thread doesn't outlive the dialog),
 * calls refresh() to seed the initial folder display, and calls dialog.show() inside a try/catch
 * that shuts the executor down on BadTokenException. Callers must NOT call refresh() / show() /
 * shutdown() manually — attachToDialog owns the whole refresh + show + dismiss + shutdown chain.
 */
final class FolderBrowser
{
	/**
	 * One row in the adapter's data model — either a folder (full-width navigation row) or a
	 * file (cell, full-width in list mode, third-width in grid mode). The adapter inspects which
	 * record type each item is and dispatches to the matching ViewHolder. Sealed so the
	 * switch / pattern-match inside the adapter is exhaustive at compile time.
	 */
	private sealed interface BrowserItem permits FolderRow, FileRow {}

	private record FolderRow(File folder) implements BrowserItem {}

	private record FileRow(File file) implements BrowserItem {}

	/**
	 * Bg-enumeration output bundle. Carries the assembled BrowserItem list together with the
	 * count of FolderRow entries at the head so BrowserAdapter.setItems can install both in one
	 * lockstep call — without the count, BrowserSpanSizeLookup couldn't tell where folders end
	 * and files begin without a fresh O(N) walk.
	 *
	 * @param items       ordered list (folders first, then files in sort-mode order)
	 * @param folderCount number of FolderRow entries at the head of items; index of the first
	 *                    FileRow when items contains both, or items.size() when files-only
	 */
	private record EnumerationResult(List<BrowserItem> items, int folderCount) {}

	/**
	 * Image file plus its lowercase-normalised name, captured at enumeration time so the sort
	 * comparator can read a Java String field instead of re-extracting and re-lowercasing per
	 * comparison. On a 50k-item Camera folder this drops sort cost from O(N log N) × 2
	 * getName + toLowerCase calls (~hundreds of ms for 55k items) to a pure String compare.
	 *
	 * @param file     image file as returned by File.listFiles + isImageFile filter
	 * @param sortKey  file.getName().toLowerCase(Locale.ROOT) snapshot — used as the sort key
	 *                 so files prefixed with date strings (20240101_*, PXL_20240101_*,
	 *                 IMG-20240101-WA0001.jpg, etc.) sort chronologically. Switching from
	 *                 lastModified to filename-based sort fixes the "restored years cluster at
	 *                 top because copy reset their mtime" issue users hit with restored
	 *                 Camera albums.
	 */
	private record FileEntry(File file, String sortKey) {}

	/**
	 * Cached enumeration of a single folder. Held in cachedSnapshot so a sort-mode toggle re-uses
	 * the already-paid listFiles + isDirectory syscalls instead of paying them again on a 50k-
	 * photo folder. Folders are pre-sorted alphabetically at enumeration time (cheap — typically
	 * a handful of subdirectories); files are kept in listFiles order with their lowercase name
	 * cached as FileEntry.sortKey so sortAndAssemble can apply the requested SortMode in
	 * O(N log N) of pure CPU.
	 *
	 * Cache invalidates on folder change (cachedSnapshot.folder().equals(target) miss) and on
	 * shutdown (memory release). A file added or removed from the folder while the picker is
	 * open survives until the user navigates away and back; acceptable for a picker dialog.
	 *
	 * @param folder        the folder whose enumeration this snapshot represents
	 * @param sortedFolders subdirectories of folder, already alphabetical
	 * @param files         image files in the folder with their lowercase name cached as
	 *                      FileEntry.sortKey
	 */
	private record FolderSnapshot(File folder, List<File> sortedFolders, List<FileEntry> files) {}

	/**
	 * File-sort direction selected by the sort-toggle icon in the title row. Applies only to
	 * FileRow positions — folders are always alphabetical so the user navigates by name. Persisted
	 * to SharedPreferences via KEY_SORT_MODE; default is NEWEST_FIRST (matches Camera apps and
	 * gallery apps where the user expects fresh shots on top).
	 */
	private enum SortMode
	{
		NEWEST_FIRST,
		OLDEST_FIRST
	}

	/**
	 * RecyclerView adapter backing the content list. Dispatches each position to the matching
	 * ViewHolder via getItemViewType (FOLDER vs FILE_LIST vs FILE_GRID), then onBindViewHolder
	 * forwards to that ViewHolder's bind(File) so the per-cell setup (label text, click handler
	 * wiring, thumbnail submission) happens against a fresh data row.
	 *
	 * setItems is the only mutation entry point; refresh() calls it after enumerating the current
	 * folder. notifyDataSetChanged is used (rather than DiffUtil) because folder navigations
	 * fully replace the content — the user's mental model is "navigate = new list", not "diff
	 * old list against new"; a DiffUtil pass on a 50,000-photo Camera folder would spend cycles
	 * computing diffs the user can't perceive.
	 */
	private final class BrowserAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>
	{
		private final List<BrowserItem> items = new ArrayList<>();
		// Count of FolderRow entries at the head of `items`. Folders always sort first in
		// enumerateFolder, so the file segment starts at position `folderCount`. BrowserSpanSizeLookup
		// reads this for O(1) span-index math instead of walking getSpanSize from position 0
		// (which collapsed toggle/scroll performance on 50k-item folders).
		private int folderCount;

		@Override
		public int getItemCount()
		{
			return items.size();
		}

		@Override
		public int getItemViewType(int position)
		{
			BrowserItem item = items.get(position);
			if (item instanceof FolderRow)
			{
				return VIEW_TYPE_FOLDER;
			}
			return gridMode ? VIEW_TYPE_FILE_GRID : VIEW_TYPE_FILE_LIST;
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position)
		{
			BrowserItem item = items.get(position);
			if (holder instanceof FolderViewHolder folderVh && item instanceof FolderRow row)
			{
				folderVh.bind(row.folder());
			}
			else if (holder instanceof FileListViewHolder listVh && item instanceof FileRow row)
			{
				listVh.bind(row.file());
			}
			else if (holder instanceof FileGridViewHolder gridVh && item instanceof FileRow row)
			{
				gridVh.bind(row.file());
			}
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
		{
			return switch (viewType)
			{
				case VIEW_TYPE_FOLDER -> new FolderViewHolder(makeFolderItemView());
				case VIEW_TYPE_FILE_LIST -> new FileListViewHolder(makeFileListItemView());
				case VIEW_TYPE_FILE_GRID -> new FileGridViewHolder(makeFileGridItemView());
				default -> throw new IllegalStateException("Unknown view type: " + viewType);
			};
		}

		int folderCount()
		{
			return folderCount;
		}

		void setItems(List<BrowserItem> newItems, int newFolderCount)
		{
			items.clear();
			items.addAll(newItems);
			folderCount = newFolderCount;
			notifyDataSetChanged();
		}
	}

	/**
	 * Grid-mode file cell: a square thumbnail in a 3-column slot. Folder rows in grid mode span
	 * the full row via the GridLayoutManager.SpanSizeLookup wired in buildPanel, so a grid cell
	 * is always a file. The ImageView's CENTER_CROP scale type fills the cell with the bitmap's
	 * central rectangle, matching the typical photo-picker grid look.
	 *
	 * Tag-based rebind protection: bind() stamps the ImageView's tag with a path+lastModified
	 * key before submitting the bg decode; on UI post the decode callback re-checks the tag
	 * matches before painting, so a fast scroll that recycles the cell to a different file
	 * doesn't end up with the old file's bitmap landing on the new file's cell.
	 */
	private final class FileGridViewHolder extends RecyclerView.ViewHolder
	{
		private final AppCompatImageView thumb;
		private Future<?> pendingDecode;

		FileGridViewHolder(AppCompatImageView itemView)
		{
			super(itemView);
			this.thumb = itemView;
		}

		void bind(File file)
		{
			boolean supported = jpegOnly ? isJpegSourceFormat(file) : isSupportedSourceFormat(file);
			thumb.setAlpha(supported ? 1f : 0.4f);
			// TalkBack needs a per-cell label — image-only cells with no text would otherwise
			// announce as "unlabeled". For supported files announce the filename (the action
			// "select" is implicit from the clickable role); for unsupported files mark them as
			// "not selectable" so users hear why tapping does nothing.
			thumb.setContentDescription(supported ? file.getName()
				: file.getName() + " (unsupported format, not selectable)");
			if (supported)
			{
				thumb.setBackgroundResource(folderRowRippleResId);
				thumb.setOnClickListener(view -> onFileTapped.accept(file));
			}
			else
			{
				thumb.setBackgroundColor(ThemeColors.SURFACE0);
				thumb.setOnClickListener(null);
				thumb.setClickable(false);
			}
			// Cancel the previous file's pending decode before submitting this file's — without
			// cancellation, a fast scroll piles stale decode tasks behind the currently-visible
			// ones, starving the user's view of the thumbnails they're actually looking at.
			if (pendingDecode != null && !pendingDecode.isDone())
			{
				pendingDecode.cancel(false);
			}
			pendingDecode = submitDecodeForCell(file, thumb, !decodeDeferred);
		}
	}

	/**
	 * List-mode file row: 40dp square thumbnail + filename label. Whole row is the tap target
	 * (matches the original list-view UX where the row, not just the thumbnail, takes the
	 * click). Same tag-based rebind protection as the grid cell — bind() stamps the thumb's
	 * tag and the UI-post path verifies before painting.
	 */
	private final class FileListViewHolder extends RecyclerView.ViewHolder
	{
		private final TextView label;
		private final AppCompatImageView thumb;
		private Future<?> pendingDecode;

		FileListViewHolder(LinearLayout itemView)
		{
			super(itemView);
			this.thumb = (AppCompatImageView) itemView.getChildAt(0);
			this.label = (TextView) itemView.getChildAt(1);
		}

		void bind(File file)
		{
			label.setText(file.getName());
			LinearLayout row = (LinearLayout) itemView;
			boolean supported = jpegOnly ? isJpegSourceFormat(file) : isSupportedSourceFormat(file);
			row.setAlpha(supported ? 1f : 0.4f);
			// TalkBack: the filename label already announces by virtue of being a TextView child,
			// but mark the whole row's role explicitly so unsupported rows (which DON'T receive
			// the click handler below) announce as non-selectable rather than silently being
			// "clickable" in name only.
			row.setContentDescription(supported ? file.getName()
				: file.getName() + " (unsupported format, not selectable)");
			if (supported)
			{
				row.setBackgroundResource(folderRowRippleResId);
				row.setOnClickListener(view -> onFileTapped.accept(file));
			}
			else
			{
				row.setBackground(null);
				row.setOnClickListener(null);
				row.setClickable(false);
			}
			// Cancel previous decode — see FileGridViewHolder.bind for the rationale.
			if (pendingDecode != null && !pendingDecode.isDone())
			{
				pendingDecode.cancel(false);
			}
			pendingDecode = submitDecodeForCell(file, thumb, !decodeDeferred);
		}
	}

	/**
	 * Folder row: 24dp folder icon + folder name. Tapping navigates into the folder if it still
	 * passes isInsideRoot (symlink boundary guard mirrors the breadcrumb-tap site). Used in
	 * both grid and list view modes — the GridLayoutManager.SpanSizeLookup ensures it spans the
	 * full row width regardless of view mode.
	 */
	private final class FolderViewHolder extends RecyclerView.ViewHolder
	{
		private final TextView label;
		private File boundFolder;

		FolderViewHolder(LinearLayout itemView)
		{
			super(itemView);
			this.label = (TextView) itemView.getChildAt(1);
			itemView.setOnClickListener(view ->
			{
				if (boundFolder != null && isInsideRoot(boundFolder))
				{
					current = boundFolder;
					refresh();
				}
			});
		}

		void bind(File folder)
		{
			this.boundFolder = folder;
			label.setText(folder.getName());
		}
	}

	/**
	 * GridLayoutManager.SpanSizeLookup with O(1) span-index and span-group-index math, exploiting
	 * the picker's two-segment layout: FolderRow positions [0..folderCount) each occupy a full
	 * row, then FileRow positions [folderCount..itemCount) flow as 1-span cells across THE
	 * THUMBNAIL_GRID_COLUMNS-column grid.
	 *
	 * The default SpanSizeLookup implementation answers getSpanIndex / getSpanGroupIndex by
	 * iterating getSpanSize from position 0 forward, accumulating spans until reaching the query
	 * position. On a 50,000-item folder this is O(N) per call. RecyclerView calls these methods
	 * during layout (and during scroll for grid mode) — a toggle from list to grid at scroll
	 * position 25,000 would trigger ~25,000 iterations per layout-affecting frame. Caching helps
	 * after the cache is warm, but invalidates on every setSpanCount / notifyDataSetChanged,
	 * forcing a fresh O(N) build on each toggle.
	 *
	 * The closed-form override sidesteps the cache entirely: every call is O(1) regardless of
	 * folder size or scroll position, so toggle/refresh on 50k folders feels instant.
	 */
	private final class BrowserSpanSizeLookup extends GridLayoutManager.SpanSizeLookup
	{
		@Override
		public int getSpanGroupIndex(int adapterPosition, int spanCount)
		{
			if (!gridMode)
			{
				return adapterPosition;
			}
			int folderCount = adapter.folderCount();
			if (adapterPosition < folderCount)
			{
				return adapterPosition;
			}
			return folderCount + (adapterPosition - folderCount) / spanCount;
		}

		@Override
		public int getSpanIndex(int position, int spanCount)
		{
			if (!gridMode)
			{
				return 0;
			}
			int folderCount = adapter.folderCount();
			if (position < folderCount)
			{
				return 0;
			}
			return (position - folderCount) % spanCount;
		}

		@Override
		public int getSpanSize(int position)
		{
			if (!gridMode)
			{
				return 1;
			}
			int folderCount = adapter.folderCount();
			return position < folderCount ? THUMBNAIL_GRID_COLUMNS : 1;
		}
	}

	/**
	 * RecyclerView spacing decoration for grid-mode file cells. Adds the inter-cell gap that
	 * the old eager-inflation path expressed via LinearLayout margin params. Skips folder rows
	 * (they span the full width — adding side margins would shrink the row asymmetrically)
	 * and skips all rows in list mode (list rows have their own vertical padding).
	 */
	private final class GridSpacingDecoration extends RecyclerView.ItemDecoration
	{
		private final int gap;

		GridSpacingDecoration(int gap)
		{
			this.gap = gap;
		}

		@Override
		public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
			@NonNull RecyclerView parent, @NonNull RecyclerView.State state)
		{
			outRect.setEmpty();
			if (!gridMode)
			{
				return;
			}
			int position = parent.getChildAdapterPosition(view);
			if (position == RecyclerView.NO_POSITION)
			{
				return;
			}
			// Compare to folderCount instead of items.get(position) so we stay O(1) (matches
			// BrowserSpanSizeLookup's closed-form approach) and stay safe if the adapter's items
			// list is mid-transition during a refresh (a stale `position >= itemAt` lookup would
			// throw IndexOutOfBoundsException).
			if (position < adapter.folderCount())
			{
				return;
			}
			int spanIndex = layoutManager.getSpanSizeLookup()
				.getSpanIndex(position, THUMBNAIL_GRID_COLUMNS);
			// Proportional distribution keeps cell widths equal across columns. Without this — using
			// `left = spanIndex == 0 ? 0 : interGap` — column 0 occupies its full slot while columns
			// 1 and 2 lose `interGap` of width AND height (the cell's onMeasure forces height = width),
			// so the first column ends up ~8dp taller than the other two. The (i * g / n,
			// g - (i+1) * g / n) split distributes the inter-cell gap evenly across each cell's slot,
			// keeping per-cell offsets summing to `interGap - interGap/n` for every column. Adjacent
			// cells still see `interGap` between them (cell[i].right + cell[i+1].left == interGap).
			int interGap = gap * 2;
			outRect.left = spanIndex * interGap / THUMBNAIL_GRID_COLUMNS;
			outRect.right = interGap - (spanIndex + 1) * interGap / THUMBNAIL_GRID_COLUMNS;
			outRect.top = gap;
			outRect.bottom = gap;
		}
	}

	private static final String TAG = "FolderBrowser";

	// Process-wide bitmap cache shared between FolderPickerDialog and OpenPickerDialog. Survives
	// dialog dismissals so re-opening the same folder (or switching between Save and Open at the
	// same folder) hits the cache rather than re-decoding from disk. Bitmaps are NEVER explicitly
	// recycled — cached bitmaps may still be referenced by ImageViews in the recycler's view pool,
	// and recycling would corrupt the cache. Eviction releases the cache's reference; GC reclaims
	// when no ImageView still holds the bitmap.
	//
	// 64 MB cap sized to comfortably hold a typical folder's working set at the THUMBNAIL_DECODE_DP
	// target — typical Samsung 4000×3000 captures decode to 500×375 at ~750 KB ARGB, so 64 MB
	// holds ~85 entries. Since RecyclerView only inflates cells in the visible window + a small
	// pool, only cells the user actually scrolls through trigger decode; the cache absorbs them
	// and serves cache hits on re-entry / view-mode toggle.
	private static final LruCache<String, Bitmap> THUMBNAIL_CACHE =
		new LruCache<>(64 * 1024 * 1024)
		{
			@Override
			protected int sizeOf(String key, Bitmap value)
			{
				return value.getByteCount();
			}
		};

	private static final String BREADCRUMB_SEPARATOR = " › ";
	private static final String KEY_GRID_MODE = "grid_mode";
	private static final String KEY_SORT_MODE = "sort_mode";
	private static final String PREFS_NAME = "cropcenter_picker_view";
	private static final String ROOT_LABEL = "Internal storage";
	// Screen-height reserve subtracted to size the browser card (screenHeight − this, measured EXACTLY
	// by buildPanel; floored at 240dp). Shared by ALL pickers (Load / Graft via OpenPickerDialog, Save
	// via FolderPickerDialog) so the three dialogs open at the same maximum size. Covers only the chrome
	// BELOW the card — dialog frame + button row + system bars — because Save's options + filename rows
	// live INSIDE the card as fixed children (the file list is the weighted flex child that shrinks for
	// them), so the reserve doesn't need to budget them separately.
	static final int CARD_RESERVED_DP = 220;
	// How many file rows from the top of a freshly-enumerated folder get their thumbnails
	// pre-decoded during the bg pass. 30 covers a typical grid viewport (~10 rows × 3 cols),
	// so the visible window paints from cache on first layout. Larger values would pre-decode
	// off-screen cells too — diminishing returns relative to the bg-thread occupancy cost.
	private static final int PREFETCH_LIMIT = 30;
	// Single decode target shared between grid mode and list mode. Routing both callers through
	// the same target value is what makes the THUMBNAIL_CACHE actually pay off when the user
	// toggles view modes — different decode targets would produce different cache keys, so each
	// toggle would re-decode every file. Both view modes display scaled-down (grid cells are
	// ~96dp ≈ 288px on a typical phone; list cells are 40dp ≈ 120px); 110dp ≈ 330px decode is
	// sized so BitmapFactory's power-of-2 sampleSize lands in the 500-pixel range for typical
	// 4000-pixel-wide sources, producing ~750 KB ARGB bitmaps.
	private static final int THUMBNAIL_DECODE_DP = 110;
	private static final int THUMBNAIL_GRID_COLUMNS = 3;
	// Adapter view-type constants. FOLDER cells span the full row in both modes; FILE_LIST is a
	// full-width row used in list mode; FILE_GRID is a single cell in a 3-column grid. Values are
	// arbitrary (RecyclerView just needs them distinct); ordering here is alphabetical by name per
	// CLAUDE.md's within-tier sort.
	private static final int VIEW_TYPE_FILE_GRID = 0;
	private static final int VIEW_TYPE_FILE_LIST = 1;
	private static final int VIEW_TYPE_FOLDER = 2;

	private final BrowserAdapter adapter = new BrowserAdapter();
	// File-tap callback. Open flow's callback selects-and-dismisses; Save flow's callback
	// populates the filename input. Always non-null — both pickers wire taps; a picker with
	// no taps wouldn't be useful.
	private final Consumer<File> onFileTapped;
	private final Context ctx;
	private final File rootDir;
	private final Handler uiHandler = new Handler(Looper.getMainLooper());
	// When true, only JPEG files are tappable — PNGs render greyed-out (same treatment as unsupported
	// formats like HEIC). Set by the graft caller; the load-an-image flow leaves it false so PNGs are
	// selectable. The flag is the picker-level guard the Apply External Edit caller relies on so a tap
	// on a PNG can't reach EditAligner.align (which would reject the bytes as non-SOI and surface
	// "Selected file is not a JPEG" after the picker dismissed).
	private final boolean jpegOnly;
	private final float density;
	private AppCompatImageView sortToggle;
	private AppCompatImageView viewModeToggle;
	private BrowserFastScroller fastScroller;
	private BrowserRecyclerView recyclerView;
	private File current;
	// volatile because the bg executor runs with 3 threads — the enumeration task can race
	// briefly against a stale-generation task that hasn't checked refreshGeneration yet, and
	// volatile gives the cross-thread visibility for the atomic reference swap. Reads/writes
	// of a reference field are atomic per JLS, so no synchronization needed beyond visibility.
	private volatile FolderSnapshot cachedSnapshot;
	private GridLayoutManager layoutManager;
	// Spinner overlay shown while bg enumeration is in flight. Visibility toggle lives entirely in
	// refresh() (show via a delayed runnable so cached / instant loads don't flash the spinner; hide
	// in the UI post that lands the items). On the Camera album (50k+ entries) listFiles + sort
	// takes ~700ms — without the spinner the user can't tell whether the dialog is loading or
	// whether the folder is empty.
	private ProgressBar progressSpinner;
	// Pending show-spinner runnable, posted with a 200ms delay so the spinner doesn't flash on
	// cached / fast loads. Cleared in the UI post that lands items so it never fires after items
	// arrive. Removed on shutdown so a posted show doesn't outlive the dialog.
	private Runnable pendingShowProgress;
	private SortMode sortMode;
	private TextView breadcrumbLabel;
	private TextView titleLabel;
	private ThreadPoolExecutor bgExecutor;
	private boolean decodeDeferred;
	private boolean gridMode;
	private int folderRowRippleResId;
	// Generation counter bumped on each refresh(). Bg-thread enumeration and decode callbacks
	// capture the value at submit time and re-check on UI post — a rapid folder tap moves the
	// counter forward, so stale enumeration results / decoded bitmaps for the previous folder
	// are dropped silently rather than painting into the new folder's cells. volatile so the
	// bg threads (3 of them) see the latest write from the UI thread without going through
	// synchronization — without volatile, the bg-side gen check could read a cached value and
	// keep doing work that will be discarded at the UI gate anyway (correctness-safe, just
	// wasted CPU on stale tasks).
	private volatile int refreshGeneration;

	/**
	 * Walk the path chain from rootDir to current (inclusive), one File per segment. The first
	 * element is always rootDir; if current equals rootDir, the chain has length 1. Otherwise the
	 * chain contains rootDir plus one File per "/"-separated segment of the path relative to
	 * rootDir. Package-private static so the breadcrumb-rendering test can exercise the
	 * chain-building logic without spinning up an AlertDialog. buildBreadcrumb delegates to this
	 * for the segment enumeration; the spannable formatting (ClickableSpan / StyleSpan /
	 * ForegroundColorSpan) stays in the View layer's instance method where it can capture the
	 * browser's mutable `current` field.
	 *
	 * Both paths are canonicalized before the relative-slice so a symlink in either input produces
	 * the same chain a direct path would. Without this, a current path like `/sdcard/Pictures`
	 * (the /sdcard symlink to /storage/emulated/0) under rootDir = `/storage/emulated/0` would
	 * pass isInsideRoot (which canonicalizes) but then crash here with
	 * StringIndexOutOfBoundsException because the absolute-path prefix doesn't match. The
	 * defensive startsWith check after canonicalization handles the residual case where a stale
	 * `current` from a concurrent refresh is no longer a descendant — degenerates to a root-only
	 * chain rather than throwing.
	 *
	 * @param rootDir absolute path representing the breadcrumb's leftmost ("Internal storage")
	 *                segment
	 * @param current the folder currently displayed by the browser; expected to be a descendant
	 *                of rootDir (verified at navigation time by isInsideRoot)
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
			// caller bypassed isInsideRoot). Without this guard the substring below would
			// underflow or return a path with leading separator that split into a bogus first
			// segment.
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
	 * Decode a thumbnail for `file` aimed at the target display size in pixels and apply EXIF
	 * orientation so a sideways-stored JPEG renders right-side-up. Checks the process-wide
	 * THUMBNAIL_CACHE first — a hit returns the cached bitmap immediately (no disk I/O, no
	 * decode), which makes repeat folder navigations effectively instant. On miss, tries
	 * ExifInterface.getThumbnail() for JPEG sources (the embedded thumbnail decodes in
	 * milliseconds vs. a full-image read), then falls back to a subsampled
	 * BitmapFactory.decodeFile pass. Successful decodes are put into the cache before returning.
	 *
	 * Cache key combines path + lastModified + targetSize:
	 * - path + lastModified makes the entry invalidate when the file content changes (user
	 *   replaces a photo at the same filename via Gallery edit, sync app overwrite, etc.).
	 * - targetSize is part of the key for defensive correctness (a future caller passing a
	 *   different size still gets correct cache semantics), but in practice both current callers
	 *   pass the same THUMBNAIL_DECODE_DP value — list mode decodes at the same target as grid
	 *   mode so the 40dp ImageView simply scales the ~500px bitmap down at GPU draw time.
	 *   Toggling between view modes hits a single shared cache entry instead of re-decoding.
	 *
	 * Caller MUST NOT recycle the returned bitmap — it lives in the LruCache and recycling
	 * would corrupt the cache. Bitmap lifecycle is now: cache holds the strong reference until
	 * eviction; eviction drops the cache's reference; GC reclaims when no ImageView still draws
	 * the bitmap.
	 *
	 * @param file       image file to thumbnail
	 * @param targetSize target display dimension in pixels
	 * @return EXIF-oriented thumbnail bitmap (possibly cached), or null if all decode paths
	 *         failed
	 */
	static Bitmap decodeThumbnail(File file, int targetSize)
	{
		String cacheKey = file.getAbsolutePath() + ":" + file.lastModified() + ":" + targetSize;
		Bitmap cached = THUMBNAIL_CACHE.get(cacheKey);
		if (cached != null && !cached.isRecycled())
		{
			return cached;
		}
		String path = file.getAbsolutePath();
		String lower = file.getName().toLowerCase(Locale.ROOT);
		Bitmap raw = null;
		int orientation = ExifInterface.ORIENTATION_NORMAL;
		boolean isJpeg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
		boolean isPng = lower.endsWith(".png");
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
					// path rather than crashing the bg executor.
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
		else if (isPng)
		{
			// PNG eXIf orientation — mirror ImageLoadController.chooseOrientationByFormat so a
			// PNG with eXIf orientation=6 doesn't render sideways in the picker grid while loading
			// upright in the editor. The cap inside readPngOrientationCapped keeps the bg thumbnail
			// decoder from reading a multi-MB PNG into RAM just to look up the orientation tag.
			// PNGs without eXIf (or with eXIf placed past the cap) fall through with orientation=1,
			// matching the pre-fix render — no regression for unaffected PNGs.
			orientation = readPngOrientationCapped(file);
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
		// Reject zero-dim decode (corrupt embedded thumbnail bytes that decode to a 0×0 Bitmap
		// would crash Bitmap.createBitmap inside applyOrientation, leaking `raw`).
		if (raw.getWidth() <= 0 || raw.getHeight() <= 0)
		{
			raw.recycle();
			return null;
		}
		// Identity-orientation predicate matches BitmapUtils.applyOrientation / UltraHdrCompat:
		// values ≤ 1 are upright (ExifInterface.ORIENTATION_NORMAL = 1; 0 is a malformed/missing
		// tag that all three sites also treat as identity).
		Bitmap result;
		if (orientation <= ExifInterface.ORIENTATION_NORMAL)
		{
			result = raw;
		}
		else
		{
			try
			{
				result = BitmapUtils.applyOrientation(raw, orientation);
			}
			catch (RuntimeException | OutOfMemoryError e)
			{
				// applyOrientation calls Bitmap.createBitmap which can OOM on multi-MP
				// intermediates. Its Javadoc recycles `raw` only when rotation SUCCEEDS — on
				// throw, the input survives, so recycle here to avoid leaking the native pixel
				// buffer until GC.
				Log.w(TAG, "applyOrientation failed for " + file.getName() + ": " + e.getMessage());
				raw.recycle();
				return null;
			}
		}
		// Successful decode — put into the process-wide cache before returning so a subsequent
		// navigation to the same folder reuses the bitmap. cacheKey was built at function entry
		// (path + lastModified + targetSize); the cache's sizeOf override keys eviction off
		// bitmap.getByteCount().
		THUMBNAIL_CACHE.put(cacheKey, result);
		return result;
	}

	/**
	 * Extension filter for the file-list pass in refresh() — decides which files in the picked
	 * folder are visible in the grid / list view. Matches the broader image-format set
	 * (JPEG / PNG / WebP / HEIC / HEIF) so users see all their photos in the picker; the
	 * narrower isSupportedSourceFormat predicate then decides which of those are tappable for
	 * loading vs greyed-out. Package-private rather than private so the test class can pin
	 * the accepted extension set directly.
	 *
	 * @param file candidate file (only the name's lowercase suffix is inspected; the File doesn't
	 *             need to exist)
	 * @return true when the filename's lowercase suffix matches an accepted extension; false
	 *         otherwise
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
	 * Tighter form of isSupportedSourceFormat — accepts JPEG only. Used by the graft-mode tappability
	 * gate (jpegOnly=true). PNG files pass isImageFile (visible in the picker) but fail this and so
	 * render greyed-out, matching the way WebP / HEIC / HEIF are treated for the load flow.
	 *
	 * @param file candidate file (only the name's lowercase suffix is inspected)
	 * @return true when the filename's lowercase suffix is `.jpg` or `.jpeg`; false otherwise
	 */
	static boolean isJpegSourceFormat(File file)
	{
		String name = file.getName().toLowerCase(Locale.ROOT);
		return name.endsWith(".jpg") || name.endsWith(".jpeg");
	}

	/**
	 * Stricter filter than isImageFile — returns true only for formats the load pipeline
	 * actually accepts (JPEG / PNG). WebP / HEIC / HEIF pass isImageFile (so they appear in
	 * the picker as greyed-out non-tappable cells), but `ImageLoadController.applyBytes`
	 * rejects them via magic-byte gate. Without this two-tier filter, tapping a HEIC in the
	 * picker decoded a thumbnail successfully but then surfaced "Unsupported image format"
	 * on load — confusing UX. Now the unsupported formats render at half alpha with no click
	 * handler, communicating "visible but not selectable" at a glance.
	 *
	 * Routes through `ImageLoadController.isJpegSignature` / `isPngSignature`? No — those
	 * inspect bytes, not extensions. Magic-byte inspection here would require reading the
	 * file head (slow for grid mode with 60+ files). Extension match is fast and matches the
	 * loader's accepted formats in practice (mismatch only on a JPEG masquerading as
	 * `.heic` — pathological).
	 *
	 * @param file candidate file (only the name's lowercase suffix is inspected)
	 * @return true when the filename's lowercase suffix matches an extension the load
	 *         pipeline accepts (`.jpg` / `.jpeg` / `.png`); false otherwise
	 */
	static boolean isSupportedSourceFormat(File file)
	{
		String name = file.getName().toLowerCase(Locale.ROOT);
		return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
	}

	/**
	 * Read the EXIF orientation tag (1..8) from a PNG file's eXIf chunk, reading only a capped
	 * prefix of the file so the bg thumbnail decoder doesn't slurp a 100 MB PNG into RAM just for
	 * the orientation lookup. The 64 KB cap covers any reasonable PNG — PNG spec requires the eXIf
	 * chunk to appear before IDAT and typical phone / editor PNGs put IHDR + eXIf (+ any small
	 * ancillary chunks) well under that bound. PNGs with eXIf placed past the cap, or with no eXIf
	 * at all, return 1 (NORMAL) and the picker renders upright — the same behaviour as the
	 * pre-fix code for those rare cases. The main load path
	 * (ImageLoadController.chooseOrientationByFormat) reads the full file and would still find
	 * orientation in the past-cap case, so the editor view stays correct.
	 *
	 * Mirrors the JPEG path's catch-and-default-to-NORMAL on IOException so a file that vanished
	 * between enumeration and decode doesn't crash the executor.
	 *
	 * @param file PNG file to inspect
	 * @return orientation 1..8, or 1 when the eXIf chunk is absent / past the cap / unreadable
	 */
	static int readPngOrientationCapped(File file)
	{
		int cap = (int) Math.min(file.length(), 65536L);
		if (cap <= 8)
		{
			// Too short to even hold the PNG signature — definitely no eXIf, skip the read.
			return ExifInterface.ORIENTATION_NORMAL;
		}
		byte[] head = new byte[cap];
		try (FileInputStream in = new FileInputStream(file))
		{
			int read = 0;
			while (read < head.length)
			{
				int n = in.read(head, read, head.length - read);
				if (n < 0)
				{
					break;
				}
				read += n;
			}
			if (read < head.length)
			{
				// File shorter than the cap (or rare short read) — pass only the bytes we got so
				// PngMetadataExtractor's chunk walker doesn't read past valid data.
				byte[] truncated = new byte[read];
				System.arraycopy(head, 0, truncated, 0, read);
				head = truncated;
			}
		}
		catch (IOException ignored)
		{
			// File unreadable mid-stream (deleted between enumeration and decode, permission
			// revoked) — default to NORMAL, matching the JPEG path's IOException fall-through.
			return ExifInterface.ORIENTATION_NORMAL;
		}
		return PngMetadataExtractor.extractOrientation(head);
	}

	/**
	 * Construct the browser.
	 *
	 * @param ctx                  Context for inflation + SharedPreferences access
	 * @param density              display density (resources.getDisplayMetrics().density)
	 * @param startDir             initial folder to display; clamped to rootDir if outside; null
	 *                             lands at rootDir
	 * @param onFileTapped         callback invoked when the user taps a tappable file's row or
	 *                             grid cell. Open flow's callback selects-and-dismisses; Save
	 *                             flow's callback populates the filename input. Must be non-null —
	 *                             the picker has no use case for non-interactive file display.
	 * @param jpegOnly             when true, only JPEG files (.jpg / .jpeg) are tappable; PNGs and
	 *                             other supported-by-load formats render greyed-out. Set true for
	 *                             the Apply External Edit graft picker (graft splices JPEG bytes
	 *                             only) and false for the load-an-image flow which accepts both.
	 */
	FolderBrowser(Context ctx, float density, File startDir, Consumer<File> onFileTapped,
		boolean jpegOnly)
	{
		this.ctx = ctx;
		this.density = density;
		this.onFileTapped = onFileTapped;
		this.jpegOnly = jpegOnly;
		this.rootDir = Environment.getExternalStorageDirectory();
		this.current = (startDir != null && isInsideRoot(startDir)) ? startDir : rootDir;
		SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		this.gridMode = prefs.getBoolean(KEY_GRID_MODE, true);
		// Persist sort mode by enum name() rather than ordinal() so a future enum reordering
		// doesn't silently flip every user's stored preference. Defaults to NEWEST_FIRST to
		// match the Camera-app convention (latest captures on top).
		SortMode parsed;
		try
		{
			parsed = SortMode.valueOf(prefs.getString(KEY_SORT_MODE, SortMode.NEWEST_FIRST.name()));
		}
		catch (IllegalArgumentException ignored)
		{
			// Pref corrupted (legacy schema, manual edit) — fall back to the default rather than
			// crashing the picker on first launch.
			parsed = SortMode.NEWEST_FIRST;
		}
		this.sortMode = parsed;
	}

	/**
	 * Install the composite OnDismissListener that both pickers need (FolderBrowser.shutdown +
	 * onCancel-when-not-decided + host transient-dialog cleanup), call refresh() to seed the
	 * initial folder display, and call dialog.show() inside a try/catch that shuts the executor
	 * down on BadTokenException (the config-change race window between picker construction and
	 * the first frame would otherwise leak the executor's worker thread).
	 *
	 * Caller funnels through this AFTER building the AlertDialog so the dismiss listener +
	 * refresh + show ordering is guaranteed. Without the dismiss listener the executor leaks;
	 * without refresh() the dialog opens empty; without the try/catch a BadTokenException
	 * strands the executor's worker thread.
	 *
	 * @param dialog              the dialog to attach to
	 * @param decided             true when the user has committed a selection — drives whether
	 *                            onCancel fires on dismiss; passed as a Supplier so callers can
	 *                            close over a one-element array (FolderPickerDialog, set inside
	 *                            a lambda) or a field (OpenPickerDialog, set in a method) with
	 *                            the same shape
	 * @param onCancel            ran on dismiss when decided is still false — typically
	 *                            `() -> onPicked.accept(null)`
	 * @param hostDismissCallback host's transient-dialog tracking cleanup; called after the
	 *                            other listeners; null skips this step
	 * @throws RuntimeException re-thrown from dialog.show() after the executor is shut down
	 */
	void attachToDialog(AlertDialog dialog, BooleanSupplier decided, Runnable onCancel,
		DialogInterface.OnDismissListener hostDismissCallback)
	{
		dialog.setOnDismissListener(dismissed ->
			onDialogDismissed(dismissed, decided, onCancel, hostDismissCallback));
		refresh();
		try
		{
			dialog.show();
		}
		catch (RuntimeException e)
		{
			// BadTokenException (config-change race) — shut the executor down so its worker
			// thread doesn't outlive the dialog that owned it.
			shutdown();
			throw e;
		}
	}

	/**
	 * Build the inlaid panel: title block (folder name + breadcrumb + sort toggle + grid/list
	 * toggle) followed by the RecyclerView-backed content list (inflated from
	 * res/layout/folder_browser_recycler.xml, which overlays the custom BrowserFastScroller on
	 * the RecyclerView's right edge via FrameLayout z-order — RecyclerView's built-in
	 * fastScrollEnabled is deliberately NOT used; see BrowserFastScroller's class Javadoc for
	 * the rationale). Also boots the bg executor as a side-effect — attachToDialog(...) installs
	 * the composite OnDismissListener that calls shutdown() to clean it up, so callers funnel
	 * buildPanel + attachToDialog rather than wiring shutdown themselves. Returns a single
	 * SURFACE0-backed DialogCards panel ready to be added (with margins) to the dialog content;
	 * the caller may also append additional rows (options / filename) onto the panel before it
	 * goes on screen.
	 *
	 * @return the inlaid DialogCards panel (LinearLayout with SURFACE0 fill + rounded corners)
	 *         containing the browser views; caller wraps in margins and adds to the dialog body
	 */
	LinearLayout buildPanel()
	{
		int padSmall = DpToPx.toPx(4, density);
		TypedValue rippleAttr = new TypedValue();
		ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, rippleAttr, true);
		this.folderRowRippleResId = rippleAttr.resourceId;

		// Inflate the FrameLayout container that holds the BrowserRecyclerView + the
		// BrowserFastScroller overlay (the fast scroller draws on top of the recycler's right
		// edge). The container is needed because the FastScroller is a sibling-overlay rather
		// than an internal RecyclerView decoration — overlay placement is what lets it stay
		// always-visible with a min thumb size and use position-based drag math (the built-in
		// initFastScroller has neither min size nor O(1) drag math).
		FrameLayout container = (FrameLayout) LayoutInflater.from(ctx)
			.inflate(R.layout.folder_browser_recycler, null, false);
		recyclerView = container.findViewById(R.id.folder_browser_recycler);
		fastScroller = container.findViewById(R.id.folder_browser_fast_scroller);
		progressSpinner = container.findViewById(R.id.folder_browser_progress);
		int reservedPx = DpToPx.toPx(CARD_RESERVED_DP, density);
		int screenHeightPx = ctx.getResources().getDisplayMetrics().heightPixels;
		final int panelMaxHeightPx = Math.max(DpToPx.toPx(240, density), screenHeightPx - reservedPx);
		// RecyclerView self-cap bounds its own intrinsic-height report so a 50k-item folder doesn't
		// claim an absurd base height during the panel's first measure pass; the panel cap below is
		// the real layout governor (it's what shrinks the weighted container so the footer rows stay
		// visible).
		recyclerView.setMaxHeightPx(panelMaxHeightPx);
		// GridLayoutManager always — spanCount=3 for grid mode, spanCount=1 for list mode (toggled
		// in toggleViewMode). Folder rows always span the full row width regardless of mode (see
		// BrowserSpanSizeLookup), so grid mode renders the folder rows as full-width headers and
		// the file rows as 3-column cells underneath.
		layoutManager = new GridLayoutManager(ctx, gridMode ? THUMBNAIL_GRID_COLUMNS : 1);
		layoutManager.setSpanSizeLookup(new BrowserSpanSizeLookup());
		recyclerView.setLayoutManager(layoutManager);
		recyclerView.setAdapter(adapter);
		recyclerView.addItemDecoration(new GridSpacingDecoration(DpToPx.toPx(4, density)));
		// Cap the recycled-view pool — RecyclerView's default 5-per-type is fine for small folders,
		// but a wider window (16) keeps fast-scroll smoother by reducing cell-inflation churn at the
		// scroll-edge boundary on long folders.
		recyclerView.getRecycledViewPool().setMaxRecycledViews(VIEW_TYPE_FOLDER, 16);
		recyclerView.getRecycledViewPool().setMaxRecycledViews(VIEW_TYPE_FILE_LIST, 16);
		recyclerView.getRecycledViewPool().setMaxRecycledViews(VIEW_TYPE_FILE_GRID, 16);
		// Disable change/move animations — DefaultItemAnimator runs a 250ms cross-fade per
		// changed cell on notifyDataSetChanged. For a folder-navigation refresh (which clears
		// then repopulates) this means every visible cell animates twice; on huge folders the
		// animation overhead is enough to feel like the dialog stutters. Pickers don't benefit
		// from animations anyway — the user's mental model is "navigate = jump", not "morph".
		recyclerView.setItemAnimator(null);
		fastScroller.attachTo(recyclerView);
		// Pause decode submissions while the user is fast-scrolling so the bg threads aren't
		// thrashing through stale decodes for cells that recycle past before paint. On drag end
		// (listener fires with false), triggerDecodeForVisibleCells re-binds the visible window
		// so cells decode normally for the final scroll position.
		fastScroller.setOnDragStateChangedListener(this::setDecodeDeferred);

		// Fixed-height card sized to panelMaxHeightPx (or the dialog's available height, whichever is
		// smaller). The panel measures EXACTLY that height rather than wrapping to content, and the
		// browser container below is a weight=1 / height=0 flex child that fills the remainder after
		// the fixed title and any footer rows the caller appends. Two things fall out of this:
		//   - The dialog opens at a stable, generous extent regardless of WHEN the list populates.
		//     The adapter fills asynchronously (after show(), off the bg enumeration), so a
		//     wrap-to-content panel would measure against an EMPTY list at show() time and open
		//     squished, never growing back when items land. Filling to the cap sidesteps that.
		//   - Footer rows (Save's format / Export Grid / filename) keep their full measured height
		//     and the list absorbs all the flex, so they stay on-screen below the grid. targetMax
		//     never exceeds the offered height, so the panel can't overflow the non-scrolling
		//     AlertDialog custom view (the reserve in scrollerReservedPxDp covers the frame + buttons
		//     + system bars below the card).
		LinearLayout panel = new LinearLayout(ctx)
		{
			@Override
			protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
			{
				int heightMode = View.MeasureSpec.getMode(heightMeasureSpec);
				int heightSize = View.MeasureSpec.getSize(heightMeasureSpec);
				int targetMax = (heightMode == View.MeasureSpec.UNSPECIFIED)
					? panelMaxHeightPx
					: Math.min(heightSize, panelMaxHeightPx);
				super.onMeasure(widthMeasureSpec,
					View.MeasureSpec.makeMeasureSpec(targetMax, View.MeasureSpec.EXACTLY));
			}
		};
		DialogCards.styleCard(panel, density);
		panel.addView(buildTitleBlock(padSmall));
		// height=0 + weight=1: the container takes ALL space left after the fixed title and footer
		// rows are measured. It's the only weighted child, so footer rows the caller appends never
		// shrink — the list does (and scrolls internally) when the content exceeds the region.
		panel.addView(container, new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

		// 3-thread bg executor with an explicit queue so refresh() can clear pending decodes for
		// the previous folder before pushing the new folder's enumeration. Without queue.clear,
		// the new enumeration would wait behind however many decodes the user kicked off scrolling
		// through the previous folder — on a deep Camera scroll that's seconds of stalling before
		// the new folder even starts listing. Three threads triple decode throughput so a fast
		// scroll through a 30-cell grid window paints in ~300ms instead of ~750ms with 2 threads;
		// ViewHolder Future cancellation + drag-deferred submission keep the queue from filling
		// with stale decodes for cells that have already scrolled past.
		bgExecutor = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(), task ->
			{
				Thread thread = new Thread(task, "FolderBrowser-bg");
				thread.setDaemon(true);
				return thread;
			});

		return panel;
	}

	/**
	 * Folder currently displayed by the browser. Used by the save flow's Save here button to
	 * pass the target directory into the SaveChoices result.
	 *
	 * @return current folder; never null (constructor guarantees this lands at rootDir at
	 *         minimum)
	 */
	File getCurrent()
	{
		return current;
	}

	/**
	 * Re-render the panel against `current`. Updates title + breadcrumb synchronously, clears
	 * the adapter immediately so the user sees the navigation respond, then runs the
	 * directory-enumeration + sort on the bg executor and pushes the resulting BrowserItem list
	 * back to the adapter on the UI thread. For the Camera album (50k+ entries) the synchronous
	 * version blocked the UI for ~700ms; bg dispatch keeps the dialog responsive while the
	 * filesystem call runs.
	 *
	 * Pre-emption: bumps refreshGeneration and clears the bg queue so a pending enumeration /
	 * decode for the previous folder is dropped (in-flight bg tasks — up to 3 since bgExecutor
	 * runs with 3 threads — finish their current work, but the bg-side and UI-side generation
	 * checks discard their results). A user who taps folder A then folder B before A's
	 * enumeration completes only sees B's content.
	 */
	void refresh()
	{
		int gen = ++refreshGeneration;
		File targetFolder = current;
		SortMode targetSort = sortMode;
		titleLabel.setText(targetFolder.getAbsolutePath().equals(rootDir.getAbsolutePath())
			? ROOT_LABEL : targetFolder.getName());
		breadcrumbLabel.setText(buildBreadcrumb());
		// Clear immediately so the user sees the dialog respond to the tap, even on a slow
		// filesystem where listFiles takes a beat. The new items appear once the bg enumeration
		// posts back.
		adapter.setItems(Collections.emptyList(), 0);
		// Schedule the progress spinner to appear after a short delay. Cached / instant loads will
		// land items via the UI post below before this delay elapses (the runnable is cancelled
		// there), so the spinner never flashes on fast navigations. Only the truly slow paths
		// (uncached huge folders — Camera at 50k+ entries) end up actually showing it. The
		// 200ms threshold is short enough that the user perceives the spinner as "the dialog
		// noticed I'm waiting" rather than as a delayed reaction.
		if (pendingShowProgress != null)
		{
			uiHandler.removeCallbacks(pendingShowProgress);
		}
		pendingShowProgress = () ->
		{
			if (gen == refreshGeneration && progressSpinner != null)
			{
				progressSpinner.setVisibility(View.VISIBLE);
			}
		};
		uiHandler.postDelayed(pendingShowProgress, 200);
		if (bgExecutor == null || bgExecutor.isShutdown())
		{
			return;
		}
		bgExecutor.getQueue().clear();
		bgExecutor.execute(() ->
		{
			if (gen != refreshGeneration)
			{
				return;
			}
			// Cache lookup: if we've already enumerated targetFolder this session, skip the
			// expensive listFiles + isDirectory syscall storm and just re-run the cheap
			// sortAndAssemble pass. This is what makes a sort-mode toggle on a 50k-photo
			// Camera folder near-instant instead of a fresh ~500ms enumeration.
			FolderSnapshot snap = cachedSnapshot;
			if (snap == null || !snap.folder().equals(targetFolder))
			{
				snap = enumerateFolder(targetFolder);
				cachedSnapshot = snap;
			}
			if (gen != refreshGeneration)
			{
				return;
			}
			EnumerationResult result = sortAndAssemble(snap, targetSort);
			// Pre-submit decodes for the top-of-list cells so their bitmaps land in
			// THUMBNAIL_CACHE before the UI thread runs adapter.setItems below. By the time the
			// visible cells bind, cache hits paint immediately instead of waiting on a fresh
			// decode pass — shaves ~300ms off the initial-load "items visible but blank" gap.
			prefetchInitialThumbnails(result.items(), gen);
			uiHandler.post(() ->
			{
				if (gen != refreshGeneration)
				{
					return;
				}
				// Hide the spinner and cancel any pending show — items have arrived, so even if
				// the delayed runnable hasn't fired yet, we don't want it to fire after items
				// land. Re-check both because the post might race with a rapid folder tap that
				// already bumped the generation.
				if (pendingShowProgress != null)
				{
					uiHandler.removeCallbacks(pendingShowProgress);
					pendingShowProgress = null;
				}
				if (progressSpinner != null)
				{
					progressSpinner.setVisibility(View.GONE);
				}
				adapter.setItems(result.items(), result.folderCount());
				// Scroll to top — folder navigations should land the user at the first item, not
				// wherever the previous folder's scroll left them. notifyDataSetChanged (inside
				// setItems) preserves scroll position by default; the explicit scroll override
				// is what gives the "new folder = new view" UX expectation.
				recyclerView.scrollToPosition(0);
			});
		});
	}

	/**
	 * Stop the bg work pipeline (enumeration + thumbnail decode) and release executor resources.
	 * MUST be called by the host dialog's OnDismissListener — without it the executor's worker
	 * thread stays alive holding ImageView references through its task closures until GC.
	 * Tag-based rebind protection in submitDecodeForCell prevents a late-arriving decode from
	 * painting onto a recycled ImageView whose tag no longer matches; shutdownNow stops further
	 * submissions. Idempotent: safe to call from the BadTokenException catch in show() AND from
	 * the normal dismiss path.
	 */
	void shutdown()
	{
		// Bump refreshGeneration FIRST so any UI post a bg task already enqueued (between its
		// bg-thread gen check and the dismiss) sees gen != refreshGeneration on the UI side and
		// bails out instead of mutating adapter / spinner / recycler views that are about to be
		// destroyed. shutdownNow stops future submissions but can't recall a post that's already
		// in uiHandler's queue; the gen-mismatch path is what catches that window deterministically.
		refreshGeneration++;
		if (bgExecutor != null)
		{
			bgExecutor.shutdownNow();
		}
		// Drop the cached enumeration so its File / FileEntry list doesn't outlive the dialog.
		// For a 50k Camera folder the snapshot holds ~50k FileEntry records (~1.2 MB) plus the
		// File references themselves — small but no reason to keep it once the picker is closed.
		cachedSnapshot = null;
		// Cancel any pending show-spinner runnable so it doesn't fire on a destroyed dialog
		// trying to flip visibility on a recycled view.
		if (pendingShowProgress != null)
		{
			uiHandler.removeCallbacks(pendingShowProgress);
			pendingShowProgress = null;
		}
	}

	/**
	 * Wrap the inlaid panel in 12dp margins inside a vertical LinearLayout suitable for
	 * AlertDialog.Builder.setView. Use as `setView(browser.wrapInDialogMargins(panel))`. Caller
	 * appends any extra child rows (FolderPickerDialog's options + filename row) to `panel`
	 * BEFORE calling this — wrapInDialogMargins doesn't touch the panel's children, just wraps
	 * the assembled panel in symmetric gutters so the dialog frame's visible border looks
	 * identical top/bottom/left/right.
	 *
	 * @param panel inlaid DialogCards panel — typically the result of buildPanel(), possibly
	 *              with extra child rows appended
	 * @return content View ready to pass to AlertDialog.Builder.setView
	 */
	View wrapInDialogMargins(LinearLayout panel)
	{
		LinearLayout content = new LinearLayout(ctx);
		content.setOrientation(LinearLayout.VERTICAL);
		int cardMargin = DpToPx.toPx(12, density);
		LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		panelLp.setMargins(cardMargin, cardMargin, cardMargin, cardMargin);
		content.addView(panel, panelLp);
		return content;
	}

	/**
	 * Build the breadcrumb spannable. Every segment is bold; intermediate segments are clickable
	 * mauve ClickableSpans that navigate to that level on tap; the current segment is
	 * TEXT-colored and NOT clickable. The chevron separator (U+203A) renders in the muted
	 * SUBTEXT0 the TextView is set to.
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
	 * Build the title block — bold folder name on the left, sort-direction toggle + grid/list
	 * view-mode toggle icons on the right, then the clickable breadcrumb underneath. Horizontal
	 * padding lives on the enclosing DialogCards card; this block only supplies the small
	 * vertical gap between the title row and the breadcrumb below it.
	 *
	 * @param padSmall vertical gap between the title row and the breadcrumb label
	 * @return assembled title block ready to be added as the first child of the inlaid panel
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

		int toggleSize = DpToPx.toPx(40, density);
		int togglePad = DpToPx.toPx(8, density);
		// Sort toggle. Shows the icon for the OTHER sort direction — clicking switches into that
		// direction (parallels the grid/list toggle convention). Sort applies to file rows only;
		// folders stay alphabetical irrespective of mode.
		sortToggle = new AppCompatImageView(ctx);
		sortToggle.setImageResource(sortIconForCurrentMode());
		sortToggle.setContentDescription(sortToggleDescriptionForCurrentMode());
		sortToggle.setBackgroundResource(folderRowRippleResId);
		sortToggle.setPadding(togglePad, togglePad, togglePad, togglePad);
		sortToggle.setOnClickListener(view -> toggleSortMode());
		titleRow.addView(sortToggle, new LinearLayout.LayoutParams(toggleSize, toggleSize));

		// Grid/list toggle. Shows the icon for the OTHER mode — clicking switches into that mode.
		// Tap feedback via selectableItemBackground ripple; persists choice to SharedPreferences.
		viewModeToggle = new AppCompatImageView(ctx);
		viewModeToggle.setImageResource(gridMode ? R.drawable.ic_view_list : R.drawable.ic_view_grid);
		viewModeToggle.setContentDescription(viewModeToggleDescriptionForCurrentMode());
		viewModeToggle.setBackgroundResource(folderRowRippleResId);
		viewModeToggle.setPadding(togglePad, togglePad, togglePad, togglePad);
		viewModeToggle.setOnClickListener(view -> toggleViewMode());
		titleRow.addView(viewModeToggle, new LinearLayout.LayoutParams(toggleSize, toggleSize));
		titleBlock.addView(titleRow);

		breadcrumbLabel = new TextView(ctx);
		breadcrumbLabel.setTextSize(13);
		breadcrumbLabel.setTextColor(ThemeColors.SUBTEXT0);
		breadcrumbLabel.setSingleLine(true);
		// Ellipsize at START so a deep path keeps the CURRENT segment visible and clips the root
		// prefix instead. Without this, a long breadcrumb hides the current segment.
		breadcrumbLabel.setEllipsize(TextUtils.TruncateAt.START);
		breadcrumbLabel.setPadding(0, padSmall, 0, 0);
		breadcrumbLabel.setMovementMethod(LinkMovementMethod.getInstance());
		titleBlock.addView(breadcrumbLabel);
		return titleBlock;
	}

	/**
	 * Read `folder` and produce a FolderSnapshot ready to be sorted. Designed to run on the bg
	 * executor and to be the EXPENSIVE half of refresh() — listFiles on a 50k-entry Camera
	 * folder is ~500ms cold, plus isDirectory syscall per entry. The result is meant to be
	 * cached so a follow-up sort-mode toggle only needs the cheap sortAndAssemble pass on the
	 * already-paid enumeration.
	 *
	 * Each file's lowercase name is captured here as FileEntry.sortKey so the subsequent sort
	 * is a pure String compare with no per-call toLowerCase. Filename-based sort (rather than
	 * lastModified) matches user expectations of "newest first" for camera-app filenames that
	 * embed the capture date (20240101_*, PXL_20240101_*, IMG-20240101-WA0001.jpg) — and stays
	 * correct even when a backup/restore touches lastModified on every file.
	 *
	 * Folder list is pre-sorted alphabetically at this stage — sort mode applies only to FILE
	 * rows, so the folder ordering is invariant across SortMode toggles.
	 *
	 * @param folder directory to enumerate; snapshot is empty when listFiles returns null
	 *               (folder unreadable / non-existent — happens on a folder that was deleted
	 *               between the refresh() call and this bg task running)
	 * @return FolderSnapshot holding the alphabetical folder list + the unsorted file list with
	 *         the lowercase name cached per entry as the sort key
	 */
	private FolderSnapshot enumerateFolder(File folder)
	{
		File[] kids = folder.listFiles();
		if (kids == null)
		{
			Log.w(TAG, "listFiles returned null for " + folder.getAbsolutePath());
			return new FolderSnapshot(folder, Collections.emptyList(), Collections.emptyList());
		}
		List<File> folders = new ArrayList<>();
		List<FileEntry> files = new ArrayList<>();
		int rejectedExt = 0;
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
				files.add(new FileEntry(f, f.getName().toLowerCase(Locale.ROOT)));
			}
			else
			{
				rejectedExt++;
			}
		}
		folders.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
		// Diagnostic for the "JPGs missing from picker" reports — surfacing raw listFiles count
		// vs filtered counts lets the user spot whether files are being dropped at enumeration
		// (filesystem / permission), at the extension filter (unusual extension), or somewhere
		// downstream (rendering / scroll). Filter logcat for FolderBrowser to compare against
		// the Gallery app's file count for the same folder.
		Log.d(TAG, "enumerateFolder " + folder.getAbsolutePath()
			+ " kids=" + kids.length
			+ " folders=" + folders.size()
			+ " imageFiles=" + files.size()
			+ " rejectedByExtension=" + rejectedExt);
		return new FolderSnapshot(folder, folders, files);
	}

	/**
	 * Containment check used by every navigation entry point (folder-row tap, breadcrumb tap,
	 * and the constructor's startDir clamp) — canonicalises both paths so a `/sdcard/...`
	 * symlink against a rootDir of `/storage/emulated/0/...` resolves through. Rejects siblings
	 * that share the same textual prefix (`/storage/emulated/0` vs `/storage/emulated/0abc`) by
	 * requiring the path-separator boundary after the prefix. Returns false on IOException so a
	 * hostile filesystem (symlink loop, unreadable parent) can't crash the picker UI.
	 *
	 * @param candidate folder being navigated to
	 * @return true when candidate sits at or below rootDir; false on out-of-tree or
	 *         canonicalisation failure
	 */
	private boolean isInsideRoot(File candidate)
	{
		try
		{
			String candidatePath = candidate.getCanonicalPath();
			String rootPath = rootDir.getCanonicalPath();
			return candidatePath.equals(rootPath)
				|| candidatePath.startsWith(rootPath + File.separator);
		}
		catch (IOException ignored)
		{
			// Canonicalization fails on symlink loops or unreadable parents — reject for safety.
			return false;
		}
	}

	/**
	 * Build the ClickableSpan for a single breadcrumb segment. On tap navigates to `target`
	 * when it still passes isInsideRoot.
	 *
	 * @param target folder this segment points at
	 * @return ClickableSpan ready to apply via Spannable.setSpan
	 */
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

	/**
	 * Build a square thumbnail ImageView for a grid-mode file cell. The CENTER_CROP scale type
	 * makes the bitmap fill the cell with central cropping (typical photo-picker look). The
	 * anonymous-subclass onMeasure forces square height — without it the WRAP_CONTENT cell
	 * collapses to zero height before the bitmap arrives.
	 *
	 * @return fresh AppCompatImageView ready to be installed as a FileGridViewHolder's itemView
	 */
	private AppCompatImageView makeFileGridItemView()
	{
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
		iv.setLayoutParams(new RecyclerView.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return iv;
	}

	/**
	 * Build a list-mode file row: 40dp square thumbnail + filename label. Layout is fixed at
	 * inflation time; FileListViewHolder.bind only updates the dynamic per-file state (label
	 * text, alpha, click handler, thumbnail submission).
	 *
	 * @return fresh LinearLayout ready to be installed as a FileListViewHolder's itemView
	 */
	private LinearLayout makeFileListItemView()
	{
		int thumbSize = DpToPx.toPx(40, density);
		int padInner = DpToPx.toPx(8, density);
		int padSmall = DpToPx.toPx(4, density);
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		// No setMinimumHeight: lets the row WRAP its natural content height so list view's
		// row-to-row spacing matches grid view's. No horizontal padding — the row spans the
		// full card-content width so list and grid views share the same outer-bound width.
		row.setPadding(0, padSmall, 0, padSmall);
		row.setLayoutParams(new RecyclerView.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		AppCompatImageView thumb = new AppCompatImageView(ctx);
		thumb.setBackgroundColor(ThemeColors.SURFACE0);
		thumb.setScaleType(AppCompatImageView.ScaleType.CENTER_CROP);
		row.addView(thumb, new LinearLayout.LayoutParams(thumbSize, thumbSize));
		TextView label = new TextView(ctx);
		label.setTextSize(15);
		label.setTextColor(ThemeColors.TEXT);
		label.setSingleLine(true);
		label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
		label.setPadding(padInner, 0, 0, 0);
		row.addView(label, new LinearLayout.LayoutParams(0,
			LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		return row;
	}

	/**
	 * Build a folder row: 24dp folder icon + folder name. Layout is fixed at inflation time;
	 * FolderViewHolder.bind only updates the dynamic per-folder state (label text and the
	 * navigation target captured by the row's existing click listener). 48dp minimum height
	 * matches the standard Material list-item touch target.
	 *
	 * @return fresh LinearLayout ready to be installed as a FolderViewHolder's itemView
	 */
	private LinearLayout makeFolderItemView()
	{
		int rowMinHeight = DpToPx.toPx(48, density);
		int iconSize = DpToPx.toPx(24, density);
		int padInner = DpToPx.toPx(8, density);
		int padSmall = DpToPx.toPx(4, density);
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setMinimumHeight(rowMinHeight);
		// No horizontal padding — folder rows align with the file list / grid rows and the
		// options / filename rows below, spanning the full card-content width.
		row.setPadding(0, 0, 0, 0);
		row.setBackgroundResource(folderRowRippleResId);
		row.setLayoutParams(new RecyclerView.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		AppCompatImageView icon = new AppCompatImageView(ctx);
		icon.setImageResource(R.drawable.ic_folder);
		row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
		TextView label = new TextView(ctx);
		label.setTextSize(16);
		label.setTextColor(ThemeColors.TEXT);
		label.setPadding(padInner + padSmall, 0, 0, 0);
		row.addView(label, new LinearLayout.LayoutParams(0,
			LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		return row;
	}

	/**
	 * Composite dismiss handler — extracted out of the OnDismissListener lambda body installed
	 * in attachToDialog so the lambda stays at one line (the 3-line lambda cap forbids the four
	 * statements + paired ifs that the full body needs). Shuts down the bg executor, fires the
	 * cancel callback if the user dismissed without selecting, then forwards to the host's
	 * transient-dialog tracking cleanup.
	 *
	 * @param dismissed           the DialogInterface that fired the dismiss event
	 * @param decided             whether the user committed a selection before dismiss
	 * @param onCancel            run when decided is false (user backed out)
	 * @param hostDismissCallback host's tracking cleanup; null skips this step
	 */
	private void onDialogDismissed(DialogInterface dismissed, BooleanSupplier decided,
		Runnable onCancel, DialogInterface.OnDismissListener hostDismissCallback)
	{
		shutdown();
		if (!decided.getAsBoolean())
		{
			onCancel.run();
		}
		if (hostDismissCallback != null)
		{
			hostDismissCallback.onDismiss(dismissed);
		}
	}

	/**
	 * Pre-submit decode tasks for the first viewport's worth of file rows so their bitmaps land
	 * in THUMBNAIL_CACHE before the UI thread runs adapter.setItems. Called from the bg task
	 * right after sortAndAssemble — the bg executor has 3 threads, so this prefetch occupies
	 * the other 2 threads in parallel with the UI thread's layout pass. By the time
	 * onBindViewHolder fires for visible cells, the cache hits paint immediately. Tasks that
	 * find cellTag already cached (e.g., same file seen in a previous folder visit) are no-ops.
	 *
	 * Gen-guarded: tasks early-return if the user navigated away. Cached entries from a
	 * cancelled prefetch are still valid (path+lastModified+targetSize key) — if the user
	 * later navigates back to this folder, those entries serve cache hits.
	 *
	 * @param items items list from sortAndAssemble; only the first PREFETCH_LIMIT file rows
	 *              get prefetched (folders are skipped since they have no thumbnail)
	 * @param gen   generation captured by the parent bg task; tasks check it before decoding
	 *              so a fast folder-tap chain doesn't waste bg cycles on stale folders
	 */
	private void prefetchInitialThumbnails(List<BrowserItem> items, int gen)
	{
		int displayTarget = Math.max(1, DpToPx.toPx(THUMBNAIL_DECODE_DP, density));
		int submitted = 0;
		for (BrowserItem item : items)
		{
			if (submitted >= PREFETCH_LIMIT)
			{
				break;
			}
			if (!(item instanceof FileRow fileRow))
			{
				continue;
			}
			File file = fileRow.file();
			String cacheKey = file.getAbsolutePath() + ":" + file.lastModified() + ":" + displayTarget;
			if (THUMBNAIL_CACHE.get(cacheKey) != null)
			{
				continue;
			}
			// safeBgSubmit catches RejectedExecutionException — the loop body runs from the bg
			// enumeration task while UI-thread dismissal can call shutdownNow() between the entry
			// check and any iteration's execute(). On rejection, stop submitting: the dialog is
			// going away and the remaining prefetch work is moot.
			boolean accepted = safeBgSubmit(() ->
			{
				if (gen != refreshGeneration)
				{
					return;
				}
				decodeThumbnail(file, displayTarget);
			});
			if (!accepted)
			{
				return;
			}
			submitted++;
		}
	}

	/**
	 * Submit a Runnable to bgExecutor while tolerating a concurrent shutdown. The submit-into-executor
	 * pattern has an inherent race: bgExecutor.execute() throws RejectedExecutionException when the
	 * UI-thread dismiss path's shutdownNow() lands between an isShutdown() check and the actual submit
	 * (or, in a loop of submits, between iterations). The wrapper folds the null / isShutdown / catch
	 * legs into one place so callers — particularly the bg-thread loop in prefetchInitialThumbnails —
	 * don't have to repeat the boilerplate. Returns false on any of the three reject paths so loop
	 * callers can stop submitting once the executor has gone away.
	 *
	 * @param task work to submit on bgExecutor
	 * @return true when accepted; false when bgExecutor is null, already shut down, or rejected the
	 *         submit (concurrent shutdownNow from the dismiss path)
	 */
	private boolean safeBgSubmit(Runnable task)
	{
		if (bgExecutor == null || bgExecutor.isShutdown())
		{
			return false;
		}
		try
		{
			bgExecutor.execute(task);
			return true;
		}
		catch (RejectedExecutionException ignored)
		{
			// Concurrent shutdownNow from the UI-thread dismiss path landed between the isShutdown
			// check above and execute(). Treat the same as a pre-check shutdown — drop the task and
			// let the caller fall through.
			return false;
		}
	}

	/**
	 * Toggle whether bind() submits bg decodes (true = skip submit on cache miss, false =
	 * submit normally). Called by the FastScroller drag-state callback so the bg threads
	 * aren't churning on decode work for cells that will recycle out of view before they're
	 * painted. On false (drag ended), re-binds the currently-visible cells so they decode
	 * normally for the final scroll position.
	 *
	 * @param deferred true when entering deferred mode (drag begin); false when leaving (drag
	 *                 end) — false triggers the re-bind sweep for visible cells
	 */
	private void setDecodeDeferred(boolean deferred)
	{
		if (decodeDeferred == deferred)
		{
			return;
		}
		decodeDeferred = deferred;
		if (!deferred)
		{
			triggerDecodeForVisibleCells();
		}
	}

	/**
	 * Sort the cached files in `snap` per the requested mode and assemble the BrowserItem list
	 * with folders first. Cheap — reads the cached FileEntry.sortKey string without touching
	 * the filesystem, so on 50k items this is pure-CPU ~10ms instead of the enumeration pass's
	 * ~500ms+ disk read. This is the fast half that runs on every refresh; enumerateFolder is
	 * the slow half that runs only on a cache miss (folder change).
	 *
	 * Sort key is the lowercase filename — for camera-app naming conventions that prefix
	 * filenames with capture date (20240101_*, PXL_20240101_*, IMG-20240101-WA0001.jpg),
	 * alphabetical filename order ≈ true chronological order. Switching to filename sort
	 * (away from lastModified) fixes the "restored years cluster at top because the restore
	 * touched their mtime" issue on backed-up Camera albums.
	 *
	 * @param snap cached folder snapshot from enumerateFolder
	 * @param mode active sort direction for FILE rows (folders are unaffected)
	 * @return EnumerationResult bundling the items list and the folder-count head offset
	 */
	private EnumerationResult sortAndAssemble(FolderSnapshot snap, SortMode mode)
	{
		List<FileEntry> sortedFiles = new ArrayList<>(snap.files());
		Comparator<FileEntry> byName = (mode == SortMode.NEWEST_FIRST)
			? (a, b) -> b.sortKey().compareTo(a.sortKey())
			: (a, b) -> a.sortKey().compareTo(b.sortKey());
		sortedFiles.sort(byName);
		List<BrowserItem> items = new ArrayList<>(snap.sortedFolders().size() + sortedFiles.size());
		for (File child : snap.sortedFolders())
		{
			items.add(new FolderRow(child));
		}
		for (FileEntry entry : sortedFiles)
		{
			items.add(new FileRow(entry.file()));
		}
		return new EnumerationResult(items, snap.sortedFolders().size());
	}

	/**
	 * Resolve the sort-toggle icon resource. The icon shows the OTHER sort direction (the one
	 * that a tap will switch into), matching the grid/list toggle convention. So when sorting
	 * newest first, the icon depicts oldest-first to advertise the tap action.
	 *
	 * @return drawable resource ID for the inverse of the current sortMode
	 */
	private int sortIconForCurrentMode()
	{
		return sortMode == SortMode.NEWEST_FIRST
			? R.drawable.ic_sort_oldest_first
			: R.drawable.ic_sort_newest_first;
	}

	/**
	 * TalkBack label for the sort toggle. Like the icon, it advertises the ACTION a tap will
	 * perform (switch to the OTHER direction) rather than the current state — accessibility
	 * services then announce the same forward-looking text that the icon visually depicts.
	 *
	 * @return action-describing description matching the sort-toggle icon currently shown
	 */
	private String sortToggleDescriptionForCurrentMode()
	{
		return sortMode == SortMode.NEWEST_FIRST
			? "Sort by oldest first"
			: "Sort by newest first";
	}

	/**
	 * Submit a bg thumbnail decode for `file` and post the resulting bitmap to `target` on the
	 * UI thread, with tag-based rebind protection so a fast scroll that recycles the ImageView
	 * to a different file doesn't end up painting the old file's bitmap onto the new cell.
	 *
	 * Cache-aware: the path-prefix cache lookup runs synchronously so a cache hit paints
	 * immediately without scheduling a bg task. On miss, the bg decode posts the bitmap back
	 * to the UI thread; the UI-thread post re-reads the tag and only paints when it still
	 * matches the file we submitted for. Bitmaps live in the process-wide THUMBNAIL_CACHE, so
	 * the stale-tag branch deliberately does NOT recycle — recycling a cached bitmap would
	 * corrupt the cache (other dialog instances and future cache hits would read pixels from
	 * a recycled native buffer).
	 *
	 * @param file           image file to decode
	 * @param target         ImageView to paint into on success; tag is set to a path+lastModified
	 *                       token before bg submission so the UI-post callback can detect a
	 *                       recycled cell and skip the paint
	 * @param allowBgSubmit  false when called from a bind that should NOT enqueue bg decode work
	 *                       (used during a fast-scroller drag — see decodeDeferred). Cache hits
	 *                       still paint immediately, but misses leave the cell blank; the
	 *                       triggerDecodeForVisibleCells re-bind at drag-end fills them in
	 * @return Future the caller can cancel on rebind, or null when no bg task was submitted
	 *         (cache hit, executor down, or allowBgSubmit=false on a cache miss)
	 */
	private Future<?> submitDecodeForCell(File file, AppCompatImageView target, boolean allowBgSubmit)
	{
		int displayTarget = Math.max(1, DpToPx.toPx(THUMBNAIL_DECODE_DP, density));
		String cellTag = file.getAbsolutePath() + ":" + file.lastModified();
		target.setTag(cellTag);
		Bitmap cached = THUMBNAIL_CACHE.get(cellTag + ":" + displayTarget);
		if (cached != null && !cached.isRecycled())
		{
			target.setImageBitmap(cached);
			return null;
		}
		target.setImageDrawable(null);
		if (!allowBgSubmit || bgExecutor == null || bgExecutor.isShutdown())
		{
			return null;
		}
		int gen = refreshGeneration;
		// submit (not execute) so the caller gets a Future it can cancel on rebind — without
		// cancellation, a fast scroll through a 50k-photo album queues hundreds of decode tasks
		// for cells that recycle past long before their decode reaches the head of the queue,
		// starving the currently-visible cells of decode budget. ViewHolders track the Future
		// and cancel it before submitting the next file's decode.
		return bgExecutor.submit(() ->
		{
			if (gen != refreshGeneration)
			{
				return;
			}
			Bitmap bmp = decodeThumbnail(file, displayTarget);
			if (bmp == null)
			{
				return;
			}
			uiHandler.post(() ->
			{
				if (gen == refreshGeneration && cellTag.equals(target.getTag()))
				{
					target.setImageBitmap(bmp);
				}
				// No recycle on stale tag/generation: the bitmap lives in THUMBNAIL_CACHE and a
				// recycle would corrupt the cache for any subsequent dialog or cache hit.
			});
		});
	}

	/**
	 * Flip the file sort direction in response to the sort-toggle tap. Persists the new direction
	 * to SharedPreferences, swaps the icon to advertise the next tap's action, and calls
	 * refresh() so the bg enumeration re-sorts the file list. Folder ordering doesn't change
	 * (always alphabetical) but going through refresh() keeps the rebuild path identical to a
	 * folder navigation — the same loading-then-populated UX applies.
	 */
	private void toggleSortMode()
	{
		sortMode = (sortMode == SortMode.NEWEST_FIRST)
			? SortMode.OLDEST_FIRST
			: SortMode.NEWEST_FIRST;
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			.edit().putString(KEY_SORT_MODE, sortMode.name()).apply();
		sortToggle.setImageResource(sortIconForCurrentMode());
		sortToggle.setContentDescription(sortToggleDescriptionForCurrentMode());
		refresh();
	}

	/**
	 * Flip the grid/list view mode in response to a toggle-icon tap. Swaps the layout
	 * manager's span count (3 for grid, 1 for list), persists the choice to SharedPreferences,
	 * and notifies the adapter so each visible cell re-binds against the new view-type
	 * dispatch (file rows become FILE_GRID vs FILE_LIST). Folder rows are unaffected — the
	 * SpanSizeLookup keeps them full-width in both modes.
	 */
	private void toggleViewMode()
	{
		gridMode = !gridMode;
		viewModeToggle.setImageResource(gridMode ? R.drawable.ic_view_list : R.drawable.ic_view_grid);
		viewModeToggle.setContentDescription(viewModeToggleDescriptionForCurrentMode());
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			.edit().putBoolean(KEY_GRID_MODE, gridMode).apply();
		layoutManager.setSpanCount(gridMode ? THUMBNAIL_GRID_COLUMNS : 1);
		adapter.notifyDataSetChanged();
	}

	/**
	 * Iterate the RecyclerView's currently-visible cells and re-call bind() on each so
	 * decodeDeferred=false picks up where the deferred-mode binds left off. Used by
	 * setDecodeDeferred(false) (drag-end) to fill in the thumbnails for the final scroll
	 * position without waiting for the user to scroll again.
	 */
	private void triggerDecodeForVisibleCells()
	{
		if (layoutManager == null || recyclerView == null)
		{
			return;
		}
		int firstVisible = layoutManager.findFirstVisibleItemPosition();
		int lastVisible = layoutManager.findLastVisibleItemPosition();
		if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION)
		{
			return;
		}
		int itemCount = adapter.getItemCount();
		for (int pos = firstVisible; pos <= lastVisible && pos < itemCount; pos++)
		{
			RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(pos);
			if (holder == null)
			{
				continue;
			}
			BrowserItem item = adapter.items.get(pos);
			if (!(item instanceof FileRow fileRow))
			{
				continue;
			}
			File file = fileRow.file();
			if (holder instanceof FileGridViewHolder gridHolder)
			{
				gridHolder.bind(file);
			}
			else if (holder instanceof FileListViewHolder listHolder)
			{
				listHolder.bind(file);
			}
		}
	}

	/**
	 * TalkBack label for the grid/list toggle. Like the icon (which depicts the OTHER mode),
	 * the description advertises the action a tap will perform so accessibility services
	 * announce the same forward-looking text.
	 *
	 * @return action-describing description matching the view-mode-toggle icon currently shown
	 */
	private String viewModeToggleDescriptionForCurrentMode()
	{
		return gridMode ? "Switch to list view" : "Switch to grid view";
	}
}
