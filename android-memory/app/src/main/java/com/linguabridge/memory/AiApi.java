package com.linguabridge.memory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Minimal OpenAI-compatible client. It intentionally never logs request bodies or API keys. */
public final class AiApi implements AiEnrichmentRunner.Client {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private Boolean ollamaNativeAvailable;

    @Override
    public List<AiEnrichmentResult> enrich(AiConfig config, String contentMode, List<AiWorkItem> items) throws Exception {
        if (config == null || !config.isConfigured()) throw new IllegalStateException("请先配置 AI 服务");
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", instruction(contentMode)))
                .put(new JSONObject().put("role", "user").put("content", itemsJson(items).toString()));
        if (hasNativeOllama(config)) return enrichWithNativeOllama(config, messages);
        JSONObject request = new JSONObject()
                .put("model", config.model)
                .put("temperature", 0.2)
                .put("max_tokens", 900)
                .put("response_format", new JSONObject().put("type", "json_object"))
                .put("messages", messages);
        JSONObject response = request(endpoint(config.baseUrl, "chat/completions"), "POST", request, config.apiKey, 90_000);
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IllegalStateException("AI 服务没有返回可用结果");
        String content = choices.optJSONObject(0) == null ? "" : choices.optJSONObject(0).optJSONObject("message") == null
                ? "" : choices.optJSONObject(0).optJSONObject("message").optString("content", "");
        return parseItems(content);
    }

    /**
     * Ollama's OpenAI compatibility route does not consistently honor think:false for reasoning
     * models. Its native chat route does, which avoids spending the request timeout on hidden
     * reasoning before JSON is returned.
     */
    private List<AiEnrichmentResult> enrichWithNativeOllama(AiConfig config, JSONArray messages) throws Exception {
        JSONObject request = new JSONObject()
                .put("model", config.model)
                .put("stream", false)
                .put("format", "json")
                .put("think", false)
                .put("options", new JSONObject().put("temperature", 0.2).put("num_predict", 900))
                .put("messages", messages);
        JSONObject response = request(ollamaEndpoint(config.baseUrl, "chat"), "POST", request, config.apiKey, 90_000);
        JSONObject message = response.optJSONObject("message");
        String content = message == null ? "" : message.optString("content", "");
        if (content.trim().isEmpty()) throw new IllegalStateException("Ollama 没有返回补全内容");
        return parseItems(content);
    }

    /**
     * Reads OpenAI-compatible models and falls back to Ollama's native model endpoint. This keeps
     * a local Ollama install usable whether its compatibility route is enabled or not.
     */
    public List<String> listModels(String baseUrl, String apiKey) throws Exception {
        Exception compatibleError = null;
        try {
            List<String> models = parseModelIds(request(endpoint(baseUrl, "models"), "GET", null, apiKey, 20_000));
            if (!models.isEmpty()) return models;
        } catch (Exception error) {
            compatibleError = error;
        }
        try {
            List<String> models = parseModelIds(request(ollamaEndpoint(baseUrl, "tags"), "GET", null, apiKey, 20_000));
            if (!models.isEmpty()) return models;
        } catch (Exception fallbackError) {
            if (compatibleError != null) {
                fallbackError.addSuppressed(compatibleError);
            }
            throw new IllegalStateException("无法读取模型列表。请检查 Base URL、手机网络或 ADB 反向转发。", fallbackError);
        }
        throw new IllegalStateException("AI 服务没有返回可选择的模型（已尝试 /v1/models 和 /api/tags）");
    }

    static List<String> parseModelIds(JSONObject response) {
        JSONArray items = response == null ? null : response.optJSONArray("data");
        if (items == null && response != null) items = response.optJSONArray("models");
        Set<String> unique = new LinkedHashSet<>();
        if (items != null) for (int index = 0; index < items.length(); index++) {
            Object item = items.opt(index);
            String id = "";
            if (item instanceof String) id = ((String) item).trim();
            else if (item instanceof JSONObject) {
                Object rawId = ((JSONObject) item).opt("id");
                if (!(rawId instanceof String)) rawId = ((JSONObject) item).opt("name");
                if (rawId instanceof String) id = ((String) rawId).trim();
            }
            if (!id.isEmpty() && id.length() <= 200) unique.add(id);
        }
        return new ArrayList<>(unique);
    }

    private String instruction(String mode) {
        String type = "technical".equals(mode) ? "technical" : "general".equals(mode) ? "general" : "auto";
        return "Return JSON only, with an items array. Every item must keep the supplied string id. "
                + "For each word create one natural English context sentence in context (max 320 chars). "
                + "Also provide a concise Simplified Chinese translation of that sentence in contextTranslation (max 240 chars). "
                + "Mode is " + type + ". For general mode (ordinary vocabulary such as CET-4/CET-6 words), provide only natural usage and its Chinese translation; do not invent technicalNotes. "
                + "For technical mode (engineering, hardware, software, science or other domain terms), also create a concise Chinese technicalNotes string (max 280 chars). "
                + "In auto mode include resolvedMode as exactly general or technical, and include technicalNotes only when resolvedMode is technical. "
                + "Never rewrite front or back; omit no requested ids. Do not include reasoning, markdown, or text outside the JSON.";
    }

    private JSONArray itemsJson(List<AiWorkItem> items) throws Exception {
        JSONArray result = new JSONArray();
        for (AiWorkItem item : items) result.put(new JSONObject()
                .put("id", String.valueOf(item.id))
                .put("front", item.front)
                .put("back", item.back)
                .put("category", item.category)
                .put("existingContext", item.context)
                .put("existingContextTranslation", item.contextTranslation)
                .put("existingTechnicalNotes", item.technicalNote));
        return result;
    }

    static List<AiEnrichmentResult> parseItems(String raw) throws Exception {
        String json = raw == null ? "" : raw.trim();
        if (json.startsWith("```")) {
            int firstLine = json.indexOf('\n');
            int closing = json.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) json = json.substring(firstLine + 1, closing).trim();
        }
        JSONObject root = new JSONObject(json);
        JSONArray items = root.optJSONArray("items");
        if (items == null) throw new IllegalArgumentException("AI 返回不是 items JSON");
        List<AiEnrichmentResult> result = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            Object rawId = item.opt("id");
            Object rawContext = item.opt("context");
            Object rawContextTranslation = item.has("contextTranslation")
                    ? item.opt("contextTranslation")
                    : item.has("contextZh") ? item.opt("contextZh") : item.opt("translation");
            Object rawNote = item.has("technicalNotes") ? item.opt("technicalNotes") : item.opt("technicalNote");
            Object rawMode = item.opt("resolvedMode");
            if (!(rawId instanceof String) || (rawContext != null && rawContext != JSONObject.NULL && !(rawContext instanceof String))
                    || (rawContextTranslation != null && rawContextTranslation != JSONObject.NULL && !(rawContextTranslation instanceof String))
                    || (rawNote != null && rawNote != JSONObject.NULL && !(rawNote instanceof String))
                    || (rawMode != null && rawMode != JSONObject.NULL && !(rawMode instanceof String))) continue;
            String id = ((String) rawId).trim();
            if (!id.matches("[1-9][0-9]*")) continue;
            result.add(new AiEnrichmentResult(Long.parseLong(id), rawContext instanceof String ? (String) rawContext : "",
                    rawContextTranslation instanceof String ? (String) rawContextTranslation : "",
                    rawNote instanceof String ? (String) rawNote : "", rawMode instanceof String ? (String) rawMode : ""));
        }
        return result;
    }

    private String endpoint(String rawBaseUrl, String resource) throws Exception {
        String base = normalizedBaseUrl(rawBaseUrl);
        return base.endsWith("/v1") ? base + "/" + resource : base + "/v1/" + resource;
    }

    private boolean hasNativeOllama(AiConfig config) {
        if (ollamaNativeAvailable != null) return ollamaNativeAvailable;
        try {
            request(ollamaEndpoint(config.baseUrl, "version"), "GET", null, config.apiKey, 8_000);
            ollamaNativeAvailable = true;
        } catch (Exception ignored) {
            ollamaNativeAvailable = false;
        }
        return ollamaNativeAvailable;
    }

    private String ollamaEndpoint(String rawBaseUrl, String resource) throws Exception {
        String base = normalizedBaseUrl(rawBaseUrl);
        if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);
        return base + "/api/" + resource;
    }

    private String normalizedBaseUrl(String rawBaseUrl) throws Exception {
        String base = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URL parsed = new URL(base);
        if (!("http".equalsIgnoreCase(parsed.getProtocol()) || "https".equalsIgnoreCase(parsed.getProtocol())) || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getRef() != null) {
            throw new IllegalArgumentException("AI 服务地址必须是有效的 HTTP(S) 地址");
        }
        return base;
    }

    private JSONObject request(String endpoint, String method, JSONObject body, String apiKey, int readTimeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(readTimeout);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        if (body != null) {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
        }
        int status = connection.getResponseCode();
        String text = readLimited(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        JSONObject response = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        if (status >= 400) throw new IllegalStateException(response.optString("error", "AI 服务错误 " + status));
        return response;
    }

    private String readLimited(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int total = 0; int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("AI 返回内容过大");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
