package com.clipvault.clip;

import java.time.Instant;
import java.util.UUID;

/**
 * 클립 API 응답이자 WebSocket 푸시 메시지.
 * content는 항상 평문. 이미지 클립은 content에 "[이미지 W×H]" 안내 문구가 들어가고
 * width/height/size가 채워진다 (텍스트 클립은 null).
 */
public record ClipResponse(UUID id, ClipType type, String content, String contentHash, UUID sourceDeviceId,
                           Instant createdAt, Instant expiresAt, Integer width, Integer height, Long size) {

    /** 엔티티 + 복호화한 content로 응답을 만든다. */
    public static ClipResponse of(Clip c, String plain) {
        return new ClipResponse(c.getId(), c.getType(), plain, c.getContentHash(), c.getSourceDeviceId(),
                c.getCreatedAt(), c.getExpiresAt(), c.getWidth(), c.getHeight(), c.getSize());
    }
}
