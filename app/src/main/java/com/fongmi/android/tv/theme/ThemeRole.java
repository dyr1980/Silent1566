package com.fongmi.android.tv.theme;

import java.util.Locale;

/**
 * The bounded set of semantic roles the controlled binder may rewrite.
 *
 * <p>Only the 13 user-editable color slots and the six foreground roles the
 * resolver derives from them are listed. Player, media, logo, rating, health and
 * brand colors are intentionally absent so a profile can never reach them.
 */
public enum ThemeRole {
    PRIMARY,
    PRIMARY_CONTAINER,
    SECONDARY_CONTAINER,
    FOCUS,
    SURFACE,
    SURFACE_CONTAINER,
    SURFACE_CONTAINER_HIGH,
    ON_SURFACE,
    ON_SURFACE_VARIANT,
    OUTLINE,
    ERROR,
    SUCCESS,
    WARNING,
    ON_PRIMARY,
    ON_PRIMARY_CONTAINER,
    ON_SECONDARY_CONTAINER,
    ON_ERROR,
    ON_SUCCESS,
    ON_WARNING;

    public static final String TAG_PREFIX = "webhtv:";

    /** Resolves an explicit {@code android:tag="webhtv:primary"} marker. */
    public static ThemeRole fromTag(Object tag) {
        if (!(tag instanceof String value)) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(TAG_PREFIX)) return null;
        String name = normalized.substring(TAG_PREFIX.length()).trim();
        if (name.isEmpty()) return null;
        for (ThemeRole role : values()) {
            if (role.name().toLowerCase(Locale.ROOT).equals(name)) return role;
        }
        return null;
    }

    /** The active value of this role, or 0 when the role is not bound. */
    public int colorOf(ThemeTokens tokens) {
        if (tokens == null) return 0;
        return switch (this) {
            case PRIMARY -> tokens.colorPrimary();
            case PRIMARY_CONTAINER -> tokens.colorPrimaryContainer();
            case SECONDARY_CONTAINER -> tokens.colorSecondaryContainer();
            case FOCUS -> tokens.colorFocus();
            case SURFACE -> tokens.colorSurface();
            case SURFACE_CONTAINER -> tokens.colorSurfaceContainer();
            case SURFACE_CONTAINER_HIGH -> tokens.colorSurfaceContainerHigh();
            case ON_SURFACE -> tokens.colorOnSurface();
            case ON_SURFACE_VARIANT -> tokens.colorOnSurfaceVariant();
            case OUTLINE -> tokens.colorOutline();
            case ERROR -> tokens.colorError();
            case SUCCESS -> tokens.colorSuccess();
            case WARNING -> tokens.colorWarning();
            case ON_PRIMARY -> tokens.colorOnPrimary();
            case ON_PRIMARY_CONTAINER -> tokens.colorOnPrimaryContainer();
            case ON_SECONDARY_CONTAINER -> tokens.colorOnSecondaryContainer();
            case ON_ERROR -> tokens.colorOnError();
            case ON_SUCCESS -> tokens.colorOnSuccess();
            case ON_WARNING -> tokens.colorOnWarning();
        };
    }

    /** Roles whose value a user profile may directly override. */
    public boolean isUserSlot() {
        return ordinal() <= WARNING.ordinal();
    }
}
