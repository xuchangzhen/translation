package com.linguabridge.memory;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class CloudWordbookTest {
    @Test public void largeUnicodeWordbookChunksStayBoundedAndLossless() throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < 20000; i++) items.put(new JSONObject().put("front", "word" + i).put("back", "中文释义"));
        List<JSONArray> chunks = CloudApi.wordbookChunks(items);
        assertTrue(chunks.size() > 1);
        int index = 0;
        for (JSONArray chunk : chunks) {
            assertTrue(chunk.toString().getBytes(StandardCharsets.UTF_8).length <= 256 * 1024);
            for (int j = 0; j < chunk.length(); j++) assertEquals("word" + index++, chunk.getJSONObject(j).getString("front"));
        }
        assertEquals(20000, index);
    }

    @Test public void cloudChangesPreserveLocalConflictAndRollbackOnFailure() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("word-memory.db");
        try (MemoryDb db = new MemoryDb(context)) {
            String id = UUID.randomUUID().toString();
            String space = UUID.randomUUID().toString();
            ImportPreview initial = WordbookImporter.parse("word,translation\ncache,云端", "Cloud.csv");
            long localId = db.applyCloudWordbook(initial, id, space, 1).wordbookId;
            assertEquals(1, db.cloudWordbookVersion(id, space));
            assertEquals(0, db.applyCloudWordbook(initial, id, space, 1).updated);
            db.importWordbook(WordbookImporter.parse("word,translation\ncache,本地编辑", "Cloud.csv"), "Cloud");
            db.getWritableDatabase().execSQL("CREATE TRIGGER reject_bad BEFORE INSERT ON cards WHEN NEW.front = 'bad' BEGIN SELECT RAISE(ABORT, 'test'); END");
            ImportPreview bad = WordbookImporter.parse("word,translation\nbad,失败", "Cloud.csv");
            try { db.applyCloudWordbook(bad, id, space, 2); fail(); }
            catch (android.database.sqlite.SQLiteException expected) { }
            assertEquals(1, db.cloudWordbookVersion(id, space));
            assertEquals(2, db.wordbooks(0).size());
            assertEquals("Cloud", db.wordbooks(0).get(1).name);
            assertEquals("本地编辑", db.nextDue(Long.MAX_VALUE, localId).back);
            MemoryDb.WordbookImportResult updated = db.applyCloudWordbook(initial, id, space, 2);
            assertFalse(updated.conflictCopy.isEmpty());
            assertNotEquals(localId, updated.wordbookId);
            assertEquals("本地编辑", db.nextDue(Long.MAX_VALUE, localId).back);
            assertEquals("云端", db.nextDue(Long.MAX_VALUE, updated.wordbookId).back);
            assertEquals(2, db.cloudWordbookVersion(id, space));
        } finally { context.deleteDatabase("word-memory.db"); }
    }
}
