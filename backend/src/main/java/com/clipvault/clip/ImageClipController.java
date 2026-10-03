package com.clipvault.clip;

import com.clipvault.auth.AuthUser;
import com.clipvault.auth.UserRepository;
import com.clipvault.common.AesCipher;
import com.clipvault.common.HashUtil;
import com.clipvault.storage.ImageStore;
import com.clipvault.vault.VaultGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.Dimension;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 이미지 클립 API.
 *
 * <ul>
 *   <li>POST /api/clips/image (image/png) : 옛 방식(볼트 없음). 본문 = PNG 바이트. 검사 → 썸네일 생성 → 암호화해 버킷 저장 → DB 저장 → 푸시</li>
 *   <li>POST /api/clips/image (multipart) : e2e. 앱이 암호화한 원본·썸네일을 그대로 버킷 저장 → DB 저장 → 푸시</li>
 *   <li>GET /api/clips/{id}/image, /thumbnail : 버킷에서 꺼낸 이미지 (옛 행은 서버가 복호화한 PNG, e2e 행은 암호문 그대로)</li>
 * </ul>
 *
 * <p>목록, 삭제, 만료는 텍스트와 같은 {@link ClipController}, {@link ClipCleanupJob}이 처리한다.</p>
 */
@RestController
@RequestMapping("/api/clips")
public class ImageClipController {
    private static final Logger log = LoggerFactory.getLogger(ImageClipController.class);

    /** 이미지 한 장 최대 크기 (PNG 바이트) */
    static final long MAX_BYTES = 10L * 1024 * 1024;
    /** 최대 픽셀 수. 작은 파일이 풀면 수 GB가 되는 압축 폭탄을 막는다 */
    static final long MAX_PIXELS = 50_000_000L;
    /** 썸네일 긴 변 (px) */
    static final int THUMB_SIZE = 240;
    /** 버킷 객체 이름 앞부분 */
    public static final String IMAGES = "images/";
    public static final String THUMBS = "thumbs/";

    private final ClipRepository clips;
    private final AesCipher cipher;
    private final ImageStore store;
    private final SimpMessagingTemplate messaging;
    private final UserRepository users;
    /** 업로드가 e2e인지 옛 방식인지 판정 (X-Vault-Version 헤더). */
    private final VaultGuard vaultGuard;

    public ImageClipController(ClipRepository clips, AesCipher cipher, ImageStore store, SimpMessagingTemplate messaging,
                               UserRepository users, VaultGuard vaultGuard) {
        this.vaultGuard = vaultGuard;
        this.clips = clips;
        this.cipher = cipher;
        this.store = store;
        this.messaging = messaging;
        this.users = users;
    }

    /** 이미지 업로드. 신규 201, 가장 최근 클립과 같은 이미지면 시각만 갱신하고 200. */
    // ponytail(의도적 단순화): 중복 확인(조회)과 저장 사이에 잠금이 없다. 똑같은 이미지가 정확히 동시에 두 번 올라오면
    // 둘 다 새 클립으로 저장될 수 있다. 클립보드 특성상 거의 일어나지 않고 일어나도 해가 없어서 그대로 둔다.
    @PostMapping(path = "/image", consumes = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<ClipResponse> upload(@AuthenticationPrincipal AuthUser me, HttpServletRequest req,
                                               @RequestHeader(value = VaultGuard.HEADER, required = false) Integer vaultVersion)
            throws IOException {
        // 옛 방식 전용 경로. 볼트가 있으면 426, e2e 앱은 multipart 경로를 써야 한다
        if (vaultGuard.e2e(me, vaultVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use multipart upload for end-to-end encrypted images");
        }
        // 1. 크기: 본문을 읽기 전에 Content-Length로 거절하고, 읽을 때도 한도+1바이트까지만 읽는다
        long length = req.getContentLengthLong();
        if (length < 0 || length > MAX_BYTES) throw tooLarge();
        byte[] png = req.getInputStream().readNBytes((int) MAX_BYTES + 1);
        if (png.length > MAX_BYTES) throw tooLarge();

        // 2. 형식·픽셀 수: 헤더만 읽어서 확인 (픽셀을 풀기 전에)
        Dimension dim;
        try {
            dim = ImageBytes.dimensions(png);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body must be a PNG image");
        }
        if ((long) dim.width * dim.height > MAX_PIXELS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image has too many pixels");
        }

        // 3. 중복: 가장 최근 클립과 같은 이미지면 시각만 갱신
        String hash = HashUtil.sha256(png);
        Instant now = Instant.now();
        Clip latest = clips.findFirstByUserIdOrderByCreatedAtDesc(me.userId()).orElse(null);
        if (latest != null && latest.getContentHash().equals(hash) && renewObjects(latest)) {
            latest.refresh(me.deviceId(), now, now.plus(users.clipTtl(me.userId())));
            Clip saved = clips.save(latest);
            return push(me, ClipResponse.of(saved, cipher), HttpStatus.OK);
        }

        // 4. 썸네일을 만들고 원본·썸네일을 암호화해서 버킷에 저장.
        // 썸네일은 서브샘플링으로 작게만 풀어서 만든다 (전체를 풀면 16비트 PNG에서 수백 MB가 된다)
        byte[] thumb;
        try {
            thumb = ImageBytes.thumbnail(png, THUMB_SIZE);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body must be a PNG image");
        }
        String key = UUID.randomUUID().toString();
        try {
            store.put(IMAGES + key, cipher.encryptBytes(png));
            store.put(THUMBS + key, cipher.encryptBytes(thumb));
        } catch (RuntimeException e) {
            log.error("Image storage failed", e);
            deleteQuietly(store, key);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage unavailable");
        }

        // 5. DB 저장. 실패하면 방금 올린 객체를 지워 고아 파일을 남기지 않는다
        String label = "[이미지 " + dim.width + "×" + dim.height + "]";
        Clip saved;
        try {
            saved = clips.save(Clip.image(me.userId(), me.deviceId(), cipher.encrypt(label), hash, key,
                    dim.width, dim.height, png.length, now, now.plus(users.clipTtl(me.userId()))));
        } catch (RuntimeException e) {
            deleteQuietly(store, key);
            throw e;
        }
        return push(me, ClipResponse.of(saved, label), HttpStatus.CREATED);
    }

    /**
     * e2e 이미지 업로드 (multipart: image·thumb = 앱이 암호화한 바이트, width, height, contentHash = HMAC hex).
     * 서버는 PNG 검사·썸네일 생성·암호화를 하지 않고 받은 바이트를 그대로 버킷에 저장한다(풀 수 없으므로).
     * 저장 순서와 실패 처리(버킷 먼저, DB 실패 시 객체 삭제, 503)는 옛 방식과 같다.
     */
    @PostMapping(path = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ClipResponse> uploadE2e(@AuthenticationPrincipal AuthUser me,
                                                  @RequestHeader(value = VaultGuard.HEADER, required = false) Integer vaultVersion,
                                                  @RequestPart("image") MultipartFile image, @RequestPart("thumb") MultipartFile thumb,
                                                  @RequestParam int width, @RequestParam int height,
                                                  @RequestParam String contentHash) {
        if (!vaultGuard.e2e(me, vaultVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End-to-end upload requires a vault");
        }
        E2eInput.hash(contentHash);
        E2eInput.dimensions(width, height);
        byte[][] parts = E2eInput.image(image, thumb);
        Instant now = Instant.now();
        Duration ttl = users.clipTtl(me.userId());
        // 중복: 가장 최근 클립과 같은 이미지(같은 HMAC)면 객체를 다시 써서 시각만 갱신
        Clip latest = clips.findFirstByUserIdOrderByCreatedAtDesc(me.userId()).orElse(null);
        if (latest != null && latest.getContentHash().equals(contentHash) && renewObjects(latest)) {
            latest.refresh(me.deviceId(), now, now.plus(ttl));
            return push(me, ClipResponse.of(clips.save(latest), cipher), HttpStatus.OK);
        }
        String key = UUID.randomUUID().toString();
        try {
            store.put(IMAGES + key, parts[0]);
            store.put(THUMBS + key, parts[1]);
        } catch (RuntimeException e) {
            log.error("Image storage failed", e);
            deleteQuietly(store, key);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage unavailable");
        }
        Clip saved;
        try {
            // content는 빈 문자열(가로세로는 컬럼에), 크기는 PNG 크기 = 암호문 - 28
            Clip c = Clip.image(me.userId(), me.deviceId(), "", contentHash, key, width, height,
                    parts[0].length - E2eInput.OVERHEAD, now, now.plus(ttl));
            c.markE2e();
            saved = clips.save(c);
        } catch (RuntimeException e) {
            deleteQuietly(store, key);
            throw e;
        }
        return push(me, ClipResponse.of(saved, ""), HttpStatus.CREATED);
    }

    /** 원본 PNG */
    @GetMapping(path = "/{id}/image", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] image(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        return load(me, id, IMAGES);
    }

    /** 썸네일 PNG (긴 변 최대 240px) */
    @GetMapping(path = "/{id}/thumbnail", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] thumbnail(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        return load(me, id, THUMBS);
    }

    /**
     * 중복 업로드로 만료를 늘릴 때, 버킷 객체도 다시 써서 생성 시각을 새로 한다.
     * 텍스트 클립은 할 일이 없어 true. 객체가 이미 없으면 false → 새 클립(새 키)으로 저장하게 한다.
     *
     * @throws ResponseStatusException 503 저장소 오류
     */
    private boolean renewObjects(Clip c) {
        if (c.getImageKey() == null) return true;
        try {
            return renew(store, c.getImageKey());
        } catch (RuntimeException e) {
            log.error("Image storage failed", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage unavailable");
        }
    }

    /**
     * 이미지 클립의 두 객체(원본, 썸네일)를 같은 내용으로 다시 써서 버킷의 생성 시각을 지금으로 바꾼다.
     * 버킷 수명 주기 규칙은 객체 생성 31일 뒤에 지우므로(최대 보관 기간 30일 + 하루),
     * 클립을 더 오래 보여 줘야 할 때(중복 업로드, 고정, 고정 해제) 부른다.
     *
     * @return 두 객체가 있어서 다시 썼으면 true, 하나라도 이미 없으면 false (아무것도 쓰지 않음)
     * @throws RuntimeException 저장소 오류 (호출한 쪽이 처리)
     */
    public static boolean renew(ImageStore store, String imageKey) {
        byte[] image = store.get(IMAGES + imageKey);
        byte[] thumb = store.get(THUMBS + imageKey);
        if (image == null || thumb == null) return false;
        store.put(IMAGES + imageKey, image);
        store.put(THUMBS + imageKey, thumb);
        return true;
    }

    /** 이미지 클립의 두 객체(원본, 썸네일)를 지운다. 실패해도 예외 없이 로그만 (남은 객체는 버킷 수명 주기 규칙이 정리). */
    public static void deleteQuietly(ImageStore store, String imageKey) {
        for (String prefix : new String[]{IMAGES, THUMBS}) {
            try {
                store.delete(prefix + imageKey);
            } catch (RuntimeException e) {
                log.warn("Failed to delete {}{}", prefix, imageKey, e);
            }
        }
    }

    /** 내 이미지 클립의 객체를 돌려준다 (옛 행은 복호화, e2e 행은 그대로). 남의 클립, 텍스트 클립, 객체 없음 → 404. */
    private byte[] load(AuthUser me, UUID id, String prefix) {
        Clip c = clips.findByIdAndUserId(id, me.userId())
                .filter(x -> x.getImageKey() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));
        byte[] data = store.get(prefix + c.getImageKey());
        if (data == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
        // e2e 행은 앱이 암호화한 바이트 그대로 (앱이 푼다), 옛 행은 서버 키로 복호화
        return c.isE2e() ? data : cipher.decryptBytes(data);
    }

    /** 내 기기들에 실시간 알림을 보내고 응답한다. */
    private ResponseEntity<ClipResponse> push(AuthUser me, ClipResponse body, HttpStatus status) {
        messaging.convertAndSend("/topic/clips/" + me.userId(), body);
        return ResponseEntity.status(status).body(body);
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image must be at most 10MB");
    }
}
