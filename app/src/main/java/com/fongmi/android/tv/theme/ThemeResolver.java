package com.fongmi.android.tv.theme;

import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.SchemeTonalSpot;

/** Resolves semantic tokens without exposing seed colors directly to UI surfaces. */
public final class ThemeResolver {

    private static final ThreadLocal<String> LAST_DIAGNOSTIC = ThreadLocal.withInitial(() -> "default");

    /** Material's own baseline scheme keeps primary at or above this on every surface role. */
    private static final double MIN_ACCENT_CONTRAST = 4.5;

    private ThemeResolver() {
    }

    public static ThemeTokens resolve(ThemeMode mode, ThemeSeed seed, int seedColor,
                                      int wallpaperColor, boolean systemDark) {
        return resolve(mode, seed, seedColor, wallpaperColor, null, systemDark);
    }

    /**
     * Resolves the frozen palette, an optional seed, and the B-safe profile in one
     * deterministic order. A profile may only narrow the 16 user slots; it cannot
     * reach player colors or the derived on* pairs.
     */
    public static ThemeTokens resolve(ThemeMode mode, ThemeSeed seed, int seedColor,
                                      int wallpaperColor, ThemeProfile profile, boolean systemDark) {
        ThemeProfile lastGood = null;
        try {
            lastGood = ThemeProfileStore.loadLastGood();
        } catch (RuntimeException ignored) {
            // Android preference access is unavailable (for example in a JVM test); defaults remain valid.
        }
        return resolve(mode, seed, seedColor, wallpaperColor, profile, lastGood, systemDark);
    }

    /** Testable overload with an explicit last-good fallback. */
    public static ThemeTokens resolve(ThemeMode mode, ThemeSeed seed, int seedColor, int wallpaperColor,
                                      ThemeProfile profile, ThemeProfile lastGood, boolean systemDark) {
        boolean dark = mode == ThemeMode.DARK || (mode != ThemeMode.LIGHT && systemDark);
        ThemeTokens fallback = dark ? ThemeTokens.dark() : ThemeTokens.light();
        ThemeTokens base = fallback;
        String path = "default";
        try {
            if (seed != null && seed != ThemeSeed.NONE) {
                int source = seed == ThemeSeed.WALLPAPER ? wallpaperColor : seedColor;
                if (!isOpaque(source)) {
                    LAST_DIAGNOSTIC.set("fallback:invalid-seed:" + seed.name().toLowerCase(java.util.Locale.US));
                    return fallback;
                }
                base = derive(source, dark, fallback);
                path = "seed:" + seed.name().toLowerCase(java.util.Locale.US);
            }
            ThemeTokens candidate = profile == null ? base : applyProfile(base, profile, dark);
            candidate.requireContrast();
            LAST_DIAGNOSTIC.set(profile == null ? path : path + "+profile:" + profile.displayName());
            return candidate;
        } catch (RuntimeException error) {
            String cause = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (lastGood != null && lastGood != profile) {
                try {
                    ThemeTokens recovered = applyProfile(base, lastGood, dark);
                    recovered.requireContrast();
                    LAST_DIAGNOSTIC.set("last-good:" + cause);
                    return recovered;
                } catch (RuntimeException ignored) {
                    // Fall through to the frozen default palette.
                }
            }
            LAST_DIAGNOSTIC.set("fallback:profile-resolve:" + cause);
            return fallback;
        }
    }

    /**
     * Applies only the slots the user actually overrode. Empty/null slots keep the
     * resolved base value, and on* pairs are recomputed from black/white contrast so
     * the profile cannot create unreadable foreground/background pairs.
     */
    static ThemeTokens applyProfile(ThemeTokens base, ThemeProfile profile, boolean dark) {
        ThemeProfileValidator.Result validated = ThemeProfileValidator.validate(profile);
        if (!validated.valid()) throw new IllegalArgumentException(validated.message());
        ThemeProfile.SlotSet slots = dark ? validated.profile().dark : validated.profile().light;

        boolean hasPrimary = slots.primary != null;
        boolean hasPrimaryContainer = slots.primaryContainer != null;
        boolean hasSecondaryContainer = slots.secondaryContainer != null;
        boolean hasFocus = slots.focus != null;
        boolean hasSurface = slots.surface != null;
        boolean hasContainer = slots.surfaceContainer != null;
        boolean hasContainerHigh = slots.surfaceContainerHigh != null;
        boolean hasOnSurface = slots.onSurface != null;
        boolean hasOnSurfaceVariant = slots.onSurfaceVariant != null;
        boolean hasOutline = slots.outline != null;
        boolean hasError = slots.error != null;
        boolean hasSuccess = slots.success != null;
        boolean hasWarning = slots.warning != null;

        int primary = color(slots.primary, base.colorPrimary());
        int primaryContainer = color(slots.primaryContainer, base.colorPrimaryContainer());
        int secondaryContainer = color(slots.secondaryContainer, base.colorSecondaryContainer());
        int surface = color(slots.surface, base.colorSurface());
        int surfaceContainer = color(slots.surfaceContainer, base.colorSurfaceContainer());
        int surfaceContainerHigh = color(slots.surfaceContainerHigh, base.colorSurfaceContainerHigh());
        if (hasContainer && surfaceContainer == surface) {
            surfaceContainer = mix(surface, dark ? 0xFFFFFFFF : 0xFF000000, 0.06);
        }
        if (hasContainerHigh && surfaceContainerHigh == surfaceContainer) {
            surfaceContainerHigh = mix(surfaceContainer, dark ? 0xFFFFFFFF : 0xFF000000, 0.06);
        }
        int surfaceContainerHighest = hasContainer || hasContainerHigh
                ? mix(surfaceContainerHigh, surfaceContainer, 0.35)
                : base.colorSurfaceContainerHighest();
        // Material 3 derives primary and the surface roles from one tonal palette, so
        // primary is legible on every surface role by construction (the shipped baseline
        // scheme measures 4.97-10.91:1). The editor exposes both slots independently,
        // which can break that guarantee - primary is also the dialog action-button text
        // colour, so a broken pair renders actions invisible. Restoring the guarantee is
        // what the documented B-safe contract promises: a profile must never be able to
        // create an unreadable pair. The move keeps hue and chroma and only shifts tone.
        int readablePrimary = readableAccent(
                primary, surface, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest);
        boolean primaryAdjusted = readablePrimary != primary;
        primary = readablePrimary;
        int error = color(slots.error, base.colorError());
        int success = color(slots.success, base.colorSuccess());
        int warning = color(slots.warning, base.colorWarning());

        // Derived pairs are only recomputed when the user actually touched a slot that
        // feeds them; an empty profile must stay byte-identical to the frozen palette.
        int onPrimary = (hasPrimary || primaryAdjusted)
                ? readableOn(primary, base.colorOnPrimary()) : base.colorOnPrimary();
        int onPrimaryContainer = hasPrimaryContainer
                ? readableOn(primaryContainer, base.colorOnPrimaryContainer()) : base.colorOnPrimaryContainer();
        int onSecondaryContainer = hasSecondaryContainer
                ? readableOn(secondaryContainer, base.colorOnSecondaryContainer()) : base.colorOnSecondaryContainer();
        int onError = hasError ? readableOn(error, base.colorOnError()) : base.colorOnError();
        int onSuccess = hasSuccess ? readableOn(success, base.colorOnSuccess()) : base.colorOnSuccess();
        int onWarning = hasWarning ? readableOn(warning, base.colorOnWarning()) : base.colorOnWarning();

        boolean surfaceChanged = hasSurface || hasContainer || hasContainerHigh;
        int onSurface = surfaceChanged || hasOnSurface
                ? enforceBase(color(slots.onSurface, base.colorOnSurface()), surface,
                        hasContainer ? surfaceContainer : -1,
                        hasContainerHigh ? surfaceContainerHigh : -1)
                : base.colorOnSurface();
        int onSurfaceVariant = surfaceChanged || hasOnSurfaceVariant
                ? enforceBase(color(slots.onSurfaceVariant, base.colorOnSurfaceVariant()), surface,
                        hasContainer ? surfaceContainer : -1, -1)
                : base.colorOnSurfaceVariant();
        int outline = hasOutline || hasSurface
                ? ensureContrast(color(slots.outline, base.colorOutline()), surface, 3.0)
                : base.colorOutline();
        // The frozen palette deliberately ships focus == primary. Keeping that relationship
        // matters beyond aesthetics: the binder resolves a view colour by its semantic role,
        // and a baseline colour shared by two roles is only rewritten when both agree. If
        // focus drifted away from primary, primary-as-text views (dialog actions) would keep
        // their compiled colour and stay unreadable. `primary` already clears 4.5:1 on the
        // surface, so it also clears the 3.0:1 focus requirement.
        int focus = hasFocus || hasSurface
                ? ensureContrast(color(slots.focus, primary), surface, 3.0)
                : base.colorFocus();

        int surfaceDim = hasSurface ? mix(surface, 0xFF000000, dark ? 0.06 : 0.10) : base.colorSurfaceDim();
        int surfaceBright = hasSurface ? mix(surface, 0xFFFFFFFF, dark ? 0.14 : 0.08) : base.colorSurfaceBright();
        float scrimOpacity = slots.scrimOpacity == null ? (base.colorScrim() >>> 24) / 255f : slots.scrimOpacity;
        float dialogOpacity = slots.dialogOpacity == null ? base.dialogOpacity() : slots.dialogOpacity;
        float overlayOpacity = slots.overlayOpacity == null ? (base.colorOverlayLight() >>> 24) / 255f : slots.overlayOpacity;

        return new ThemeTokens(
                primary, onPrimary, primaryContainer, onPrimaryContainer,
                base.colorSecondary(), base.colorOnSecondary(),
                secondaryContainer, onSecondaryContainer,
                base.colorTertiary(), base.colorOnTertiary(),
                error, onError, base.colorErrorContainer(), base.colorOnErrorContainer(),
                success, onSuccess, base.colorSuccessContainer(), base.colorOnSuccessContainer(),
                warning, onWarning, base.colorWarningContainer(), base.colorOnWarningContainer(),
                surface, surfaceDim, surfaceBright, base.colorSurfaceContainerLowest(),
                base.colorSurfaceContainerLow(), surfaceContainer, surfaceContainerHigh, surfaceContainerHighest,
                onSurface, onSurfaceVariant, outline, base.colorOutlineVariant(),
                base.colorInverseSurface(), base.colorInverseOnSurface(), base.colorInversePrimary(),
                withAlpha(base.colorScrim(), scrimOpacity), base.colorShadow(), focus, base.focusScale(), dialogOpacity,
                base.colorPlayerControl(), base.colorPlayerControlMuted(), base.colorPlayerControlActive(), base.colorPlayerScrim(),
                base.colorHealthGood(), base.colorHealthWarn(), base.colorHealthBad(),
                withAlpha(base.colorOverlayLight(), overlayOpacity), base.colorOverlayDark()
        );
    }

    private static int enforceBase(int foreground, int surface, int container, int containerHigh) {
        int result = ensureContrast(foreground, surface, 4.5);
        if (container >= 0) result = ensureContrast(result, container, 4.5);
        if (containerHigh >= 0) result = ensureContrast(result, containerHigh, 4.5);
        return result;
    }

    private static int color(String value, int fallback) {
        return value == null ? fallback : ThemeProfileValidator.parseColor(value, fallback);
    }

    private static int readableOn(int background, int preferred) {
        double black = ThemeContrast.ratio(0xFF000000, background);
        double white = ThemeContrast.ratio(0xFFFFFFFF, background);
        boolean useBlack = black >= white;
        if ((preferred == 0xFF000000) == useBlack && ThemeContrast.ratio(preferred, background) >= 4.5) return preferred;
        return useBlack ? 0xFF000000 : 0xFFFFFFFF;
    }

    /**
     * Keeps {@code color} when it clears {@link #MIN_ACCENT_CONTRAST} on every backdrop,
     * otherwise returns the nearest tone on the same hue/chroma that does.
     *
     * <p>Walking tone rather than snapping to black/white preserves the user's brand
     * hue - the same thing Material's tonal palette does when it assigns primary.
     */
    private static int readableAccent(int color, int... backdrops) {
        if (clearsContrast(color, backdrops)) return color;
        Hct source = Hct.fromInt(color);
        double hue = source.getHue();
        double chroma = source.getChroma();
        double original = source.getTone();
        int best = color;
        double bestDistance = Double.MAX_VALUE;
        for (int tone = 0; tone <= 100; tone++) {
            int candidate = Hct.from(hue, chroma, tone).toInt();
            if (!clearsContrast(candidate, backdrops)) continue;
            double distance = Math.abs(tone - original);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (bestDistance != Double.MAX_VALUE) return best;
        // Unreachable for real surfaces (tone 0 / 100 always bracket), but never return
        // an unreadable colour if a future call site passes something degenerate.
        int fallback = ensureContrast(color, backdrops[0], MIN_ACCENT_CONTRAST);
        for (int backdrop : backdrops) fallback = ensureContrast(fallback, backdrop, MIN_ACCENT_CONTRAST);
        return fallback;
    }

    private static boolean clearsContrast(int color, int... backdrops) {
        for (int backdrop : backdrops) {
            if (ThemeContrast.ratio(color, backdrop) + 0.0001 < MIN_ACCENT_CONTRAST) return false;
        }
        return true;
    }

    private static int ensureContrast(int foreground, int background, double minimum) {
        if (ThemeContrast.ratio(foreground, background) + 0.0001 >= minimum) return foreground;
        double black = ThemeContrast.ratio(0xFF000000, background);
        double white = ThemeContrast.ratio(0xFFFFFFFF, background);
        return black >= white ? 0xFF000000 : 0xFFFFFFFF;
    }

    private static int mix(int first, int second, double amount) {
        double inverse = 1.0 - amount;
        int red = (int) Math.round(((first >>> 16) & 0xFF) * inverse + ((second >>> 16) & 0xFF) * amount);
        int green = (int) Math.round(((first >>> 8) & 0xFF) * inverse + ((second >>> 8) & 0xFF) * amount);
        int blue = (int) Math.round((first & 0xFF) * inverse + (second & 0xFF) * amount);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static int withAlpha(int color, float opacity) {
        int alpha = Math.max(0, Math.min(255, Math.round(opacity * 255f)));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    public static String lastDiagnostic() {
        return LAST_DIAGNOSTIC.get();
    }

    static ThemeTokens requireOrFallback(ThemeTokens candidate, ThemeTokens fallback) {
        try {
            return candidate.requireContrast();
        } catch (IllegalArgumentException error) {
            LAST_DIAGNOSTIC.set("fallback:seed-contrast:" + error.getMessage());
            return fallback;
        }
    }

    private static ThemeTokens derive(int seedColor, boolean dark, ThemeTokens fallback) {
        DynamicScheme scheme = new SchemeTonalSpot(Hct.fromInt(seedColor), dark, 0.0);
        return new ThemeTokens(
                scheme.getPrimary(), scheme.getOnPrimary(), scheme.getPrimaryContainer(), scheme.getOnPrimaryContainer(),
                scheme.getSecondary(), scheme.getOnSecondary(), scheme.getSecondaryContainer(), scheme.getOnSecondaryContainer(),
                scheme.getTertiary(), scheme.getOnTertiary(), scheme.getError(), scheme.getOnError(),
                scheme.getErrorContainer(), scheme.getOnErrorContainer(),
                fallback.colorSuccess(), fallback.colorOnSuccess(), fallback.colorSuccessContainer(), fallback.colorOnSuccessContainer(),
                fallback.colorWarning(), fallback.colorOnWarning(), fallback.colorWarningContainer(), fallback.colorOnWarningContainer(),
                scheme.getSurface(), scheme.getSurfaceDim(), scheme.getSurfaceBright(), scheme.getSurfaceContainerLowest(),
                scheme.getSurfaceContainerLow(), scheme.getSurfaceContainer(), scheme.getSurfaceContainerHigh(), scheme.getSurfaceContainerHighest(),
                scheme.getOnSurface(), scheme.getOnSurfaceVariant(), scheme.getOutline(), scheme.getOutlineVariant(),
                scheme.getInverseSurface(), scheme.getInverseOnSurface(), scheme.getInversePrimary(), scheme.getScrim(),
                scheme.getShadow(), scheme.getPrimary(), fallback.focusScale(), fallback.dialogOpacity(), fallback.colorPlayerControl(),
                fallback.colorPlayerControlMuted(), fallback.colorPlayerControlActive(), fallback.colorPlayerScrim(),
                fallback.colorHealthGood(), fallback.colorHealthWarn(), fallback.colorHealthBad(), fallback.colorOverlayLight(), fallback.colorOverlayDark()
        );
    }

    private static boolean isOpaque(int color) {
        return ((color >>> 24) & 0xFF) == 0xFF;
    }
}
