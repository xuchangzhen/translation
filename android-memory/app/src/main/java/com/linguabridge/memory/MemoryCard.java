package com.linguabridge.memory;

import java.util.ArrayList;
import java.util.List;

public final class MemoryCard {
    public long id;
    public long wordbookId;
    public String category = "";
    public String syncKey = "";
    public String type = "word";
    public String front = "";
    public String back = "";
    public String frontLanguage = "en";
    public String backLanguage = "zh-CN";
    public String phonetic = "";
    public String context = "";
    public String contextTranslation = "";
    public List<String> technicalNotes = new ArrayList<>();
    /** Persistent AI state lets every card explain an empty analysis instead of rendering a blank area. */
    public String aiStatus = "pending";
    public String aiError = "";
    public String aiMode = "";
    public long createdAt;
    public long updatedAt;
    public long dueAt;
    public int intervalDays;
    public double easeFactor = 2.5;
    public int repetitions;
    public int lapses;
    public String state = "new";

    /** The result of the first rating in a review session is always computed from this snapshot. */
    public ReviewBaseline reviewBaseline() {
        return new ReviewBaseline(intervalDays, repetitions, easeFactor, lapses, state, dueAt);
    }
}
