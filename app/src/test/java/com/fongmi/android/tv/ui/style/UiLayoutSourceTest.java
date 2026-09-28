package com.fongmi.android.tv.ui.style;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Freezes the list/card geometry contracts introduced by stages C and D. */
public class UiLayoutSourceTest {

    private static final Pattern DIMENSION = Pattern.compile("android:layout_(?:width|height)=\"([0-9]+)dp\"");

    @Test
    public void posterCardsKeepTheTwoByThreeRatio() throws Exception {
        assertRatio("src/main/res/layout/adapter_tmdb_recommendation.xml", 108, 162, 2.0 / 3.0);
        assertRatio("src/main/res/layout/adapter_tmdb_rail_item.xml", 132, 184, 132.0 / 184.0);
    }

    @Test
    public void landscapeCardsKeepTheSixteenByNineRatio() throws Exception {
        assertRatio("src/main/res/layout/adapter_tmdb_recommendation_landscape.xml", 276, 156, 16.0 / 9.0);
        assertRatio("src/main/res/layout/adapter_tmdb_video.xml", 276, 156, 16.0 / 9.0);
        assertRatio("src/leanback/res/layout/adapter_episode_card.xml", 280, 160, 7.0 / 4.0);
    }

    @Test
    public void tvCardsUseTheSharedCardGapTokenAndRoundedFamily() throws Exception {
        String card = Files.readString(Path.of("src/leanback/res/layout/adapter_episode_card.xml"), StandardCharsets.UTF_8);
        assertTrue("TV episode cards must keep the shared rounded family",
                card.contains("app:cardCornerRadius=\"@dimen/webhtv_card_radius_default\""));
        assertFalse("TV episode card colours must be token-only", card.matches("(?s).*#[0-9A-Fa-f]{6,8}.*"));
    }

    @Test
    public void cardsKeepImageTextOverContentSafeScrim() throws Exception {
        for (String file : new String[]{
                "src/main/res/layout/adapter_tmdb_recommendation.xml",
                "src/main/res/layout/adapter_tmdb_recommendation_landscape.xml",
                "src/leanback/res/layout/adapter_episode_card.xml"
        }) {
            String source = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            assertTrue(file + " must keep a scrim behind poster text",
                    source.contains("shape_tmdb_episode_overlay")
                            || source.contains("shape_episode_card_overlay")
                            || source.contains("shape_tmdb_recommendation_info"));
        }
    }

    private static void assertRatio(String file, int width, int height, double expected) throws Exception {
        String source = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        Matcher matcher = DIMENSION.matcher(source);
        int actualWidth = -1;
        int actualHeight = -1;
        while (matcher.find()) {
            if (actualWidth < 0) actualWidth = Integer.parseInt(matcher.group(1));
            else if (actualHeight < 0) actualHeight = Integer.parseInt(matcher.group(1));
        }
        if (width > 0) assertEquals(file + " width", width, actualWidth);
        if (height > 0) assertEquals(file + " height", height, actualHeight);
        if (expected > 0) assertEquals(file + " ratio", expected, (double) actualWidth / actualHeight, 0.05);
    }
}
