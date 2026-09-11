package com.linguabridge.memory;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores an optional AI API key encrypted with a device-local Android Keystore key. */
public final class AiConfigStore {
    private static final String KEY_ALIAS = "linguabridge-memory-ai-config";
    private static final String PREFS = "secure-ai";
    private static final String CONFIG = "config";
    private final SharedPreferences preferences;

    public AiConfigStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(AiConfig config) throws Exception {
        if (config == null || !config.isConfigured()) throw new IllegalArgumentException("请填写 AI 服务地址和模型名称。");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        JSONObject wrapper = new JSONObject()
                .put("nonce", encode(cipher.getIV()))
                .put("ciphertext", encode(cipher.doFinal(config.toJson().toString().getBytes(StandardCharsets.UTF_8))));
        preferences.edit().putString(CONFIG, wrapper.toString()).apply();
    }

    public synchronized AiConfig load() {
        String stored = preferences.getString(CONFIG, "");
        if (stored == null || stored.isEmpty()) return null;
        try {
            JSONObject wrapper = new JSONObject(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, decode(wrapper.getString("nonce"))));
            AiConfig config = AiConfig.fromJson(new JSONObject(new String(cipher.doFinal(decode(wrapper.getString("ciphertext"))), StandardCharsets.UTF_8)));
            return config.isConfigured() ? config : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private String encode(byte[] value) { return Base64.encodeToString(value, Base64.NO_WRAP); }
    private byte[] decode(String value) { return Base64.decode(value, Base64.NO_WRAP); }
}
