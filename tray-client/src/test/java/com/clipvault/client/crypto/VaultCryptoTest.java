package com.clipvault.client.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class VaultCryptoTest {
    static final char[] PASS = "correct horse".toCharArray();

    @Test
    void wrapUnwrapRoundTrip() {
        byte[] vk = VaultCrypto.newVaultKey();
        assertEquals(32, vk.length);
        VaultCrypto.Wrapped w = VaultCrypto.wrap(vk, PASS, "user-1");
        assertEquals(VaultCrypto.ITERATIONS, w.iterations());
        assertEquals(16, java.util.Base64.getDecoder().decode(w.salt()).length);
        assertEquals(60, java.util.Base64.getDecoder().decode(w.wrappedKey()).length);
        assertArrayEquals(vk, VaultCrypto.unwrap(w, PASS, "user-1"));
    }

    @Test
    void wrongPassphraseOrUserFails() {
        VaultCrypto.Wrapped w = VaultCrypto.wrap(VaultCrypto.newVaultKey(), PASS, "user-1");
        assertThrows(VaultCrypto.BadPassphraseException.class, () -> VaultCrypto.unwrap(w, "wrong pass".toCharArray(), "user-1"));
        assertThrows(VaultCrypto.BadPassphraseException.class, () -> VaultCrypto.unwrap(w, PASS, "user-2"), "AAD binds userId");
    }

    @Test
    void rejectsWeakIterations() {
        VaultCrypto.Wrapped w = VaultCrypto.wrap(VaultCrypto.newVaultKey(), PASS, "u");
        VaultCrypto.Wrapped weak = new VaultCrypto.Wrapped(w.salt(), 99_999, w.wrappedKey());
        assertThrows(IllegalArgumentException.class, () -> VaultCrypto.unwrap(weak, PASS, "u"));
    }

    @Test
    void sealOpenWithAad() {
        byte[] key = VaultCrypto.subKey(VaultCrypto.newVaultKey(), VaultCrypto.ENC);
        byte[] plain = "회의 링크 https://meet".getBytes(StandardCharsets.UTF_8);
        byte[] a = VaultCrypto.seal(key, plain, "text-v1");
        byte[] b = VaultCrypto.seal(key, plain, "text-v1");
        assertEquals(plain.length + 28, a.length);
        assertFalse(java.util.Arrays.equals(a, b), "random IV");
        assertArrayEquals(plain, VaultCrypto.open(key, a, "text-v1"));
        assertThrows(IllegalArgumentException.class, () -> VaultCrypto.open(key, a, "image-v1"), "AAD mismatch");
        a[a.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> VaultCrypto.open(key, a, "text-v1"), "tampered");
    }

    @Test
    void hmacIsDeterministicAndKeyed() {
        byte[] vk = VaultCrypto.newVaultKey();
        byte[] k1 = VaultCrypto.subKey(vk, VaultCrypto.HASH);
        byte[] k2 = VaultCrypto.subKey(VaultCrypto.newVaultKey(), VaultCrypto.HASH);
        byte[] data = "same".getBytes(StandardCharsets.UTF_8);
        String h = VaultCrypto.hmacHex(k1, data);
        assertTrue(h.matches("[0-9a-f]{64}"));
        assertEquals(h, VaultCrypto.hmacHex(k1, data));
        assertNotEquals(h, VaultCrypto.hmacHex(k2, data));
        assertFalse(java.util.Arrays.equals(VaultCrypto.subKey(vk, VaultCrypto.ENC), k1), "enc/hash keys differ");
    }
}
