package com.linguabridge.memory;

/** Immutable SRS state captured before a card is rated during a review session. */
public final class ReviewBaseline {
    public final int intervalDays;
    public final int repetitions;
    public final double easeFactor;
    public final int lapses;
    public final String state;
    public final long dueAt;

    public ReviewBaseline(int intervalDays, int repetitions, double easeFactor, int lapses, String state, long dueAt) {
        this.intervalDays = intervalDays;
        this.repetitions = repetitions;
        this.easeFactor = easeFactor;
        this.lapses = lapses;
        this.state = state == null ? "new" : state;
        this.dueAt = dueAt;
    }
}
