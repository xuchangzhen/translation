package com.linguabridge.memory;

/** Formats IPA strictly for UI text; database and sync values remain standard IPA. */
public final class IpaFormatter {
    private IpaFormatter() {}

    public static String formatIpaForDisplay(String ipa) {
        if (ipa == null || ipa.isEmpty()) return "";
        return ipa.replace("\u02C8", "").replace("\u02CC", "");
    }
}
