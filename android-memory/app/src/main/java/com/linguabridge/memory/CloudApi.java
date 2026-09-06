package com.linguabridge.memory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class CloudApi {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private CloudApi() {}

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

    public static String desktopSummary(SyncConfig config) throws Exception {
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
        if (clients == null || clients.length() == 0) return "尚未发现在线电脑";
        int online = 0;
        StringBuilder names = new StringBuilder();
        for (int index = 0; index < clients.length(); index++) {
            JSONObject client = clients.optJSONObject(index);
            if (client == null) continue;
            boolean active = Math.abs(serverTime - client.optLong("lastSeenAt", 0)) < 5 * 60_000;
            if (active) online++;
            if (index < 3) {
                if (names.length() > 0) names.append(" · ");
                names.append(client.optString("name", "电脑"));
            }
        }
        return clients.length() + " 台电脑 · " + online + " 台在线\n" + names;
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
