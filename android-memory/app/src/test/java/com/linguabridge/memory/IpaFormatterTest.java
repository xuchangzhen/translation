package com.linguabridge.memory;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class IpaFormatterTest {
    @Test
    public void preservesPrimaryAndSecondaryStressMarksForDisplay() {
        String storedIpa = "/ˈklɑk/";
        assertEquals("/ˈklɑk/", IpaFormatter.formatIpaForDisplay(storedIpa));
        assertEquals("/ˈklɑk/", storedIpa);
        assertEquals("/ˈɑsəˌleɪtɚ/", IpaFormatter.formatIpaForDisplay("/ˈɑsəˌleɪtɚ/"));
        assertEquals("/əˈbændən/", IpaFormatter.formatIpaForDisplay("/əˈbændən/"));
    }

    @Test
    public void retainsOtherIpaGlyphsAndPunctuation() {
        assertEquals("/ˈθðŋæɚ/", IpaFormatter.formatIpaForDisplay("/ˈθðŋæɚ/"));
        assertEquals("/'a’b/", IpaFormatter.formatIpaForDisplay("/'a’b/"));
        assertEquals("", IpaFormatter.formatIpaForDisplay(null));
    }

    @Test
    public void normalizesBareAndBracketedSourcesToTheSameDesktopStyle() {
        assertEquals("/kæʃ/", IpaFormatter.formatIpaForDisplay("kæʃ"));
        assertEquals("/ˈkæʃ/", IpaFormatter.formatIpaForDisplay("[ˈkæʃ]"));
        assertEquals("/ˈkæʃ/", IpaFormatter.formatIpaForDisplay(" /ˈkæʃ/ "));
    }
}
