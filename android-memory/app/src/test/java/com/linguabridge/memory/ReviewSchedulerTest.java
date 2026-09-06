package com.linguabridge.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReviewSchedulerTest {
    @Test
    public void goodStartsAtTwoDays() {
        long now = 1_700_000_000_000L;
        ReviewScheduler.Result result = ReviewScheduler.schedule(0, 0, 2.5, 0, "good", now);
        assertEquals(2, result.intervalDays());
        assertEquals(now + 2 * ReviewScheduler.DAY_MS, result.dueAt());
        assertEquals("review", result.state());
    }

    @Test
    public void againReturnsSoonAndCountsALapse() {
        long now = 1_700_000_000_000L;
        ReviewScheduler.Result result = ReviewScheduler.schedule(8, 3, 2.5, 1, "again", now);
        assertEquals(0, result.repetitions());
        assertEquals(2, result.lapses());
        assertEquals("learning", result.state());
        assertTrue(result.dueAt() < now + ReviewScheduler.DAY_MS);
    }

    @Test
    public void easyEventuallyMarksCardsMastered() {
        ReviewScheduler.Result result = ReviewScheduler.schedule(20, 4, 2.5, 0, "easy", 0);
        assertTrue(result.intervalDays() >= 30);
        assertEquals("mastered", result.state());
    }
}
