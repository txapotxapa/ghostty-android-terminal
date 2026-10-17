/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.SparseIntArray;
import android.util.TypedValue;

import io.github.sylirre.terminal.R;

/**
 * Shared styling vocabulary for the views that are built entirely in code
 * (tab strip, extra-keys toolbar, search bar, color picker). It is the code
 * counterpart to the XML design tokens: every color resolves through the
 * same theme attrs layouts use, so layouts and Java draw the same palette. Prefer these
 * factories over hand-rolled {@link GradientDrawable}/hex constants so the
 * chrome stays consistent and re-themable from one place.
 */
final class Chrome {

    private Chrome() {}

    /**
     * Resolves a color token through the context's theme, so it follows the
     * app theme's palette ({@link AppTheme}); tokens without a palette attr
     * (onboarding branding, scrim) resolve as plain resources.
     */
    static int color(Context c, int colorRes) {
        int attr = PALETTE_ATTRS.get(colorRes);
        if (attr != 0) {
            TypedValue v = new TypedValue();
            if (c.getTheme().resolveAttribute(attr, v, true)
                    && v.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && v.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return v.data;
            }
        }
        return c.getColor(colorRes);
    }

    /** Color token → the theme attr of the same name (res/values/attrs.xml). */
    private static final SparseIntArray PALETTE_ATTRS = new SparseIntArray();

    static {
        int[] pairs = {
            R.color.surface_base, R.attr.surface_base,
            R.color.surface_1, R.attr.surface_1,
            R.color.surface_2, R.attr.surface_2,
            R.color.surface_3, R.attr.surface_3,
            R.color.surface_4, R.attr.surface_4,
            R.color.divider, R.attr.divider,
            R.color.border, R.attr.border,
            R.color.text_primary, R.attr.text_primary,
            R.color.text_secondary, R.attr.text_secondary,
            R.color.text_tertiary, R.attr.text_tertiary,
            R.color.on_accent, R.attr.on_accent,
            R.color.accent, R.attr.accent,
            R.color.accent_pressed, R.attr.accent_pressed,
            R.color.accent_deep, R.attr.accent_deep,
            R.color.accent_translucent, R.attr.accent_translucent,
            R.color.accent_soft, R.attr.accent_soft,
            R.color.danger, R.attr.danger,
            R.color.danger_pressed, R.attr.danger_pressed,
            R.color.success, R.attr.success,
            R.color.warning, R.attr.warning,
            R.color.nav_badge, R.attr.nav_badge,
            R.color.nav_badge_ink, R.attr.nav_badge_ink
        };
        for (int i = 0; i < pairs.length; i += 2) PALETTE_ATTRS.put(pairs[i], pairs[i + 1]);
    }

    /** Resolves a dimension token to whole pixels. */
    static int dp(Context c, int dimenRes) {
        return c.getResources().getDimensionPixelSize(dimenRes);
    }

    /** Resolves a dimension token to pixels (fractional). */
    static float dimen(Context c, int dimenRes) {
        return c.getResources().getDimension(dimenRes);
    }

    /**
     * A rounded solid rectangle. {@code strokeRes == 0} draws no border.
     */
    static GradientDrawable rounded(Context c, int fillRes, float radiusPx, int strokeRes) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color(c, fillRes));
        d.setCornerRadius(radiusPx);
        if (strokeRes != 0) {
            d.setStroke(dp(c, R.dimen.stroke_hairline), color(c, strokeRes));
        }
        return d;
    }

    /**
     * A rounded fill with an accent-tinted press ripple clipped to the same
     * corners. {@code strokeRes == 0} draws no border.
     */
    static RippleDrawable ripple(Context c, int fillRes, float radiusPx, int strokeRes) {
        return new RippleDrawable(
                ColorStateList.valueOf(color(c, R.color.accent_translucent)),
                rounded(c, fillRes, radiusPx, strokeRes),
                roundedMask(radiusPx));
    }

    /**
     * A transparent (background-less) rounded ripple, for ghost buttons and
     * inactive tabs that only reveal an accent tint on touch.
     */
    static RippleDrawable rippleTransparent(Context c, float radiusPx) {
        return new RippleDrawable(
                ColorStateList.valueOf(color(c, R.color.accent_translucent)),
                null, roundedMask(radiusPx));
    }

    /**
     * The accent press ripple layered over an arbitrary content drawable
     * (e.g. a hand-built stroked card fill), clipped to the same corners.
     */
    static RippleDrawable rippleOver(Context c, android.graphics.drawable.Drawable content,
            float radiusPx) {
        return new RippleDrawable(
                ColorStateList.valueOf(color(c, R.color.accent_translucent)),
                content, roundedMask(radiusPx));
    }

    /**
     * A pressed/normal state list of rounded fills, used where the caller also
     * swaps the whole background to signal state (e.g. active/locked modifier
     * keys) rather than relying on a ripple. {@code strokeRes == 0} → no border.
     */
    static StateListDrawable stateful(Context c, int fillRes, int pressedFillRes,
            float radiusPx, int strokeRes) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed},
                rounded(c, pressedFillRes, radiusPx, strokeRes));
        s.addState(new int[]{}, rounded(c, fillRes, radiusPx, strokeRes));
        return s;
    }

    private static GradientDrawable roundedMask(float radiusPx) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radiusPx);
        return mask;
    }
}
