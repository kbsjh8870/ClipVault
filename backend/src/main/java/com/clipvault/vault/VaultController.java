package com.clipvault.vault;

import com.clipvault.auth.AuthUser;
import com.clipvault.auth.User;
import com.clipvault.auth.UserRepository;
import com.clipvault.clip.ClipRepository;
import com.clipvault.clip.ImageClipController;
import com.clipvault.storage.ImageStore;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 볼트 API (device 토큰 필요). 종단간 암호화의 "감싼 볼트 키"를 보관한다.
 *
 * <p>볼트 키는 앱이 만들고, 볼트 암호에서 PBKDF2로 만든 잠금 키로 감싸서(AES-GCM) 보낸다. 서버는 볼트 암호를 모르므로
 * 이 값을 풀 수 없고 그대로 보관·전달만 한다. 새 PC는 이 값을 받아 볼트 암호로 풀어서 볼트 키를 얻는다.</p>
 *
 * <ul>
 *   <li>{@code GET /api/vault} - 200 {salt, iterations, wrappedKey, version} / 없음 404</li>
 *   <li>{@code POST /api/vault} - 처음 만들기 201 {version: 1} / 이미 있음 409</li>
 *   <li>{@code PUT /api/vault} - 암호 변경(다시 감싸기) 204 / version 불일치 409 / 없음 404</li>
 *   <li>{@code POST /api/vault/reset} - 초기화: 클립 전부 삭제 + 새 키, version +1 → 200 {version}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/vault")
public class VaultController {

    /** 요청/응답 본문. version은 PUT에서만 필요하다. */
    public record VaultBody(String salt, Integer iterations, String wrappedKey, Integer version) {
    }

    /** PBKDF2 반복 횟수 하한. 이보다 약한 설정은 받지 않는다 (앱도 같은 하한을 강제). */
    static final int MIN_ITERATIONS = 100_000;

    private final UserRepository users;
    private final ClipRepository clips;
    private final ImageStore store;

    public VaultController(UserRepository users, ClipRepository clips, ImageStore store) {
        this.users = users;
        this.clips = clips;
        this.store = store;
    }

    @GetMapping
    public VaultBody get(@AuthenticationPrincipal AuthUser me) {
        User u = user(me);
        if (!u.hasVault()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vault not set");
        return new VaultBody(u.getVaultSalt(), u.getVaultIterations(), u.getVaultWrappedKey(), u.getVaultVersion());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Integer>> create(@AuthenticationPrincipal AuthUser me, @RequestBody VaultBody req) {
        validate(req);
        User u = user(me);
        if (u.hasVault()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Vault already exists");
        // 조건부 갱신(version = 0일 때만): 동시에 두 번 만들어도 한쪽만 성공하고 다른 쪽은 409
        if (users.replaceVaultIfVersion(me.userId(), req.salt(), req.iterations(), req.wrappedKey(), 0) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vault already exists");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("version", 1));
    }

    /** 암호 변경: 같은 볼트 키를 새 잠금 키로 감싼 값으로 바꾼다. 클립은 건드리지 않는다. */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void change(@AuthenticationPrincipal AuthUser me, @RequestBody VaultBody req) {
        validate(req);
        User u = user(me);
        if (!u.hasVault()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vault not set");
        if (req.version() == null || req.version() != u.getVaultVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vault version changed");
        }
        u.setVault(req.salt(), req.iterations(), req.wrappedKey());
        users.save(u);
    }

    /**
     * 초기화(볼트 암호를 잊었을 때): 옛 볼트 키로 암호화된 클립은 아무도 풀 수 없으므로 모두 지우고 새 키로 시작한다.
     * 버킷 객체부터 지운다(행을 지우면 키를 알 수 없으므로). 객체 삭제 실패는 로그만 남고 버킷 수명 주기 규칙이 정리한다.
     */
    @PostMapping("/reset")
    @Transactional
    public Map<String, Integer> reset(@AuthenticationPrincipal AuthUser me, @RequestBody VaultBody req) {
        validate(req);
        User u = user(me);
        for (String key : clips.findImageKeysByUserId(me.userId())) ImageClipController.deleteQuietly(store, key);
        clips.deleteByUserId(me.userId());
        // 이 요청이 읽은 버전에서만 갱신: 동시에 초기화한 다른 요청이 먼저 이겼으면 0행 → 409.
        // 예외로 트랜잭션이 롤백되므로 위의 클립 행 삭제도 취소된다.
        int expected = u.getVaultVersion();
        if (users.replaceVaultIfVersion(me.userId(), req.salt(), req.iterations(), req.wrappedKey(), expected) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vault changed");
        }
        return Map.of("version", expected + 1);
    }

    /** 형식 검사: salt = base64 16바이트, iterations ≥ 10만, wrappedKey = base64 60바이트. 아니면 400. */
    private static void validate(VaultBody req) {
        if (req.iterations() == null || req.iterations() < MIN_ITERATIONS
                || decodedLength(req.salt()) != 16 || decodedLength(req.wrappedKey()) != 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid vault parameters");
        }
    }

    /** base64 디코딩 길이. null이거나 base64가 아니면 -1. */
    private static int decodedLength(String b64) {
        if (b64 == null) return -1;
        try {
            return Base64.getDecoder().decode(b64).length;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private User user(AuthUser me) {
        return users.findById(me.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
