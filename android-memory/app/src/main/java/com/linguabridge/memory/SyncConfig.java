package com.linguabridge.memory;

import android.net.Uri;

import org.json.JSONObject;

import java.net.URL;
import java.util.Base64;
import java.util.UUID;

public final class SyncConfig {
    public final String serverUrl;
    public final String deviceId;
    public final String uploadToken;
    public final String readToken;
    public final String contentKey;

    public SyncConfig(
            String serverUrl,
            String deviceId,
            String uploadToken,
            String readToken,
            String contentKey
    ) {
        this.serverUrl = serverUrl;
        this.deviceId = deviceId;
        this.uploadToken = uploadToken;
        this.readToken = readToken;
        this.contentKey = contentKey;
    }

    public JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("serverUrl", serverUrl)
                .put("deviceId", deviceId)
                .put("uploadToken", uploadToken)
                .put("readToken", readToken)
                .put("contentKey", contentKey);
    }

    public static SyncConfig fromJson(JSONObject json) {
        return new SyncConfig(
                json.optString("serverUrl"),
                json.optString("deviceId"),
                json.optString("uploadToken"),
                json.optString("readToken"),
                json.optString("contentKey")
        );
    }

    public String pairingUri() {
        return new Uri.Builder()
                .scheme("linguabridge-memory")
                .authority("pair")
                .appendQueryParameter("server", serverUrl)
                .appendQueryParameter("device", deviceId)
                .appendQueryParameter("token", uploadToken)
                .appendQueryParameter("read", readToken)
                .appendQueryParameter("key", contentKey)
                .build()
                .toString();
    }

    public static SyncConfig fromPairingUri(String rawValue) throws Exception {
        Uri uri = Uri.parse(rawValue == null ? "" : rawValue.trim());
        if (!"linguabridge-memory".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) {
            throw new IllegalArgumentException("这不是有效的单词记忆配对二维码");
        }
        String serverUrl = normalizeServerUrl(uri.getQueryParameter("server"));
        String deviceId = value(uri, "device");
        String uploadToken = value(uri, "token");
        String readToken = value(uri, "read");
        String contentKey = value(uri, "key");
        UUID.fromString(deviceId);
        validateSecret(uploadToken, "上传凭据", false);
        validateSecret(readToken, "接收凭据", false);
        validateSecret(contentKey, "内容密钥", true);
        return new SyncConfig(
                serverUrl,
                deviceId,
                uploadToken,
                readToken,
                contentKey
        );
    }

    private static String value(Uri uri, String name) {
        String value = uri.getQueryParameter(name);
        return value == null ? "" : value;
    }

    private static String normalizeServerUrl(String rawValue) throws Exception {
        URL url = new URL(rawValue == null ? "" : rawValue.trim());
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("同步服务器必须使用 HTTPS");
        }
        if (url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) {
            throw new IllegalArgumentException("同步服务器地址无效");
        }
        String result = url.toString();
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    private static void validateSecret(String value, String label, boolean exactLength) {
        try {
            int decodedLength = Base64.getUrlDecoder().decode(value).length;
            if (!value.matches("^[A-Za-z0-9_-]+$") || (exactLength ? decodedLength != 32 : decodedLength < 32)) {
                throw new IllegalArgumentException(label + "无效");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(label + "无效");
        }
    }
}
