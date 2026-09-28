package com.fongmi.android.tv.theme;

import org.junit.Test;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ThemeContractTest {

    private static final Pattern COLOR = Pattern.compile("<color name=\"(webhtv_color_[a-z0-9_]+)\">#([0-9A-Fa-f]{6,8})</color>");

    @Test
    public void resourceMappingMatchesTheImmutableRecordForEveryColor() throws Exception {
        assertResourceMapping(ThemeTokens.light(), Path.of("src/main/res/values/webhtv_tokens.xml"));
        assertResourceMapping(ThemeTokens.dark(), Path.of("src/main/res/values-night/webhtv_tokens.xml"));
    }

    @Test
    public void allContrastPairsAreFrozenAndPass() {
        ThemeTokens.light().requireContrast();
        ThemeTokens.dark().requireContrast();
        assertEquals(6.386, ThemeContrast.ratio(ThemeTokens.light().colorOnPrimary(), ThemeTokens.light().colorPrimary()), 0.002);
        assertEquals(4.284, ThemeContrast.ratio(ThemeTokens.light().colorOutline(), ThemeTokens.light().colorSurface()), 0.002);
        assertEquals(14.724, ThemeContrast.ratio(ThemeTokens.dark().colorPlayerControlActive(), 0xFF000000), 0.002);
    }

    @Test
    public void playerControlsAreIsolatedFromGeneralFocusAndPrimary() {
        for (ThemeTokens tokens : new ThemeTokens[]{ThemeTokens.light(), ThemeTokens.dark()}) {
            assertNotEquals(tokens.colorPrimary(), tokens.colorPlayerControlActive());
            assertNotEquals(tokens.colorFocus(), tokens.colorPlayerControlActive());
        }
    }

    @Test
    public void allSemanticAttrsAreDeclared() throws Exception {
        String attrs = Files.readString(Path.of("src/main/res/values/webhtv_attrs.xml"), StandardCharsets.UTF_8);
        for (RecordComponent component : ThemeTokens.class.getRecordComponents()) {
            if (!component.getType().equals(int.class)) continue;
            assertTrue(component.getName(), attrs.contains("name=\"" + attrName(component.getName()) + "\""));
        }
        assertFalse(attrs.contains("format=\"string\""));
    }

    /**
     * A semantic attr that is declared and referenced but never assigned is a crash, not a
     * silent fallback.
     *
     * <p>{@code ?attr/webhtvColorOnSurface} and {@code ?attr/webhtvColorOnSurfaceVariant} were
     * declared in {@code webhtv_attrs.xml} and referenced by every {@code TextAppearance.WebHTV.*}
     * style, but no theme ever assigned them - {@code Theme.WebHTV} only maps the standard
     * Material attrs. Reading such a text appearance makes {@code TextView.readTextAppearance}
     * call {@code TypedArray.getColorStateList} on an unresolvable attribute, which throws
     * {@code UnsupportedOperationException} inside the constructor, so inflation dies. That is
     * exactly how "TMDB data configuration" crashed on open (and with it every other dialog
     * using {@code Widget.WebHTV.Label}/{@code Helper}/{@code SliderLabel}).
     *
     * <p>Guard: every referenced {@code webhtv*} attr must also be assigned by a theme, so the
     * attrs and their assignments can never drift apart again.
     */
    @Test
    public void everyReferencedSemanticAttrIsAssignedByATheme() throws Exception {
        Pattern reference = Pattern.compile("\\?attr/(webhtv[A-Za-z0-9_]+)");
        java.util.Set<String> referenced = new java.util.TreeSet<>();
        for (String sourceSet : new String[]{"main", "mobile", "leanback"}) {
            Path base = Path.of("src/" + sourceSet + "/res");
            if (!Files.exists(base)) continue;
            try (java.util.stream.Stream<Path> paths = Files.walk(base)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".xml")).toList()) {
                    Matcher matcher = reference.matcher(Files.readString(path, StandardCharsets.UTF_8));
                    while (matcher.find()) referenced.add(matcher.group(1));
                }
            }
        }

        StringBuilder assignedSources = new StringBuilder();
        Path values = Path.of("src/main/res/values");
        try (java.util.stream.Stream<Path> paths = Files.walk(values)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".xml")).toList()) {
                if (path.getFileName().toString().equals("webhtv_attrs.xml")) continue;
                assignedSources.append(Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        String themes = assignedSources.toString();
        java.util.List<String> unassigned = new java.util.ArrayList<>();
        for (String attr : referenced) {
            // An assignment is an <item name="attr"> entry; the bare declaration is not enough.
            if (!themes.contains("name=\"" + attr + "\">")) unassigned.add(attr);
        }
        assertTrue("these semantic attrs are referenced but no theme assigns them, so any view "
                + "reading them crashes during inflation: " + unassigned, unassigned.isEmpty());
    }

    private static void assertResourceMapping(ThemeTokens tokens, Path path) throws Exception {
        Map<String, Integer> colors = colors(path);
        assertEquals(49, colors.size());
        for (RecordComponent component : ThemeTokens.class.getRecordComponents()) {
            if (!component.getType().equals(int.class)) continue;
            String name = component.getName();
            String resource = "webhtv_color_" + snakeCase(name.substring("color".length()));
            Integer actual = colors.get(resource);
            assertTrue(path + " missing " + resource, actual != null);
            assertEquals(resource, component.getAccessor().invoke(tokens), actual);
        }
    }

    private static Map<String, Integer> colors(Path path) throws Exception {
        Map<String, Integer> colors = new LinkedHashMap<>();
        Matcher matcher = COLOR.matcher(Files.readString(path, StandardCharsets.UTF_8));
        while (matcher.find()) {
            long value = Long.parseLong(matcher.group(2), 16);
            colors.put(matcher.group(1), matcher.group(2).length() == 6 ? (int) (0xFF000000L | value) : (int) value);
        }
        return colors;
    }

    private static String attrName(String getter) {
        return "webhtv" + Character.toUpperCase(getter.charAt(0)) + getter.substring(1);
    }

    private static String snakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.US);
    }
}
