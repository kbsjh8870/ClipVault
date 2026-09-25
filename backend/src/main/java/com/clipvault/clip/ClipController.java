package com.clipvault.clip;

import com.clipvault.auth.AuthUser;
import com.clipvault.common.AesCipher;
import com.clipvault.common.HashUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 클립 API: 업로드, 목록 조회, 삭제. 모든 API는 device 토큰이 필요하다(SecurityConfig).
 *
 * <ul>
 *   <li>{@code POST /api/clips} - 복사한 텍스트 업로드 + 다른 기기에 실시간 알림</li>
 *   <li>{@code GET /api/clips?limit=20} - 최근 클립 목록</li>
 *   <li>{@code DELETE /api/clips/{id}} - 클립 한 건 삭제</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/clips")
public class ClipController {

    /** 업로드 요청. 공백만 있는 문자열은 안 되고, 최대 10만 자. */
    public record UploadRequest(@NotBlank @Size(max = 100_000) String content) {
    }

    private final ClipRepository clips;
    private final AesCipher cipher;
    /** WebSocket(STOMP)으로 메시지를 보내는 스프링 도구. 특정 topic을 구독 중인 모든 연결에 메시지를 뿌린다. */
    private final SimpMessagingTemplate messaging;
    /** 클립 보관 기간 (기본 7일, application.yml의 clipvault.clip.ttl). */
    private final Duration ttl;

    public ClipController(ClipRepository clips, AesCipher cipher, SimpMessagingTemplate messaging,
                          @Value("${clipvault.clip.ttl}") Duration ttl) {
        this.clips = clips;
        this.cipher = cipher;
        this.messaging = messaging;
        this.ttl = ttl;
    }

    /**
     * 클립 업로드. 트레이 앱이 Ctrl+C를 감지하면 호출한다.
     *
     * <p><b>중복 처리</b>: 사용자의 가장 최근 클립과 내용(해시)이 같으면 새로 만들지 않고 시각만 갱신한다 → 200 OK.
     * 다르면 새 클립을 만든다 → 201 Created.
     * 같은 텍스트를 여러 번 Ctrl+C 해도 목록에 한 줄만 남게 하기 위함이다.</p>
     *
     * <p><b>실시간 알림</b>: 저장 후 {@code /topic/clips/{userId}}로 클립을 보낸다.
     * 같은 사용자의 모든 기기가 이 topic을 구독하고 있으므로 다른 PC들이 즉시 알림을 받는다.
     * (올린 기기 자신도 받지만, sourceDeviceId를 보고 스스로 무시한다.)</p>
     */
    // ponytail(의도적 단순화): 중복 확인(조회)과 저장 사이에 잠금이 없다. 똑같은 내용이 정확히 동시에 두 번 올라오면
    // 둘 다 새 클립으로 저장될 수 있다. 클립보드 특성상 거의 일어나지 않고 일어나도 해가 없어서 그대로 둔다.
    @PostMapping
    public ResponseEntity<ClipResponse> upload(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody UploadRequest req) {
        String hash = HashUtil.sha256(req.content());
        Instant now = Instant.now();
        // 직전 클립과 비교 (전체 이력이 아니라 "가장 최근 1건"과만 비교한다)
        Clip latest = clips.findFirstByUserIdOrderByCreatedAtDesc(me.userId()).orElse(null);
        boolean duplicate = latest != null && latest.getContentHash().equals(hash);
        Clip clip;
        if (duplicate) {
            // 같은 내용 재복사: 시각과 복사한 기기만 갱신
            latest.refresh(me.deviceId(), now, now.plus(ttl));
            clip = clips.save(latest);
        } else {
            // 새 내용: 암호화해서 저장. 만료 시각 = 지금 + 보관기간(7일)
            clip = clips.save(new Clip(me.userId(), me.deviceId(), cipher.encrypt(req.content()), hash, now, now.plus(ttl)));
        }
        // 응답과 알림에는 평문을 담는다 (방금 받은 원문을 그대로 쓰므로 다시 복호화할 필요 없음)
        ClipResponse body = toResponse(clip, req.content());
        // 같은 사용자의 모든 기기에 실시간 알림 (중복 재복사도 알림을 보낸다 → 다른 기기 목록에서 맨 위로 올라가도록)
        messaging.convertAndSend("/topic/clips/" + me.userId(), body);
        return ResponseEntity.status(duplicate ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    /**
     * 최근 클립 목록 (최신순, 만료된 것 제외).
     *
     * @param limit 가져올 개수. 기본 20. 너무 작거나 크게 보내도 1~100 사이로 잘라서(clamp) 쓴다.
     */
    @GetMapping
    public List<ClipResponse> list(@AuthenticationPrincipal AuthUser me, @RequestParam(defaultValue = "20") int limit) {
        return clips.findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(me.userId(), Instant.now(), Limit.of(Math.clamp(limit, 1, 100)))
                // DB의 암호문을 평문으로 복호화해서 응답
                .stream().map(c -> toResponse(c, cipher.decrypt(c.getContent()))).toList();
    }

    /**
     * 클립 한 건 삭제. 204 No Content.
     *
     * @throws ResponseStatusException 404 클립이 없거나 다른 사람의 클립
     *         (다른 사람의 클립이 "존재한다"는 사실조차 알려 주지 않기 위해 403 대신 404를 쓴다)
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        clips.delete(clips.findByIdAndUserId(id, me.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clip not found")));
    }

    /** 엔티티 + 평문 → 응답 DTO. 엔티티의 content는 암호문이라서 평문을 따로 받는다. */
    private static ClipResponse toResponse(Clip c, String plain) {
        return new ClipResponse(c.getId(), plain, c.getContentHash(), c.getSourceDeviceId(), c.getCreatedAt(), c.getExpiresAt());
    }
}
