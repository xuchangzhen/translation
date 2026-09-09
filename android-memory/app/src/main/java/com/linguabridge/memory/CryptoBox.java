package com.linguabridge.memory;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoBox {
    private CryptoBox() {}

    public static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return encode(bytes);
    }

    public static JSONObject decrypt(JSONObject envelope, String encodedKey) throws Exception {
        return new JSONObject(decryptToString(
                envelope.optString("algorithm"),
                envelope.optString("nonce"),
                envelope.optString("ciphertext"),
                encodedKey
        ));
    }

    public static JSONObject encrypt(String plaintext, String encodedKey) throws Exception {
        byte[] key = decode(encodedKey);
        if (key.length != 32) throw new IllegalArgumentException("Invalid encryption key");
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("algorithm", "A256GCM")
                .put("nonce", encode(nonce)).put("ciphertext", encode(ciphertext));
    }

    public static String decryptToString(
            String algorithm,
            String encodedNonce,
            String encodedCiphertext,
            String encodedKey
    ) throws Exception {
        if (!"A256GCM".equals(algorithm)) {
            throw new IllegalArgumentException("Unsupported encryption algorithm");
        }
        byte[] key = decode(encodedKey);
        byte[] nonce = decode(encodedNonce);
        byte[] combined = decode(encodedCiphertext);
        if (key.length != 32 || nonce.length != 12 || combined.length < 17) {
            throw new IllegalArgumentException("Invalid encrypted batch");
        }
        byte[] encrypted = Arrays.copyOfRange(combined, 0, combined.length - 16);
        byte[] tag = Arrays.copyOfRange(combined, combined.length - 16, combined.length);
        byte[] cipherInput = new byte[encrypted.length + tag.length];
        System.arraycopy(encrypted, 0, cipherInput, 0, encrypted.length);
        System.arraycopy(tag, 0, cipherInput, encrypted.length, tag.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce)
        );
        byte[] plaintext = cipher.doFinal(cipherInput);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    public static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
