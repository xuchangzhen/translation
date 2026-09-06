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

public final class SecureStore {
    private static final String KEY_ALIAS = "linguabridge-memory-sync-config";
    private static final String PREFS = "secure-sync";
    private static final String CONFIG = "config";
    private final SharedPreferences preferences;

    public SecureStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(SyncConfig config) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] encrypted = cipher.doFinal(config.toJson().toString().getBytes(StandardCharsets.UTF_8));
        JSONObject wrapper = new JSONObject()
                .put("nonce", encode(cipher.getIV()))
                .put("ciphertext", encode(encrypted));
        preferences.edit().putString(CONFIG, wrapper.toString()).apply();
    }

    public synchronized SyncConfig load() {
        String stored = preferences.getString(CONFIG, "");
        if (stored == null || stored.isEmpty()) return null;
        try {
            JSONObject wrapper = new JSONObject(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    new GCMParameterSpec(128, decode(wrapper.getString("nonce")))
            );
            byte[] plaintext = cipher.doFinal(decode(wrapper.getString("ciphertext")));
            return SyncConfig.fromJson(new JSONObject(new String(plaintext, StandardCharsets.UTF_8)));
        } catch (Exception error) {
            return null;
        }
    }

    public synchronized void clear() {
        preferences.edit().remove(CONFIG).apply();
    }

    private SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private byte[] decode(String value) {
        return Base64.decode(value, Base64.NO_WRAP);
    }
}
