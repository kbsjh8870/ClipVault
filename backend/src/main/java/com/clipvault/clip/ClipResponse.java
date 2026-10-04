package com.clipvault.clip;

import com.clipvault.common.AesCipher;
import java.time.Instant;
import java.util.UUID;

/**
 * 클립 API 응답이자 WebSocket 푸시 메시지.
 * content는 옛 행이면 평문, e2e = 종단간 암호화 행이면 base64 암호문(앱이 푼다). 이미지 클립은 content에 "[이미지 W×H]" 안내 문구가 들어가고
 * width/height/size가 채워진다 (텍스트 클립은 null). pinned = 즐겨찾기(고정) 여부.
 */
public record ClipResponse(UUID id, ClipType type, String content, String contentHash, UUID sourceDeviceId,
                           Instant createdAt, Instant expiresAt, Integer width, Integer height, Long size,
                           boolean pinned, boolean e2e) {

    /** 엔티티 + 응답에 담을 content로 응답을 만든다. */
    public static ClipResponse of(Clip c, String content) {
        return new ClipResponse(c.getId(), c.getType(), content, c.getContentHash(), c.getSourceDeviceId(),
                c.getCreatedAt(), c.getExpiresAt(), c.getWidth(), c.getHeight(), c.getSize(),
                c.isPinned(), c.isE2e());
    }

    /** DB 행 → 응답. e2e 행은 저장된 암호문 그대로, 옛 행은 서버 키로 복호화한 평문. */
    public static ClipResponse of(Clip c, AesCipher cipher) {
        return of(c, c.isE2e() ? c.getContent() : cipher.decrypt(c.getContent()));
    }
}
