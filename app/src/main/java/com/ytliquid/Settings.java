package com.ytliquid;

import android.content.SharedPreferences;

/**
 * Module settings, read from the module's SharedPreferences file.
 *
 * <p>The settings screen writes with {@code MODE_WORLD_READABLE} (LSPosed hooks
 * {@code ContextImpl.checkMode} for modules that declare {@code xposedsharedprefs}),
 * and the hook reads them back through {@link XSharedPreferences}.
 */
public final class Settings {

    public static final String PREF_NAME = "miuix_nav_prefs";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_BLUR_RADIUS = "blur_radius";
    public static final String KEY_BG_ALPHA = "background_alpha";
    public static final String KEY_SELECTED_COLOR = "selected_color";
    public static final String KEY_INACTIVE_COLOR = "inactive_color";
    public static final String KEY_CORNER_RADIUS = "corner_radius";
    public static final String KEY_BAR_HEIGHT = "bar_height";
    public static final String KEY_SIDE_MARGIN = "side_margin";
    public static final String KEY_BOTTOM_MARGIN = "bottom_margin";
    public static final String KEY_ICON_SIZE = "icon_size";
    public static final String KEY_SHOW_LABELS = "show_labels";
    public static final String KEY_ANIMATION = "animation_enabled";

    public boolean enabled = true;
    public int blurRadius = 18;
    public int bgAlpha = 45;
    public int selectedColor = 0xFF2F7BFF;
    public int inactiveColor = 0xFFB8B8B8;
    public int cornerRadius = 28;
    /** 0 means "keep YouTube's own height". */
    public int barHeight = 0;
    public int sideMargin = 14;
    public int bottomMargin = 12;
    public int iconSize = 24;
    public boolean showLabels = true;
    public boolean animation = true;

    public static Settings read(SharedPreferences p) {
        Settings s = new Settings();
        s.enabled = p.getBoolean(KEY_ENABLED, s.enabled);
        s.blurRadius = clamp(p.getInt(KEY_BLUR_RADIUS, s.blurRadius), 0, 25);
        s.bgAlpha = clamp(p.getInt(KEY_BG_ALPHA, s.bgAlpha), 0, 100);
        s.selectedColor = p.getInt(KEY_SELECTED_COLOR, s.selectedColor);
        s.inactiveColor = p.getInt(KEY_INACTIVE_COLOR, s.inactiveColor);
        s.cornerRadius = clamp(p.getInt(KEY_CORNER_RADIUS, s.cornerRadius), 0, 60);
        s.barHeight = clamp(p.getInt(KEY_BAR_HEIGHT, s.barHeight), 0, 100);
        s.sideMargin = clamp(p.getInt(KEY_SIDE_MARGIN, s.sideMargin), 0, 60);
        s.bottomMargin = clamp(p.getInt(KEY_BOTTOM_MARGIN, s.bottomMargin), 0, 60);
        s.iconSize = clamp(p.getInt(KEY_ICON_SIZE, s.iconSize), 12, 40);
        s.showLabels = p.getBoolean(KEY_SHOW_LABELS, s.showLabels);
        s.animation = p.getBoolean(KEY_ANIMATION, s.animation);
        return s;
    }

    /** True when any value differs from {@code other}. */
    public boolean changedFrom(Settings other) {
        if (other == null) return true;
        return enabled != other.enabled
                || blurRadius != other.blurRadius
                || bgAlpha != other.bgAlpha
                || selectedColor != other.selectedColor
                || inactiveColor != other.inactiveColor
                || cornerRadius != other.cornerRadius
                || barHeight != other.barHeight
                || sideMargin != other.sideMargin
                || bottomMargin != other.bottomMargin
                || iconSize != other.iconSize
                || showLabels != other.showLabels
                || animation != other.animation;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
