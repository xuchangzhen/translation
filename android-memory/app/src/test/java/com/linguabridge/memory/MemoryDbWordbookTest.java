package com.linguabridge.memory;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class MemoryDbWordbookTest {
    private Context context;
    private MemoryDb db;
    @Before public void setup() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase("word-memory.db"); db = new MemoryDb(context); }
    @After public void cleanup() { db.close(); context.deleteDatabase("word-memory.db"); }
    private ImportPreview file(String back) { return WordbookImporter.parse("word,translation\ncache," + back, "a.csv"); }
    @Test public void reimportPreservesAllReviewFieldsAndScopesWords() throws Exception {
        long book = db.importWordbook(file("贮藏"), "CET-4").wordbookId;
        MemoryCard card = db.nextDue(Long.MAX_VALUE, book);
        db.review(card.id, "good", 1000);
        MemoryCard reviewed = db.nextDue(Long.MAX_VALUE, book);
        MemoryDb.WordbookImportResult result = db.importWordbook(file("隐藏处"), "CET-4");
        assertEquals(0, result.added); assertEquals(1, result.updated);
        MemoryCard updated = db.nextDue(Long.MAX_VALUE, book);
        assertEquals(card.id, updated.id); assertEquals("隐藏处", updated.back);
        assertEquals(reviewed.dueAt, updated.dueAt); assertEquals(reviewed.intervalDays, updated.intervalDays);
        assertEquals(reviewed.repetitions, updated.repetitions); assertEquals(reviewed.easeFactor, updated.easeFactor, 0);
        assertEquals(reviewed.lapses, updated.lapses); assertEquals(reviewed.state, updated.state);
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT last_reviewed_at FROM cards WHERE id = " + card.id, null)) { assertTrue(c.moveToFirst()); assertEquals(1000, c.getLong(0)); }
        long other = db.importWordbook(file("高速缓存"), "计算机").wordbookId;
        assertNotEquals(book, other); assertEquals("高速缓存", db.nextDue(Long.MAX_VALUE, other).back);
        JSONObject payload = new JSONObject().put("items", new JSONArray().put(new JSONObject().put("key", "cache").put("front", "cache").put("back", "桌面释义")));
        assertEquals(1, db.importPayload(payload).accepted); assertEquals(1, db.importPayload(payload).updated);
        assertEquals(3, db.stats(Long.MAX_VALUE).total);
        assertEquals(1, db.nextDue(Long.MAX_VALUE, 1).wordbookId);
    }
    @Test public void upgradesV1WithoutLosingCardsOrReviewLog() throws Exception {
        db.close();
        try (SQLiteDatabase old = context.openOrCreateDatabase("word-memory.db", 0, null)) {
            String schema = new String(getClass().getResourceAsStream("/memory-v1.sql").readAllBytes(), StandardCharsets.UTF_8);
            for (String sql : schema.split(";")) if (!sql.trim().isEmpty()) old.execSQL(sql);
            old.execSQL("INSERT INTO cards(id,sync_key,external_id,type,front,back,front_language,back_language,created_at,content_updated_at,due_at,state,interval_days,ease_factor,repetitions,lapses,last_reviewed_at) VALUES(42,'cache','external','word','cache','缓存','en','zh',10,20,99999,'review',8,2.8,4,2,100)");
            old.execSQL("INSERT INTO review_log(id,card_id,rating,reviewed_at,next_due_at) VALUES(5,42,'good',100,99999)"); old.setVersion(1);
        }
        db = new MemoryDb(context);
        MemoryCard c = db.nextDue(Long.MAX_VALUE, 1);
        assertEquals(42, c.id); assertEquals("缓存", c.back); assertEquals(4, c.repetitions); assertEquals(8, c.intervalDays);
        assertEquals(99999, c.dueAt); assertEquals(2.8, c.easeFactor, 0); assertEquals(2, c.lapses); assertEquals("review", c.state);
        try (Cursor logs = db.getReadableDatabase().rawQuery("SELECT id,card_id,rating,reviewed_at,next_due_at FROM review_log", null)) {
            assertTrue(logs.moveToFirst()); assertEquals(5, logs.getLong(0)); assertEquals(42, logs.getLong(1));
            assertEquals("good", logs.getString(2)); assertEquals(100, logs.getLong(3)); assertEquals(99999, logs.getLong(4)); assertFalse(logs.moveToNext());
        }
        assertEquals("桌面翻译", db.wordbooks(0).get(0).name);
        assertEquals(6, db.getReadableDatabase().getVersion());
    }
    @Test public void importsTwentyThousandAndUsesExistingScheduler() {
        StringBuilder source = new StringBuilder("word,translation\n");
        for (int i = 0; i < 20000; i++) source.append("term").append(i).append(",释义\n");
        long started = System.currentTimeMillis();
        MemoryDb.WordbookImportResult r = db.importWordbook(WordbookImporter.parse(source.toString(), "large.csv"), "大词库");
        assertEquals(20000, r.added); assertEquals(20000, db.stats(System.currentTimeMillis()).due);
        MemoryCard card = db.nextDue(System.currentTimeMillis(), r.wordbookId); assertEquals("new", card.state);
        for (String rating : new String[]{"again", "hard", "good", "easy"}) db.review(card.id, rating, System.currentTimeMillis());
        assertEquals(19999, db.wordbooks(System.currentTimeMillis()).get(1).due);
        assertEquals(60, db.recent(60, r.wordbookId, 60).size());
        System.out.println("20,000-word parse + transaction + review: " + (System.currentTimeMillis() - started) + " ms");
    }
    @Test public void keepsPracticeAvailableAfterTodaysDueCardsAreFinished() {
        long book = db.importWordbook(file("贮藏"), "CET-4").wordbookId;
        long now = System.currentTimeMillis();
        MemoryCard card = db.nextDue(now, book);
        db.review(card.id, "good", now);
        assertNull(db.nextDue(now + 1, book));
        assertEquals(1, db.practiceCount(now + 1, book));
        MemoryCard practice = db.nextPractice(now + 1, book);
        assertNotNull(practice);
        assertEquals(card.id, practice.id);
        db.review(practice.id, "good", now + 2);
        assertNull(db.nextPractice(now + 1, book));
        assertEquals(0, db.practiceCount(now + 1, book));
    }
    @Test public void reviewNavigationLoadsUnseenCardsWithoutChangingTheirSchedules() {
        long book = db.importWordbook(WordbookImporter.parse("word,translation\nfirst,第一\nsecond,第二", "navigation.csv"), "导航").wordbookId;
        long now = System.currentTimeMillis();
        assertEquals(2, db.dueCount(now, book));
        List<Long> shown = new ArrayList<>();
        MemoryCard first = db.nextDueExcluding(now, book, shown);
        shown.add(first.id);
        MemoryCard second = db.nextDueExcluding(now, book, shown);
        shown.add(second.id);
        assertNotEquals(first.id, second.id);
        assertNull(db.nextDueExcluding(now, book, shown));
        assertEquals(2, db.stats(now).due);
    }
    @Test public void failedImportRollsBackEntireBook() {
        db.getWritableDatabase().execSQL("CREATE TRIGGER reject_bad BEFORE INSERT ON cards WHEN NEW.front = 'bad' BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        try {
            db.importWordbook(WordbookImporter.parse("word,translation\nfirst,正常\nbad,失败", "a.csv"), "回滚测试");
            fail();
        } catch (android.database.sqlite.SQLiteException expected) { }
        assertEquals(0, db.stats(0).total); assertEquals(1, db.wordbooks(0).size());
    }
    @Test public void desktopBookIsReserved() {
        try { db.importWordbook(file("缓存"), "桌面翻译"); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(0, db.stats(0).total);
    }
}
