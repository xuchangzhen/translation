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
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CloudApi {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final String WORDBOOK_PROTOCOL = "linguabridge-wordbooks/1";

    private CloudApi() {}

    public static final class DesktopClient {
        public final String name;
        public final String platform;
        public final String appVersion;
        public final long lastSeenAt;
        public final boolean online;

        DesktopClient(String name, String platform, String appVersion, long lastSeenAt, boolean online) {
            this.name = name;
            this.platform = platform;
            this.appVersion = appVersion;
            this.lastSeenAt = lastSeenAt;
            this.online = online;
        }
    }

    public static final class DesktopPresence {
        public final long serverTime;
        public final List<DesktopClient> clients;
        public final int online;

        DesktopPresence(long serverTime, List<DesktopClient> clients, int online) {
            this.serverTime = serverTime;
            this.clients = clients;
            this.online = online;
        }
    }

    public static SyncConfig register(String rawServerUrl, String registrationKey) throws Exception {
        String serverUrl = normalizeServerUrl(rawServerUrl);
        if (registrationKey.trim().length() < 16) {
            throw new IllegalArgumentException("服务器注册码至少需要 16 个字符");
        }
        SyncConfig config = new SyncConfig(
                serverUrl,
                UUID.randomUUID().toString(),
                CryptoBox.randomSecret(),
                CryptoBox.randomSecret(),
                CryptoBox.randomSecret()
        );
        JSONObject body = new JSONObject()
                .put("deviceId", config.deviceId)
                .put("uploadToken", config.uploadToken)
                .put("readToken", config.readToken);
        JSONObject response = request(
                serverUrl + "/v1/devices",
                "POST",
                body,
                "X-Registration-Key",
                registrationKey.trim(),
                12_000
        );
        if (!response.optBoolean("created")) throw new IllegalStateException("服务器未创建设备");
        return config;
    }

    public static JSONObject receive(SyncConfig config) throws Exception {
        return request(
                config.serverUrl + "/v1/devices/" + config.deviceId + "/batches?wait=25",
                "GET",
                null,
                "Authorization",
                "Bearer " + config.readToken,
                35_000
        );
    }

    public static void test(SyncConfig config) throws Exception {
        JSONObject response = request(
                config.serverUrl + "/v1/devices/" + config.deviceId + "/status",
                "GET",
                null,
                "Authorization",
                "Bearer " + config.uploadToken,
                12_000
        );
        if (!"linguabridge-memory/1".equals(response.optString("protocol"))) {
            throw new IllegalStateException("服务器版本不兼容");
        }
    }

    public static void acknowledge(SyncConfig config, String batchId) throws Exception {
        JSONObject response = request(
                config.serverUrl + "/v1/devices/" + config.deviceId + "/batches/" + batchId + "/ack",
                "POST",
                new JSONObject(),
                "Authorization",
                "Bearer " + config.readToken,
                12_000
        );
        if (!response.optBoolean("acknowledged")) throw new IllegalStateException("服务器未确认批次");
    }

    public static JSONArray listWordbooks(SyncConfig config) throws Exception {
        JSONObject response = request(config.serverUrl + "/v1/devices/" + config.deviceId + "/wordbooks", "GET", null,
                "Authorization", "Bearer " + config.readToken, 20_000);
        requireWordbookProtocol(response);
        return response.optJSONArray("wordbooks") == null ? new JSONArray() : response.getJSONArray("wordbooks");
    }

    public static JSONObject uploadWordbook(SyncConfig config, MemoryDb.CloudSnapshot snapshot) throws Exception {
        JSONArray encodedItems = snapshot.items;
        String uploadId = UUID.randomUUID().toString();
        List<JSONArray> chunks = wordbookChunks(encodedItems);
        int chunkCount = chunks.size();
        for (int start = 0; start < chunks.size(); start++) {
            JSONArray chunk = chunks.get(start);
            JSONObject body = new JSONObject().put("protocol", WORDBOOK_PROTOCOL)
                    .put("envelope", CryptoBox.encrypt(chunk.toString(), config.contentKey));
            request(config.serverUrl + "/v1/devices/" + config.deviceId + "/wordbooks/" + snapshot.id + "/uploads/" + uploadId + "/chunks/" + start,
                    "PUT", body, "Authorization", "Bearer " + config.readToken, 30_000);
        }
        JSONObject manifest = new JSONObject().put("name", snapshot.name).put("itemCount", encodedItems.length());
        JSONObject commit = new JSONObject().put("protocol", WORDBOOK_PROTOCOL)
                .put("baseVersion", snapshot.version).put("chunkCount", chunkCount)
                .put("manifest", CryptoBox.encrypt(manifest.toString(), config.contentKey));
        JSONObject response = request(config.serverUrl + "/v1/devices/" + config.deviceId + "/wordbooks/" + snapshot.id + "/uploads/" + uploadId + "/commit",
                "POST", commit, "Authorization", "Bearer " + config.readToken, 30_000);
        requireWordbookProtocol(response);
        return response;
    }

    static List<JSONArray> wordbookChunks(JSONArray encodedItems) throws Exception {
        if (encodedItems.length() > WordbookImporter.MAX_ITEMS) throw new IllegalArgumentException("词库最多同步 100000 条。");
        List<JSONArray> chunks = new ArrayList<>();
        JSONArray current = new JSONArray();
        int currentBytes = 2;
        for (int i = 0; i < encodedItems.length(); i++) {
            JSONObject item = encodedItems.getJSONObject(i);
            int itemBytes = item.toString().getBytes(StandardCharsets.UTF_8).length;
            if (itemBytes + 2 > 256 * 1024) throw new IllegalArgumentException("单条词汇内容过长，无法同步。");
            int separator = current.length() > 0 ? 1 : 0;
            if (currentBytes + separator + itemBytes > 256 * 1024) {
                chunks.add(current);
                current = new JSONArray();
                currentBytes = 2;
                separator = 0;
            }
            current.put(item);
            currentBytes += separator + itemBytes;
        }
        if (current.length() > 0 || encodedItems.length() == 0) chunks.add(current);
        if (chunks.size() > 1024) throw new IllegalArgumentException("词库过大，无法同步。");
        return chunks;
    }

    public static final class DownloadedWordbook {
        public final String id, name;
        public final long version;
        public final JSONArray items;
        DownloadedWordbook(String id, String name, long version, JSONArray items) {
            this.id = id; this.name = name; this.version = version; this.items = items;
        }
    }

    public static DownloadedWordbook downloadWordbook(SyncConfig config, JSONObject summary) throws Exception {
        String id = summary.optString("id");
        String uploadId = summary.optString("uploadId");
        long version = summary.optLong("version", 0);
        int chunkCount = summary.optInt("chunkCount", -1);
        JSONObject manifestEnvelope = summary.optJSONObject("manifest");
        UUID.fromString(id);
        UUID.fromString(uploadId);
        if (version < 1 || chunkCount < 0 || chunkCount > 1024 || manifestEnvelope == null) throw new IllegalStateException("云端词库清单无效");
        JSONObject manifest = new JSONObject(CryptoBox.decryptToString(manifestEnvelope.optString("algorithm"), manifestEnvelope.optString("nonce"), manifestEnvelope.optString("ciphertext"), config.contentKey));
        String name = manifest.optString("name", "云端词库");
        int itemCount = manifest.optInt("itemCount", -1);
        if (itemCount < 0 || itemCount > WordbookImporter.MAX_ITEMS) throw new IllegalStateException("云端词库条数无效");
        JSONArray items = new JSONArray();
        long totalBytes = 0;
        for (int index = 0; index < chunkCount; index++) {
            JSONObject response = request(config.serverUrl + "/v1/devices/" + config.deviceId + "/wordbooks/" + id + "/uploads/" + uploadId + "/chunks/" + index,
                    "GET", null, "Authorization", "Bearer " + config.readToken, 30_000);
            requireWordbookProtocol(response);
            JSONObject envelope = response.getJSONObject("envelope");
            String plaintext = CryptoBox.decryptToString(envelope.optString("algorithm"), envelope.optString("nonce"), envelope.optString("ciphertext"), config.contentKey);
            totalBytes += plaintext.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes > WordbookImporter.MAX_BYTES) throw new IllegalStateException("云端词库过大");
            JSONArray chunk = new JSONArray(plaintext);
            if (items.length() + chunk.length() > itemCount) throw new IllegalStateException("云端词库条数不匹配");
            for (int i = 0; i < chunk.length(); i++) items.put(chunk.get(i));
        }
        if (items.length() != itemCount) throw new IllegalStateException("云端词库条数不匹配");
        return new DownloadedWordbook(id, name, version, items);
    }

    private static void requireWordbookProtocol(JSONObject response) {
        if (!WORDBOOK_PROTOCOL.equals(response.optString("protocol"))) throw new IllegalStateException("云端词库协议版本不兼容");
    }

    public static DesktopPresence desktopPresence(SyncConfig config) throws Exception {
        JSONObject response = request(
                config.serverUrl + "/v1/devices/" + config.deviceId + "/clients",
                "GET",
                null,
                "Authorization",
                "Bearer " + config.readToken,
                12_000
        );
        if (!"linguabridge-memory/1".equals(response.optString("protocol"))) {
            throw new IllegalStateException("服务器版本不兼容");
        }
        long serverTime = response.optLong("serverTime", System.currentTimeMillis());
        JSONArray clients = response.optJSONArray("clients");
        List<DesktopClient> result = new ArrayList<>();
        if (clients == null) return new DesktopPresence(serverTime, result, 0);
        int online = 0;
        for (int index = 0; index < clients.length(); index++) {
            JSONObject client = clients.optJSONObject(index);
            if (client == null) continue;
            long lastSeenAt = client.optLong("lastSeenAt", 0);
            boolean active = Math.abs(serverTime - lastSeenAt) < 5 * 60_000;
            if (active) online++;
            result.add(new DesktopClient(
                    client.optString("name", "电脑"),
                    client.optString("platform", ""),
                    client.optString("appVersion", ""),
                    lastSeenAt,
                    active
            ));
        }
        return new DesktopPresence(serverTime, result, online);
    }

    private static String normalizeServerUrl(String raw) throws Exception {
        URL url = new URL(raw.trim());
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("同步服务器必须使用 HTTPS");
        }
        if (url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) {
            throw new IllegalArgumentException("服务器地址不能包含账号或查询参数");
        }
        String result = url.toString();
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    private static JSONObject request(
            String url,
            String method,
            JSONObject body,
            String headerName,
            String headerValue,
            int readTimeout
    ) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(readTimeout);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty(headerName, headerValue);
        if (body != null) {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = readLimited(stream);
        connection.disconnect();
        JSONObject response = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        if (status >= 400) {
            throw new IllegalStateException(response.optString("error", "服务器错误 " + status));
        }
        return response;
    }

    private static String readLimited(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("服务器响应过大");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
