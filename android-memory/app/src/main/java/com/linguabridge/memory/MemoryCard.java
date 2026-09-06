package com.linguabridge.memory;

import java.util.ArrayList;
import java.util.List;

public final class MemoryCard {
    public long id;
    public String syncKey = "";
    public String type = "word";
    public String front = "";
    public String back = "";
    public String frontLanguage = "en";
    public String backLanguage = "zh-CN";
    public String phonetic = "";
    public String context = "";
    public List<String> technicalNotes = new ArrayList<>();
    public long createdAt;
    public long updatedAt;
    public long dueAt;
    public int intervalDays;
    public double easeFactor = 2.5;
    public int repetitions;
    public int lapses;
    public String state = "new";
}
