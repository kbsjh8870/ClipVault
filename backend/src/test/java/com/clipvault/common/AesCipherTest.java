package com.clipvault.common;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AesCipherTest {
    static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void roundTripAndNonDeterministic() {
        AesCipher cipher = new AesCipher(KEY);
        String plain = "hello 클립보드 \n\t!";
        String c1 = cipher.encrypt(plain);
        String c2 = cipher.encrypt(plain);

        assertNotEquals(plain, c1);
        assertNotEquals(c1, c2, "same plaintext must yield different ciphertext (random IV)");
        assertEquals(plain, cipher.decrypt(c1));
        assertEquals(plain, cipher.decrypt(c2));
        // base64 with 12-byte IV prefix + 16-byte GCM tag
        assertTrue(Base64.getDecoder().decode(c1).length >= 12 + 16);
    }
}
