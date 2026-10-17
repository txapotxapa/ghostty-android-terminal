/* SPDX-License-Identifier: Apache-2.0 */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.sylirre.terminal.R;

/**
 * The app's bottom navigation — Home (the agent board), Terminal, Activity
 * and Settings — under every main screen: the board, each of its
 * sub-screens and the terminal, keyboard up or not, so home and settings are
 * always one tap away. It never hides, and its height never changes, so it
 * never resizes the terminal (no SIGWINCH to the shell).
 *
 * Home, Terminal and Activity are places: the current one wears an
 * indicator pill and the strong ink. Settings opens its own screen and is
 * never current. Home and Activity carry count badges (agents waiting on
 * you), fed by the board page. {@link MainActivity} recolors the bar for the
 * screen it sits under (the board's tokens, or the terminal theme's chrome).
 */
public class NavBarView extends LinearLayout {

    public static final int HOME = 0;
    public static final int TERMINAL = 1;
    public static final int ACTIVITY = 2;
    public static final int SETTINGS = 3;

    public interface Listener {
        void onNavItem(int item);
    }

    private static final int[] IDS = {
            R.id.nav_home, R.id.nav_terminal, R.id.nav_activity, R.id.settings_button};
    private static final int[] ICONS = {
            R.drawable.ic_glyph_home, R.drawable.ic_glyph_terminal,
            R.drawable.ic_glyph_activity, R.drawable.ic_glyph_settings};
    private static final int[] LABELS = {
            R.string.nav_home, R.string.nav_terminal, R.string.nav_activity, R.string.nav_settings};

    /** One destination: indicator pill (icon + badge) over a label. */
    private static final class Item {
        final LinearLayout root;
        final FrameLayout pill;
        final ImageView icon;
        final TextView label;
        final TextView badge;
        int count;

        Item(LinearLayout root, FrameLayout pill, ImageView icon, TextView label, TextView badge) {
            this.root = root;
            this.pill = pill;
            this.icon = icon;
            this.label = label;
            this.badge = badge;
        }
    }

    private final Item[] items = new Item[IDS.length];
    private Listener listener;
    private int selected = -1;
    private int ink = Color.GRAY;
    private int inkActive = Color.WHITE;
    private int indicator = Color.DKGRAY;
    private int ripple = 0x337FD1B9;

    public NavBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int padV = dp(R.dimen.nav_pad_v);
        setPadding(0, padV, 0, padV);
        for (int i = 0; i < items.length; i++) {
            items[i] = makeItem(i);
            addView(items[i].root, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        restyle();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Marks the current place: {@link #HOME}, {@link #TERMINAL} or {@link #ACTIVITY}. */
    public void setSelected(int item) {
        if (item == selected) return;
        selected = item;
        restyle();
    }

    public int selected() {
        return selected;
    }

    /** A count badge on {@code item}; 0 hides it. */
    public void setBadge(int item, int count) {
        Item it = items[item];
        if (it.count == count) return;
        it.count = count;
        it.badge.setVisibility(count > 0 ? VISIBLE : GONE);
        it.badge.setText(count > 99 ? "99+" : String.valueOf(count));
        describe(item);
    }

    /**
     * Recolors the bar: {@code background} fills it (with its own hairline),
     * {@code ink}/{@code inkActive} are the resting and current inks, and
     * {@code indicator} fills the current item's pill.
     */
    public void applyColors(Drawable background, int ink, int inkActive, int indicator,
            int ripple) {
        setBackground(background);
        this.ink = ink;
        this.inkActive = inkActive;
        this.indicator = indicator;
        this.ripple = ripple;
        restyle();
    }

    private Item makeItem(int i) {
        Context c = getContext();
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setId(IDS[i]);
        root.setClickable(true);
        root.setFocusable(true);
        root.setOnClickListener(v -> {
            if (listener != null) listener.onNavItem(i);
        });

        FrameLayout pill = new FrameLayout(c);
        ImageView icon = new ImageView(c);
        icon.setImageResource(ICONS[i]);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconPx = dp(R.dimen.nav_icon);
        pill.addView(icon, new FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER));

        TextView badge = new TextView(c);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, Chrome.dimen(c, R.dimen.text_nav_badge));
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setTextColor(Chrome.color(c, R.color.nav_badge_ink));
        int badgePx = dp(R.dimen.nav_badge);
        GradientDrawable dot = new GradientDrawable();
        dot.setColor(Chrome.color(c, R.color.nav_badge));
        dot.setCornerRadius(badgePx / 2f);
        badge.setBackground(dot);
        badge.setMinWidth(badgePx);
        badge.setPadding(dp(R.dimen.space_1), 0, dp(R.dimen.space_1), 0);
        badge.setVisibility(GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, badgePx, Gravity.TOP | Gravity.END);
        pill.addView(badge, blp);
        root.addView(pill, new LayoutParams(dp(R.dimen.nav_indicator_w), dp(R.dimen.nav_indicator_h)));

        TextView label = new TextView(c);
        label.setText(LABELS[i]);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, Chrome.dimen(c, R.dimen.text_nav));
        LayoutParams llp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(R.dimen.nav_label_gap);
        root.addView(label, llp);

        Item it = new Item(root, pill, icon, label, badge);
        items[i] = it;
        describe(i);
        return it;
    }

    private void restyle() {
        float r = dp(R.dimen.nav_indicator_h) / 2f;
        for (int i = 0; i < items.length; i++) {
            Item it = items[i];
            if (it == null) continue;
            boolean on = i == selected;
            int c = on ? inkActive : ink;
            it.icon.setImageTintList(ColorStateList.valueOf(c));
            it.label.setTextColor(c);
            it.label.setTypeface(on
                    ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
            GradientDrawable fill = new GradientDrawable();
            fill.setCornerRadius(r);
            fill.setColor(on ? indicator : Color.TRANSPARENT);
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(r);
            mask.setColor(Color.WHITE);
            // The ripple lives on the pill, so a tap lights the same shape
            // the current item wears.
            it.pill.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), fill, mask));
            it.root.setSelected(on);
        }
    }

    private void describe(int i) {
        Item it = items[i];
        String name = getContext().getString(LABELS[i]);
        it.root.setContentDescription(it.count > 0
                ? getContext().getString(R.string.nav_badge_description, name, it.count) : name);
    }

    private int dp(int dimenRes) {
        return Chrome.dp(getContext(), dimenRes);
    }
}
