package com.linguabridge.memory;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public final class MemoryDb extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "word-memory.db";
    private static final int DATABASE_VERSION = 1;

    public MemoryDb(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cards (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "sync_key TEXT NOT NULL UNIQUE," +
                "external_id TEXT NOT NULL," +
                "type TEXT NOT NULL," +
                "front TEXT NOT NULL," +
                "back TEXT NOT NULL," +
                "front_language TEXT NOT NULL," +
                "back_language TEXT NOT NULL," +
                "phonetic TEXT NOT NULL DEFAULT ''," +
                "technical_notes TEXT NOT NULL DEFAULT '[]'," +
                "context TEXT NOT NULL DEFAULT ''," +
                "terms TEXT NOT NULL DEFAULT '[]'," +
                "created_at INTEGER NOT NULL," +
                "content_updated_at INTEGER NOT NULL," +
                "encounter_count INTEGER NOT NULL DEFAULT 1," +
                "state TEXT NOT NULL DEFAULT 'new'," +
                "due_at INTEGER NOT NULL," +
                "interval_days INTEGER NOT NULL DEFAULT 0," +
                "ease_factor REAL NOT NULL DEFAULT 2.5," +
                "repetitions INTEGER NOT NULL DEFAULT 0," +
                "lapses INTEGER NOT NULL DEFAULT 0," +
                "last_reviewed_at INTEGER NOT NULL DEFAULT 0," +
                "archived_at INTEGER NOT NULL DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX cards_due_idx ON cards(archived_at, due_at)");
        db.execSQL("CREATE TABLE review_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "card_id INTEGER NOT NULL," +
                "rating TEXT NOT NULL," +
                "reviewed_at INTEGER NOT NULL," +
                "next_due_at INTEGER NOT NULL" +
                ")");
        db.execSQL("CREATE INDEX review_log_date_idx ON review_log(reviewed_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported database upgrade " + oldVersion + " -> " + newVersion);
    }

    public synchronized ImportResult importPayload(JSONObject payload) throws Exception {
        JSONArray items = payload.optJSONArray("items");
        if (items == null) return new ImportResult(0, 0, 0);
        int accepted = 0;
        int updated = 0;
        int ignored = 0;
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int index = 0; index < Math.min(items.length(), 100); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null) {
                    ignored += 1;
                    continue;
                }
                String front = clean(item.optString("front"), 1000);
                String back = clean(item.optString("back"), 2000);
                String key = clean(item.optString("key"), 1000).toLowerCase(Locale.ROOT);
                if (key.isEmpty()) key = normalizeKey(front);
                if (front.isEmpty() || back.isEmpty() || key.isEmpty()) {
                    ignored += 1;
                    continue;
                }
                long incomingUpdatedAt = Math.max(0, item.optLong("updatedAt", now));
                Cursor cursor = db.query(
                        "cards",
                        new String[]{"id", "content_updated_at"},
                        "sync_key = ?",
                        new String[]{key},
                        null,
                        null,
                        null,
                        "1"
                );
                try {
                    if (cursor.moveToFirst()) {
                        long id = cursor.getLong(0);
                        long existingUpdatedAt = cursor.getLong(1);
                        if (incomingUpdatedAt >= existingUpdatedAt) {
                            ContentValues values = contentValues(item, key, front, back, incomingUpdatedAt, now);
                            db.update("cards", values, "id = ?", new String[]{String.valueOf(id)});
                            updated += 1;
                        } else {
                            ignored += 1;
                        }
                    } else {
                        ContentValues values = contentValues(item, key, front, back, incomingUpdatedAt, now);
                        values.put("state", "new");
                        values.put("due_at", now);
                        db.insertOrThrow("cards", null, values);
                        accepted += 1;
                    }
                } finally {
                    cursor.close();
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new ImportResult(accepted, updated, ignored);
    }

    private ContentValues contentValues(
            JSONObject item,
            String key,
            String front,
            String back,
            long incomingUpdatedAt,
            long now
    ) {
        ContentValues values = new ContentValues();
        values.put("sync_key", key);
        values.put("external_id", clean(item.optString("id"), 100));
        values.put("type", "sentence".equals(item.optString("type")) ? "sentence" : "word");
        values.put("front", front);
        values.put("back", back);
        values.put("front_language", cleanOr(item.optString("frontLanguage"), "en", 20));
        values.put("back_language", cleanOr(item.optString("backLanguage"), "zh-CN", 20));
        values.put("phonetic", clean(item.optString("phonetic"), 300));
        values.put("technical_notes", safeArray(item.optJSONArray("technicalNotes"), 20, 1000).toString());
        values.put("context", clean(item.optString("context"), 2000));
        values.put("terms", safeArray(item.optJSONArray("terms"), 20, 2000).toString());
        values.put("created_at", Math.max(0, item.optLong("createdAt", now)));
        values.put("content_updated_at", incomingUpdatedAt);
        values.put("encounter_count", Math.max(1, item.optInt("encounterCount", 1)));
        return values;
    }

    private JSONArray safeArray(JSONArray source, int maxItems, int maxLength) {
        JSONArray result = new JSONArray();
        if (source == null) return result;
        for (int index = 0; index < Math.min(source.length(), maxItems); index++) {
            Object value = source.opt(index);
            if (value instanceof String) result.put(clean((String) value, maxLength));
            else if (value instanceof JSONObject) result.put(value);
        }
        return result;
    }

    public synchronized Stats stats(long now) {
        SQLiteDatabase db = getReadableDatabase();
        int total = scalarInt(db, "SELECT COUNT(*) FROM cards WHERE archived_at = 0", null);
        int due = scalarInt(db, "SELECT COUNT(*) FROM cards WHERE archived_at = 0 AND due_at <= ?", new String[]{String.valueOf(now)});
        int fresh = scalarInt(db, "SELECT COUNT(*) FROM cards WHERE archived_at = 0 AND state = 'new'", null);
        int mastered = scalarInt(db, "SELECT COUNT(*) FROM cards WHERE archived_at = 0 AND state = 'mastered'", null);
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long startOfDay = calendar.getTimeInMillis();
        int today = scalarInt(db, "SELECT COUNT(*) FROM review_log WHERE reviewed_at >= ?", new String[]{String.valueOf(startOfDay)});
        return new Stats(total, due, fresh, mastered, today);
    }

    public synchronized MemoryCard nextDue(long now) {
        Cursor cursor = getReadableDatabase().query(
                "cards",
                null,
                "archived_at = 0 AND due_at <= ?",
                new String[]{String.valueOf(now)},
                null,
                null,
                "due_at ASC, repetitions ASC, created_at ASC",
                "1"
        );
        try {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    public synchronized List<MemoryCard> recent(int limit) {
        Cursor cursor = getReadableDatabase().query(
                "cards",
                null,
                "archived_at = 0",
                null,
                null,
                null,
                "content_updated_at DESC",
                String.valueOf(Math.max(1, Math.min(limit, 100)))
        );
        List<MemoryCard> cards = new ArrayList<>();
        try {
            while (cursor.moveToNext()) cards.add(fromCursor(cursor));
        } finally {
            cursor.close();
        }
        return cards;
    }

    public synchronized void review(long cardId, String rating, long now) {
        if (!("again".equals(rating) || "hard".equals(rating) || "good".equals(rating) || "easy".equals(rating))) {
            throw new IllegalArgumentException("Unknown review rating");
        }
        SQLiteDatabase db = getWritableDatabase();
        Cursor cursor = db.query(
                "cards",
                new String[]{"interval_days", "repetitions", "ease_factor", "lapses"},
                "id = ? AND archived_at = 0",
                new String[]{String.valueOf(cardId)},
                null,
                null,
                null,
                "1"
        );
        ReviewScheduler.Result result;
        try {
            if (!cursor.moveToFirst()) throw new IllegalArgumentException("Card not found");
            result = ReviewScheduler.schedule(
                    cursor.getInt(0), cursor.getInt(1), cursor.getDouble(2), cursor.getInt(3), rating, now
            );
        } finally {
            cursor.close();
        }
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("interval_days", result.intervalDays());
            values.put("repetitions", result.repetitions());
            values.put("ease_factor", result.easeFactor());
            values.put("lapses", result.lapses());
            values.put("due_at", result.dueAt());
            values.put("state", result.state());
            values.put("last_reviewed_at", now);
            db.update("cards", values, "id = ?", new String[]{String.valueOf(cardId)});

            ContentValues log = new ContentValues();
            log.put("card_id", cardId);
            log.put("rating", rating);
            log.put("reviewed_at", now);
            log.put("next_due_at", result.dueAt());
            db.insertOrThrow("review_log", null, log);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized long nextFutureDue(long now) {
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT MIN(due_at) FROM cards WHERE archived_at = 0 AND due_at > ?",
                new String[]{String.valueOf(now)}
        );
        try {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0;
        } finally {
            cursor.close();
        }
    }

    private MemoryCard fromCursor(Cursor cursor) {
        MemoryCard card = new MemoryCard();
        card.id = getLong(cursor, "id");
        card.syncKey = getString(cursor, "sync_key");
        card.type = getString(cursor, "type");
        card.front = getString(cursor, "front");
        card.back = getString(cursor, "back");
        card.frontLanguage = getString(cursor, "front_language");
        card.backLanguage = getString(cursor, "back_language");
        card.phonetic = getString(cursor, "phonetic");
        card.context = getString(cursor, "context");
        card.technicalNotes = jsonStrings(getString(cursor, "technical_notes"));
        card.createdAt = getLong(cursor, "created_at");
        card.updatedAt = getLong(cursor, "content_updated_at");
        card.dueAt = getLong(cursor, "due_at");
        card.intervalDays = getInt(cursor, "interval_days");
        card.easeFactor = getDouble(cursor, "ease_factor");
        card.repetitions = getInt(cursor, "repetitions");
        card.lapses = getInt(cursor, "lapses");
        card.state = getString(cursor, "state");
        return card;
    }

    private List<String> jsonStrings(String value) {
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(value);
            for (int index = 0; index < array.length(); index++) {
                String item = array.optString(index).trim();
                if (!item.isEmpty()) result.add(item);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private int scalarInt(SQLiteDatabase db, String sql, String[] arguments) {
        Cursor cursor = db.rawQuery(sql, arguments);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }

    private String normalizeKey(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\s.,!?;:，。！？；：]+|[\\s.,!?;:，。！？；：]+$", "")
                .trim();
    }

    private String clean(String value, int maxLength) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    private String cleanOr(String value, String fallback, int maxLength) {
        String cleaned = clean(value, maxLength);
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private String getString(Cursor cursor, String column) {
        return cursor.getString(cursor.getColumnIndexOrThrow(column));
    }

    private long getLong(Cursor cursor, String column) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(column));
    }

    private int getInt(Cursor cursor, String column) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(column));
    }

    private double getDouble(Cursor cursor, String column) {
        return cursor.getDouble(cursor.getColumnIndexOrThrow(column));
    }

    public static final class ImportResult {
        public final int accepted;
        public final int updated;
        public final int ignored;

        public ImportResult(int accepted, int updated, int ignored) {
            this.accepted = accepted;
            this.updated = updated;
            this.ignored = ignored;
        }
    }

    public static final class Stats {
        public final int total;
        public final int due;
        public final int fresh;
        public final int mastered;
        public final int todayReviews;

        public Stats(int total, int due, int fresh, int mastered, int todayReviews) {
            this.total = total;
            this.due = due;
            this.fresh = fresh;
            this.mastered = mastered;
            this.todayReviews = todayReviews;
        }
    }
}
