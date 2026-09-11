package com.linguabridge.memory;

import org.json.JSONObject;

/** Connection details for an OpenAI-compatible completion endpoint. */
public final class AiConfig {
    public final String baseUrl;
    public final String model;
    public final String apiKey;

    public AiConfig(String baseUrl, String model, String apiKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !model.isEmpty();
    }

    JSONObject toJson() throws Exception {
        return new JSONObject().put("baseUrl", baseUrl).put("model", model).put("apiKey", apiKey);
    }

    static AiConfig fromJson(JSONObject json) {
        return new AiConfig(json.optString("baseUrl"), json.optString("model"), json.optString("apiKey"));
    }
}
