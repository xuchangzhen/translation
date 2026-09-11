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
    /** v4 adds wordbook management/AI state; v5 makes same-session reviews replaceable; v6 adds context translations. */
    private static final int DATABASE_VERSION = 6;
    private static final String PHONETIC_BACKFILL_KEY = "phonetic_backfill_v1";

    public MemoryDb(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createWordbooks(db);
        createCards(db);
        createWordbookSyncColumns(db);
        createWordbookFeatureColumns(db);
        createAuxiliaryTables(db);
        db.execSQL("CREATE TABLE review_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "card_id INTEGER NOT NULL," +
                "rating TEXT NOT NULL," +
                "reviewed_at INTEGER NOT NULL," +
                "next_due_at INTEGER NOT NULL," +
                "session_id TEXT NOT NULL DEFAULT ''" +
                ")");
        db.execSQL("CREATE INDEX review_log_date_idx ON review_log(reviewed_at)");
        db.execSQL("CREATE UNIQUE INDEX review_log_session_card_idx ON review_log(session_id, card_id) WHERE session_id <> ''");
    }

    private void createWordbooks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE wordbooks (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, source TEXT NOT NULL)");
        db.execSQL("INSERT INTO wordbooks(id, name, source) VALUES (1, '桌面翻译', 'desktop')");
    }

    private void createWordbookSyncColumns(SQLiteDatabase db) {
        addColumnIfMissing(db, "wordbooks", "cloud_id TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(db, "wordbooks", "cloud_space TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(db, "wordbooks", "cloud_version INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "wordbooks", "local_revision INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "wordbooks", "synced_revision INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE UNIQUE INDEX wordbooks_cloud_idx ON wordbooks(cloud_space, cloud_id) WHERE cloud_id <> ''");
    }

    private void createWordbookFeatureColumns(SQLiteDatabase db) {
        addColumnIfMissing(db, "wordbooks", "content_mode TEXT NOT NULL DEFAULT 'general'");
        addColumnIfMissing(db, "wordbooks", "pinned_at INTEGER NOT NULL DEFAULT 0");
    }

    private void createAuxiliaryTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS app_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS ignored_cloud_wordbooks (cloud_id TEXT NOT NULL, cloud_space TEXT NOT NULL, deleted_at INTEGER NOT NULL, PRIMARY KEY(cloud_id, cloud_space))");
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
                "context_translation TEXT NOT NULL DEFAULT ''," +
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
                "ai_status TEXT NOT NULL DEFAULT 'pending'," +
                "ai_error TEXT NOT NULL DEFAULT ''," +
                "ai_updated_at INTEGER NOT NULL DEFAULT 0," +
                "ai_mode TEXT NOT NULL DEFAULT ''," +
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
        if (oldVersion < 4) {
            createWordbookFeatureColumns(db);
            addColumnIfMissing(db, "cards", "ai_status TEXT NOT NULL DEFAULT 'pending'");
            addColumnIfMissing(db, "cards", "ai_error TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "cards", "ai_updated_at INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, "cards", "ai_mode TEXT NOT NULL DEFAULT ''");
            // Existing imported examples are already usable learning content; never re-request
            // them merely because this status column was added in an upgrade.
            db.execSQL("UPDATE cards SET ai_status = 'complete' WHERE context <> '' AND ai_status = 'pending'");
            createAuxiliaryTables(db);
        }
        if (oldVersion < 5) {
            addColumnIfMissing(db, "review_log", "session_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS review_log_session_card_idx ON review_log(session_id, card_id) WHERE session_id <> ''");
        }
        if (oldVersion < 6) {
            addColumnIfMissing(db, "cards", "context_translation TEXT NOT NULL DEFAULT ''");
            // AI-created cards need one more pass to receive the newly required translation;
            // imported/example content with ai_updated_at = 0 remains untouched.
            db.execSQL("UPDATE cards SET ai_status = 'pending', ai_error = '' WHERE ai_status = 'complete' AND ai_updated_at > 0 AND context_translation = ''");
        }
    }

    private void addColumnIfMissing(SQLiteDatabase db, String table, String definition) {
        String name = definition.trim().split("\\s+", 2)[0];
        try (Cursor columns = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (columns.moveToNext()) if (name.equals(columns.getString(1))) return;
        }
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + definition);
    }

    public synchronized List<Wordbook> wordbooks(long now) {
        List<Wordbook> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT w.id, w.name, COUNT(c.id), COALESCE(SUM(c.due_at <= ?),0), COALESCE(SUM(c.state = 'new'),0), w.pinned_at, w.content_mode " +
                "FROM wordbooks w LEFT JOIN cards c ON c.wordbook_id = w.id AND c.archived_at = 0 " +
                "GROUP BY w.id ORDER BY CASE WHEN w.pinned_at > 0 THEN 0 ELSE 1 END, w.pinned_at DESC, w.id ASC", new String[]{String.valueOf(now)})) {
            while (cursor.moveToNext()) result.add(new Wordbook(cursor.getLong(0), cursor.getString(1), cursor.getInt(2), cursor.getInt(3), cursor.getInt(4), cursor.getLong(5), cursor.getString(6)));
        }
        return result;
    }

    public synchronized WordbookImportResult importWordbook(ImportPreview preview, String requestedName) {
        return importWordbook(preview, requestedName, "general");
    }

    /** Import stays entirely offline: content mode is metadata for a later user-started AI job. */
    public synchronized WordbookImportResult importWordbook(ImportPreview preview, String requestedName, String requestedContentMode) {
        String name = requestedName.trim();
        if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException("词库名称需要 1–100 个字符。");
        String contentMode = normalizeContentMode(requestedContentMode);
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis(), wordbookId;
        int added = 0, updated = 0;
        db.beginTransaction();
        try {
            try (Cursor cursor = db.rawQuery("SELECT id, source FROM wordbooks WHERE name = ?", new String[]{name})) {
                if (cursor.moveToFirst()) {
                    if (!"local".equals(cursor.getString(1))) throw new IllegalArgumentException("“桌面翻译”用于自动同步，请为本地词库使用其他名称。");
                    wordbookId = cursor.getLong(0);
                    ContentValues mode = new ContentValues(); mode.put("content_mode", contentMode);
                    db.update("wordbooks", mode, "id = ?", new String[]{String.valueOf(wordbookId)});
                } else {
                    ContentValues book = new ContentValues(); book.put("name", name); book.put("source", "local"); book.put("content_mode", contentMode);
                    wordbookId = db.insertOrThrow("wordbooks", null, book);
                }
            }
            boolean hasMissingPhonetic = false;
            for (WordbookImporter.Item item : preview.items) {
                String key = WordbookImporter.normalizeFront(item.front);
                ContentValues values = new ContentValues();
                values.put("front", item.front); values.put("back", item.back); values.put("phonetic", item.phonetic);
                values.put("technical_notes", item.definition.isEmpty() ? "[]" : new JSONArray().put(item.definition).toString());
                values.put("category", item.category); values.put("context", item.context); values.put("content_updated_at", now);
                int changed = db.update("cards", values, "wordbook_id = ? AND sync_key = ?", new String[]{String.valueOf(wordbookId), key});
                if (changed > 0) { updated++; if (item.phonetic.isEmpty()) hasMissingPhonetic = true; continue; }
                values.put("wordbook_id", wordbookId); values.put("sync_key", key); values.put("external_id", "");
                values.put("type", "word"); values.put("front_language", "en"); values.put("back_language", "zh-CN");
                values.put("created_at", now); values.put("state", "new"); values.put("due_at", now);
                values.put("ai_status", aiContentIsComplete(contentMode, item.context, item.definition) ? "complete" : "pending");
                db.insertOrThrow("cards", null, values); added++;
                if (item.phonetic.isEmpty()) hasMissingPhonetic = true;
            }
            if (hasMissingPhonetic) clearMetadata(db, PHONETIC_BACKFILL_KEY);
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
            try (Cursor cards = database.rawQuery("SELECT front,back,phonetic,technical_notes,category,context,context_translation FROM cards WHERE wordbook_id = ? AND archived_at = 0 ORDER BY id", new String[]{String.valueOf(id)})) {
                while (cards.moveToNext()) {
                    JSONArray notes = new JSONArray(cards.getString(3));
                    items.put(new JSONObject().put("front", cards.getString(0)).put("back", cards.getString(1))
                            .put("phonetic", cards.getString(2)).put("definition", notes.optString(0, ""))
                            .put("category", cards.getString(4)).put("context", cards.getString(5))
                            .put("contextTranslation", cards.getString(6)));
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
            WordbookImportResult result = importWordbook(preview, name, "general");
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

    private String normalizeContentMode(String value) {
        return "technical".equals(value) || "auto".equals(value) ? value : "general";
    }

    private boolean aiContentIsComplete(String mode, String context, String definition) {
        if (context == null || context.trim().isEmpty()) return false;
        return !"technical".equals(mode) || (definition != null && !definition.trim().isEmpty());
    }

    /** Pinning affects only display order; it never changes card order or review scheduling. */
    public synchronized boolean setWordbookPinned(long wordbookId, boolean pinned) {
        if (wordbookId <= 1) return false;
        ContentValues values = new ContentValues(); values.put("pinned_at", pinned ? System.currentTimeMillis() : 0);
        return getWritableDatabase().update("wordbooks", values, "id = ? AND source = 'local'", new String[]{String.valueOf(wordbookId)}) == 1;
    }

    /** Deletes only a custom local wordbook and leaves a local cloud tombstone when needed. */
    public synchronized void deleteWordbook(long wordbookId) {
        if (wordbookId <= 1) throw new IllegalArgumentException("桌面翻译词库不能删除。");
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try (Cursor book = database.rawQuery("SELECT source,cloud_id,cloud_space FROM wordbooks WHERE id = ?", new String[]{String.valueOf(wordbookId)})) {
            if (!book.moveToFirst() || !"local".equals(book.getString(0))) throw new IllegalArgumentException("只能删除自定义词库。");
            String cloudId = book.getString(1);
            String cloudSpace = book.getString(2);
            if (!cloudId.isEmpty() && !cloudSpace.isEmpty()) {
                ContentValues tombstone = new ContentValues(); tombstone.put("cloud_id", cloudId); tombstone.put("cloud_space", cloudSpace); tombstone.put("deleted_at", System.currentTimeMillis());
                database.insertWithOnConflict("ignored_cloud_wordbooks", null, tombstone, SQLiteDatabase.CONFLICT_REPLACE);
            }
            database.execSQL("DELETE FROM review_log WHERE card_id IN (SELECT id FROM cards WHERE wordbook_id = ?)", new Object[]{wordbookId});
            database.delete("cards", "wordbook_id = ?", new String[]{String.valueOf(wordbookId)});
            database.delete("wordbooks", "id = ?", new String[]{String.valueOf(wordbookId)});
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    public synchronized boolean isCloudWordbookIgnored(String cloudId, String cloudSpace) {
        if (cloudId == null || cloudSpace == null || cloudId.isEmpty() || cloudSpace.isEmpty()) return false;
        return scalarInt(getReadableDatabase(), "SELECT COUNT(*) FROM ignored_cloud_wordbooks WHERE cloud_id = ? AND cloud_space = ?", new String[]{cloudId, cloudSpace}) > 0;
    }

    public synchronized AiEnrichmentStats aiStats(long wordbookId) {
        if (wordbookId <= 0) return new AiEnrichmentStats(0, 0, 0, 0, 0);
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(ai_status = 'complete'),0), COALESCE(SUM(ai_status = 'pending'),0), COALESCE(SUM(ai_status = 'error'),0), COALESCE(SUM(ai_status = 'processing'),0) FROM cards WHERE wordbook_id = ? AND archived_at = 0",
                new String[]{String.valueOf(wordbookId)})) {
            if (!cursor.moveToFirst()) return new AiEnrichmentStats(0, 0, 0, 0, 0);
            return new AiEnrichmentStats(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3), cursor.getInt(4));
        }
    }

    /** Claims a stable, single-concurrency batch. Pending cards are exhausted before failed retries. */
    public synchronized List<AiWorkItem> claimAiBatch(long wordbookId, int limit) {
        int capped = Math.max(1, Math.min(20, limit));
        SQLiteDatabase database = getWritableDatabase();
        long now = System.currentTimeMillis();
        database.beginTransaction();
        try {
            database.execSQL("UPDATE cards SET ai_status = 'error', ai_error = '上次补全未完成，可手动重试', ai_updated_at = ? WHERE wordbook_id = ? AND ai_status = 'processing' AND ai_updated_at < ?",
                    new Object[]{now, wordbookId, now - 30L * 60L * 1000L});
            List<AiWorkItem> result = queryAiWork(database, wordbookId, "pending", capped);
            if (result.isEmpty()) result = queryAiWork(database, wordbookId, "error", capped);
            if (!result.isEmpty()) {
                StringBuilder ids = new StringBuilder();
                for (int index = 0; index < result.size(); index++) {
                    if (index > 0) ids.append(',');
                    ids.append(result.get(index).id);
                }
                ContentValues claimed = new ContentValues(); claimed.put("ai_status", "processing"); claimed.put("ai_error", ""); claimed.put("ai_updated_at", now);
                database.update("cards", claimed, "id IN (" + ids + ") AND wordbook_id = ?", new String[]{String.valueOf(wordbookId)});
            }
            database.setTransactionSuccessful();
            return result;
        } finally { database.endTransaction(); }
    }

    private List<AiWorkItem> queryAiWork(SQLiteDatabase database, long wordbookId, String status, int limit) {
        List<AiWorkItem> result = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                // Keep AI work in the same order as the learning queue. Otherwise a completed
                // batch can be reported as successful while the first cards shown in study still
                // belong to a different part of the wordbook.
                "SELECT id,front,back,category,context,context_translation,technical_notes FROM cards WHERE wordbook_id = ? AND archived_at = 0 AND ai_status = ? ORDER BY due_at ASC, repetitions ASC, created_at ASC, id ASC LIMIT " + limit,
                new String[]{String.valueOf(wordbookId), status})) {
            while (cursor.moveToNext()) {
                result.add(new AiWorkItem(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), firstJsonString(cursor.getString(6))));
            }
        }
        return result;
    }

    /** Applies every valid item independently, so malformed output cannot poison other cards. */
    public synchronized int applyAiBatch(long wordbookId, List<AiWorkItem> claimed, List<AiEnrichmentResult> results, String wordbookMode) {
        SQLiteDatabase database = getWritableDatabase();
        java.util.Map<Long, AiEnrichmentResult> byId = new java.util.HashMap<>();
        if (results != null) for (AiEnrichmentResult result : results) if (result != null && !byId.containsKey(result.id)) byId.put(result.id, result);
        int completed = 0;
        long now = System.currentTimeMillis();
        database.beginTransaction();
        try {
            for (AiWorkItem item : claimed) {
                AiEnrichmentResult result = byId.get(item.id);
                String effectiveMode = "auto".equals(wordbookMode) && result != null ? normalizeContentMode(result.resolvedMode) : normalizeContentMode(wordbookMode);
                if ("auto".equals(effectiveMode)) effectiveMode = "general";
                String context = item.context.isEmpty() && result != null ? bounded(result.context, 2000) : item.context;
                String contextTranslation = item.contextTranslation.isEmpty() && result != null ? bounded(result.contextTranslation, 500) : item.contextTranslation;
                String note = item.technicalNote.isEmpty() && result != null ? bounded(result.technicalNote, 1000) : item.technicalNote;
                boolean valid = result != null && !context.isEmpty()
                        && (!result.translationRequired || !contextTranslation.isEmpty())
                        && (!"technical".equals(effectiveMode) || !note.isEmpty());
                if (!valid) {
                    ContentValues error = new ContentValues(); error.put("ai_status", "error"); error.put("ai_error", "AI 返回内容不完整，可手动重试"); error.put("ai_updated_at", now);
                    database.update("cards", error, "id = ? AND wordbook_id = ? AND ai_status = 'processing'", new String[]{String.valueOf(item.id), String.valueOf(wordbookId)});
                    continue;
                }
                ContentValues values = new ContentValues();
                if (item.context.isEmpty()) values.put("context", context);
                if (item.contextTranslation.isEmpty() && !contextTranslation.isEmpty()) values.put("context_translation", contextTranslation);
                if ("technical".equals(effectiveMode) && item.technicalNote.isEmpty() && !note.isEmpty()) values.put("technical_notes", new JSONArray().put(note).toString());
                // AI content is user-visible card content; refresh the library's recent-content
                // ordering when a batch actually writes it.
                values.put("content_updated_at", now);
                values.put("ai_status", "complete"); values.put("ai_error", ""); values.put("ai_updated_at", now); values.put("ai_mode", effectiveMode);
                if (database.update("cards", values, "id = ? AND wordbook_id = ? AND ai_status = 'processing'", new String[]{String.valueOf(item.id), String.valueOf(wordbookId)}) == 1) completed++;
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        return completed;
    }

    public synchronized void markAiBatchError(long wordbookId, List<AiWorkItem> claimed, String message) {
        if (claimed == null || claimed.isEmpty()) return;
        String safeMessage = bounded(message, 240);
        if (safeMessage.isEmpty()) safeMessage = "AI 服务暂时不可用，可手动重试";
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            ContentValues values = new ContentValues(); values.put("ai_status", "error"); values.put("ai_error", safeMessage); values.put("ai_updated_at", System.currentTimeMillis());
            for (AiWorkItem item : claimed) database.update("cards", values, "id = ? AND wordbook_id = ? AND ai_status = 'processing'", new String[]{String.valueOf(item.id), String.valueOf(wordbookId)});
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    private String firstJsonString(String value) {
        try { return new JSONArray(value).optString(0, "").trim(); }
        catch (Exception ignored) { return ""; }
    }

    private String bounded(String value, int maximum) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.length() > maximum ? "" : cleaned;
    }

    /** Backfills old, plain-English word cards once per dictionary version, off the UI thread. */
    public synchronized PhoneticBackfillResult backfillMissingPhonetics(PhoneticDictionary dictionary) {
        if (dictionary == null || metadata(getReadableDatabase(), PHONETIC_BACKFILL_KEY) != null) return new PhoneticBackfillResult(0, 0, dictionary != null && dictionary.isUnavailable(), true);
        dictionary.find("linguabridge-backfill-probe");
        if (dictionary.isUnavailable()) return new PhoneticBackfillResult(0, 0, true, false);
        SQLiteDatabase database = getWritableDatabase();
        int scanned = 0, filled = 0;
        try {
            long afterId = 0;
            while (true) {
                List<long[]> candidates = new ArrayList<>();
                List<String> fronts = new ArrayList<>();
                try (Cursor cursor = database.rawQuery("SELECT id,front FROM cards WHERE id > ? AND type = 'word' AND phonetic = '' ORDER BY id ASC LIMIT 200", new String[]{String.valueOf(afterId)})) {
                    while (cursor.moveToNext()) { candidates.add(new long[]{cursor.getLong(0)}); fronts.add(cursor.getString(1)); afterId = cursor.getLong(0); }
                }
                if (candidates.isEmpty()) break;
                database.beginTransaction();
                try {
                    for (int index = 0; index < candidates.size(); index++) {
                        String front = fronts.get(index);
                        if (!isPhoneticCandidate(front)) continue;
                        scanned++;
                        String phonetic = dictionary.find(front);
                        if (dictionary.isUnavailable()) throw new IllegalStateException("本地音标词典不可用");
                        if (phonetic != null && !phonetic.trim().isEmpty()) {
                            ContentValues values = new ContentValues(); values.put("phonetic", phonetic.trim());
                            database.update("cards", values, "id = ? AND phonetic = ''", new String[]{String.valueOf(candidates.get(index)[0])});
                            filled++;
                        }
                    }
                    database.setTransactionSuccessful();
                } finally { database.endTransaction(); }
            }
            putMetadata(database, PHONETIC_BACKFILL_KEY, "done");
            return new PhoneticBackfillResult(scanned, filled, false, false);
        } catch (Exception ignored) {
            return new PhoneticBackfillResult(scanned, filled, dictionary.isUnavailable(), false);
        }
    }

    private boolean isPhoneticCandidate(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z'\\-]{0,79}");
    }

    private String metadata(SQLiteDatabase database, String key) {
        try (Cursor cursor = database.rawQuery("SELECT value FROM app_metadata WHERE key = ?", new String[]{key})) { return cursor.moveToFirst() ? cursor.getString(0) : null; }
    }

    private void putMetadata(SQLiteDatabase database, String key, String value) {
        ContentValues item = new ContentValues(); item.put("key", key); item.put("value", value);
        database.insertWithOnConflict("app_metadata", null, item, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void clearMetadata(SQLiteDatabase database, String key) { database.delete("app_metadata", "key = ?", new String[]{key}); }

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
        values.put("context_translation", clean(item.optString("contextTranslation", item.optString("contextZh")), 500));
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

    public synchronized int dueCount(long now, long wordbookId) {
        String scope = wordbookId > 0 ? " AND wordbook_id = ?" : "";
        String[] arguments = wordbookId > 0
                ? new String[]{String.valueOf(now), String.valueOf(wordbookId)}
                : new String[]{String.valueOf(now)};
        return scalarInt(getReadableDatabase(), "SELECT COUNT(*) FROM cards WHERE archived_at = 0 AND due_at <= ?" + scope, arguments);
    }

    public synchronized MemoryCard nextDue(long now, long wordbookId) {
        Cursor cursor = getReadableDatabase().query(
                "cards",
                null,
                "archived_at = 0 AND due_at <= ?" + (wordbookId > 0 ? " AND wordbook_id = " + wordbookId : ""),
                new String[]{String.valueOf(now)},
                null,
                null,
                "CASE WHEN ai_status = 'complete' THEN 0 ELSE 1 END, due_at ASC, repetitions ASC, created_at ASC, id ASC",
                "1"
        );
        try {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    /** Returns the next due card that has not already been shown in this review session. */
    public synchronized MemoryCard nextDueExcluding(long now, long wordbookId, List<Long> excludedIds) {
        List<String> args = new ArrayList<>();
        StringBuilder selection = new StringBuilder("archived_at = 0 AND due_at <= ?");
        args.add(String.valueOf(now));
        appendWordbookAndExclusions(selection, args, wordbookId, excludedIds);
        return firstMatchingCard(selection.toString(), args, "CASE WHEN ai_status = 'complete' THEN 0 ELSE 1 END, due_at ASC, repetitions ASC, created_at ASC, id ASC");
    }

    /**
     * Returns an already learned card that has not been touched in the current
     * self-study session. This lets learners keep practicing after all due cards
     * are complete without changing the scheduling rules for the rest of the day.
     */
    public synchronized MemoryCard nextPractice(long sessionStartedAt, long wordbookId) {
        Cursor cursor = getReadableDatabase().query(
                "cards",
                null,
                "archived_at = 0 AND last_reviewed_at <= ?" + (wordbookId > 0 ? " AND wordbook_id = " + wordbookId : ""),
                new String[]{String.valueOf(sessionStartedAt)},
                null,
                null,
                "CASE WHEN ai_status = 'complete' THEN 0 ELSE 1 END, last_reviewed_at ASC, due_at ASC, repetitions ASC, created_at ASC, id ASC",
                "1"
        );
        try {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    public synchronized int practiceCount(long sessionStartedAt, long wordbookId) {
        String scope = wordbookId > 0 ? " AND wordbook_id = ?" : "";
        String[] arguments = wordbookId > 0
                ? new String[]{String.valueOf(sessionStartedAt), String.valueOf(wordbookId)}
                : new String[]{String.valueOf(sessionStartedAt)};
        return scalarInt(getReadableDatabase(), "SELECT COUNT(*) FROM cards WHERE archived_at = 0 AND last_reviewed_at <= ?" + scope, arguments);
    }

    /** Returns the next practice card that has not already been shown in this self-study session. */
    public synchronized MemoryCard nextPracticeExcluding(long sessionStartedAt, long wordbookId, List<Long> excludedIds) {
        List<String> args = new ArrayList<>();
        StringBuilder selection = new StringBuilder("archived_at = 0 AND last_reviewed_at <= ?");
        args.add(String.valueOf(sessionStartedAt));
        appendWordbookAndExclusions(selection, args, wordbookId, excludedIds);
        return firstMatchingCard(selection.toString(), args, "CASE WHEN ai_status = 'complete' THEN 0 ELSE 1 END, last_reviewed_at ASC, due_at ASC, repetitions ASC, created_at ASC, id ASC");
    }

    private void appendWordbookAndExclusions(StringBuilder selection, List<String> args, long wordbookId, List<Long> excludedIds) {
        if (wordbookId > 0) {
            selection.append(" AND wordbook_id = ?");
            args.add(String.valueOf(wordbookId));
        }
        if (excludedIds == null || excludedIds.isEmpty()) return;
        selection.append(" AND id NOT IN (");
        for (int index = 0; index < excludedIds.size(); index++) {
            if (index > 0) selection.append(',');
            selection.append('?');
            args.add(String.valueOf(excludedIds.get(index)));
        }
        selection.append(')');
    }

    private MemoryCard firstMatchingCard(String selection, List<String> args, String orderBy) {
        Cursor cursor = getReadableDatabase().query(
                "cards", null, selection, args.toArray(new String[0]), null, null, orderBy, "1"
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
        ReviewBaseline baseline;
        try (Cursor cursor = getReadableDatabase().query(
                "cards", new String[]{"interval_days", "repetitions", "ease_factor", "lapses", "state", "due_at"},
                "id = ? AND archived_at = 0", new String[]{String.valueOf(cardId)}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) throw new IllegalArgumentException("Card not found");
            baseline = new ReviewBaseline(cursor.getInt(0), cursor.getInt(1), cursor.getDouble(2), cursor.getInt(3), cursor.getString(4), cursor.getLong(5));
        }
        reviewFromBaseline(cardId, rating, now, baseline, "");
    }

    /**
     * Replaces a card's rating within one review session. The scheduling calculation always uses
     * the snapshot from before the first rating, avoiding accidental double SRS advancement.
     */
    public synchronized void reviewFromBaseline(long cardId, String rating, long now, ReviewBaseline baseline, String sessionId) {
        if (!("again".equals(rating) || "hard".equals(rating) || "good".equals(rating) || "easy".equals(rating))) {
            throw new IllegalArgumentException("Unknown review rating");
        }
        if (baseline == null) throw new IllegalArgumentException("Review baseline is required");
        String safeSessionId = sessionId == null ? "" : sessionId.trim();
        SQLiteDatabase db = getWritableDatabase();
        ReviewScheduler.Result result = ReviewScheduler.schedule(
                baseline.intervalDays, baseline.repetitions, baseline.easeFactor, baseline.lapses, rating, now
        );
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
            if (db.update("cards", values, "id = ? AND archived_at = 0", new String[]{String.valueOf(cardId)}) != 1) throw new IllegalArgumentException("Card not found");

            if (!safeSessionId.isEmpty()) db.delete("review_log", "card_id = ? AND session_id = ?", new String[]{String.valueOf(cardId), safeSessionId});

            ContentValues log = new ContentValues();
            log.put("card_id", cardId);
            log.put("rating", rating);
            log.put("reviewed_at", now);
            log.put("next_due_at", result.dueAt());
            log.put("session_id", safeSessionId);
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
        card.contextTranslation = getString(cursor, "context_translation");
        card.technicalNotes = jsonStrings(getString(cursor, "technical_notes"));
        card.aiStatus = getString(cursor, "ai_status");
        card.aiError = getString(cursor, "ai_error");
        card.aiMode = getString(cursor, "ai_mode");
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
