package com.linguabridge.memory;

public final class Wordbook {
    public final long id;
    public final String name;
    public final int total, due, fresh;
    public final long pinnedAt;
    public final String contentMode;
    public Wordbook(long id, String name, int total, int due, int fresh, long pinnedAt, String contentMode) {
        this.id = id; this.name = name; this.total = total; this.due = due; this.fresh = fresh;
        this.pinnedAt = pinnedAt;
        this.contentMode = contentMode == null || contentMode.isEmpty() ? "general" : contentMode;
    }
}
