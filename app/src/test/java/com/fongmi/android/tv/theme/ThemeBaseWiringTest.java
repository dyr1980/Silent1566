package com.fongmi.android.tv.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Layer 1 integration contract: both flavours inherit the WebHTV semantic theme and the
 * Activity theme maps every Material color role used by layouts onto a webhtv token, so no
 * screen silently falls back to the Material baseline palette.
 */
public class ThemeBaseWiringTest {

    private static final String[] ACTIVITY_ROLES = {
            "colorPrimary", "colorOnPrimary", "colorPrimaryContainer", "colorOnPrimaryContainer",
            "colorSecondary", "colorOnSecondary", "colorSecondaryContainer", "colorOnSecondaryContainer",
            "colorTertiary", "colorOnTertiary",
            "colorError", "colorOnError", "colorErrorContainer", "colorOnErrorContainer",
            "colorSurface", "colorSurfaceDim", "colorSurfaceBright",
            "colorSurfaceContainerLowest", "colorSurfaceContainerLow", "colorSurfaceContainer",
            "colorSurfaceContainerHigh", "colorSurfaceContainerHighest", "colorSurfaceVariant",
            "colorOnSurface", "colorOnSurfaceVariant", "colorOutline", "colorOutlineVariant",
            "colorSurfaceInverse", "colorOnSurfaceInverse", "colorPrimaryInverse",
            "colorControlNormal", "colorControlActivated",
    };

    private static final String[] DIALOG_ROLES = {
            "colorPrimary", "colorOnPrimary",
            "colorSecondaryContainer", "colorOnSecondaryContainer",
            "colorError", "colorOnError", "colorErrorContainer", "colorOnErrorContainer",
            "colorSurface", "colorSurfaceContainer", "colorSurfaceContainerHigh", "colorSurfaceContainerHighest",
            "colorOnSurface", "colorOnSurfaceVariant", "colorOutline", "colorOutlineVariant",
            "colorControlNormal", "colorControlActivated",
    };

    /** Layouts draw these over video, so they keep their own alpha instead of ?attr/colorOnSurface. */
    private static final String[] ON_SURFACE_ALPHA_ATTRS = {
            "colorOnSurface_20", "colorOnSurface_70", "colorOnSurface_80", "colorOnSurface_90",
    };

    @Test
    public void flavourBaseThemesInheritTheWebhtvSemanticTheme() throws Exception {
        String mobile = read("src/mobile/res/values/styles.xml");
        assertTrue(mobile.contains("<style name=\"Theme.Base\" parent=\"Theme.WebHTV.Mobile\">"));
        assertFalse(mobile, mobile.contains("Theme.Material3.DynamicColors"));
        assertTrue(mobile.contains("<item name=\"materialAlertDialogTheme\">@style/ThemeOverlay.WebHTV.Dialog</item>"));
        assertTrue(mobile.contains("<item name=\"android:statusBarColor\">@color/transparent</item>"));

        String leanback = read("src/leanback/res/values/styles.xml");
        assertTrue(leanback.contains("<style name=\"Theme.Base\" parent=\"Theme.WebHTV.TV\">"));
        assertFalse(leanback, leanback.contains("<item name=\"colorPrimary\">@color/white</item>"));
    }

    @Test
    public void activityThemeMapsEveryMaterialColorRole() throws Exception {
        String theme = read("src/main/res/values/webhtv_styles.xml");
        assertFalse(theme, theme.contains("DynamicColors"));
        String body = styleBody(theme, "Theme.WebHTV");
        for (String role : ACTIVITY_ROLES) {
            assertTrue(role, body.contains("<item name=\"" + role + "\">@color/webhtv_"));
        }
        assertTrue(body.contains("<item name=\"android:colorBackground\">@color/webhtv_color_surface</item>"));
        assertTrue(body.contains("<item name=\"android:windowBackground\">?attr/colorSurface</item>"));
    }

    /**
     * Material points the framework text attributes at its own
     * {@code m3_sys_color_*} palette, so a widget built from the Activity context keeps
     * Material's colours and ThemeBinder cannot rewrite them (measured on device:
     * Material's {@code on_surface_variant} {@code #49454F} survived a custom theme).
     * The activity theme must therefore map the framework text roles onto our tokens.
     */
    @Test
    public void activityThemeMapsFrameworkTextRolesToWebhtvTokens() throws Exception {
        String theme = read("src/main/res/values/webhtv_styles.xml");
        String body = styleBody(theme, "Theme.WebHTV");
        for (String role : new String[]{
                "android:textColorPrimary", "android:textColorSecondary",
                "android:textColorTertiary", "android:textColorHint"}) {
            assertTrue(role + " must resolve through a webhtv selector",
                    body.contains("<item name=\"" + role + "\">@color/webhtv_text_"));
        }
        String primary = read("src/main/res/color/webhtv_text_primary.xml");
        String secondary = read("src/main/res/color/webhtv_text_secondary.xml");
        assertTrue(primary.contains("@color/webhtv_color_on_surface"));
        assertTrue(secondary.contains("@color/webhtv_color_on_surface_variant"));
        // Disabled emphasis must survive, otherwise disabled widgets no longer dim.
        assertTrue(primary.contains("android:state_enabled=\"false\""));
        assertTrue(secondary.contains("android:state_enabled=\"false\""));
    }

    @Test
    public void dialogsReuseTheSameSemanticRoles() throws Exception {
        String theme = read("src/main/res/values/webhtv_styles.xml");
        for (String style : new String[]{"Theme.WebHTV.Dialog", "ThemeOverlay.WebHTV.Dialog"}) {
            String body = styleBody(theme, style);
            for (String role : DIALOG_ROLES) {
                assertTrue(style + " is missing " + role, body.contains("<item name=\"" + role + "\">"));
            }
        }
    }

    @Test
    public void everyLayoutReferencedOnSurfaceAlphaAttrIsDeclaredAndThemed() throws Exception {
        StringBuilder layouts = new StringBuilder();
        for (String directory : new String[]{
                "src/main/res/layout", "src/mobile/res/layout", "src/leanback/res/layout"}) {
            Path root = Path.of(directory);
            if (!Files.isDirectory(root)) continue;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    layouts.append(Files.readString(path, StandardCharsets.UTF_8));
                }
            }
        }
        String attrs = read("src/main/res/values/webhtv_attrs.xml");
        String theme = styleBody(read("src/main/res/values/webhtv_styles.xml"), "Theme.WebHTV");
        for (String attr : ON_SURFACE_ALPHA_ATTRS) {
            if (!layouts.toString().contains("?attr/" + attr)) continue;
            assertTrue(attr + " is used by a layout but never declared", attrs.contains("name=\"" + attr + "\" format=\"color\""));
            assertTrue(attr + " is declared but never assigned by Theme.WebHTV", theme.contains("<item name=\"" + attr + "\">"));
        }
    }

    @Test
    public void onSurfaceAlphaResourcesTrackTheOnSurfaceToken() throws Exception {
        String[][] palettes = {
                {"src/main/res/values/webhtv_tokens.xml", "webhtv_color_on_surface", "src/main/res/values-night/webhtv_tokens.xml"},
        };
        for (String[] palette : palettes) {
            assertAlphaVariants(palette[0], palette[2]);
        }
        assertAlphaVariants("src/leanback/res/values/webhtv_tokens.xml", null);
    }

    private static void assertAlphaVariants(String lightOrTvPath, String nightPath) throws Exception {
        for (String path : nightPath == null ? new String[]{lightOrTvPath} : new String[]{lightOrTvPath, nightPath}) {
            String source = read(path);
            String onSurface = hex(source, "webhtv_color_on_surface");
            assertEquals(path + " must declare the onSurface token", 6, onSurface.length());
            assertTrue(path + " webhtv_on_surface_20",
                    source.contains("<color name=\"webhtv_on_surface_20\">#33" + onSurface + "</color>"));
            assertTrue(path + " webhtv_on_surface_70",
                    source.contains("<color name=\"webhtv_on_surface_70\">#B3" + onSurface + "</color>"));
            assertTrue(path + " webhtv_on_surface_80",
                    source.contains("<color name=\"webhtv_on_surface_80\">#CC" + onSurface + "</color>"));
            assertTrue(path + " webhtv_on_surface_90",
                    source.contains("<color name=\"webhtv_on_surface_90\">#E6" + onSurface + "</color>"));
        }
    }

    private static String hex(String source, String colorName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<color name=\"" + colorName + "\">#([0-9A-Fa-f]{6})</color>")
                .matcher(source);
        assertTrue(colorName + " is missing", matcher.find());
        return matcher.group(1).toUpperCase(java.util.Locale.ROOT);
    }

    private static String styleBody(String source, String style) {
        int start = source.indexOf("<style name=\"" + style + "\"");
        assertTrue(style + " is missing", start >= 0);
        int end = source.indexOf("</style>", start);
        assertTrue(style + " is not closed", end > start);
        return source.substring(start, end);
    }

    private static String read(String path) throws Exception {
        Path root = Files.exists(Path.of("src")) ? Path.of("") : Path.of("app");
        return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
    }
}
