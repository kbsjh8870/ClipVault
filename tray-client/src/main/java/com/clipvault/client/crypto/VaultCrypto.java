package com.clipvault.client.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 종단간 암호화의 암호 함수 모음 (상태 없음, JDK 표준만 사용).
 *
 * <p><b>키 구조 (봉투 암호화)</b>: 사용자당 무작위 <i>볼트 키</i>(32바이트)가 있고, 클립은 볼트 키에서 만든 암호화 키로 암호화한다.
 * 볼트 키는 볼트 암호에서 PBKDF2로 만든 <i>잠금 키</i>로 감싸서(AES-GCM) 서버에 맡긴다. 그래서 암호를 바꿔도
 * 볼트 키를 다시 감싸기만 하면 되고 클립은 건드리지 않는다. 서버는 볼트 암호를 모르니 감싼 키를 풀 수 없다.</p>
 *
 * <p>암호문 형식은 모두 {@code IV(12바이트) ‖ 암호문 ‖ GCM 태그(16바이트)}. AAD(추가 인증 데이터)로 용도를 묶어서
 * 서버가 원본/썸네일/텍스트나 다른 사용자의 감싼 키를 바꿔치기하면 복호화가 실패하게 한다.</p>
 */
public final class VaultCrypto {
    /** 새 볼트를 만들 때의 PBKDF2 반복 횟수 (2023 OWASP 권장치) */
    public static final int ITERATIONS = 600_000;
    /** 이보다 약한 반복 횟수는 거부한다 (서버가 낮은 값을 내려줘도 따르지 않게) */
    public static final int MIN_ITERATIONS = 100_000;
    /** 하위 키 용도 이름: 클립 암호화 / 중복 판별 해시 */
    public static final String ENC = "clipvault-enc-v1";
    public static final String HASH = "clipvault-hash-v1";

    private static final SecureRandom RNG = new SecureRandom();
    private static final int IV = 12, TAG_BITS = 128;

    /** 서버에 맡기는 값: salt(base64), 반복 횟수, 감싼 볼트 키(base64). */
    public record Wrapped(String salt, int iterations, String wrappedKey) {
    }

    /** 볼트 암호가 틀렸다 (감싼 키의 GCM 태그 검증 실패). */
    public static final class BadPassphraseException extends RuntimeException {
        BadPassphraseException() {
            super("wrong vault passphrase");
        }
    }

    private VaultCrypto() {
    }

    /** 무작위 볼트 키 32바이트. */
    public static byte[] newVaultKey() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return k;
    }

    /** 볼트 키를 볼트 암호로 감싼다 (새 salt, {@link #ITERATIONS}회). AAD = "clipvault-vault-v1:" + userId. */
    public static Wrapped wrap(byte[] vaultKey, char[] passphrase, String userId) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] kek = kek(passphrase, salt, ITERATIONS);
        byte[] sealed = seal(kek, vaultKey, vaultAad(userId));
        Arrays.fill(kek, (byte) 0);
        Base64.Encoder b64 = Base64.getEncoder();
        return new Wrapped(b64.encodeToString(salt), ITERATIONS, b64.encodeToString(sealed));
    }

    /**
     * 감싼 볼트 키를 볼트 암호로 푼다.
     *
     * @throws BadPassphraseException 암호가 틀렸거나 다른 사용자의 값
     * @throws IllegalArgumentException 반복 횟수가 {@link #MIN_ITERATIONS} 미만이거나 형식 오류
     */
    public static byte[] unwrap(Wrapped w, char[] passphrase, String userId) {
        if (w.iterations() < MIN_ITERATIONS) throw new IllegalArgumentException("iterations too low: " + w.iterations());
        byte[] kek = kek(passphrase, Base64.getDecoder().decode(w.salt()), w.iterations());
        try {
            return open(kek, Base64.getDecoder().decode(w.wrappedKey()), vaultAad(userId));
        } catch (IllegalArgumentException e) {
            throw new BadPassphraseException();
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    /** 볼트 키에서 용도별 하위 키 (HMAC-SHA256(볼트 키, 용도 이름)). 한 키를 두 용도로 쓰지 않으려고 나눈다. */
    public static byte[] subKey(byte[] vaultKey, String label) {
        return hmac(vaultKey, label.getBytes(StandardCharsets.UTF_8));
    }

    /** AES-256-GCM 암호화. 결과 = IV ‖ 암호문 ‖ 태그 (평문 + 28바이트). */
    public static byte[] seal(byte[] key, byte[] plain, String aad) {
        try {
            byte[] iv = new byte[IV];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain);
            return ByteBuffer.allocate(IV + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@link #seal}의 반대. 키·AAD가 다르거나 값이 바뀌었으면 IllegalArgumentException. */
    public static byte[] open(byte[] key, byte[] sealed, String aad) {
        if (sealed.length < IV + TAG_BITS / 8) throw new IllegalArgumentException("ciphertext too short");
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, sealed, 0, IV));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(sealed, IV, sealed.length - IV);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("cannot decrypt", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("cannot decrypt", e);
        }
    }

    /** HMAC-SHA256 결과를 소문자 hex 64자로 (중복 판별 해시). */
    public static String hmacHex(byte[] key, byte[] data) {
        return HexFormat.of().formatHex(hmac(key, data));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 볼트 암호 → 잠금 키 (PBKDF2-HMAC-SHA256, 256비트). 0.3~0.5초 걸리므로 화면 스레드에서 부르지 말 것. */
    static byte[] kek(char[] passphrase, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String vaultAad(String userId) {
        return "clipvault-vault-v1:" + userId;
    }
}
