package com.linguabridge.memory;

public final class PhoneticBackfillResult {
    public final int scanned;
    public final int filled;
    public final boolean dictionaryUnavailable;
    public final boolean alreadyCompleted;

    PhoneticBackfillResult(int scanned, int filled, boolean dictionaryUnavailable, boolean alreadyCompleted) {
        this.scanned = scanned;
        this.filled = filled;
        this.dictionaryUnavailable = dictionaryUnavailable;
        this.alreadyCompleted = alreadyCompleted;
    }
}
