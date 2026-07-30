package com.cropcenter.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cropcenter.util.DpToPx;
import com.cropcenter.util.ThemeColors;

/**
 * Shared card-layout primitives for the SettingsDialog and SaveDialog (and any future dialog that adopts the same
 * Catppuccin Mocha visual language). Keeps the two dialogs rendering identically without hand-copying the same private
 * static helpers.
 */
final class DialogCards
{
	private DialogCards() {}

	/**
	 * Add a bold mauve title TextView at the top of a card container. Caller is responsible for the card's
	 * enclosing vertical-margin spacing; this helper only writes the title row.
	 *
	 * @param card container that receives the new title TextView (added as the last child)
	 * @param text title text to display in mauve bold
	 */
	static void addCardTitle(LinearLayout card, String text)
	{
		TextView title = new TextView(card.getContext());
		title.setText(text);
		title.setTextSize(13);
		title.setTextColor(ThemeColors.MAUVE);
		title.setTypeface(title.getTypeface(), Typeface.BOLD);
		card.addView(title);
	}

	/**
	 * Rounded-corner vertical card container for grouping related dialog controls. Uses the SURFACE0 background + 8
	 * dp corner radius + 12 / 10 dp padding the dialogs share.
	 *
	 * @param ctx     hosting Context for the new LinearLayout / GradientDrawable
	 * @param density display density (dp → px conversion factor); pass `getResources().getDisplayMetrics().density`
	 * @return a fresh empty LinearLayout (vertical orientation) ready to receive child views
	 */
	static LinearLayout newCard(Context ctx, float density)
	{
		LinearLayout card = new LinearLayout(ctx);
		styleCard(card, density);
		return card;
	}

	/**
	 * Mauve-tinted checkbox shared by the save-options and settings dialogs: TEXT-colored 12sp label with a MAUVE
	 * button tint.
	 *
	 * @param ctx     hosting Context for the new CheckBox
	 * @param text    checkbox label
	 * @param checked initial checked state
	 * @return the configured CheckBox, ready to add to a card
	 */
	static CheckBox newMauveCheckBox(Context ctx, String text, boolean checked)
	{
		CheckBox box = new CheckBox(ctx);
		box.setText(text);
		box.setTextSize(12);
		box.setTextColor(ThemeColors.TEXT);
		box.setChecked(checked);
		box.setButtonTintList(ColorStateList.valueOf(ThemeColors.MAUVE));
		return box;
	}

	/**
	 * Bold centered single-line toggle chip (13sp) used for the paired JPEG / PNG format selectors. Carries no
	 * width of its own — callers size it via layout_weight in a horizontal row — and no background: styleChipPair
	 * paints the selected / unselected state.
	 *
	 * @param ctx           hosting Context for the new TextView
	 * @param text          chip label
	 * @param density       display density (dp → px conversion factor)
	 * @param verticalPadDp symmetric vertical padding in dp (per-dialog tap-target tuning)
	 * @return the styled chip, ready for a weighted row slot
	 */
	static TextView newToggleChip(Context ctx, String text, float density, int verticalPadDp)
	{
		TextView btn = new TextView(ctx);
		btn.setText(text);
		btn.setTextSize(13);
		btn.setGravity(Gravity.CENTER);
		btn.setTypeface(btn.getTypeface(), Typeface.BOLD);
		int verticalPad = DpToPx.toPx(verticalPadDp, density);
		btn.setPadding(0, verticalPad, 0, verticalPad);
		btn.setSingleLine(true);
		return btn;
	}

	/**
	 * Apply the shared card styling (vertical orientation + SURFACE0 fill + 8 dp corner radius + 12 / 10 dp
	 * padding) to an existing LinearLayout. Split out of newCard so callers that need a LinearLayout subclass (e.g.
	 * FolderBrowser's height-capped panel, which overrides onMeasure) can still get the canonical card look without
	 * duplicating the GradientDrawable / padding setup.
	 *
	 * @param card    LinearLayout to style in place; orientation is forced VERTICAL
	 * @param density display density (dp → px conversion factor)
	 */
	static void styleCard(LinearLayout card, float density)
	{
		card.setOrientation(LinearLayout.VERTICAL);
		GradientDrawable background = new GradientDrawable();
		background.setColor(ThemeColors.SURFACE0);
		background.setCornerRadius(DpToPx.toPx(8, density));
		card.setBackground(background);
		int padding = DpToPx.toPx(12, density);
		card.setPadding(padding, DpToPx.toPx(10, density), padding, DpToPx.toPx(10, density));
	}

	/**
	 * Style a mutually-exclusive chip pair's selected state: the selected chip gets a MAUVE fill + CRUST text,
	 * the unselected chip a SURFACE1 fill + TEXT text — the single home of the toggle-chip selection palette.
	 *
	 * @param first         first chip of the pair
	 * @param second        second chip of the pair; always styled opposite to first
	 * @param firstSelected true when the first chip is the current selection
	 * @param density       display density (dp → px conversion factor)
	 * @param cornerDp      corner radius in dp (per-dialog shape tuning)
	 */
	static void styleChipPair(TextView first, TextView second, boolean firstSelected, float density, int cornerDp)
	{
		float cornerPx = DpToPx.toPx(cornerDp, density);
		styleToggleChip(first, firstSelected, cornerPx);
		styleToggleChip(second, !firstSelected, cornerPx);
	}

	/**
	 * MATCH_PARENT × WRAP_CONTENT LayoutParams with a top margin — used to space cards vertically inside a dialog's
	 * scroll view.
	 *
	 * @param margin top margin in pixels (caller is responsible for the dp → px conversion via DpToPx.toPx)
	 * @return fresh LinearLayout.LayoutParams sized MATCH_PARENT × WRAP_CONTENT with topMargin set; safe for
	 *         the caller to mutate further
	 */
	static LinearLayout.LayoutParams topMargin(int margin)
	{
		LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		layoutParams.topMargin = margin;
		return layoutParams;
	}

	private static void styleToggleChip(TextView chip, boolean selected, float cornerPx)
	{
		GradientDrawable background = new GradientDrawable();
		background.setColor(selected ? ThemeColors.MAUVE : ThemeColors.SURFACE1);
		background.setCornerRadius(cornerPx);
		chip.setBackground(background);
		chip.setTextColor(selected ? ThemeColors.CRUST : ThemeColors.TEXT);
	}
}
