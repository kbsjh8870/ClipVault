package com.clipvault.clip;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * e2e 업로드 입력 검사. 서버는 내용을 볼 수 없으니 형식과 크기만 확인한다.
 * 암호문 형식 = IV 12 + 암호문 + 태그 16 이라 최소 28바이트.
 */
final class E2eInput {
    /** GCM 덧붙는 바이트 (IV 12 + 태그 16) */
    static final int OVERHEAD = 28;
    /** 텍스트 평문 최대 바이트 (10만 자 × UTF-8 최대 3바이트) */
    static final int MAX_TEXT = 300_000;
    /** 썸네일 암호문 최대 바이트 */
    static final long MAX_THUMB = 1024 * 1024;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private E2eInput() {
    }

    /** base64 텍스트 암호문 검사. 디코딩 길이 28 ~ 300,028. 아니면 400. */
    static void text(String b64) {
        int n;
        try {
            n = b64 == null ? -1 : Base64.getDecoder().decode(b64).length;
        } catch (IllegalArgumentException e) {
            n = -1;
        }
        if (n < OVERHEAD || n > MAX_TEXT + OVERHEAD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must be base64 ciphertext");
        }
    }

    /** contentHash = 소문자 hex 64자 (HMAC-SHA256). 아니면 400. */
    static void hash(String h) {
        if (h == null || !HASH.matcher(h).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentHash must be 64 lowercase hex chars");
        }
    }

    /** 가로·세로 검사: 1 이상, 곱 ≤ 5천만. 아니면 400. */
    static void dimensions(int width, int height) {
        if (width < 1 || height < 1 || (long) width * height > ImageClipController.MAX_PIXELS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid image dimensions");
        }
    }

    /** 이미지·썸네일 파트 크기 검사 후 바이트로. {원본, 썸네일}. 넘으면 413, 너무 짧으면 400. */
    static byte[][] image(MultipartFile image, MultipartFile thumb) {
        if (image.getSize() > ImageClipController.MAX_BYTES + OVERHEAD || thumb.getSize() > MAX_THUMB) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image must be at most 10MB");
        }
        if (image.getSize() < OVERHEAD || thumb.getSize() < OVERHEAD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parts must be ciphertext");
        }
        try {
            return new byte[][]{image.getBytes(), thumb.getBytes()};
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
