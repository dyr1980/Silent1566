package com.fongmi.android.tv.theme;

/**
 * Editing session for one B-safe theme profile.
 *
 * <p>The draft is a deep copy. Every mutation is validated before it reaches the
 * draft, so an invalid hex value or an out-of-range opacity can never be applied.
 * {@link #apply()} is the only method that writes to disk; it is atomic and
 * refreshes the legacy {@code theme_color} mirror through {@link ThemeProfileStore}.
 * Cancelling simply drops the draft.
 */
public final class ThemeEditor {

    /** A user-editable slot of one mode. */
    public enum Slot {
        PRIMARY, PRIMARY_CONTAINER, SECONDARY_CONTAINER, FOCUS,
        SURFACE, SURFACE_CONTAINER, SURFACE_CONTAINER_HIGH,
        ON_SURFACE, ON_SURFACE_VARIANT, OUTLINE,
        ERROR, SUCCESS, WARNING,
        SCRIM_OPACITY, DIALOG_OPACITY, OVERLAY_OPACITY;

        public boolean isColor() {
            return ordinal() <= WARNING.ordinal();
        }
    }

    private final ThemeProfile original;
    private final ThemeProfile draft;

    public ThemeEditor(ThemeProfile source) {
        this.original = source == null ? ThemeProfile.defaultProfile() : source.copy();
        this.draft = this.original.copy();
    }

    public static ThemeEditor load() {
        return new ThemeEditor(ThemeProfileStore.load());
    }

    public ThemeProfile draft() {
        return draft.copy();
    }

    public boolean isDirty() {
        return !ThemeProfileCodec.encode(original).equals(ThemeProfileCodec.encode(draft));
    }

    /** Applies a color or opacity value to one slot of one mode. */
    public Result set(Slot slot, boolean dark, String color, float opacity) {
        if (slot == null) return Result.failure("unknown slot");
        ThemeProfile.SlotSet slots = draft.slots(dark);
        if (slot.isColor()) {
            String normalized = ThemeProfileValidator.normalizeColor(color);
            if (normalized == null) return Result.failure("invalid color");
            setColor(slots, slot, normalized);
        } else {
            Double range = clamp(slot, opacity);
            if (range == null) return Result.failure("opacity out of range");
            setOpacity(slots, slot, opacity);
        }
        ThemeProfileValidator.Result validated = ThemeProfileValidator.validate(draft);
        if (!validated.valid()) return Result.failure(validated.message());
        return Result.success(draft.copy());
    }

    /** Clears one slot so it inherits the built-in token or seed result again. */
    public Result clear(Slot slot, boolean dark) {
        if (slot == null) return Result.failure("unknown slot");
        ThemeProfile.SlotSet slots = draft.slots(dark);
        if (slot.isColor()) setColor(slots, slot, null);
        else setOpacity(slots, slot, null);
        return Result.success(draft.copy());
    }

    public Result setMode(String mode) {
        draft.mode = ThemeProfile.normalizeMode(mode);
        return Result.success(draft.copy());
    }

    public Result setSeed(String seedSource, String seedColor) {
        ThemeProfile.SlotSet ignored = draft.light;
        String source = ThemeProfile.normalizeSeedSource(seedSource);
        if (ThemeProfile.SEED_CUSTOM.equals(source)) {
            String normalized = ThemeProfileValidator.normalizeColor(seedColor);
            if (normalized == null) return Result.failure("invalid seed color");
            draft.seedSource = source;
            draft.seedColor = normalized;
        } else {
            draft.seedSource = source;
            if (ThemeProfile.SEED_NONE.equals(source)) draft.seedColor = null;
        }
        return Result.success(draft.copy());
    }

    /** Resolves the draft for previewing without touching persisted state. */
    public ThemeTokens preview(ThemeMode mode, ThemeSeed seed, int seedColor, int wallpaperColor, boolean systemDark) {
        return ThemeResolver.resolve(mode, seed, seedColor, wallpaperColor, draft, null, systemDark);
    }

    /** Atomically persists the draft. */
    public ThemeProfileStore.ApplyResult apply() {
        ThemeProfileValidator.Result validated = ThemeProfileValidator.validate(draft);
        if (!validated.valid()) return ThemeProfileStore.ApplyResult.failure(validated.message());
        return ThemeProfileStore.apply(validated.profile());
    }

    /** Restores the frozen default profile. */
    public ThemeProfileStore.ApplyResult reset() {
        return ThemeProfileStore.reset();
    }

    public String valueOf(Slot slot, boolean dark) {
        if (slot == null) return null;
        ThemeProfile.SlotSet slots = draft.slots(dark);
        return switch (slot) {
            case PRIMARY -> slots.primary;
            case PRIMARY_CONTAINER -> slots.primaryContainer;
            case SECONDARY_CONTAINER -> slots.secondaryContainer;
            case FOCUS -> slots.focus;
            case SURFACE -> slots.surface;
            case SURFACE_CONTAINER -> slots.surfaceContainer;
            case SURFACE_CONTAINER_HIGH -> slots.surfaceContainerHigh;
            case ON_SURFACE -> slots.onSurface;
            case ON_SURFACE_VARIANT -> slots.onSurfaceVariant;
            case OUTLINE -> slots.outline;
            case ERROR -> slots.error;
            case SUCCESS -> slots.success;
            case WARNING -> slots.warning;
            case SCRIM_OPACITY -> slots.scrimOpacity == null ? null : String.valueOf(slots.scrimOpacity);
            case DIALOG_OPACITY -> slots.dialogOpacity == null ? null : String.valueOf(slots.dialogOpacity);
            case OVERLAY_OPACITY -> slots.overlayOpacity == null ? null : String.valueOf(slots.overlayOpacity);
        };
    }

    private static void setColor(ThemeProfile.SlotSet slots, Slot slot, String value) {
        switch (slot) {
            case PRIMARY -> slots.primary = value;
            case PRIMARY_CONTAINER -> slots.primaryContainer = value;
            case SECONDARY_CONTAINER -> slots.secondaryContainer = value;
            case FOCUS -> slots.focus = value;
            case SURFACE -> slots.surface = value;
            case SURFACE_CONTAINER -> slots.surfaceContainer = value;
            case SURFACE_CONTAINER_HIGH -> slots.surfaceContainerHigh = value;
            case ON_SURFACE -> slots.onSurface = value;
            case ON_SURFACE_VARIANT -> slots.onSurfaceVariant = value;
            case OUTLINE -> slots.outline = value;
            case ERROR -> slots.error = value;
            case SUCCESS -> slots.success = value;
            case WARNING -> slots.warning = value;
            default -> throw new IllegalArgumentException("not a color slot");
        }
    }

    private static void setOpacity(ThemeProfile.SlotSet slots, Slot slot, Float value) {
        switch (slot) {
            case SCRIM_OPACITY -> slots.scrimOpacity = value;
            case DIALOG_OPACITY -> slots.dialogOpacity = value;
            case OVERLAY_OPACITY -> slots.overlayOpacity = value;
            default -> throw new IllegalArgumentException("not an opacity slot");
        }
    }

    /** Returns the allowed range when the value is valid, otherwise null. */
    public static Double clamp(Slot slot, float value) {
        if (!Float.isFinite(value)) return null;
        return switch (slot) {
            case SCRIM_OPACITY -> inRange(value, ThemeProfileValidator.MIN_SCRIM_OPACITY, ThemeProfileValidator.MAX_SCRIM_OPACITY);
            case DIALOG_OPACITY -> inRange(value, ThemeProfileValidator.MIN_DIALOG_OPACITY, ThemeProfileValidator.MAX_DIALOG_OPACITY);
            case OVERLAY_OPACITY -> inRange(value, ThemeProfileValidator.MIN_OVERLAY_OPACITY, ThemeProfileValidator.MAX_OVERLAY_OPACITY);
            default -> null;
        };
    }

    private static Double inRange(float value, float minimum, float maximum) {
        return value < minimum || value > maximum ? null : (double) value;
    }

    public record Result(boolean success, ThemeProfile profile, String error) {

        public static Result success(ThemeProfile profile) {
            return new Result(true, profile, "");
        }

        public static Result failure(String error) {
            return new Result(false, null, error == null ? "unknown error" : error);
        }
    }
}
