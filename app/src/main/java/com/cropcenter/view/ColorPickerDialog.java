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

import com.cropcenter.util.ThemeColors;

import java.util.Locale;

/**
 * Color picker: grid of preset colors, alpha slider, and hex input. The hex field always
 * reflects the current selection and its background acts as the live preview.
 */
public class ColorPickerDialog
{
	public interface OnColorSelectedListener
	{
		void onColorSelected(int color);
	}

	// Standard 8x6 palette: mostly opaque colors, with a row of common translucents at bottom.
	public static final int[] PALETTE_OPAQUE = {
		0xFFFF0000, 0xFFFF8000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0080FF, 0xFF0000FF, 0xFFFF00FF,
		0xFFCC0000, 0xFFCC6600, 0xFFCCCC00, 0xFF00CC00, 0xFF00CCCC, 0xFF0066CC, 0xFF0000CC, 0xFFCC00CC,
		0xFF993333, 0xFF996633, 0xFF999933, 0xFF339933, 0xFF339999, 0xFF336699, 0xFF333399, 0xFF993399,
		0xFFFF9999, 0xFFFFCC99, 0xFFFFFF99, 0xFF99FF99, 0xFF99FFFF, 0xFF99CCFF, 0xFF9999FF, 0xFFFF99FF,
		0xFFFFFFFF, 0xFFDDDDDD, 0xFFAAAAAA, 0xFF888888, 0xFF555555, 0xFF333333, 0xFF111111, 0xFF000000,
		0x80FFFFFF, 0x80FF0000, 0x80FFFF00, 0x8000FF00, 0x8000FFFF, 0x800000FF, 0x80FF00FF, 0x80000000,
	};

	// Translucent-first palette for selection / paint overlays. All saturated colors at 50% alpha
	// so selections don't obscure the image. Bottom row offers opaque fallbacks when needed.
	public static final int[] PALETTE_TRANSLUCENT = {
		0x80FF0000, 0x80FF8000, 0x80FFFF00, 0x8000FF00, 0x8000FFFF, 0x800080FF, 0x800000FF, 0x80FF00FF,
		0x80CC0000, 0x80CC6600, 0x80CCCC00, 0x8000CC00, 0x8000CCCC, 0x800066CC, 0x800000CC, 0x80CC00CC,
		0x80993333, 0x80996633, 0x80999933, 0x80339933, 0x80339999, 0x80336699, 0x80333399, 0x80993399,
		0x80FF9999, 0x80FFCC99, 0x80FFFF99, 0x8099FF99, 0x8099FFFF, 0x8099CCFF, 0x809999FF, 0x80FF99FF,
		0x80FFFFFF, 0x80DDDDDD, 0x80AAAAAA, 0x80888888, 0x80555555, 0x80333333, 0x80111111, 0x80000000,
		// Bottom row: opaque fallbacks
		0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFF00FFFF, 0xFFFF00FF,
	};

	private static final int COLS = 8;
	private static final int LUMA_CONTRAST_CUTOFF = 140; // above this → dark text on the swatch
	private static final int ROWS = 6;

	public static void show(Context context, int currentColor, OnColorSelectedListener listener)
	{
		show(context, currentColor, PALETTE_OPAQUE, listener);
	}

	public static void show(Context context, int currentColor, int[] palette,
		OnColorSelectedListener listener)
	{
		float density = context.getResources().getDisplayMetrics().density;
		int dp = (int) density;

		LinearLayout root = new LinearLayout(context);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(12 * dp, 8 * dp, 12 * dp, 4 * dp);

		final int[] selected = { currentColor };

		// Color grid
		int cellSize = (int) (36 * density);
		ColorGridView grid = new ColorGridView(context, palette, COLS, ROWS, cellSize, currentColor);
		LinearLayout.LayoutParams gridLP = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		root.addView(grid, gridLP);

		// Alpha slider row
		LinearLayout alphaRow = new LinearLayout(context);
		alphaRow.setOrientation(LinearLayout.HORIZONTAL);
		alphaRow.setGravity(Gravity.CENTER_VERTICAL);
		TextView lblA = new TextView(context);
		lblA.setText("Opacity ");
		lblA.setTextSize(12);
		lblA.setTextColor(ThemeColors.SUBTEXT0);
		alphaRow.addView(lblA);
		SeekBar seekA = new SeekBar(context);
		seekA.setMax(255);
		seekA.setProgress(Color.alpha(currentColor));
		alphaRow.addView(seekA, new LinearLayout.LayoutParams(
			0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		TextView txtA = new TextView(context);
		txtA.setText(String.valueOf(Color.alpha(currentColor)));
		txtA.setTextSize(11);
		txtA.setTextColor(ThemeColors.MAUVE);
		txtA.setMinWidth(28 * dp);
		txtA.setGravity(Gravity.END);
		alphaRow.addView(txtA);
		LinearLayout.LayoutParams aLP = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		aLP.topMargin = 6 * dp;
		root.addView(alphaRow, aLP);

		// Hex input — its background acts as the live preview
		EditText hexInput = new EditText(context);
		hexInput.setTextSize(14);
		hexInput.setSingleLine(true);
		hexInput.setGravity(Gravity.CENTER);
		hexInput.setHint("#AARRGGBB");
		hexInput.setPadding(12 * dp, 10 * dp, 12 * dp, 10 * dp);
		LinearLayout.LayoutParams hLP = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, 44 * dp);
		hLP.topMargin = 8 * dp;
		root.addView(hexInput, hLP);

		// Helper: update the hex text and background to reflect selected[0].
		// The "suppress" flag prevents the TextWatcher from re-entering when WE change the text.
		final boolean[] suppressHexWatcher = { false };
		Runnable syncHexToSelection = () ->
		{
			suppressHexWatcher[0] = true;
			hexInput.setText(String.format(Locale.ROOT, "#%08X", selected[0]));
			suppressHexWatcher[0] = false;
			applySwatchPreview(hexInput, selected[0], density);
		};
		syncHexToSelection.run();

		// Grid tap → update selection, alpha slider, hex
		grid.setOnColorTapListener(color ->
		{
			selected[0] = color;
			seekA.setProgress(Color.alpha(color));
			// alpha listener updates txtA; sync hex here since slider may not fire if alpha
			// unchanged
			syncHexToSelection.run();
		});

		// Alpha slider → update alpha channel + hex
		seekA.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
		{
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
			{
				selected[0] = (selected[0] & 0x00FFFFFF) | (progress << 24);
				txtA.setText(String.valueOf(progress));
				syncHexToSelection.run();
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {}
		});

		// Hex edit → parse and update selection + alpha slider (suppressed during programmatic
		// updates)
		hexInput.addTextChangedListener(new TextWatcher()
		{
			@Override
			public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence text, int start, int before, int count) {}

			@Override
			public void afterTextChanged(Editable editable)
			{
				if (suppressHexWatcher[0])
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
						selected[0] = parsed;
						// Update alpha slider WITHOUT re-triggering syncHexToSelection
						int alpha = Color.alpha(parsed);
						if (seekA.getProgress() != alpha)
						{
							seekA.setProgress(alpha);
						}
						// Update preview background (don't overwrite text the user is typing)
						applySwatchPreview(hexInput, parsed, density);
					}
				}
				catch (NumberFormatException ignored)
				{
				}
			}
		});

		new AlertDialog.Builder(context)
			.setTitle("Pick Color")
			.setView(root)
			.setPositiveButton("OK", (dialog, which) -> listener.onColorSelected(selected[0]))
			.setNegativeButton("Cancel", null)
			.show();
	}

	/**
	 * Paint the hex EditText's background with the current color and pick a contrasting text color
	 * (ITU-R BT.601 luma: Y' = 0.299R + 0.587G + 0.114B).
	 */
	private static void applySwatchPreview(EditText hexInput, int color, float density)
	{
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(color);
		bg.setCornerRadius(4 * density);
		bg.setStroke(1, ThemeColors.SURFACE1);
		hexInput.setBackground(bg);
		int r = (color >> 16) & 0xFF;
		int g = (color >> 8) & 0xFF;
		int b = color & 0xFF;
		float lum = 0.299f * r + 0.587f * g + 0.114f * b;
		hexInput.setTextColor(lum > LUMA_CONTRAST_CUTOFF ? Color.BLACK : Color.WHITE);
	}

	/**
	 * Grid view that draws color swatches in a tap-to-select grid.
	 */
	private static class ColorGridView extends View
	{
		interface OnColorTapListener
		{
			void onTap(int color);
		}

		private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final int cellSize;
		private final int cols;
		private final int[] colors;
		private OnColorTapListener listener;
		private final Paint paint = new Paint();
		private final int rows;
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
				int idx = row * cols + col;
				if (idx >= 0 && idx < colors.length)
				{
					selectedColor = colors[idx];
					if (listener != null)
					{
						listener.onTap(colors[idx]);
					}
					invalidate();
				}
				return true;
			}
			return super.onTouchEvent(event);
		}

		void setOnColorTapListener(OnColorTapListener listener)
		{
			this.listener = listener;
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
					paint.setColor(0xFFCCCCCC);
					canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paint);
					paint.setColor(0xFF999999);
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
	}
}
