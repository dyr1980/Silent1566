package com.fongmi.android.tv.theme;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.app.Dialog;
import android.os.Build;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

/** Read-only application entry point for future theme adoption. */
public final class ThemeController {

    private static volatile ThemeTokens current = ThemeTokens.light();
    private static volatile ThemeTokens baseline = ThemeTokens.light();
    private static volatile ThemeProfile profile = ThemeProfile.defaultProfile();

    private ThemeController() {
    }

    public static void apply(AppCompatActivity activity, ThemeTokens tokens) {
        ThemeTokens safe = tokens == null ? ThemeTokens.light() : tokens;
        current = safe;
        int color = safe.colorSurface();
        activity.getWindow().setStatusBarColor(color);
        activity.getWindow().setNavigationBarColor(color);
    }

    /**
     * Keeps AppCompat's DayNight configuration aligned with the persisted
     * appearance mode so the whole activity tree (mobile and leanback) shares
     * one resolution order: explicit mode > system > product default.
     */
    public static void applyNightModeToApp() {
        int mode = com.fongmi.android.tv.setting.Setting.getThemeMode();
        int delegate = switch (mode) {
            case 0 -> AppCompatDelegate.MODE_NIGHT_NO;
            case 1 -> AppCompatDelegate.MODE_NIGHT_YES;
            default -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        };
        if (AppCompatDelegate.getDefaultNightMode() != delegate) {
            AppCompatDelegate.setDefaultNightMode(delegate);
        }
    }

    public static ThemeTokens current() {
        return current;
    }

    /** The frozen Layer 1 palette the binder compares against. */
    public static ThemeTokens baseline() {
        return baseline;
    }

    /**
     * Single source of truth for "the current app appearance is dark".
     *
     * <p>The Activity configuration already reflects a forced {@code theme_mode}, so
     * ordinary UI must ask this method instead of reading the raw system night bit,
     * which would contradict an explicit light/dark choice.
     */
    public static boolean isNight(Context context) {
        Configuration configuration = context == null ? Resources.getSystem().getConfiguration()
                : context.getResources().getConfiguration();
        return (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * The user's explicit scrim opacity, or {@code null} when they never set one.
     *
     * <p>Callers must keep this distinguished from "the set opacity happens to equal
     * the shipped default". Modal scrims in this app do not use the token colour:
     * the episode-detail and TMDB-person dialogs paint their own scrim (a translucent
     * white in light mode, translucent black in dark) and that is the shipped design.
     * Replacing those colours with {@code colorScrim()} was measured to flip light-mode
     * scrims from lightening to darkening, so the wiring instead lets the user drive
     * only the alpha of each dialog's own scrim colour, and stays a strict no-op while
     * the slot is unset so the default rendering is byte-identical.
     *
     * @param light which slot set the caller resolved for its own colours; the scrim
     *              owner decides light/dark, so the same answer is reused here instead
     *              of re-deriving it from the uiMode bit
     * @return the configured alpha in {@code 0..1}, or {@code null} when unset
     */
    public static Float configuredScrimOpacity(boolean light) {
        ThemeProfile active = profile;
        if (active == null) return null;
        ThemeProfile.SlotSet slots = light ? active.light : active.dark;
        if (slots == null) return null;
        Float value = slots.scrimOpacity;
        if (value == null || !Float.isFinite(value)) return null;
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * Applies a scrim opacity to a surface's own scrim colour, replacing only the alpha.
     *
     * <p>{@code null} (the user never set the slot) returns {@code base} untouched, which
     * is what keeps the shipped look byte-identical. Package-visible and taking the
     * opacity explicitly so both branches can be asserted without an Android device.
     */
    public static int applyScrimOpacity(int base, Float opacity) {
        if (opacity == null) return base;
        int alpha = Math.max(0, Math.min(255, Math.round(opacity * 255f)));
        return (base & 0x00FFFFFF) | (alpha << 24);
    }

    public static void refresh() {
        boolean systemDark = (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        current = ThemeResolver.resolve(ThemeMode.SYSTEM, ThemeSeed.NONE, 0, 0, systemDark);
    }

    /**
     * Resolves the active semantic snapshot from the persisted appearance
     * preferences. {@code themeColor} follows the existing WebHTV contract:
     * {@code -1} disables tinting, {@code 0} uses the wallpaper seed and any
     * other value is an explicit ARGB seed.
     */
    public static ThemeTokens resolveFromPreferences() {
        ThemeProfile stored = null;
        try {
            stored = ThemeProfileStore.load();
        } catch (RuntimeException ignored) {
            // Preference access is unavailable; Layer 1 defaults remain valid.
        }
        return resolveWith(stored);
    }

    private static ThemeTokens resolveWith(ThemeProfile profile) {
        // Both the base palette and the profile's light/dark slot selection follow the
        // compiled table, so the active tokens stay comparable with inflated colours on
        // every flavour (see resolvedDark()).
        boolean systemDark = resolvedDark();
        ThemeMode themeMode = ThemeMode.SYSTEM;
        int themeColor = com.fongmi.android.tv.setting.Setting.getThemeColor();
        if (themeColor == -1) {
            return ThemeResolver.resolve(themeMode, ThemeSeed.NONE, 0, 0, profile, null, systemDark);
        }
        ThemeSeed seed = themeColor == 0 ? ThemeSeed.WALLPAPER : ThemeSeed.EXPLICIT;
        int explicit = themeColor == 0 ? 0 : themeColor;
        int wallpaper = com.fongmi.android.tv.setting.Setting.getWallColor();
        return ThemeResolver.resolve(themeMode, seed, explicit, wallpaper, profile, null, systemDark);
    }

    /**
     * The frozen compiled palette the binder compares view colours against.
     *
     * <p>Every {@code ?attr/color*} attribute was resolved from the static
     * {@code webhtv_color_*} resources when the view was inflated, so this baseline
     * must ignore {@code theme_color}/{@code wall_color}: those preferences only
     * reach the activity through {@link #resolveWith(ThemeProfile)}. Deriving the
     * baseline from the active seed made it describe colours no inflated view ever
     * held, and {@link ThemeBinder} then had nothing left to match - the feature
     * silently degraded to the legacy site dialog.
     */
    private static ThemeTokens frozenPalette() {
        return ThemeResolver.resolve(ThemeMode.SYSTEM, ThemeSeed.NONE, 0, 0, null, null, resolvedDark());
    }

    private static ThemeMode currentThemeMode() {
        int mode = com.fongmi.android.tv.setting.Setting.getThemeMode();
        return mode < 0 ? ThemeMode.SYSTEM : (mode == 0 ? ThemeMode.LIGHT : ThemeMode.DARK);
    }

    /**
     * Whether the palette the running build actually ships is the dark table.
     *
     * <p>{@link #frozenPalette()} must describe the colours inflation produced, and
     * those come from the compiled {@code webhtv_color_*} resources. Resource
     * selection is not purely a function of uiMode: the TV flavour overrides
     * {@code values/} with the dark table and ships no light table of its own, so on
     * a light-mode device its views are dark while a uiMode-derived baseline was
     * light. Every binder rewrite compares against that baseline exactly, so the
     * mismatch silently turned the whole TV theme channel into a no-op (measured:
     * probe vs default = 0 changed pixels on TV while the identical probe changed
     * ~49k pixels on mobile, and switching the device to dark made TV respond).
     *
     * <p>The compiled table is therefore read from resources whenever it can be
     * identified, and only an unidentifiable palette falls back to the uiMode rule.
     */
    private static boolean resolvedDark() {
        boolean systemDark = (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return darkPaletteFor(compiledDarkPalette(), currentThemeMode(), systemDark);
    }

    /**
     * The dark/light decision shared by the baseline and the active palette.
     *
     * <p>When the compiled table is identifiable it is authoritative: it is literally
     * what inflation resolved, so it must win even over an explicit appearance mode.
     * The TV flavour needs that - its {@code values/} holds the dark table, so a
     * light-mode device (or a user who picked light) still renders dark views. Only an
     * unidentifiable palette falls back to the historical mode/uiMode rule.
     */
    static boolean darkPaletteFor(Boolean compiledDark, ThemeMode mode, boolean systemDark) {
        if (compiledDark != null) return compiledDark;
        return mode == ThemeMode.DARK || (mode != ThemeMode.LIGHT && systemDark);
    }

    /**
     * Identifies which canonical table this APK compiled by comparing every role the
     * binder may rewrite against its {@code webhtv_color_*} resource. Returns
     * {@code null} when no context is available or neither table matches exactly, so
     * an unmodelled future palette keeps the previous uiMode behaviour instead of
     * silently binding against the wrong baseline.
     */
    private static Boolean compiledDarkPalette() {
        Context context = App.get();
        if (context == null) return null;
        ThemeTokens light = ThemeTokens.light();
        ThemeTokens dark = ThemeTokens.dark();
        boolean matchesLight = true;
        boolean matchesDark = true;
        for (ThemeRole role : ThemeRole.values()) {
            int resource = colorResourceOf(role);
            if (resource == 0) continue;
            int compiled = ContextCompat.getColor(context, resource);
            matchesLight &= compiled == role.colorOf(light);
            matchesDark &= compiled == role.colorOf(dark);
        }
        if (matchesLight == matchesDark) return null;
        return matchesDark;
    }

    /** The compiled default of a binder role, or 0 when the role has no resource. */
    private static int colorResourceOf(ThemeRole role) {
        return switch (role) {
            case PRIMARY -> R.color.webhtv_color_primary;
            case PRIMARY_CONTAINER -> R.color.webhtv_color_primary_container;
            case SECONDARY_CONTAINER -> R.color.webhtv_color_secondary_container;
            case FOCUS -> R.color.webhtv_color_focus;
            case SURFACE -> R.color.webhtv_color_surface;
            case SURFACE_CONTAINER -> R.color.webhtv_color_surface_container;
            case SURFACE_CONTAINER_HIGH -> R.color.webhtv_color_surface_container_high;
            case ON_SURFACE -> R.color.webhtv_color_on_surface;
            case ON_SURFACE_VARIANT -> R.color.webhtv_color_on_surface_variant;
            case OUTLINE -> R.color.webhtv_color_outline;
            case ERROR -> R.color.webhtv_color_error;
            case SUCCESS -> R.color.webhtv_color_success;
            case WARNING -> R.color.webhtv_color_warning;
            case ON_PRIMARY -> R.color.webhtv_color_on_primary;
            case ON_PRIMARY_CONTAINER -> R.color.webhtv_color_on_primary_container;
            case ON_SECONDARY_CONTAINER -> R.color.webhtv_color_on_secondary_container;
            case ON_ERROR -> R.color.webhtv_color_on_error;
            case ON_SUCCESS -> R.color.webhtv_color_on_success;
            case ON_WARNING -> R.color.webhtv_color_on_warning;
        };
    }

    /**
     * Applies the persisted appearance snapshot to one activity.
     *
     * <p>Only the read-only token snapshot is refreshed here. System-bar colors
     * are updated solely when the window still owns an opaque bar; edge-to-edge
     * windows keep their transparent bars so this hook cannot regress the
     * existing immersive layouts.
     */
    public static void applyFromPreferences(AppCompatActivity activity) {
        ThemeProfile stored = null;
        try {
            stored = ThemeProfileStore.load();
        } catch (RuntimeException ignored) {
            // Preference access is unavailable; Layer 1 defaults remain valid.
        }
        profile = stored == null ? ThemeProfile.defaultProfile() : stored;
        baseline = frozenPalette();
        current = resolveWith(profile);
        if (activity == null || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        if (activity.getWindow().getStatusBarColor() != android.graphics.Color.TRANSPARENT) {
            activity.getWindow().setStatusBarColor(current.colorSurface());
        }
        if (activity.getWindow().getNavigationBarColor() != android.graphics.Color.TRANSPARENT) {
            activity.getWindow().setNavigationBarColor(current.colorSurface());
        }
    }

    /**
     * Applies the active profile to an already-created view tree.
     *
     * <p>When no profile override is active this returns immediately, so the
     * default Layer 1 path is byte-identical and costs nothing.
     */
    public static void bindTheme(View root) {
        ThemeBinder.bind(root, baseline, current);
    }

    /** Same controlled channel for a dialog or bottom-sheet window. */
    public static void bindDialog(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        bindTheme(dialog.getWindow().getDecorView());
    }

    /**
     * Applies the active profile to a window-level background drawable.
     *
     * <p>Alert dialog panels live on the window rather than on a view, so they need
     * this second entry point next to {@link #bindTheme(View)}. It is a no-op while the
     * active tokens equal the frozen baseline.
     */
    public static void bindWindowBackground(Drawable background) {
        if (background == null) return;
        ThemeBinder.bindWindowBackground(background, baseline, current);
    }

    /** True when the active palette differs from the frozen compiled baseline. */
    public static boolean hasProfileOverrides() {
        return !baseline.equals(current);
    }

    public static ThemeProfile activeProfile() {
        return profile;
    }
}
