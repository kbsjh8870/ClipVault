package com.clipvault.clip;

import java.time.Instant;
import java.util.UUID;

/**
 * 클립 한 건을 클라이언트에게 보낼 때의 형식.
 * REST 응답 본문과 WebSocket 알림 메시지에 똑같이 쓴다.
 *
 * <p>DB에는 암호문이 저장되어 있지만, 여기의 {@code content}는 항상 복호화된 평문이다.
 * (전송 구간은 HTTPS/WSS로 보호한다는 전제)</p>
 *
 * @param id             클립 ID
 * @param content        클립 내용(평문)
 * @param contentHash    내용의 SHA-256 해시
 * @param sourceDeviceId 복사한 기기 ID - 클라이언트가 자기 기기에서 온 알림을 무시할 때 쓴다
 * @param createdAt      생성(또는 마지막 재복사) 시각, ISO-8601 UTC 문자열로 직렬화된다
 * @param expiresAt      만료 시각
 */
public record ClipResponse(UUID id, String content, String contentHash, UUID sourceDeviceId, Instant createdAt, Instant expiresAt) {
}
