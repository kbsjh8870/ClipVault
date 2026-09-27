package com.clipvault.clip;

import com.clipvault.auth.AuthUser;
import com.clipvault.auth.UserRepository;
import com.clipvault.common.AesCipher;
import com.clipvault.common.HashUtil;
import com.clipvault.storage.ImageStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 *   <li>{@code GET /api/clips?limit=20&before=...} - 최근 클립 목록 (before: 더 보기 기준 시각)</li>
 *   <li>{@code GET /api/clips?pinned=true} - 고정한 클립 전체</li>
 *   <li>{@code DELETE /api/clips/{id}} - 클립 한 건 삭제</li>
 *   <li>{@code PUT/DELETE /api/clips/{id}/pin} - 즐겨찾기 고정/해제</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/clips")
public class ClipController {

    /** 업로드 요청. 공백만 있는 문자열은 안 되고, 최대 10만 자. */
    public record UploadRequest(@NotBlank @Size(max = 100_000) String content) {
    }

    /** 사용자당 고정할 수 있는 클립 수. 고정한 클립은 만료되지 않으므로 무한정 쌓이지 않게 막는다. */
    static final int MAX_PINNED = 10;
    /** before를 안 줬을 때 쓰는 "아주 먼 미래" (= 제한 없음). DB 타임스탬프가 담을 수 있는 범위 안의 값. */
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T00:00:00Z");

    private final ClipRepository clips;
    private final AesCipher cipher;
    /** WebSocket(STOMP)으로 메시지를 보내는 스프링 도구. 특정 topic을 구독 중인 모든 연결에 메시지를 뿌린다. */
    private final SimpMessagingTemplate messaging;
    /** 사용자별 클립 보관 기간(기본 7일)을 읽는 데 쓴다. */
    private final UserRepository users;
    private final ImageStore store;

    public ClipController(ClipRepository clips, AesCipher cipher, SimpMessagingTemplate messaging,
                          UserRepository users, ImageStore store) {
        this.clips = clips;
        this.cipher = cipher;
        this.messaging = messaging;
        this.users = users;
        this.store = store;
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
        Duration ttl = users.clipTtl(me.userId()); // 사용자가 고른 보관 기간
        // 직전 클립과 비교 (전체 이력이 아니라 "가장 최근 1건"과만 비교한다)
        Clip latest = clips.findFirstByUserIdOrderByCreatedAtDesc(me.userId()).orElse(null);
        boolean duplicate = latest != null && latest.getContentHash().equals(hash);
        Clip clip;
        if (duplicate) {
            // 같은 내용 재복사: 시각과 복사한 기기만 갱신
            latest.refresh(me.deviceId(), now, now.plus(ttl));
            clip = clips.save(latest);
        } else {
            // 새 내용: 암호화해서 저장. 만료 시각 = 지금 + 보관 기간(사용자 설정, 기본 7일)
            clip = clips.save(new Clip(me.userId(), me.deviceId(), cipher.encrypt(req.content()), hash, now, now.plus(ttl)));
        }
        // 응답과 알림에는 평문을 담는다 (방금 받은 원문을 그대로 쓰므로 다시 복호화할 필요 없음)
        ClipResponse body = ClipResponse.of(clip, req.content());
        // 같은 사용자의 모든 기기에 실시간 알림 (중복 재복사도 알림을 보낸다 → 다른 기기 목록에서 맨 위로 올라가도록)
        messaging.convertAndSend("/topic/clips/" + me.userId(), body);
        return ResponseEntity.status(duplicate ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    /**
     * 최근 클립 목록 (최신순, 만료된 것 제외. 고정한 클립은 만료 시각이 지나도 포함).
     *
     * @param limit  가져올 개수. 기본 20. 너무 작거나 크게 보내도 1~100 사이로 잘라서(clamp) 쓴다.
     * @param before 이 시각보다 먼저 만들어진 것만 ("더 보기": 앞 페이지 마지막 항목의 createdAt). 없으면 제한 없음.
     * @param pinned true면 limit/before를 무시하고 고정한 클립 전체(최대 10개)를 돌려준다.
     */
    @GetMapping
    public List<ClipResponse> list(@AuthenticationPrincipal AuthUser me, @RequestParam(defaultValue = "20") int limit,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
                                   @RequestParam(defaultValue = "false") boolean pinned) {
        List<Clip> found = pinned
                ? clips.findByUserIdAndPinnedTrueOrderByCreatedAtDesc(me.userId())
                : clips.findVisible(me.userId(), Instant.now(), before == null ? FAR_FUTURE : before, Limit.of(Math.clamp(limit, 1, 100)));
        // DB의 암호문을 평문으로 복호화해서 응답
        return found.stream().map(c -> ClipResponse.of(c, cipher.decrypt(c.getContent()))).toList();
    }

    /**
     * 즐겨찾기 고정. 204 No Content. 이미 고정돼 있으면 아무것도 안 하고 204.
     *
     * @throws ResponseStatusException 404 클립이 없거나 다른 사람의 클립,
     *         400 이미지 클립 (버킷 수명 주기 규칙이 파일을 지워서 고정해도 이미지가 사라진다),
     *         409 이미 10개를 고정함
     */
    // ponytail(의도적 단순화): 개수 확인과 저장 사이에 잠금이 없어 두 기기에서 동시에 고정하면 11개가 될 수 있다. 해가 없어 그대로 둔다.
    @PutMapping("/{id}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pin(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        Clip clip = owned(me, id);
        if (clip.isPinned()) return;
        if (clip.getType() == ClipType.IMAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image clips cannot be pinned");
        }
        if (clips.countByUserIdAndPinnedTrue(me.userId()) >= MAX_PINNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "At most " + MAX_PINNED + " clips can be pinned");
        }
        clip.pin();
        clips.save(clip);
    }

    /** 고정 해제. 204 No Content. 만료 시각은 지금부터 보관 기간(사용자 설정) 뒤로 다시 잡는다. 404는 {@link #pin}과 같다. */
    @DeleteMapping("/{id}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpin(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        Clip clip = owned(me, id);
        clip.unpin(Instant.now().plus(users.clipTtl(me.userId())));
        clips.save(clip);
    }

    /** 내 클립을 찾는다. 없거나 남의 것이면 404 (남의 클립이 "존재한다"는 사실도 알려 주지 않는다). */
    private Clip owned(AuthUser me, UUID id) {
        return clips.findByIdAndUserId(id, me.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clip not found"));
    }

    /**
     * 클립 한 건 삭제. 204 No Content.
     *
     * <p><b>이미지 클립 처리</b>: 이미지 클립이면 DB 행을 삭제할 때 함께 버킷 객체(원본, 썸네일)도 지운다.
     * 저장소 작업이 실패해도 행 삭제는 유지되고, 남은 객체는 버킷 수명 주기 규칙이 정리한다.</p>
     *
     * @throws ResponseStatusException 404 클립이 없거나 다른 사람의 클립
     *         (다른 사람의 클립이 "존재한다"는 사실조차 알려 주지 않기 위해 403 대신 404를 쓴다)
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        Clip clip = owned(me, id);
        clips.delete(clip);
        // 이미지 클립이면 버킷의 원본·썸네일도 지운다 (실패해도 행 삭제는 유지, 남은 객체는 수명 주기 규칙이 정리)
        if (clip.getImageKey() != null) ImageClipController.deleteQuietly(store, clip.getImageKey());
    }

}
