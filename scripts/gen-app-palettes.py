#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes app/src/main/res/values/palettes.xml: one Palette.* style per
built-in theme, setting the chrome color attrs (res/values/attrs.xml) that
every screen draws from. ui/AppTheme applies the style matching the selected
theme to each Activity, so one pick recolors the whole app.

Each theme names a handful of anchors (background, three text inks, accent,
semantic colors); surfaces, strokes and accent tints derive from them the same
way for every theme. "Classic" keeps the original hand-picked tokens verbatim.
Re-run after editing a theme here or adding one to ui/ThemePresets.java."""

import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/values/palettes.xml"

# name: (light, bg, text1, text2, text3, accent, on_accent, danger, success, warning, badge, badge_ink)
THEMES = {
    "Solarized Dark":   (False, "002B36", "EEE8D5", "93A1A1", "657B83", "268BD2", "FDF6E3", "DC322F", "859900", "B58900", "CB4B16", "FDF6E3"),
    "Solarized Light":  (True,  "FDF6E3", "073642", "586E75", "93A1A1", "268BD2", "FDF6E3", "DC322F", "859900", "B58900", "CB4B16", "FDF6E3"),
    "Tokyo Night":      (False, "1A1B26", "C0CAF5", "A9B1D6", "565F89", "7AA2F7", "1A1B26", "F7768E", "9ECE6A", "E0AF68", "FF9E64", "1A1B26"),
    "Catppuccin Mocha": (False, "1E1E2E", "CDD6F4", "A6ADC8", "6C7086", "CBA6F7", "1E1E2E", "F38BA8", "A6E3A1", "F9E2AF", "FAB387", "1E1E2E"),
    "Rosé Pine":        (False, "191724", "E0DEF4", "908CAA", "6E6A86", "C4A7E7", "191724", "EB6F92", "9CCFD8", "F6C177", "EBBCBA", "191724"),
    "Kanagawa":         (False, "1F1F28", "DCD7BA", "C8C093", "727169", "7E9CD8", "1F1F28", "E46876", "98BB6C", "FF9E3B", "FFA066", "1F1F28"),
    "Everforest":       (False, "2D353B", "D3C6AA", "9DA9A0", "7A8478", "A7C080", "2D353B", "E67E80", "83C092", "DBBC7F", "E69875", "2D353B"),
    "Gruvbox Dark":     (False, "282828", "EBDBB2", "A89984", "7C6F64", "FABD2F", "282828", "FB4934", "B8BB26", "FE8019", "FB4934", "282828"),
    "Nord":             (False, "2E3440", "ECEFF4", "B4BCCB", "7B869A", "88C0D0", "2E3440", "BF616A", "A3BE8C", "EBCB8B", "D08770", "2E3440"),
    "Dracula":          (False, "282A36", "F8F8F2", "BDBFCF", "6272A4", "BD93F9", "282A36", "FF5555", "50FA7B", "F1FA8C", "FF79C6", "282A36"),
    "One Dark":         (False, "282C34", "DCDFE4", "ABB2BF", "7F848E", "61AFEF", "282C34", "E06C75", "98C379", "E5C07B", "D19A66", "282C34"),
}

# The original indigo-on-near-black tokens (res/values/colors.xml), unchanged.
CLASSIC = {
    "palette_light": "false",
    "surface_base": "#0B0B10", "surface_1": "#14141B", "surface_2": "#1C1C26",
    "surface_3": "#262633", "surface_4": "#33333F",
    "divider": "#14FFFFFF", "border": "#24FFFFFF",
    "text_primary": "#F3F3F7", "text_secondary": "#A6A6B5", "text_tertiary": "#6F6F82",
    "on_accent": "#FFFFFFFF",
    "accent": "#7C6CF6", "accent_pressed": "#9384F8", "accent_deep": "#4C3FB0",
    "accent_translucent": "#337C6CF6", "accent_soft": "#1F7C6CF6",
    "danger": "#FF6B7A", "danger_pressed": "#FF8A96",
    "success": "#4CD964", "warning": "#D9A441",
    "nav_badge": "#FFF07A6A", "nav_badge_ink": "#FF1A0E0C",
}


def rgb(h):
    return [int(h[i:i + 2], 16) for i in (0, 2, 4)]


def mix(a, b, f):
    a, b = rgb(a), rgb(b)
    return "".join("%02X" % round(x + (y - x) * f) for x, y in zip(a, b))


def derive(light, bg, t1, t2, t3, accent, on_accent, danger, success, warning, badge, ink):
    if light:
        surfaces = [mix(bg, "000000", f) for f in (0.045, 0.08, 0.13, 0.18)]
        divider, border = "#14000000", "#1F000000"
        pressed, deep = mix(accent, "000000", 0.12), mix(accent, "000000", 0.30)
        danger_pressed = mix(danger, "000000", 0.12)
    else:
        surfaces = [mix(bg, t1, f) for f in (0.045, 0.085, 0.13, 0.19)]
        divider, border = "#14" + t1, "#24" + t1
        pressed, deep = mix(accent, "FFFFFF", 0.15), mix(accent, "000000", 0.38)
        danger_pressed = mix(danger, "FFFFFF", 0.15)
    return {
        "palette_light": "true" if light else "false",
        "surface_base": "#" + bg,
        "surface_1": "#" + surfaces[0], "surface_2": "#" + surfaces[1],
        "surface_3": "#" + surfaces[2], "surface_4": "#" + surfaces[3],
        "divider": divider, "border": border,
        "text_primary": "#" + t1, "text_secondary": "#" + t2, "text_tertiary": "#" + t3,
        "on_accent": "#" + on_accent,
        "accent": "#" + accent, "accent_pressed": "#" + pressed, "accent_deep": "#" + deep,
        "accent_translucent": "#33" + accent, "accent_soft": "#1F" + accent,
        "danger": "#" + danger, "danger_pressed": "#" + danger_pressed,
        "success": "#" + success, "warning": "#" + warning,
        "nav_badge": "#" + badge, "nav_badge_ink": "#" + ink,
    }


def style_name(theme):
    return "Palette." + "".join(ch for ch in theme.replace("é", "e") if ch.isalnum())


def main():
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           "<!-- Generated by scripts/gen-app-palettes.py; edit that, not this. -->",
           "<resources>"]
    palettes = dict(THEMES)
    for name, attrs in [("Classic", CLASSIC)] + [(n, derive(*v)) for n, v in palettes.items()]:
        out.append('    <style name="%s" parent="">' % style_name(name))
        for k, v in attrs.items():
            out.append('        <item name="%s">%s</item>' % (k, v))
        out.append("    </style>")
    out.append("</resources>")
    OUT.write_text("\n".join(out) + "\n")
    print("wrote", OUT, "with", len(palettes) + 1, "palettes")


if __name__ == "__main__":
    main()
