package com.linguabridge.memory;

/** A claimed card sent to one AI batch. It contains no secrets or mutable database state. */
public final class AiWorkItem {
    public final long id;
    public final String front;
    public final String back;
    public final String category;
    public final String context;
    public final String contextTranslation;
    public final String technicalNote;

    AiWorkItem(long id, String front, String back, String category, String context, String technicalNote) {
        this(id, front, back, category, context, "", technicalNote);
    }

    AiWorkItem(long id, String front, String back, String category, String context, String contextTranslation, String technicalNote) {
        this.id = id;
        this.front = front == null ? "" : front;
        this.back = back == null ? "" : back;
        this.category = category == null ? "" : category;
        this.context = context == null ? "" : context;
        this.contextTranslation = contextTranslation == null ? "" : contextTranslation;
        this.technicalNote = technicalNote == null ? "" : technicalNote;
    }
}
