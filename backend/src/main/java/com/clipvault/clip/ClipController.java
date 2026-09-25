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

@RestController
@RequestMapping("/api/clips")
public class ClipController {

    public record UploadRequest(@NotBlank @Size(max = 100_000) String content) {
    }

    private final ClipRepository clips;
    private final AesCipher cipher;
    private final SimpMessagingTemplate messaging;
    private final Duration ttl;

    public ClipController(ClipRepository clips, AesCipher cipher, SimpMessagingTemplate messaging,
                          @Value("${clipvault.clip.ttl}") Duration ttl) {
        this.clips = clips;
        this.cipher = cipher;
        this.messaging = messaging;
        this.ttl = ttl;
    }

    // ponytail: dedupe is check-then-act without a lock; two identical concurrent uploads may both insert. Harmless for a clipboard.
    @PostMapping
    public ResponseEntity<ClipResponse> upload(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody UploadRequest req) {
        String hash = HashUtil.sha256(req.content());
        Instant now = Instant.now();
        Clip latest = clips.findFirstByUserIdOrderByCreatedAtDesc(me.userId()).orElse(null);
        boolean duplicate = latest != null && latest.getContentHash().equals(hash);
        Clip clip;
        if (duplicate) {
            latest.refresh(me.deviceId(), now, now.plus(ttl));
            clip = clips.save(latest);
        } else {
            clip = clips.save(new Clip(me.userId(), me.deviceId(), cipher.encrypt(req.content()), hash, now, now.plus(ttl)));
        }
        ClipResponse body = toResponse(clip, req.content());
        messaging.convertAndSend("/topic/clips/" + me.userId(), body);
        return ResponseEntity.status(duplicate ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    @GetMapping
    public List<ClipResponse> list(@AuthenticationPrincipal AuthUser me, @RequestParam(defaultValue = "20") int limit) {
        return clips.findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(me.userId(), Instant.now(), Limit.of(Math.clamp(limit, 1, 100)))
                .stream().map(c -> toResponse(c, cipher.decrypt(c.getContent()))).toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        clips.delete(clips.findByIdAndUserId(id, me.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clip not found")));
    }

    private static ClipResponse toResponse(Clip c, String plain) {
        return new ClipResponse(c.getId(), plain, c.getContentHash(), c.getSourceDeviceId(), c.getCreatedAt(), c.getExpiresAt());
    }
}
