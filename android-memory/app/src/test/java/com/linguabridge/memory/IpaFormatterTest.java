package com.linguabridge.memory;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class IpaFormatterTest {
    @Test
    public void removesOnlyPrimaryAndSecondaryStressMarksForDisplay() {
        String storedIpa = "/ˈklɑk/";
        assertEquals("/klɑk/", IpaFormatter.formatIpaForDisplay(storedIpa));
        assertEquals("/ˈklɑk/", storedIpa);
        assertEquals("/ɑsəleɪtɚ/", IpaFormatter.formatIpaForDisplay("/ˈɑsəˌleɪtɚ/"));
        assertEquals("/əbændən/", IpaFormatter.formatIpaForDisplay("/əˈbændən/"));
    }

    @Test
    public void retainsOtherIpaGlyphsAndPunctuation() {
        assertEquals("/θðŋæɚ/", IpaFormatter.formatIpaForDisplay("/ˈθðŋæɚ/"));
        assertEquals("/'a’b/", IpaFormatter.formatIpaForDisplay("/'a’b/"));
        assertEquals("", IpaFormatter.formatIpaForDisplay(null));
    }
}
