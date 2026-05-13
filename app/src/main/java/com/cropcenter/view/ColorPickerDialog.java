package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.cropcenter.util.DpToPx;
import com.cropcenter.util.ThemeColors;

import java.util.Locale;

/**
 * Color picker: grid of preset colors, alpha slider, and hex input. The hex field always reflects the current selection
 * and its background acts as the live preview.
 *
 * Each call to show() builds and shows a single dialog. The class itself is instance-scoped (one instance per
 * dialog open) so the per-dialog mutable state (current selection, hex-watcher suppression flag, view references) lives
 * as ordinary fields rather than being smuggled through 1-element arrays into static helper signatures.
 */
public final class ColorPickerDialog
{
	/**
	 * Fires when the user confirms a color choice (taps OK). Cancellation does not invoke this.
	 */
	public interface OnColorSelectedListener
	{
		/**
		 * @param color the chosen color as a packed ARGB int
		 */
		void onColorSelected(int color);
	}

	/**
	 * Grid view that draws color swatches in a tap-to-select grid.
	 */
	private static final class ColorGridView extends View
	{
		interface OnColorTapListener
		{
			void onTap(int color);
		}

		// Two-tile alpha checkerboard for the transparent-color visualisation. Light tile fills the cell,
		// dark tile diagonals overlay (top-left + bottom-right quadrants) — same pattern as Photoshop's
		// transparency grid so users immediately read the cell as "alpha < 255". Class-private so a future
		// theme tweak lands in one spot rather than four `0xFF...` literals scattered through onDraw.
		private static final int CHECKERBOARD_DARK = 0xFF999999;
		private static final int CHECKERBOARD_LIGHT = 0xFFCCCCCC;

		private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint paint = new Paint();
		private final int cellSize;
		private final int[] colors;
		private final int cols;
		private final int rows;
		private OnColorTapListener listener;
		private int selectedColor;

		ColorGridView(Context ctx, int[] colors, int cols, int rows, int cellSize, int selectedColor)
		{
			super(ctx);
			this.colors = colors;
			this.cols = cols;
			this.rows = rows;
			this.cellSize = cellSize;
			this.selectedColor = selectedColor;
			borderPaint.setStyle(Paint.Style.STROKE);
			borderPaint.setStrokeWidth(3);
			borderPaint.setColor(ThemeColors.MAUVE);
		}

		@Override
		public boolean onTouchEvent(MotionEvent event)
		{
			if (event.getAction() == MotionEvent.ACTION_DOWN)
			{
				int col = (int) (event.getX() / (getWidth() / (float) cols));
				int row = (int) (event.getY() / (getHeight() / (float) rows));
				// Hard-reject out-of-grid coords. A touch at exactly event.getX() == getWidth() yields
				// col == cols, which folds into the next row's first cell via row-major
				// (idx = row * cols + cols still satisfies `idx < colors.length` for any row except the
				// last). Bottom edge is symmetric. Without the explicit col/row range check, the
				// boundary tap selects an unintended swatch instead of being ignored.
				if (col < 0 || col >= cols || row < 0 || row >= rows)
				{
					return true;
				}
				int idx = row * cols + col;
				if (idx >= 0 && idx < colors.length)
				{
					selectedColor = colors[idx];
					if (listener != null)
					{
						listener.onTap(colors[idx]);
					}
					invalidate();
					// Route through performClick so accessibility services see the swatch tap and
					// any OnClickListener can replicate the selection programmatically.
					performClick();
				}
				return true;
			}
			return super.onTouchEvent(event);
		}

		@Override
		public boolean performClick()
		{
			// Defer to super for the standard click sound + accessibility events; the swatch selection
			// itself is performed at ACTION_DOWN above. Required by the View / accessibility contract:
			// any view that handles touch events must also expose a programmatic click path so a11y
			// services can replicate the touch interaction.
			return super.performClick();
		}

		@Override
		protected void onDraw(Canvas canvas)
		{
			int cellWidth = getWidth() / cols;
			int cellHeight = getHeight() / rows;
			for (int i = 0; i < colors.length && i < cols * rows; i++)
			{
				int col = i % cols;
				int row = i / cols;
				float left = col * cellWidth;
				float top = row * cellHeight;
				// Checkerboard behind transparent cells
				if (Color.alpha(colors[i]) < 255)
				{
					paint.setColor(CHECKERBOARD_LIGHT);
					canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paint);
					paint.setColor(CHECKERBOARD_DARK);
					canvas.drawRect(left, top, left + cellWidth / 2f, top + cellHeight / 2f, paint);
					canvas.drawRect(left + cellWidth / 2f, top + cellHeight / 2f,
						left + cellWidth, top + cellHeight, paint);
				}
				paint.setColor(colors[i]);
				canvas.drawRect(left + 1, top + 1, left + cellWidth - 1, top + cellHeight - 1, paint);
				if (colors[i] == selectedColor)
				{
					canvas.drawRect(left + 1, top + 1,
						left + cellWidth - 1, top + cellHeight - 1, borderPaint);
				}
			}
		}

		@Override
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
		{
			setMeasuredDimension(cols * cellSize, rows * cellSize);
		}

		void setOnColorTapListener(OnColorTapListener listener)
		{
			this.listener = listener;
		}

		/**
		 * Update the highlighted swatch when the color was changed from outside the grid (hex input or alpha
		 * slider). Re-invalidates so the mauve ring moves off the previously-tapped swatch and onto whichever
		 * palette entry matches the new color, or off the grid entirely when no swatch matches. Without this,
		 * the grid's ring goes stale and the user sees a UI that lies about which color is active.
		 */
		void setSelectedColor(int color)
		{
			if (this.selectedColor != color)
			{
				this.selectedColor = color;
				invalidate();
			}
		}
	}

	private static final int COLS = 8;
	private static final int LUMA_CONTRAST_CUTOFF = 140; // above this → dark text on the swatch
	private static final int ROWS = 6;

	// Standard 8x6 palette: mostly opaque colors, with a row of common translucents at bottom.
	public static final int[] PALETTE_OPAQUE = {
		0xFFFF0000, 0xFFFF8000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0080FF, 0xFF0000FF, 0xFFFF00FF,
		0xFFCC0000, 0xFFCC6600, 0xFFCCCC00, 0xFF00CC00, 0xFF00CCCC, 0xFF0066CC, 0xFF0000CC, 0xFFCC00CC,
		0xFF993333, 0xFF996633, 0xFF999933, 0xFF339933, 0xFF339999, 0xFF336699, 0xFF333399, 0xFF993399,
		0xFFFF9999, 0xFFFFCC99, 0xFFFFFF99, 0xFF99FF99, 0xFF99FFFF, 0xFF99CCFF, 0xFF9999FF, 0xFFFF99FF,
		0xFFFFFFFF, 0xFFDDDDDD, 0xFFAAAAAA, 0xFF888888, 0xFF555555, 0xFF333333, 0xFF111111, 0xFF000000,
		0x80FFFFFF, 0x80FF0000, 0x80FFFF00, 0x8000FF00, 0x8000FFFF, 0x800000FF, 0x80FF00FF, 0x80000000,
	};

	// Translucent-first palette for selection / paint overlays. All saturated colors at 50% alpha so selections
	// don't obscure the image. Bottom row offers opaque fallbacks when needed.
	public static final int[] PALETTE_TRANSLUCENT = {
		0x80FF0000, 0x80FF8000, 0x80FFFF00, 0x8000FF00, 0x8000FFFF, 0x800080FF, 0x800000FF, 0x80FF00FF,
		0x80CC0000, 0x80CC6600, 0x80CCCC00, 0x8000CC00, 0x8000CCCC, 0x800066CC, 0x800000CC, 0x80CC00CC,
		0x80993333, 0x80996633, 0x80999933, 0x80339933, 0x80339999, 0x80336699, 0x80333399, 0x80993399,
		0x80FF9999, 0x80FFCC99, 0x80FFFF99, 0x8099FF99, 0x8099FFFF, 0x8099CCFF, 0x809999FF, 0x80FF99FF,
		0x80FFFFFF, 0x80DDDDDD, 0x80AAAAAA, 0x80888888, 0x80555555, 0x80333333, 0x80111111, 0x80000000,
		// Bottom row: opaque fallbacks
		0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFF00FFFF, 0xFFFF00FF,
	};

	// Per-dialog instance state. ColorPickerDialog is constructed fresh from show() for each open; these fields
	// hold the values that the previous static-helper version smuggled through 1-element arrays (`int[] selected`,
	// `boolean[] suppressHexWatcher`) plus the view references the wireXxx methods need to cross-reference.
	private final Context context;
	private final OnColorSelectedListener listener;
	private final float density;
	private final int[] palette;

	private ColorGridView grid;
	private EditText hexInput;
	private SeekBar alphaSeekBar;
	private TextView alphaValueText;
	private boolean suppressHexWatcher;
	private int selected;

	private ColorPickerDialog(Context context, int currentColor, int[] palette, OnColorSelectedListener listener)
	{
		this.context = context;
		this.density = context.getResources().getDisplayMetrics().density;
		this.listener = listener;
		this.palette = palette;
		this.selected = currentColor;
	}

	/**
	 * Build and show the picker. Constructs a fresh ColorPickerDialog instance to hold per-dialog state, then
	 * delegates to its private buildAndShow.
	 *
	 * Returns the AlertDialog so the caller can track and cancel it — SettingsDialog opens pickers from
	 * its swatch click handlers and needs to cancel any open picker when the parent dialog is cancelled
	 * (Codex round-17 F2). Without that propagation, a stale picker outliving SettingsDialog could fire
	 * its OK listener and mutate state.gridConfig after the parent dialog had been forced-cancelled by an
	 * inbound load.
	 *
	 * @param context      Activity context for inflation
	 * @param currentColor initial color (ARGB) to highlight + show in the hex input
	 * @param palette      grid palette to display (PALETTE_OPAQUE or PALETTE_TRANSLUCENT)
	 * @param listener     invoked on OK with the chosen color; never on Cancel
	 * @return the shown AlertDialog (caller tracks for forced-cancel propagation)
	 */
	public static AlertDialog show(Context context, int currentColor, int[] palette,
		OnColorSelectedListener listener)
	{
		return new ColorPickerDialog(context, currentColor, palette, listener).buildAndShow();
	}

	/**
	 * Paint the hex EditText's background with the current color and pick a contrasting text color (ITU-R BT.601
	 * luma: Y' = 0.299R + 0.587G + 0.114B).
	 */
	private void applySwatchPreview(int color)
	{
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(color);
		bg.setCornerRadius(DpToPx.toPx(4, density));
		bg.setStroke(1, ThemeColors.SURFACE1);
		hexInput.setBackground(bg);
		int r = (color >> 16) & 0xFF;
		int g = (color >> 8) & 0xFF;
		int b = color & 0xFF;
		float lum = 0.299f * r + 0.587f * g + 0.114f * b;
		hexInput.setTextColor(lum > LUMA_CONTRAST_CUTOFF ? Color.BLACK : Color.WHITE);
	}

	/**
	 * Build and attach the "Opacity" slider row. Stores `alphaSeekBar` and `alphaValueText` as fields so the wire
	 * methods can reach them without an explicit return value.
	 */
	private void buildAlphaRow(LinearLayout root)
	{
		LinearLayout alphaRow = new LinearLayout(context);
		alphaRow.setOrientation(LinearLayout.HORIZONTAL);
		alphaRow.setGravity(Gravity.CENTER_VERTICAL);

		TextView alphaLabel = new TextView(context);
		alphaLabel.setText("Opacity ");
		alphaLabel.setTextSize(12);
		alphaLabel.setTextColor(ThemeColors.SUBTEXT0);
		alphaRow.addView(alphaLabel);

		alphaSeekBar = new SeekBar(context);
		alphaSeekBar.setMax(255);
		alphaSeekBar.setProgress(Color.alpha(selected));
		alphaRow.addView(alphaSeekBar, new LinearLayout.LayoutParams(
			0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

		alphaValueText = new TextView(context);
		alphaValueText.setText(String.valueOf(Color.alpha(selected)));
		alphaValueText.setTextSize(11);
		alphaValueText.setTextColor(ThemeColors.MAUVE);
		alphaValueText.setMinWidth(DpToPx.toPx(28, density));
		alphaValueText.setGravity(Gravity.END);
		alphaRow.addView(alphaValueText);

		LinearLayout.LayoutParams alphaRowLayoutParams = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		alphaRowLayoutParams.topMargin = DpToPx.toPx(6, density);
		root.addView(alphaRow, alphaRowLayoutParams);
	}

	/**
	 * Assemble the full picker layout, wire all interactions, and show the dialog. Each component-build method
	 * stashes its created view(s) on instance fields; each wireXxx method then reads those fields to set up
	 * cross-component listener callbacks. The 1-element-array smuggle hack (selected / suppressHexWatcher) is gone
	 * — both are ordinary instance fields now.
	 *
	 * @return the shown AlertDialog (returned to caller for forced-cancel propagation; see show)
	 */
	private AlertDialog buildAndShow()
	{
		LinearLayout root = new LinearLayout(context);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(DpToPx.toPx(12, density), DpToPx.toPx(8, density),
			DpToPx.toPx(12, density), DpToPx.toPx(4, density));

		int cellSize = DpToPx.toPx(36, density);
		grid = new ColorGridView(context, palette, COLS, ROWS, cellSize, selected);
		root.addView(grid, new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

		buildAlphaRow(root);
		buildHexInput(root);

		syncHexDisplay();

		wireGridTap();
		wireAlphaSlider();
		wireHexInput();

		return new AlertDialog.Builder(context)
			.setTitle("Pick Color")
			.setView(root)
			.setPositiveButton(DialogStrings.OK, (dialog, which) -> listener.onColorSelected(selected))
			.setNegativeButton(DialogStrings.CANCEL, null)
			.show();
	}

	/**
	 * Build and attach the hex-code EditText. Background acts as the live swatch preview.
	 */
	private void buildHexInput(LinearLayout root)
	{
		hexInput = new EditText(context);
		hexInput.setTextSize(14);
		hexInput.setSingleLine(true);
		hexInput.setGravity(Gravity.CENTER);
		hexInput.setHint("#AARRGGBB");
		int hexPadHor = DpToPx.toPx(12, density);
		int hexPadVer = DpToPx.toPx(10, density);
		hexInput.setPadding(hexPadHor, hexPadVer, hexPadHor, hexPadVer);
		LinearLayout.LayoutParams hexInputLayoutParams = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, DpToPx.toPx(44, density));
		hexInputLayoutParams.topMargin = DpToPx.toPx(8, density);
		root.addView(hexInput, hexInputLayoutParams);
	}

	/**
	 * Push the current selection into the hex EditText + swatch background without re-entering the TextWatcher (the
	 * suppress flag shields afterTextChanged).
	 */
	private void syncHexDisplay()
	{
		suppressHexWatcher = true;
		hexInput.setText(String.format(Locale.ROOT, "#%08X", selected));
		suppressHexWatcher = false;
		applySwatchPreview(selected);
	}

	/**
	 * Alpha slider → update alpha channel + hex. Split from onStart/onStop which stay empty — SeekBar's interface
	 * requires all three.
	 */
	private void wireAlphaSlider()
	{
		alphaSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
		{
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
			{
				selected = (selected & 0x00FFFFFF) | (progress << 24);
				alphaValueText.setText(String.valueOf(progress));
				syncHexDisplay();
				// Alpha change produces a new ARGB value that may or may not still match a palette
				// entry. Keep the grid's highlighted swatch honest.
				grid.setSelectedColor(selected);
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {}
		});
	}

	/**
	 * Grid tap → update selection, alpha slider, hex. The alpha listener updates its own value label; the explicit
	 * syncHexDisplay call here covers the case where the tapped color's alpha matches the current slider position
	 * so the listener wouldn't fire.
	 */
	private void wireGridTap()
	{
		grid.setOnColorTapListener(color ->
		{
			selected = color;
			alphaSeekBar.setProgress(Color.alpha(color));
			syncHexDisplay();
		});
	}

	/**
	 * Hex EditText → parse and update selection + alpha slider. The suppress flag prevents programmatic setText
	 * from re-entering this watcher.
	 */
	private void wireHexInput()
	{
		hexInput.addTextChangedListener(new TextWatcher()
		{
			@Override
			public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence text, int start, int before, int count) {}

			@Override
			public void afterTextChanged(Editable editable)
			{
				if (suppressHexWatcher)
				{
					return;
				}
				try
				{
					String hex = editable.toString().trim();
					if (hex.startsWith("#"))
					{
						hex = hex.substring(1);
					}
					if (hex.length() == 6)
					{
						hex = "FF" + hex;
					}
					if (hex.length() == 8)
					{
						int parsed = (int) Long.parseLong(hex, 16);
						selected = parsed;
						int alpha = Color.alpha(parsed);
						if (alphaSeekBar.getProgress() != alpha)
						{
							alphaSeekBar.setProgress(alpha);
						}
						// Preview-only update — don't overwrite what the user is typing.
						applySwatchPreview(parsed);
						// Keep the grid's highlighted swatch in sync with the typed color.
						// Without this, the mauve ring stays on the last-tapped palette entry
						// even after the user types a different color — the grid visually lies
						// about which color is active.
						grid.setSelectedColor(parsed);
					}
				}
				catch (NumberFormatException ignored)
				{
					// afterTextChanged fires per keystroke; partial states ("F", "FF", "#1Z") are
					// expected — wait for a full 6- or 8-digit hex before reflecting any change.
				}
			}
		});
	}
}
