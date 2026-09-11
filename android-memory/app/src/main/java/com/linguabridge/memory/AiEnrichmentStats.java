package com.linguabridge.memory;

/** Small aggregate used by the library panel and quantity picker. */
public final class AiEnrichmentStats {
    public final int total;
    public final int complete;
    public final int pending;
    public final int error;
    public final int processing;

    public AiEnrichmentStats(int total, int complete, int pending, int error, int processing) {
        this.total = total;
        this.complete = complete;
        this.pending = pending;
        this.error = error;
        this.processing = processing;
    }

    public int remaining() { return Math.max(0, total - complete - processing); }
    public int retryable() { return pending + error; }
}
