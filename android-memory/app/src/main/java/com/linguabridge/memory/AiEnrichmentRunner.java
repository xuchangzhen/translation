package com.linguabridge.memory;

import android.util.Log;

import java.util.List;

/** Runs user-requested completion sequentially; the caller chooses its background executor. */
public final class AiEnrichmentRunner {
    // Keep each request small enough for a local model to finish promptly. A 20-word request
    // appears frozen while a modest local model is still generating its first answer.
    public static final int NETWORK_BATCH_SIZE = 5;
    private static final String LOG_TAG = "LinguaBridgeAI";

    public interface Client {
        List<AiEnrichmentResult> enrich(AiConfig config, String contentMode, List<AiWorkItem> items) throws Exception;
    }

    public interface Progress {
        void onBatch(int processed, int requested, int completed, AiEnrichmentStats stats);

        /** Called after a batch is claimed, before its network request begins. */
        default void onBatchStarted(int processed, int requested, int completed, AiEnrichmentStats stats) {}
    }

    public static final class Outcome {
        public final int requested;
        public final int processed;
        public final int completed;
        public final String error;
        Outcome(int requested, int processed, int completed, String error) {
            this.requested = requested; this.processed = processed; this.completed = completed; this.error = error == null ? "" : error;
        }
    }

    private final MemoryDb db;
    private final Client client;

    public AiEnrichmentRunner(MemoryDb db, Client client) {
        this.db = db;
        this.client = client;
    }

    public Outcome run(long wordbookId, String contentMode, int requestedCount, AiConfig config, Progress progress) {
        int requested = Math.max(0, Math.min(requestedCount, db.aiStats(wordbookId).retryable()));
        int processed = 0, completed = 0;
        String failure = "";
        while (processed < requested) {
            List<AiWorkItem> batch = db.claimAiBatch(wordbookId, Math.min(NETWORK_BATCH_SIZE, requested - processed));
            if (batch.isEmpty()) break;
            if (progress != null) progress.onBatchStarted(processed, requested, completed, db.aiStats(wordbookId));
            try {
                completed += db.applyAiBatch(wordbookId, batch, client.enrich(config, contentMode, batch), contentMode);
            } catch (Exception error) {
                failure = safeError(error);
                // Keep ADB diagnostics useful without ever logging prompts, word content, or API keys.
                Log.w(LOG_TAG, "AI batch failed; wordbook=" + wordbookId + ", batchSize=" + batch.size()
                        + ", reason=" + failure, error);
                db.markAiBatchError(wordbookId, batch, failure);
                processed += batch.size();
                if (progress != null) progress.onBatch(processed, requested, completed, db.aiStats(wordbookId));
                break; // Do not automatically spend more API calls after a service-level failure.
            }
            processed += batch.size();
            if (progress != null) progress.onBatch(processed, requested, completed, db.aiStats(wordbookId));
        }
        return new Outcome(requested, processed, completed, failure);
    }

    private String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "AI 服务暂时不可用，可手动重试";
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
}
