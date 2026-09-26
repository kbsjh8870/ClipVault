package com.clipvault.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 해시 계산 유틸.
 *
 * <p>이 프로젝트에서 두 군데에 쓴다.</p>
 * <ul>
 *   <li><b>클립 중복 판별</b>: 클립 내용은 DB에 암호화되어 저장되는데, 암호문은 매번 달라서 비교할 수 없다.
 *       그래서 원문의 해시(contentHash)를 따로 저장해 두고 "같은 내용인가?"를 해시끼리 비교한다.</li>
 *   <li><b>refresh 토큰 저장</b>: refresh 토큰 원문 대신 해시만 DB에 저장한다.
 *       DB가 유출되더라도 해시로는 토큰을 복원할 수 없으므로 더 안전하다.</li>
 * </ul>
 */
public final class HashUtil {

    /** 객체를 만들 필요가 없는 유틸 클래스라 생성자를 막아 둔다. */
    private HashUtil() {
    }

    /**
     * 문자열(UTF-8)의 SHA-256.
     */
    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 바이트 배열의 SHA-256을 소문자 16진수로. 이미지 중복 판정에 쓴다.
     */
    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 자바 런타임에 반드시 포함되어 있어서 실제로는 발생하지 않는다.
            throw new IllegalStateException(e);
        }
    }
}
