package com.linguabridge.memory;

/** Formats IPA for UI without discarding meaningful pronunciation information. */
public final class IpaFormatter {
    private IpaFormatter() {}

    public static String formatIpaForDisplay(String ipa) {
        if (ipa == null) return "";
        // Primary/secondary stress are IPA, not decoration. Keeping them makes multi-syllable
        // pronunciations legible and avoids the broken-looking flattened output of the old UI.
        String value = ipa.trim().replaceAll("\\s+", " ");
        if (value.isEmpty()) return "";
        // ECDICT stores bare IPA while desktop payloads commonly include /…/ or […].  Render
        // all three as one familiar form so custom and desktop wordbooks look identical.
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) value = value.substring(1, value.length() - 1).trim();
        if (value.startsWith("/") && value.endsWith("/")) return value;
        return "/" + value + "/";
    }
}
