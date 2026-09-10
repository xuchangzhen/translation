package com.linguabridge.memory;

/** The parsed preview plus the outcome of optional local phonetic enrichment. */
public final class PhoneticEnrichmentResult {
    public final ImportPreview preview;
    public final int existing;
    public final int filled;
    public final int missing;
    public final boolean dictionaryUnavailable;

    PhoneticEnrichmentResult(ImportPreview preview, int existing, int filled, int missing, boolean dictionaryUnavailable) {
        this.preview = preview;
        this.existing = existing;
        this.filled = filled;
        this.missing = missing;
        this.dictionaryUnavailable = dictionaryUnavailable;
    }
}
