package com.fongmi.android.tv.theme;

import java.util.Locale;

/** Persisted B-safe theme profile limited to the 16 user-editable semantic slots. */
public final class ThemeProfile {

    public static final int SCHEMA_VERSION = 2;
    public static final String FORMAT = "webhtv-theme";
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";
    public static final String SEED_NONE = "none";
    public static final String SEED_WALLPAPER = "wallpaper";
    public static final String SEED_CUSTOM = "custom";

    public int schemaVersion = SCHEMA_VERSION;
    public String format = FORMAT;
    public String id = "webhtv.local";
    public String name = "Default";
    public String mode = MODE_SYSTEM;
    public String seedSource = SEED_NONE;
    public String seedColor;
    public SlotSet light = new SlotSet();
    public SlotSet dark = new SlotSet();

    public ThemeProfile() {
    }

    public static ThemeProfile defaultProfile() {
        return new ThemeProfile();
    }

    public ThemeProfile copy() {
        ThemeProfile copy = new ThemeProfile();
        copy.schemaVersion = schemaVersion;
        copy.format = format;
        copy.id = id;
        copy.name = name;
        copy.mode = mode;
        copy.seedSource = seedSource;
        copy.seedColor = seedColor;
        copy.light = light == null ? null : light.copy();
        copy.dark = dark == null ? null : dark.copy();
        return copy;
    }

    public SlotSet slots(boolean dark) {
        if (dark) {
            if (this.dark == null) this.dark = new SlotSet();
            return this.dark;
        }
        if (light == null) light = new SlotSet();
        return light;
    }

    public String displayName() {
        return name == null || name.isBlank() ? "Default" : name;
    }

    public static String normalizeMode(String value) {
        if (MODE_LIGHT.equals(value)) return MODE_LIGHT;
        if (MODE_DARK.equals(value)) return MODE_DARK;
        return MODE_SYSTEM;
    }

    public static String normalizeSeedSource(String value) {
        if (SEED_CUSTOM.equals(value)) return SEED_CUSTOM;
        if (SEED_WALLPAPER.equals(value)) return SEED_WALLPAPER;
        return SEED_NONE;
    }

    /** The 13 color slots plus 3 opacity slots a user may edit. */
    public static final class SlotSet {
        public String primary;
        public String primaryContainer;
        public String secondaryContainer;
        public String focus;
        public String surface;
        public String surfaceContainer;
        public String surfaceContainerHigh;
        public String onSurface;
        public String onSurfaceVariant;
        public String outline;
        public String error;
        public String success;
        public String warning;
        public Float scrimOpacity;
        public Float dialogOpacity;
        public Float overlayOpacity;

        public SlotSet copy() {
            SlotSet copy = new SlotSet();
            copy.primary = primary;
            copy.primaryContainer = primaryContainer;
            copy.secondaryContainer = secondaryContainer;
            copy.focus = focus;
            copy.surface = surface;
            copy.surfaceContainer = surfaceContainer;
            copy.surfaceContainerHigh = surfaceContainerHigh;
            copy.onSurface = onSurface;
            copy.onSurfaceVariant = onSurfaceVariant;
            copy.outline = outline;
            copy.error = error;
            copy.success = success;
            copy.warning = warning;
            copy.scrimOpacity = scrimOpacity;
            copy.dialogOpacity = dialogOpacity;
            copy.overlayOpacity = overlayOpacity;
            return copy;
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s (%s)", displayName(), mode);
    }
}
