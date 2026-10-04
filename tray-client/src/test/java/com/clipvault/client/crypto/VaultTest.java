package com.clipvault.client.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class VaultTest {
    @Test
    void sealOpenAndHash() {
        Vault v = new Vault("test.vault." + System.nanoTime());
        assertFalse(v.ready());
        byte[] vk = VaultCrypto.newVaultKey();
        v.unlockInMemory(vk, 3);
        assertTrue(v.ready());
        assertEquals(3, v.version());
        assertEquals("안녕 hello", v.openText(v.sealText("안녕 hello")));
        byte[] png = {1, 2, 3};
        assertArrayEquals(png, v.open(v.seal(png, Vault.IMAGE), Vault.IMAGE));
        assertThrows(IllegalArgumentException.class, () -> v.open(v.seal(png, Vault.IMAGE), Vault.THUMB));
        assertEquals(VaultCrypto.hmacHex(VaultCrypto.subKey(vk, VaultCrypto.HASH), "x".getBytes(StandardCharsets.UTF_8)),
                v.hash("x".getBytes(StandardCharsets.UTF_8)));
        v.lock();
        assertFalse(v.ready());
    }

    /** DPAPI는 윈도우에만 있다 (CI는 리눅스라 건너뜀) */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void persistsWithDpapi() {
        String key = "test.vault." + System.nanoTime();
        Vault a = new Vault(key);
        byte[] vk = VaultCrypto.newVaultKey();
        a.unlock(vk, 7);
        Vault b = new Vault(key);
        assertTrue(b.load());
        assertEquals(7, b.version());
        assertArrayEquals(vk, b.vaultKey());
        b.lock();
        assertFalse(new Vault(key).load(), "lock() removes the stored key");
    }
}
