package com.clipvault.common;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** {@link AesCipher} 단위 테스트. 스프링 없이 객체를 직접 만들어서 검사한다. */
class AesCipherTest {
    /** 테스트용 키: base64("0123456789abcdef0123456789abcdef") = 32바이트. */
    static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /**
     * ① 암호화했다가 복호화하면 원문이 그대로 돌아오는지 (한글, 줄바꿈, 탭 포함)
     * ② 같은 원문을 두 번 암호화하면 결과가 서로 다른지 (매번 랜덤 IV를 쓰는지)
     */
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
        // 저장 형식 확인: 최소한 IV(12바이트) + 인증 태그(16바이트)는 들어 있어야 한다
        assertTrue(Base64.getDecoder().decode(c1).length >= 12 + 16);
    }

    /** 바이트 암복호화: 되돌리면 원본, 같은 평문도 매번 다른 암호문(IV 무작위), 길이 = IV 12 + 평문 + 태그 16 */
    @Test
    void bytesRoundTripWithRandomIv() {
        AesCipher cipher = new AesCipher(KEY);
        byte[] plain = {0, 1, 2, (byte) 0xFF, 42};
        byte[] a = cipher.encryptBytes(plain);
        byte[] b = cipher.encryptBytes(plain);
        assertFalse(java.util.Arrays.equals(a, b));
        assertEquals(12 + plain.length + 16, a.length);
        assertArrayEquals(plain, cipher.decryptBytes(a));
    }

    /** 암호문이 1비트라도 바뀌면 복호화가 실패해야 한다 (GCM 무결성 검사) */
    @Test
    void tamperedBytesFailToDecrypt() {
        AesCipher cipher = new AesCipher(KEY);
        byte[] a = cipher.encryptBytes(new byte[]{1, 2, 3});
        a[a.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decryptBytes(a));
    }

    /** 너무 짧은 입력(IV 12 + 태그 16 = 28바이트 미만)은 IllegalStateException을 던진다 */
    @Test
    void tooShortInputThrowsIllegalStateException() {
        AesCipher cipher = new AesCipher(KEY);
        assertThrows(IllegalStateException.class, () -> cipher.decryptBytes(new byte[5]));
    }
}
