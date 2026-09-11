package com.linguabridge.memory;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class MemoryDbEnhancementsTest {
    private Context context;
    private MemoryDb db;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("word-memory.db");
        db = new MemoryDb(context);
    }

    @After public void cleanup() { db.close(); context.deleteDatabase("word-memory.db"); }

    private long importBook(String name, String word) {
        return db.importWordbook(WordbookImporter.parse("word,translation\n" + word + ",释义", name + ".csv"), name).wordbookId;
    }

    @Test public void pinOrderAndCloudDeletionTombstoneArePersistentAndTransactional() {
        long first = importBook("第一", "first");
        long second = importBook("第二", "second");
        assertTrue(db.setWordbookPinned(second, true));
        assertEquals(second, db.wordbooks(System.currentTimeMillis()).get(0).id);
        db.getWritableDatabase().execSQL("UPDATE wordbooks SET cloud_id = '" + UUID.randomUUID() + "', cloud_space = 'space' WHERE id = " + second);
        String cloudId;
        try (Cursor cursor = db.getReadableDatabase().rawQuery("SELECT cloud_id FROM wordbooks WHERE id = ?", new String[]{String.valueOf(second)})) { assertTrue(cursor.moveToFirst()); cloudId = cursor.getString(0); }
        MemoryCard card = db.nextDue(Long.MAX_VALUE, second);
        db.review(card.id, "good", System.currentTimeMillis());
        db.deleteWordbook(second);
        assertTrue(db.isCloudWordbookIgnored(cloudId, "space"));
        assertEquals(0, db.dueCount(Long.MAX_VALUE, second));
        try (Cursor logs = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM review_log WHERE card_id = ?", new String[]{String.valueOf(card.id)})) { assertTrue(logs.moveToFirst()); assertEquals(0, logs.getInt(0)); }
        assertFalse(db.setWordbookPinned(1, true));
        assertEquals(first, db.wordbooks(System.currentTimeMillis()).get(1).id);
    }

    @Test public void phoneticBackfillIsIdempotentAndNeverOverwritesExistingIpa() throws Exception {
        long book = db.importWordbook(WordbookImporter.parse("word,translation,phonetic\ncache,缓存,\nkeep,保留,/custom/", "ipa.csv"), "音标").wordbookId;
        File fixture = context.getDatabasePath("backfill-fixture.db");
        context.deleteDatabase(fixture.getName());
        try (SQLiteDatabase fixtureDb = SQLiteDatabase.openOrCreateDatabase(fixture, null)) {
            fixtureDb.execSQL("CREATE TABLE phonetics (word TEXT PRIMARY KEY COLLATE NOCASE, phonetic TEXT NOT NULL)");
            fixtureDb.execSQL("INSERT INTO phonetics(word, phonetic) VALUES ('cache', 'kæʃ')");
        }
        try (PhoneticDictionary dictionary = new PhoneticDictionary(context, fixture)) {
            PhoneticBackfillResult first = db.backfillMissingPhonetics(dictionary);
            assertEquals(1, first.filled); assertFalse(first.dictionaryUnavailable);
        }
        try (Cursor values = db.getReadableDatabase().rawQuery("SELECT front,phonetic FROM cards WHERE wordbook_id = ? ORDER BY id", new String[]{String.valueOf(book)})) {
            assertTrue(values.moveToFirst()); assertEquals("kæʃ", values.getString(1));
            assertTrue(values.moveToNext()); assertEquals("/custom/", values.getString(1));
        }
        try (PhoneticDictionary dictionary = new PhoneticDictionary(context, fixture)) {
            assertTrue(db.backfillMissingPhonetics(dictionary).alreadyCompleted);
        }
        context.deleteDatabase(fixture.getName());
    }

    @Test public void replacingARatingUsesBaselineAndKeepsOneReviewLog() {
        long book = importBook("复习", "cache");
        MemoryCard card = db.nextDue(Long.MAX_VALUE, book);
        ReviewBaseline baseline = card.reviewBaseline();
        long now = System.currentTimeMillis();
        db.reviewFromBaseline(card.id, "good", now, baseline, "session-a");
        db.reviewFromBaseline(card.id, "again", now + 1, baseline, "session-a");
        ReviewScheduler.Result expected = ReviewScheduler.schedule(baseline.intervalDays, baseline.repetitions, baseline.easeFactor, baseline.lapses, "again", now + 1);
        MemoryCard result = db.nextDue(Long.MAX_VALUE, book);
        assertEquals(expected.intervalDays(), result.intervalDays);
        assertEquals(expected.repetitions(), result.repetitions);
        assertEquals(expected.lapses(), result.lapses);
        try (Cursor logs = db.getReadableDatabase().rawQuery("SELECT rating,COUNT(*) FROM review_log WHERE card_id = ? AND session_id = 'session-a'", new String[]{String.valueOf(card.id)})) {
            assertTrue(logs.moveToFirst()); assertEquals("again", logs.getString(0)); assertEquals(1, logs.getInt(1));
        }
    }

    @Test public void upgradesBothV2AndV3SchemasWithoutRepeatedAlterFailures() {
        db.close();
        for (int version : new int[]{2, 3}) {
            context.deleteDatabase("word-memory.db");
            try (SQLiteDatabase old = context.openOrCreateDatabase("word-memory.db", 0, null)) {
                old.execSQL("CREATE TABLE wordbooks (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, source TEXT NOT NULL)");
                old.execSQL("INSERT INTO wordbooks(id,name,source) VALUES(1,'桌面翻译','desktop')");
                if (version == 3) {
                    old.execSQL("ALTER TABLE wordbooks ADD COLUMN cloud_id TEXT NOT NULL DEFAULT ''");
                    old.execSQL("ALTER TABLE wordbooks ADD COLUMN cloud_space TEXT NOT NULL DEFAULT ''");
                    old.execSQL("ALTER TABLE wordbooks ADD COLUMN cloud_version INTEGER NOT NULL DEFAULT 0");
                    old.execSQL("ALTER TABLE wordbooks ADD COLUMN local_revision INTEGER NOT NULL DEFAULT 0");
                    old.execSQL("ALTER TABLE wordbooks ADD COLUMN synced_revision INTEGER NOT NULL DEFAULT 0");
                }
                old.execSQL("CREATE TABLE cards (id INTEGER PRIMARY KEY AUTOINCREMENT,sync_key TEXT NOT NULL,wordbook_id INTEGER NOT NULL DEFAULT 1,category TEXT NOT NULL DEFAULT '',external_id TEXT NOT NULL,type TEXT NOT NULL,front TEXT NOT NULL,back TEXT NOT NULL,front_language TEXT NOT NULL,back_language TEXT NOT NULL,phonetic TEXT NOT NULL DEFAULT '',technical_notes TEXT NOT NULL DEFAULT '[]',context TEXT NOT NULL DEFAULT '',terms TEXT NOT NULL DEFAULT '[]',created_at INTEGER NOT NULL,content_updated_at INTEGER NOT NULL,encounter_count INTEGER NOT NULL DEFAULT 1,state TEXT NOT NULL DEFAULT 'new',due_at INTEGER NOT NULL,interval_days INTEGER NOT NULL DEFAULT 0,ease_factor REAL NOT NULL DEFAULT 2.5,repetitions INTEGER NOT NULL DEFAULT 0,lapses INTEGER NOT NULL DEFAULT 0,last_reviewed_at INTEGER NOT NULL DEFAULT 0,archived_at INTEGER NOT NULL DEFAULT 0,UNIQUE(wordbook_id,sync_key))");
                old.execSQL("INSERT INTO cards(sync_key,external_id,type,front,back,front_language,back_language,created_at,content_updated_at,due_at) VALUES('cache','','word','cache','缓存','en','zh',1,1,1)");
                old.execSQL("CREATE TABLE review_log (id INTEGER PRIMARY KEY AUTOINCREMENT,card_id INTEGER NOT NULL,rating TEXT NOT NULL,reviewed_at INTEGER NOT NULL,next_due_at INTEGER NOT NULL)");
                old.setVersion(version);
            }
            try (MemoryDb upgraded = new MemoryDb(context)) {
                assertEquals(6, upgraded.getReadableDatabase().getVersion());
                assertEquals("cache", upgraded.nextDue(Long.MAX_VALUE, 1).front);
                try (Cursor columns = upgraded.getReadableDatabase().rawQuery("PRAGMA table_info(cards)", null)) {
                    boolean aiStatus = false;
                    while (columns.moveToNext()) if ("ai_status".equals(columns.getString(1))) aiStatus = true;
                    assertTrue(aiStatus);
                }
            }
        }
        db = new MemoryDb(context);
    }
}
