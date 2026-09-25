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
     * 평문을 암호화한다.
     *
     * @param plain 클립 원문
     * @return base64 문자열 (IV + 암호문 + 태그). 같은 평문이라도 호출할 때마다 결과가 다르다.
     */
    public String encrypt(String plain) {
        try {
            // 1) 매번 새로운 랜덤 IV 생성
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            // 2) AES-GCM으로 암호화 (결과 ct에는 암호문 + 인증태그가 함께 들어 있다)
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            // 3) [IV][암호문+태그] 순서로 이어 붙인 뒤 문자열로 저장할 수 있게 base64 인코딩
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(IV_BYTES + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    /**
     * {@link #encrypt}로 만든 문자열을 원래 평문으로 되돌린다.
     *
     * @throws IllegalStateException 키가 다르거나 암호문이 변조되었으면 실패한다.
     */
    public String decrypt(String cipherText) {
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // 앞 12바이트를 IV로 사용
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            // 12바이트 이후 나머지 전체(암호문+태그)를 복호화. 태그가 안 맞으면 여기서 예외 발생
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }
}
