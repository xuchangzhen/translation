package com.linguabridge.memory;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CryptoBoxTest {
    @Test
    public void decryptsDesktopNodeAesGcmEnvelope() throws Exception {
        String plaintext = CryptoBox.decryptToString(
                "A256GCM",
                "ICEiIyQlJicoKSor",
                "qRjVEwT9d29MGTC9qHea2-p4wL7u9AaDH9RWSTKoOHsa4JEmtAdWu3eVC7B0K32pgUHOtLjbkfIrz-c_Me93UkDkwcIKRMiqGqh9GOzRPw",
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        );
        assertEquals(
                "{\"schemaVersion\":1,\"items\":[{\"front\":\"cache\",\"back\":\"缓存\"}]}",
                plaintext
        );
    }
}
