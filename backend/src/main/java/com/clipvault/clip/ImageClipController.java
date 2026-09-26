package com.clipvault.clip;

import com.clipvault.auth.AuthUser;
import com.clipvault.common.AesCipher;
import com.clipvault.common.HashUtil;
import com.clipvault.storage.ImageStore;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 이미지 클립 API.
 *
 * <ul>
 *   <li>POST /api/clips/image : 본문 = PNG 바이트. 검사 → 썸네일 생성 → 암호화해 버킷 저장 → DB 저장 → 푸시</li>
 *   <li>GET /api/clips/{id}/image, /thumbnail : 버킷에서 꺼내 복호화한 PNG</li>
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
    private final Duration ttl;

    public ImageClipController(ClipRepository clips, AesCipher cipher, ImageStore store, SimpMessagingTemplate messaging,
                               @Value("${clipvault.clip.ttl}") Duration ttl) {
        this.clips = clips;
        this.cipher = cipher;
        this.store = store;
        this.messaging = messaging;
        this.ttl = ttl;
    }

    /** 이미지 업로드. 신규 201, 가장 최근 클립과 같은 이미지면 시각만 갱신하고 200. */
    // ponytail(의도적 단순화): 중복 확인(조회)과 저장 사이에 잠금이 없다. 똑같은 이미지가 정확히 동시에 두 번 올라오면
    // 둘 다 새 클립으로 저장될 수 있다. 클립보드 특성상 거의 일어나지 않고 일어나도 해가 없어서 그대로 둔다.
    @PostMapping(path = "/image", consumes = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<ClipResponse> upload(@AuthenticationPrincipal AuthUser me, HttpServletRequest req) throws IOException {
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

        // 3. 중복: 가장 최근 클립과 같은 이미지면 시각만 갱신 (버킷 작업 없음)
        String hash = HashUtil.sha256(png);
        Instant now = Instant.now();
        Clip latest = clips.findFirstByUserIdOrderByCreatedAtDesc(me.userId()).orElse(null);
        if (latest != null && latest.getContentHash().equals(hash)) {
            latest.refresh(me.deviceId(), now, now.plus(ttl));
            Clip saved = clips.save(latest);
            return push(me, ClipResponse.of(saved, cipher.decrypt(saved.getContent())), HttpStatus.OK);
        }

        // 4. 썸네일을 만들고 원본·썸네일을 암호화해서 버킷에 저장
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body must be a PNG image");
        byte[] thumb = ImageBytes.thumbnail(img, THUMB_SIZE);
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
                    dim.width, dim.height, png.length, now, now.plus(ttl)));
        } catch (RuntimeException e) {
            deleteQuietly(store, key);
            throw e;
        }
        return push(me, ClipResponse.of(saved, label), HttpStatus.CREATED);
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

    /** 내 이미지 클립의 객체를 복호화해서 돌려준다. 남의 클립, 텍스트 클립, 객체 없음 → 404. */
    private byte[] load(AuthUser me, UUID id, String prefix) {
        Clip c = clips.findByIdAndUserId(id, me.userId())
                .filter(x -> x.getImageKey() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));
        byte[] data = store.get(prefix + c.getImageKey());
        if (data == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
        return cipher.decryptBytes(data);
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
