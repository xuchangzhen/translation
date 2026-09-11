package com.linguabridge.memory;

/** Parsed, bounded response data for one requested card. */
public final class AiEnrichmentResult {
    public final long id;
    public final String context;
    public final String contextTranslation;
    public final String technicalNote;
    public final String resolvedMode;
    final boolean translationRequired;

    /** Backward-compatible constructor for callers that do not provide a translation yet. */
    public AiEnrichmentResult(long id, String context, String technicalNote, String resolvedMode) {
        this.id = id;
        this.context = context == null ? "" : context.trim();
        this.contextTranslation = "";
        this.technicalNote = technicalNote == null ? "" : technicalNote.trim();
        this.resolvedMode = resolvedMode == null ? "" : resolvedMode.trim();
        this.translationRequired = false;
    }

    public AiEnrichmentResult(long id, String context, String contextTranslation, String technicalNote, String resolvedMode) {
        this.id = id;
        this.context = context == null ? "" : context.trim();
        this.contextTranslation = contextTranslation == null ? "" : contextTranslation.trim();
        this.technicalNote = technicalNote == null ? "" : technicalNote.trim();
        this.resolvedMode = resolvedMode == null ? "" : resolvedMode.trim();
        this.translationRequired = true;
    }
}
