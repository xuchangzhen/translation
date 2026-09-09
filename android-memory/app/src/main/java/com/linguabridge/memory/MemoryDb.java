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
    private static final int DATABASE_VERSION = 3;

    public MemoryDb(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createWordbooks(db);
        createCards(db);
        createWordbookSyncColumns(db);
        db.execSQL("CREATE TABLE review_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "card_id INTEGER NOT NULL," +
                "rating TEXT NOT NULL," +
                "reviewed_at INTEGER NOT NULL," +
                "next_due_at INTEGER NOT NULL" +
                ")");
        db.execSQL("CREATE INDEX review_log_date_idx ON review_log(reviewed_at)");
    }

    private void createWordbooks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE wordbooks (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, source TEXT NOT NULL)");
        db.execSQL("INSERT INTO wordbooks(id, name, source) VALUES (1, '桌面翻译', 'desktop')");
    }

    private void createWordbookSyncColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE wordbooks ADD COLUMN cloud_id TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE wordbooks ADD COLUMN cloud_space TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE wordbooks ADD COLUMN cloud_version INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE wordbooks ADD COLUMN local_revision INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE wordbooks ADD COLUMN synced_revision INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE UNIQUE INDEX wordbooks_cloud_idx ON wordbooks(cloud_space, cloud_id) WHERE cloud_id <> ''");
    }

    private void createCards(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cards (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "sync_key TEXT NOT NULL," +
                "wordbook_id INTEGER NOT NULL DEFAULT 1 REFERENCES wordbooks(id)," +
                "category TEXT NOT NULL DEFAULT ''," +
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
                "archived_at INTEGER NOT NULL DEFAULT 0," +
                "UNIQUE(wordbook_id, sync_key)" +
                ")");
        db.execSQL("CREATE INDEX cards_due_idx ON cards(archived_at, due_at)");
        db.execSQL("CREATE INDEX cards_wordbook_due_idx ON cards(wordbook_id, archived_at, due_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // SQLiteOpenHelper wraps this migration in a transaction. Preserve IDs
            // and every v1 column, including review state; review_log stays in place.
            createWordbooks(db);
            db.execSQL("ALTER TABLE cards RENAME TO cards_v1");
            db.execSQL("DROP INDEX cards_due_idx");
            createCards(db);
            try (Cursor old = db.rawQuery("SELECT * FROM cards_v1 LIMIT 0", null)) {
                String columns = String.join(",", old.getColumnNames());
                db.execSQL("INSERT INTO cards (" + columns + ") SELECT " + columns + " FROM cards_v1");
            }
            db.execSQL("DROP TABLE cards_v1");
        }
        if (oldVersion < 3) createWordbookSyncColumns(db);
    }

    public synchronized List<Wordbook> wordbooks(long now) {
        List<Wordbook> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT w.id, w.name, COUNT(c.id), COALESCE(SUM(c.due_at <= ?),0), COALESCE(SUM(c.state = 'new'),0) " +
                "FROM wordbooks w LEFT JOIN cards c ON c.wordbook_id = w.id AND c.archived_at = 0 " +
                "GROUP BY w.id ORDER BY w.id", new String[]{String.valueOf(now)})) {
            while (cursor.moveToNext()) result.add(new Wordbook(cursor.getLong(0), cursor.getString(1), cursor.getInt(2), cursor.getInt(3), cursor.getInt(4)));
        }
        return result;
    }

    public synchronized WordbookImportResult importWordbook(ImportPreview preview, String requestedName) {
        String name = requestedName.trim();
        if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException("词库名称需要 1–100 个字符。");
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis(), wordbookId;
        int added = 0, updated = 0;
        db.beginTransaction();
        try {
            try (Cursor cursor = db.rawQuery("SELECT id, source FROM wordbooks WHERE name = ?", new String[]{name})) {
                if (cursor.moveToFirst()) {
                    if (!"local".equals(cursor.getString(1))) throw new IllegalArgumentException("“桌面翻译”用于自动同步，请为本地词库使用其他名称。");
                    wordbookId = cursor.getLong(0);
                } else {
                    ContentValues book = new ContentValues(); book.put("name", name); book.put("source", "local");
                    wordbookId = db.insertOrThrow("wordbooks", null, book);
                }
            }
            for (WordbookImporter.Item item : preview.items) {
                String key = WordbookImporter.normalizeFront(item.front);
                ContentValues values = new ContentValues();
                values.put("front", item.front); values.put("back", item.back); values.put("phonetic", item.phonetic);
                values.put("technical_notes", item.definition.isEmpty() ? "[]" : new JSONArray().put(item.definition).toString());
                values.put("category", item.category); values.put("context", item.context); values.put("content_updated_at", now);
                int changed = db.update("cards", values, "wordbook_id = ? AND sync_key = ?", new String[]{String.valueOf(wordbookId), key});
                if (changed > 0) { updated++; continue; }
                values.put("wordbook_id", wordbookId); values.put("sync_key", key); values.put("external_id", "");
                values.put("type", "word"); values.put("front_language", "en"); values.put("back_language", "zh-CN");
                values.put("created_at", now); values.put("state", "new"); values.put("due_at", now);
                db.insertOrThrow("cards", null, values); added++;
            }
            db.execSQL("UPDATE wordbooks SET local_revision = local_revision + 1 WHERE id = ?", new Object[]{wordbookId});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return new WordbookImportResult(wordbookId, added, updated, preview.duplicates, preview.invalid);
    }

    public static final class CloudSnapshot {
        public final long localId, version, revision;
        public final String id, name, space;
        public final JSONArray items;
        CloudSnapshot(long localId, String id, String name, String space, long version, long revision, JSONArray items) {
            this.localId = localId; this.id = id; this.name = name; this.space = space;
            this.version = version; this.revision = revision; this.items = items;
        }
    }

    public synchronized CloudSnapshot prepareWordbookSync(long id, String space) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try (Cursor book = database.rawQuery("SELECT name,source,cloud_id,cloud_space,cloud_version,local_revision FROM wordbooks WHERE id = ?", new String[]{String.valueOf(id)})) {
            if (!book.moveToFirst() || !"local".equals(book.getString(1))) throw new IllegalArgumentException("请选择自定义词库进行同步。");
            String cloudId = book.getString(2);
            if (!book.getString(3).isEmpty() && !space.equals(book.getString(3))) throw new IllegalArgumentException("此词库属于另一个同步空间，请先连接原同步空间。");
            if (cloudId.isEmpty()) {
                cloudId = java.util.UUID.randomUUID().toString();
                ContentValues values = new ContentValues(); values.put("cloud_id", cloudId); values.put("cloud_space", space);
                database.update("wordbooks", values, "id = ?", new String[]{String.valueOf(id)});
            }
            JSONArray items = new JSONArray();
            try (Cursor cards = database.rawQuery("SELECT front,back,phonetic,technical_notes,category,context FROM cards WHERE wordbook_id = ? AND archived_at = 0 ORDER BY id", new String[]{String.valueOf(id)})) {
                while (cards.moveToNext()) {
                    JSONArray notes = new JSONArray(cards.getString(3));
                    items.put(new JSONObject().put("front", cards.getString(0)).put("back", cards.getString(1))
                            .put("phonetic", cards.getString(2)).put("definition", notes.optString(0, ""))
                            .put("category", cards.getString(4)).put("context", cards.getString(5)));
                }
            }
            CloudSnapshot snapshot = new CloudSnapshot(id, cloudId, book.getString(0), space, book.getLong(4), book.getLong(5), items);
            database.setTransactionSuccessful();
            return snapshot;
        } finally { database.endTransaction(); }
    }

    public synchronized long cloudWordbookVersion(String cloudId, String space) {
        try (Cursor book = getReadableDatabase().rawQuery("SELECT cloud_version FROM wordbooks WHERE cloud_id = ? AND cloud_space = ?", new String[]{cloudId, space})) {
            return book.moveToFirst() ? book.getLong(0) : 0;
        }
    }

    public synchronized void markWordbookPublished(CloudSnapshot snapshot, long version) {
        ContentValues values = new ContentValues(); values.put("cloud_version", version); values.put("synced_revision", snapshot.revision);
        getWritableDatabase().update("wordbooks", values, "id = ? AND cloud_id = ? AND cloud_space = ? AND cloud_version <= ?",
                new String[]{String.valueOf(snapshot.localId), snapshot.id, snapshot.space, String.valueOf(version)});
    }

    public synchronized WordbookImportResult applyCloudWordbook(ImportPreview preview, String cloudId, String space, long version) {
        java.util.UUID.fromString(cloudId);
        if (version < 1) throw new IllegalArgumentException("云端词库版本无效。");
        SQLiteDatabase database = getWritableDatabase();
        String name = preview.name.trim();
        database.beginTransaction();
        try {
            if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException("云端词库名称无效。");
            String conflictCopy = "";
            boolean existing = false;
            try (Cursor book = database.rawQuery("SELECT id,name,cloud_version,local_revision,synced_revision FROM wordbooks WHERE cloud_id = ? AND cloud_space = ?", new String[]{cloudId, space})) {
                if (book.moveToFirst()) {
                    long id = book.getLong(0);
                    if (version < book.getLong(2)) throw new IllegalArgumentException("云端返回了过期词库版本，请刷新后重试。");
                    if (version == book.getLong(2)) {
                        database.setTransactionSuccessful();
                        return new WordbookImportResult(id, 0, 0, preview.items.size(), 0);
                    }
                    if (book.getLong(3) != book.getLong(4)) {
                        conflictCopy = availableWordbookName(database, book.getString(1) + "（本地副本）");
                        ContentValues copy = new ContentValues(); copy.put("name", conflictCopy); copy.put("cloud_id", "");
                        copy.put("cloud_space", ""); copy.put("cloud_version", 0); copy.put("synced_revision", 0);
                        database.update("wordbooks", copy, "id = ?", new String[]{String.valueOf(id)});
                    } else { name = book.getString(1); existing = true; }
                }
            }
            if (!existing) name = availableWordbookName(database, name);
            WordbookImportResult result = importWordbook(preview, name);
            ContentValues metadata = new ContentValues(); metadata.put("cloud_id", cloudId); metadata.put("cloud_space", space); metadata.put("cloud_version", version);
            database.update("wordbooks", metadata, "id = ?", new String[]{String.valueOf(result.wordbookId)});
            database.execSQL("UPDATE wordbooks SET synced_revision = local_revision WHERE id = ?", new Object[]{result.wordbookId});
            result.conflictCopy = conflictCopy;
            database.setTransactionSuccessful();
            return result;
        } finally { database.endTransaction(); }
    }

    private String availableWordbookName(SQLiteDatabase database, String requested) {
        String base = requested.substring(0, Math.min(80, requested.length()));
        String candidate = base;
        int suffix = 2;
        while (scalarInt(database, "SELECT COUNT(*) FROM wordbooks WHERE name = ?", new String[]{candidate}) > 0) candidate = base + " (" + suffix++ + ")";
        return candidate;
    }

    public static final class WordbookImportResult {
        public final long wordbookId;
        public final int added, updated, ignored, invalid;
        public String conflictCopy = "";
        WordbookImportResult(long wordbookId, int added, int updated, int ignored, int invalid) {
            this.wordbookId = wordbookId; this.added = added; this.updated = updated; this.ignored = ignored; this.invalid = invalid;
        }
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
                        "wordbook_id = 1 AND sync_key = ?",
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

    public synchronized MemoryCard nextDue(long now) { return nextDue(now, 0); }

    public synchronized MemoryCard nextDue(long now, long wordbookId) {
        Cursor cursor = getReadableDatabase().query(
                "cards",
                null,
                "archived_at = 0 AND due_at <= ?" + (wordbookId > 0 ? " AND wordbook_id = " + wordbookId : ""),
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

    public synchronized List<MemoryCard> recent(int limit) { return recent(limit, 0, 0); }

    public synchronized List<MemoryCard> recent(int limit, long wordbookId, int offset) {
        Cursor cursor = getReadableDatabase().query(
                "cards",
                null,
                "archived_at = 0" + (wordbookId > 0 ? " AND wordbook_id = " + wordbookId : ""),
                null,
                null,
                null,
                "content_updated_at DESC, id DESC",
                Math.max(0, offset) + "," + Math.max(1, Math.min(limit, 100))
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
        card.wordbookId = getLong(cursor, "wordbook_id");
        card.category = getString(cursor, "category");
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
