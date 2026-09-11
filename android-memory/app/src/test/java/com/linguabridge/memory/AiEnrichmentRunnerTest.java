package com.linguabridge.memory;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class AiEnrichmentRunnerTest {
    private Context context;
    private MemoryDb db;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("word-memory.db");
        db = new MemoryDb(context);
    }

    @After public void cleanup() {
        db.close();
        context.deleteDatabase("word-memory.db");
    }

    private long book(int count, String mode) {
        StringBuilder csv = new StringBuilder("word,translation\n");
        for (int index = 1; index <= count; index++) csv.append("term").append(index).append(",释义").append(index).append('\n');
        return db.importWordbook(WordbookImporter.parse(csv.toString(), "ai.csv"), "AI 词库", mode).wordbookId;
    }

    private static final class RecordingClient implements AiEnrichmentRunner.Client {
        final List<List<Long>> batches = new ArrayList<>();
        @Override public List<AiEnrichmentResult> enrich(AiConfig config, String mode, List<AiWorkItem> items) {
            List<Long> ids = new ArrayList<>();
            List<AiEnrichmentResult> result = new ArrayList<>();
            for (AiWorkItem item : items) {
                ids.add(item.id);
                result.add(new AiEnrichmentResult(item.id, "A natural sentence for " + item.front + ".", "技术解释", "technical"));
            }
            batches.add(ids);
            return result;
        }
    }

    @Test public void requestedSeventyThreeUsesSmallResponsiveBatchesAndKeepsFrontBack() {
        long wordbookId = book(73, "general");
        MemoryCard before = db.nextDue(Long.MAX_VALUE, wordbookId);
        RecordingClient client = new RecordingClient();
        AiEnrichmentRunner.Outcome outcome = new AiEnrichmentRunner(db, client).run(wordbookId, "general", 73, new AiConfig("http://example.test/v1", "model", ""), null);
        assertEquals(73, outcome.requested); assertEquals(73, outcome.completed);
        assertEquals(15, client.batches.size());
        for (int index = 0; index < 14; index++) assertEquals(5, client.batches.get(index).size());
        assertEquals(3, client.batches.get(14).size());
        MemoryCard after = db.nextDue(Long.MAX_VALUE, wordbookId);
        assertEquals(before.front, after.front); assertEquals(before.back, after.back);
        assertFalse(after.context.isEmpty());
        assertEquals(73, db.aiStats(wordbookId).complete);
    }

    @Test public void secondRunDoesNotRequestAlreadyCompletedCardsAndClampsRequestedCount() {
        long wordbookId = book(70, "general");
        RecordingClient first = new RecordingClient();
        new AiEnrichmentRunner(db, first).run(wordbookId, "general", 50, new AiConfig("http://example.test", "model", ""), null);
        RecordingClient second = new RecordingClient();
        AiEnrichmentRunner.Outcome outcome = new AiEnrichmentRunner(db, second).run(wordbookId, "general", 100, new AiConfig("http://example.test", "model", ""), null);
        assertEquals(20, outcome.requested);
        for (List<Long> later : second.batches) for (Long id : later) for (List<Long> earlier : first.batches) assertFalse(earlier.contains(id));
        assertEquals(70, db.aiStats(wordbookId).complete);
    }

    @Test public void malformedItemDoesNotOverwriteExistingContentOrBlockOtherItems() {
        long wordbookId = book(2, "general");
        MemoryCard first = db.nextDue(Long.MAX_VALUE, wordbookId);
        db.getWritableDatabase().execSQL("UPDATE cards SET context = '已有例句', ai_status = 'pending' WHERE id = " + first.id);
        AiEnrichmentRunner.Client client = (config, mode, items) -> {
            List<AiEnrichmentResult> results = new ArrayList<>();
            results.add(new AiEnrichmentResult(items.get(0).id, "不应覆盖", "", "general"));
            results.add(new AiEnrichmentResult(items.get(1).id, "", "", "general"));
            return results;
        };
        AiEnrichmentRunner.Outcome outcome = new AiEnrichmentRunner(db, client).run(wordbookId, "general", 2, new AiConfig("http://example.test", "model", ""), null);
        assertEquals(1, outcome.completed);
        MemoryCard stored = db.nextDue(Long.MAX_VALUE, wordbookId);
        assertEquals("已有例句", stored.context);
        assertEquals(1, db.aiStats(wordbookId).error);
    }

    @Test public void technicalCompletionKeepsExistingDefinitionInsteadOfOverwritingIt() {
        long wordbookId = book(1, "technical");
        MemoryCard card = db.nextDue(Long.MAX_VALUE, wordbookId);
        db.getWritableDatabase().execSQL("UPDATE cards SET technical_notes = '[\"已有技术解释\"]', ai_status = 'pending' WHERE id = " + card.id);
        AiEnrichmentRunner.Client client = (config, mode, items) -> java.util.Collections.singletonList(
                new AiEnrichmentResult(items.get(0).id, "A technical context sentence.", "不应覆盖", "technical"));
        AiEnrichmentRunner.Outcome outcome = new AiEnrichmentRunner(db, client).run(wordbookId, "technical", 1, new AiConfig("http://example.test", "model", ""), null);
        assertEquals(1, outcome.completed);
        assertEquals("已有技术解释", db.nextDue(Long.MAX_VALUE, wordbookId).technicalNotes.get(0));
    }

    @Test public void invalidJsonIsRejectedBeforeItCanBecomeDatabaseContent() throws Exception {
        try { AiApi.parseItems("not json"); org.junit.Assert.fail(); }
        catch (Exception expected) { assertTrue(expected.getMessage().length() > 0); }
        assertEquals(0, db.stats(Long.MAX_VALUE).total);
    }

    @Test public void aiResponseParsesContextTranslationAndResolvedMode() throws Exception {
        JSONObject item = new JSONObject()
                .put("id", "7")
                .put("context", "The technician replaced the failed module.")
                .put("contextTranslation", "技术员更换了故障模块。")
                .put("technicalNotes", "模块是可独立替换的功能单元。")
                .put("resolvedMode", "technical");
        List<AiEnrichmentResult> parsed = AiApi.parseItems(new JSONObject().put("items", new JSONArray().put(item)).toString());
        assertEquals(1, parsed.size());
        assertEquals("技术员更换了故障模块。", parsed.get(0).contextTranslation);
        assertEquals("technical", parsed.get(0).resolvedMode);
    }

    @Test public void modelListAcceptsOpenAiAndOllamaCompatibleShapesWithoutDuplicates() throws Exception {
        JSONObject response = new JSONObject().put("data", new JSONArray()
                .put(new JSONObject().put("id", "gpt-oss"))
                .put(new JSONObject().put("id", "gpt-oss"))
                .put(new JSONObject().put("name", "local-llama")));
        assertEquals(java.util.Arrays.asList("gpt-oss", "local-llama"), AiApi.parseModelIds(response));
        JSONObject ollamaResponse = new JSONObject().put("models", new JSONArray()
                .put(new JSONObject().put("name", "qwen3:8b"))
                .put(new JSONObject().put("name", "qwen3:8b")));
        assertEquals(java.util.Collections.singletonList("qwen3:8b"), AiApi.parseModelIds(ollamaResponse));
    }
}
