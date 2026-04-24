package com.cropcenter.view;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cropcenter.util.ThemeColors;

/**
 * Shared card-layout primitives for the SettingsDialog and SaveDialog (and any future
 * dialog that adopts the same Catppuccin Mocha visual language). Keeps the two dialogs
 * rendering identically without hand-copying the same private static helpers.
 */
final class DialogCards
{
	private DialogCards() {}

	/**
	 * Add a bold mauve title TextView at the top of a card container.
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
	 * Rounded-corner vertical card container for grouping related dialog controls. Uses the
	 * SURFACE0 background + 8 dp corner radius + 12 / 10 dp padding the dialogs share.
	 */
	static LinearLayout newCard(Context ctx, float density)
	{
		LinearLayout card = new LinearLayout(ctx);
		card.setOrientation(LinearLayout.VERTICAL);
		GradientDrawable background = new GradientDrawable();
		background.setColor(ThemeColors.SURFACE0);
		background.setCornerRadius(8 * density);
		card.setBackground(background);
		int padding = (int) (12 * density);
		card.setPadding(padding, (int) (10 * density), padding, (int) (10 * density));
		return card;
	}

	/**
	 * MATCH_PARENT × WRAP_CONTENT LayoutParams with a top margin — used to space cards
	 * vertically inside a dialog's scroll view.
	 */
	static LinearLayout.LayoutParams topMargin(int margin)
	{
		LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		layoutParams.topMargin = margin;
		return layoutParams;
	}
}
