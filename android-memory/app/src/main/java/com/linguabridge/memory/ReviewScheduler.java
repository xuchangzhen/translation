package com.linguabridge.memory;

public final class ReviewScheduler {
    public static final long DAY_MS = 24L * 60L * 60L * 1000L;
    public static final long TEN_MINUTES_MS = 10L * 60L * 1000L;

    private ReviewScheduler() {}

    public static Result schedule(
            int previousInterval,
            int previousRepetitions,
            double previousEase,
            int previousLapses,
            String rating,
            long now
    ) {
        if (!("again".equals(rating) || "hard".equals(rating) || "good".equals(rating) || "easy".equals(rating))) {
            throw new IllegalArgumentException("Unknown review rating");
        }
        int intervalDays = Math.max(0, previousInterval);
        int repetitions = Math.max(0, previousRepetitions);
        int lapses = Math.max(0, previousLapses);
        double ease = Math.max(1.3, previousEase);
        long dueAt;
        String state;

        if ("again".equals(rating)) {
            repetitions = 0;
            intervalDays = 0;
            lapses += 1;
            ease = Math.max(1.3, ease - 0.2);
            dueAt = now + TEN_MINUTES_MS;
            state = "learning";
        } else {
            repetitions += 1;
            if (intervalDays == 0) {
                intervalDays = "hard".equals(rating) ? 1 : "easy".equals(rating) ? 4 : 2;
            } else {
                double multiplier = "hard".equals(rating)
                        ? 1.2
                        : "easy".equals(rating) ? ease * 1.3 : ease;
                intervalDays = Math.min(
                        365,
                        Math.max(intervalDays + 1, (int) Math.round(intervalDays * multiplier))
                );
            }
            if ("hard".equals(rating)) ease = Math.max(1.3, ease - 0.15);
            if ("easy".equals(rating)) ease = Math.min(3.2, ease + 0.15);
            dueAt = now + intervalDays * DAY_MS;
            state = intervalDays >= 30 ? "mastered" : "review";
        }

        return new Result(intervalDays, repetitions, ease, lapses, dueAt, state);
    }

    public static final class Result {
        private final int intervalDays;
        private final int repetitions;
        private final double easeFactor;
        private final int lapses;
        private final long dueAt;
        private final String state;

        public Result(
                int intervalDays,
                int repetitions,
                double easeFactor,
                int lapses,
                long dueAt,
                String state
        ) {
            this.intervalDays = intervalDays;
            this.repetitions = repetitions;
            this.easeFactor = easeFactor;
            this.lapses = lapses;
            this.dueAt = dueAt;
            this.state = state;
        }

        public int intervalDays() { return intervalDays; }
        public int repetitions() { return repetitions; }
        public double easeFactor() { return easeFactor; }
        public int lapses() { return lapses; }
        public long dueAt() { return dueAt; }
        public String state() { return state; }
    }
}
