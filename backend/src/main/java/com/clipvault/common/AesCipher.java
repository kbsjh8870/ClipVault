package com.clipvault.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 클립 내용을 DB에 저장하기 전에 암호화하고, 꺼낼 때 복호화하는 클래스.
 *
 * <p><b>방식: AES-256-GCM</b></p>
 * <ul>
 *   <li>AES-256: 32바이트(256비트) 키를 쓰는 대칭키 암호. 같은 키로 암호화/복호화한다.</li>
 *   <li>GCM 모드: 암호화와 동시에 "인증 태그"를 만들어서, 누군가 DB의 암호문을 몰래 바꾸면
 *       복호화 단계에서 바로 오류가 나도록 해 준다(위변조 감지).</li>
 * </ul>
 *
 * <p><b>저장 형식</b>: {@code base64( IV 12바이트 + 암호문 + 인증태그 16바이트 )}</p>
 * <ul>
 *   <li>IV(초기화 벡터)는 암호화할 때마다 새로 뽑는 랜덤 값이다.
 *       그래서 같은 내용을 두 번 암호화해도 결과가 매번 다르다(패턴 노출 방지).</li>
 *   <li>복호화할 때 IV가 필요하므로 암호문 앞에 붙여서 함께 저장한다. IV는 비밀이 아니라서 괜찮다.</li>
 * </ul>
 *
 * <p>키는 환경변수 {@code CLIP_ENCRYPTION_KEY}(base64로 인코딩한 32바이트)로 주입한다.
 * 키를 잃어버리면 기존 클립은 복호화할 수 없으니 주의.</p>
 */
@Component
public class AesCipher {

    /** GCM에서 권장하는 IV 길이(12바이트). */
    private static final int IV_BYTES = 12;
    /** 인증 태그 길이(128비트 = 16바이트). GCM의 최대이자 권장값. */
    private static final int TAG_BITS = 128;
    /** 예측 불가능한 난수 생성기. IV를 만들 때 쓴다. (일반 Random은 예측 가능해서 암호용으로 부적합) */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /**
     * @param base64Key base64로 인코딩된 32바이트 키. application.yml의 {@code clipvault.crypto.key} 값이 들어온다.
     * @throws IllegalArgumentException 키 길이가 32바이트가 아니면 서버 시작 시점에 바로 실패시킨다(잘못된 설정 조기 발견).
     */
    public AesCipher(@Value("${clipvault.crypto.key}") String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != 32) {
            throw new IllegalArgumentException("clipvault.crypto.key must be base64 of 32 bytes, got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /**
     * 문자열 암호화 → base64(IV ‖ 암호문). DB의 content 컬럼용.
     */
    public String encrypt(String plain) {
        return Base64.getEncoder().encodeToString(encryptBytes(plain.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * {@link #encrypt}의 반대.
     */
    public String decrypt(String cipherText) {
        return new String(decryptBytes(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
    }

    /**
     * 바이트 암호화 → IV(12바이트) ‖ 암호문(GCM 태그 포함). 이미지 파일용 (base64로 늘리지 않고 그대로 저장).
     */
    public byte[] encryptBytes(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain);
            return ByteBuffer.allocate(IV_BYTES + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    /**
     * {@link #encryptBytes}의 반대. 키가 다르거나 내용이 변조되었으면 IllegalStateException.
     */
    public byte[] decryptBytes(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            return cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }
}
