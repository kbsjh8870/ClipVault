# 종단간 암호화(E2E) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 클립 내용(텍스트·이미지 원본·썸네일)을 트레이 앱에서 암호화해 올리고 받은 앱에서만 풀어, 서버·DB·버킷이 유출돼도 내용이 보이지 않게 한다.

**Architecture:** 봉투 암호화. 사용자당 무작위 볼트 키(VK)로 클립을 AES-GCM 암호화하고, VK는 볼트 암호에서 PBKDF2로 만든 잠금 키로 감싸 서버에 보관한다. 서버는 `X-Vault-Version` 헤더가 있는 업로드를 받은 그대로 저장(`clips.e2e=true`)하고, 헤더 없는 옛 방식은 볼트가 없는 사용자에게만 허용한다. 앱은 볼트 키를 DPAPI로 잠가 저장하고, 기존 서버 암호화 클립을 같은 자리에서 E2E로 옮긴다.

**Tech Stack:** Spring Boot 4.1 (JPA, multipart), JDK 21 `javax.crypto`(PBKDF2WithHmacSHA256, AES/GCM/NoPadding, HmacSHA256), JNA `Crypt32Util`(DPAPI, 이미 의존성에 있음), Swing/FlatLaf.

**Spec:** `docs/superpowers/specs/2026-10-03-e2e-encryption-design.md`

## Global Constraints

- 새 의존성 추가 금지. 암호는 JDK 표준만, DPAPI는 기존 `jna-platform` 5.17.0의 `Crypt32Util`.
- PBKDF2: `PBKDF2WithHmacSHA256`, salt 16바이트, 새로 만들 때 600,000회, 앱·서버 모두 100,000회 미만 거부, 키 256비트.
- AES-256-GCM, IV 12바이트 무작위, 태그 128비트, 형식 `IV ‖ 암호문 ‖ 태그`.
- 감싼 볼트 키 AAD = `"clipvault-vault-v1:" + userId`, 디코딩 길이 정확히 60바이트(12 + 32 + 16).
- 하위 키: 암호화 키 = `HMAC-SHA256(VK, "clipvault-enc-v1")`, 해시 키 = `HMAC-SHA256(VK, "clipvault-hash-v1")`.
- 클립 AAD: 텍스트 `text-v1`, 원본 `image-v1`, 썸네일 `thumb-v1`. `contentHash` = 소문자 hex `HMAC-SHA256(해시 키, 평문 바이트)` 64자.
- 헤더 이름 `X-Vault-Version`. 헤더 없음 + 볼트 없음 → 옛 방식, 헤더 없음 + 볼트 있음 → `426`, 헤더 ≠ 현재 version(또는 볼트 없음) → `409`.
- 크기: e2e 텍스트 디코딩 길이 28~300,028바이트, 이미지 원본 파트 ≤ 10MB + 28, 썸네일 파트 ≤ 1MB, width·height ≥ 1 그리고 곱 ≤ 50,000,000.
- 볼트 암호 8자 이상. 썸네일 긴 변 240px(원본이 더 작으면 원본 크기).
- 코드 주석은 한국어로 자세히(기존 파일들과 같은 밀도). 커밋 메시지에 Claude/AI 관련 문구 금지.
- UI(볼트 창, 메뉴)는 커밋 전에 오프스크린 렌더링(`printAll`) 스크린샷으로 사용자 확인을 받는다. 화면 캡처(Robot) 금지.
- 브랜치 `feat/e2e-encryption`에서 작업. 기존 백엔드 테스트는 옛 방식 경로(헤더 없음 + 볼트 없음)를 쓰므로 수정 없이 통과해야 한다.

## File Structure

**Backend (`backend/src/main/java/com/clipvault/`)**
- `auth/User.java` (수정) — 볼트 컬럼 4개와 메서드
- `vault/VaultController.java` (새) — `GET/POST/PUT /api/vault`, `POST /api/vault/reset`
- `vault/VaultGuard.java` (새) — 업로드 헤더 규칙 판정 (옛 방식 / e2e / 426 / 409)
- `clip/Clip.java` (수정) — `e2e` 컬럼, `markE2e()`, `convertToE2e()`
- `clip/ClipResponse.java` (수정) — `e2e` 필드, `of(Clip, AesCipher)` 도우미
- `clip/ClipRepository.java` (수정) — 사용자별 삭제·키 조회, 옛 행 조회·개수
- `clip/E2eInput.java` (새) — e2e 입력 검사 도우미(base64, 해시, 크기)
- `clip/ClipController.java` (수정) — e2e 텍스트 업로드, `legacy=true`, 텍스트 이전 `PUT /{id}/e2e`
- `clip/ImageClipController.java` (수정) — e2e 이미지 multipart 업로드, e2e 조회, 이미지 이전 `PUT /{id}/e2e`
- `clip/ClipCleanupJob.java` (수정) — 옛 행 개수 로그
- `common/GlobalExceptionHandler.java` (수정) — 업로드 크기 초과 413, 파트 누락 400
- `resources/application.yml`, `test/resources/application.yml` (수정) — multipart 한도

**Tray client (`tray-client/src/main/java/com/clipvault/client/`)**
- `crypto/VaultCrypto.java` (새) — 순수 암호 함수
- `crypto/Vault.java` (새) — 이 PC의 볼트 상태 + DPAPI 저장
- `clipboard/Images.java` (수정) — `thumbnail(BufferedImage, int)`
- `network/Multipart.java` (새) — multipart 본문 작성
- `network/ApiClient.java` (수정) — 볼트 API, 헤더, e2e 업로드, 이전 API
- `ui/VaultDialog.java` (새) — 만들기/입력/변경/초기화 창
- `ui/ClipListWindow.java` (수정) — `Thumbs`가 클립 JSON을 받음
- `TrayApp.java` (수정) — 볼트 확인, 업로드 암호화, 받기 복호화, 메뉴, 이전

---

### Task 1: 볼트 API (백엔드)

**Files:**
- Modify: `backend/src/main/java/com/clipvault/auth/User.java`
- Modify: `backend/src/main/java/com/clipvault/clip/ClipRepository.java`
- Create: `backend/src/main/java/com/clipvault/vault/VaultController.java`
- Test: `backend/src/test/java/com/clipvault/vault/VaultApiTest.java`

**Interfaces:**
- Produces: `User.getVaultVersion()` (int, 0 = 없음), `User.hasVault()`, `User.setVault(String salt, int iterations, String wrappedKey)`, `User.bumpVaultVersion()`;
  `ClipRepository.findImageKeysByUserId(UUID)`, `ClipRepository.deleteByUserId(UUID)`;
  REST `GET/POST/PUT /api/vault`, `POST /api/vault/reset`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.clipvault.vault;

import com.clipvault.Api;
import com.clipvault.storage.ImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static com.clipvault.Api.read;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 볼트 API 통합 테스트. 서버는 감싼 볼트 키를 내용을 모른 채 보관만 한다(풀 수 없음).
 * 값은 형식만 맞으면 되므로 테스트에서는 아무 바이트나 base64로 만들어 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VaultApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImageStore store;
    Api api;
    Api.DeviceTokens dev;

    static final String SALT = b64(16, 1);
    static final String KEY = b64(60, 2);

    static String b64(int n, int fill) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) fill);
        return Base64.getEncoder().encodeToString(b);
    }

    static String body(String salt, int iterations, String key, Integer version) {
        return "{\"salt\":\"" + salt + "\",\"iterations\":" + iterations + ",\"wrappedKey\":\"" + key + "\""
                + (version == null ? "" : ",\"version\":" + version) + "}";
    }

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    @Test
    void createThenGet() throws Exception {
        api.get("/api/vault", dev.accessToken()).andExpect(status().isNotFound());
        api.post("/api/vault", dev.accessToken(), body(SALT, 600_000, KEY, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(1));
        api.get("/api/vault", dev.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.salt").value(SALT))
                .andExpect(jsonPath("$.iterations").value(600_000))
                .andExpect(jsonPath("$.wrappedKey").value(KEY))
                .andExpect(jsonPath("$.version").value(1));
        api.post("/api/vault", dev.accessToken(), body(SALT, 600_000, KEY, null)).andExpect(status().isConflict());
    }

    /** 암호 변경 = 다시 감싸기. version이 현재와 같아야 한다 (다른 PC의 초기화와 엇갈리지 않게) */
    @Test
    void changeRequiresCurrentVersion() throws Exception {
        api.put("/api/vault", dev.accessToken(), body(SALT, 600_000, KEY, 1)).andExpect(status().isNotFound());
        api.post("/api/vault", dev.accessToken(), body(SALT, 600_000, KEY, null)).andExpect(status().isCreated());
        String newKey = b64(60, 3);
        api.put("/api/vault", dev.accessToken(), body(SALT, 600_000, newKey, 2)).andExpect(status().isConflict());
        api.put("/api/vault", dev.accessToken(), body(SALT, 700_000, newKey, 1)).andExpect(status().isNoContent());
        api.get("/api/vault", dev.accessToken())
                .andExpect(jsonPath("$.wrappedKey").value(newKey))
                .andExpect(jsonPath("$.iterations").value(700_000))
                .andExpect(jsonPath("$.version").value(1));
    }

    /** 초기화: 그 사용자의 클립 행과 버킷 객체를 모두 지우고 version +1 */
    @Test
    void resetDeletesClipsAndBumpsVersion() throws Exception {
        api.createClip(dev.accessToken(), "old text", 201);
        String img = api.postImage(dev.accessToken(), Api.png(6, 6, 0x101010))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String key = jdbc.queryForObject("select image_key from clips where cast(id as varchar) = ?", String.class,
                read(img, "$.id"));
        Api.DeviceTokens other = api.newUserWithDevice();
        api.createClip(other.accessToken(), "someone else", 201);
        api.post("/api/vault", dev.accessToken(), body(SALT, 600_000, KEY, null)).andExpect(status().isCreated());

        String resetKey = b64(60, 4);
        api.post("/api/vault/reset", dev.accessToken(), body(b64(16, 5), 600_000, resetKey, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        api.get("/api/clips", dev.accessToken()).andExpect(jsonPath("$.length()").value(0));
        assertNull(store.get("images/" + key));
        api.get("/api/clips", other.accessToken()).andExpect(jsonPath("$.length()").value(1));
        api.get("/api/vault", dev.accessToken()).andExpect(jsonPath("$.wrappedKey").value(resetKey))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void validatesInput() throws Exception {
        api.post("/api/vault", dev.accessToken(), body(b64(15, 1), 600_000, KEY, null)).andExpect(status().isBadRequest());
        api.post("/api/vault", dev.accessToken(), body(SALT, 99_999, KEY, null)).andExpect(status().isBadRequest());
        api.post("/api/vault", dev.accessToken(), body(SALT, 600_000, b64(59, 1), null)).andExpect(status().isBadRequest());
        api.post("/api/vault", dev.accessToken(), body("not base64!!", 600_000, KEY, null)).andExpect(status().isBadRequest());
        api.get("/api/vault", dev.accessToken()).andExpect(status().isNotFound());
    }

    /** 볼트는 사용자별: 다른 사용자의 볼트는 보이지 않는다 */
    @Test
    void perUser() throws Exception {
        api.post("/api/vault", dev.accessToken(), body(SALT, 600_000, KEY, null)).andExpect(status().isCreated());
        Api.DeviceTokens other = api.newUserWithDevice();
        api.get("/api/vault", other.accessToken()).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :backend:test --tests '*VaultApiTest'`
Expected: FAIL (`/api/vault` 매핑 없음 → 404/405 기대값 불일치)

- [ ] **Step 3: `User`에 볼트 컬럼 추가** — `clipTtlDays` 필드 아래에:

```java
    /** 볼트 salt (base64 16바이트). 볼트를 만들기 전에는 null. 서버는 이 값과 아래 감싼 키로 볼트 키를 풀 수 없다(볼트 암호가 없으므로). */
    @Column(name = "vault_salt", length = 32)
    private String vaultSalt;

    /** 볼트 암호 → 잠금 키 PBKDF2 반복 횟수. 앱이 이 값을 그대로 쓴다(나중에 올릴 수 있게 저장). */
    @Column(name = "vault_iterations")
    private Integer vaultIterations;

    /** 잠금 키로 감싼 볼트 키 (base64 60바이트 = IV 12 + 키 32 + 태그 16). */
    @Column(name = "vault_wrapped_key", length = 128)
    private String vaultWrappedKey;

    /** 볼트 버전. 0 = 볼트 없음, 만들면 1, 초기화할 때마다 +1. 옛 볼트 키를 가진 PC의 업로드를 409로 막는 데 쓴다. */
    @Column(name = "vault_version", nullable = false, columnDefinition = "integer default 0")
    private int vaultVersion;
```

getter와 메서드 (기존 getter들 아래):

```java
    public String getVaultSalt() { return vaultSalt; }
    public Integer getVaultIterations() { return vaultIterations; }
    public String getVaultWrappedKey() { return vaultWrappedKey; }
    public int getVaultVersion() { return vaultVersion; }

    /** 볼트가 있는지 (한 번이라도 만들었는지). */
    public boolean hasVault() { return vaultVersion > 0; }

    /** 감싼 볼트 키를 저장한다(만들기, 암호 변경, 초기화 공통). version은 바꾸지 않는다. 값 검사는 VaultController가 한다. */
    public void setVault(String salt, int iterations, String wrappedKey) {
        this.vaultSalt = salt;
        this.vaultIterations = iterations;
        this.vaultWrappedKey = wrappedKey;
    }

    /** 볼트 버전을 1 올린다 (만들기: 0→1, 초기화: n→n+1). */
    public void bumpVaultVersion() { this.vaultVersion++; }
```

- [ ] **Step 4: `ClipRepository`에 사용자별 조회·삭제 추가**

```java
    /** 사용자의 이미지 클립 버킷 키 전부 (볼트 초기화 때 객체부터 지우는 데 쓴다). */
    @Query("select c.imageKey from Clip c where c.userId = :userId and c.imageKey is not null")
    List<String> findImageKeysByUserId(@Param("userId") UUID userId);

    /** 사용자의 클립을 모두 지운다 (볼트 초기화). 지운 행 수. */
    @Modifying
    @Query("delete from Clip c where c.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
```

- [ ] **Step 5: `VaultController` 작성**

```java
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
        u.setVault(req.salt(), req.iterations(), req.wrappedKey());
        u.bumpVaultVersion();
        users.save(u);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("version", u.getVaultVersion()));
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
        u.setVault(req.salt(), req.iterations(), req.wrappedKey());
        u.bumpVaultVersion();
        users.save(u);
        return Map.of("version", u.getVaultVersion());
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
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :backend:test --tests '*VaultApiTest'`
Expected: PASS (5 tests)

- [ ] **Step 7: 전체 백엔드 테스트**

Run: `./gradlew :backend:test`
Expected: 기존 78개 + 5개 모두 PASS

- [ ] **Step 8: 커밋**

```bash
git add backend/src/main/java/com/clipvault/auth/User.java backend/src/main/java/com/clipvault/clip/ClipRepository.java backend/src/main/java/com/clipvault/vault/VaultController.java backend/src/test/java/com/clipvault/vault/VaultApiTest.java
git commit -m "feat(backend): 볼트 API (감싼 볼트 키 보관, 암호 변경, 초기화)"
```

---

### Task 2: e2e 업로드·조회 (백엔드)

**Files:**
- Create: `backend/src/main/java/com/clipvault/vault/VaultGuard.java`
- Create: `backend/src/main/java/com/clipvault/clip/E2eInput.java`
- Modify: `backend/src/main/java/com/clipvault/clip/Clip.java`
- Modify: `backend/src/main/java/com/clipvault/clip/ClipResponse.java`
- Modify: `backend/src/main/java/com/clipvault/clip/ClipController.java`
- Modify: `backend/src/main/java/com/clipvault/clip/ImageClipController.java`
- Modify: `backend/src/main/java/com/clipvault/common/GlobalExceptionHandler.java`
- Modify: `backend/src/main/resources/application.yml`, `backend/src/test/resources/application.yml`
- Modify: `backend/src/test/java/com/clipvault/Api.java`
- Test: `backend/src/test/java/com/clipvault/clip/E2eClipApiTest.java`

**Interfaces:**
- Consumes: `User.getVaultVersion()` (Task 1)
- Produces: `VaultGuard.HEADER = "X-Vault-Version"`, `boolean VaultGuard.e2e(AuthUser me, Integer header)`;
  `E2eInput.text(String b64)`, `E2eInput.hash(String)`, `E2eInput.image(MultipartFile, MultipartFile)` → `byte[][]`;
  `Clip.isE2e()`, `Clip.markE2e()`, `Clip.convertToE2e(String content, String contentHash)`;
  `ClipResponse.e2e()`, `ClipResponse.of(Clip, AesCipher)`;
  테스트 도우미 `Api.createVault(String token)` → int version, `Api.postE2eText(...)`, `Api.postE2eImage(...)`.

- [ ] **Step 1: 테스트 도우미 추가** — `Api.java` 끝에:

```java
    /** 볼트를 만든다 (형식만 맞는 가짜 값). 만든 version(1)을 돌려준다. */
    public int createVault(String token) throws Exception {
        String salt = java.util.Base64.getEncoder().encodeToString(new byte[16]);
        String key = java.util.Base64.getEncoder().encodeToString(new byte[60]);
        String body = post("/api/vault", token, "{\"salt\":\"" + salt + "\",\"iterations\":600000,\"wrappedKey\":\"" + key + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return read(body, "$.version");
    }

    /** e2e 텍스트 업로드 (content = base64 암호문, hash = hex 64자). version이 null이면 헤더 없이. */
    public ResultActions postE2eText(String token, Integer version, String content, String hash) throws Exception {
        var req = MockMvcRequestBuilders.post("/api/clips").contentType(MediaType.APPLICATION_JSON)
                .content(json("content", content, "contentHash", hash));
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (version != null) req.header("X-Vault-Version", version);
        return mvc.perform(req);
    }

    /** e2e 이미지 업로드 (multipart: image, thumb 암호문 + width, height, contentHash). */
    public ResultActions postE2eImage(String token, Integer version, byte[] image, byte[] thumb, int w, int h, String hash)
            throws Exception {
        var req = MockMvcRequestBuilders.multipart("/api/clips/image")
                .file(new org.springframework.mock.web.MockMultipartFile("image", "image", "application/octet-stream", image))
                .file(new org.springframework.mock.web.MockMultipartFile("thumb", "thumb", "application/octet-stream", thumb))
                .param("width", String.valueOf(w)).param("height", String.valueOf(h)).param("contentHash", hash);
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (version != null) req.header("X-Vault-Version", version);
        return mvc.perform(req);
    }

    /** n바이트 무작위 값 (가짜 암호문). */
    public static byte[] random(int n) {
        byte[] b = new byte[n];
        new java.util.Random(n).nextBytes(b);
        return b;
    }

    /** hex 64자 가짜 해시 (c를 64번). */
    public static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package com.clipvault.clip;

import com.clipvault.Api;
import com.clipvault.storage.ImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static com.clipvault.Api.read;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * e2e 업로드·조회 통합 테스트. 서버는 e2e 클립을 받은 그대로 저장하고 그대로 돌려준다(복호화·가공 없음).
 * 헤더 규칙: 없음+볼트 없음 = 옛 방식, 없음+볼트 있음 = 426, 버전 불일치 = 409.
 */
@SpringBootTest
@AutoConfigureMockMvc
class E2eClipApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImageStore store;
    Api api;
    Api.DeviceTokens dev;

    static final String CT = Base64.getEncoder().encodeToString(Api.random(40));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    @Test
    void headerRules() throws Exception {
        // 볼트 없음 + 헤더 없음 = 옛 방식 그대로 (구버전 앱)
        api.createClip(dev.accessToken(), "legacy ok", 201);
        // 볼트 없음 + 헤더 있음 = 409
        api.postE2eText(dev.accessToken(), 1, CT, Api.hash('a')).andExpect(status().isConflict());
        int v = api.createVault(dev.accessToken());
        // 볼트 있음 + 헤더 없음 = 426 (텍스트, 이미지 모두)
        api.post("/api/clips", dev.accessToken(), Api.json("content", "plain")).andExpect(status().is(426));
        api.postImage(dev.accessToken(), Api.png(4, 4, 0x111111)).andExpect(status().is(426));
        // 버전 불일치 = 409
        api.postE2eText(dev.accessToken(), v + 1, CT, Api.hash('a')).andExpect(status().isConflict());
        api.postE2eText(dev.accessToken(), v, CT, Api.hash('a')).andExpect(status().isCreated());
    }

    /** e2e 텍스트: DB에 보낸 값 그대로, 목록에도 그대로 + e2e=true. 같은 해시면 200(중복) */
    @Test
    void textStoredAsIs() throws Exception {
        int v = api.createVault(dev.accessToken());
        String body = api.postE2eText(dev.accessToken(), v, CT, Api.hash('b')).andExpect(status().isCreated())
                .andExpect(jsonPath("$.e2e").value(true)).andExpect(jsonPath("$.content").value(CT))
                .andReturn().getResponse().getContentAsString();
        String id = read(body, "$.id");
        assertEquals(CT, jdbc.queryForObject("select content from clips where cast(id as varchar) = ?", String.class, id));
        api.get("/api/clips", dev.accessToken()).andExpect(jsonPath("$[0].content").value(CT))
                .andExpect(jsonPath("$[0].e2e").value(true)).andExpect(jsonPath("$[0].contentHash").value(Api.hash('b')));
        api.postE2eText(dev.accessToken(), v, CT, Api.hash('b')).andExpect(status().isOk());
    }

    @Test
    void textValidation() throws Exception {
        int v = api.createVault(dev.accessToken());
        api.postE2eText(dev.accessToken(), v, "not base64!!", Api.hash('c')).andExpect(status().isBadRequest());
        api.postE2eText(dev.accessToken(), v, CT, "XYZ").andExpect(status().isBadRequest());
        api.postE2eText(dev.accessToken(), v, CT, Api.hash('C')).andExpect(status().isBadRequest()); // 대문자 hex 거부
        api.postE2eText(dev.accessToken(), v, Base64.getEncoder().encodeToString(Api.random(27)), Api.hash('c'))
                .andExpect(status().isBadRequest()); // IV+태그(28)보다 짧음
        api.postE2eText(dev.accessToken(), v, Base64.getEncoder().encodeToString(Api.random(300_029)), Api.hash('c'))
                .andExpect(status().isBadRequest());
    }

    /** e2e 이미지: 버킷에 보낸 바이트 그대로, /image·/thumbnail도 그대로 */
    @Test
    void imageStoredAsIs() throws Exception {
        int v = api.createVault(dev.accessToken());
        byte[] image = Api.random(5000), thumb = Api.random(700);
        String body = api.postE2eImage(dev.accessToken(), v, image, thumb, 1920, 1080, Api.hash('d'))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.e2e").value(true))
                .andExpect(jsonPath("$.type").value("IMAGE")).andExpect(jsonPath("$.width").value(1920))
                .andReturn().getResponse().getContentAsString();
        String id = read(body, "$.id");
        String key = jdbc.queryForObject("select image_key from clips where cast(id as varchar) = ?", String.class, id);
        assertArrayEquals(image, store.get("images/" + key));
        assertArrayEquals(thumb, store.get("thumbs/" + key));
        api.get("/api/clips/" + id + "/image", dev.accessToken()).andExpect(content().bytes(image));
        api.get("/api/clips/" + id + "/thumbnail", dev.accessToken()).andExpect(content().bytes(thumb));
        // 같은 해시 = 중복 200
        api.postE2eImage(dev.accessToken(), v, image, thumb, 1920, 1080, Api.hash('d')).andExpect(status().isOk());
    }

    @Test
    void imageValidation() throws Exception {
        int v = api.createVault(dev.accessToken());
        api.postE2eImage(dev.accessToken(), v, Api.random(10 * 1024 * 1024 + 29), Api.random(10), 10, 10, Api.hash('e'))
                .andExpect(status().isPayloadTooLarge());
        api.postE2eImage(dev.accessToken(), v, Api.random(100), Api.random(1024 * 1024 + 1), 10, 10, Api.hash('e'))
                .andExpect(status().isPayloadTooLarge());
        api.postE2eImage(dev.accessToken(), v, Api.random(100), Api.random(10), 0, 10, Api.hash('e'))
                .andExpect(status().isBadRequest());
        api.postE2eImage(dev.accessToken(), v, Api.random(100), Api.random(10), 10_000, 10_000, Api.hash('e'))
                .andExpect(status().isBadRequest());
        api.postE2eImage(dev.accessToken(), v, Api.random(100), Api.random(10), 10, 10, "bad")
                .andExpect(status().isBadRequest());
        // 볼트 없는 사용자의 multipart(헤더 없음) = 400 (e2e 전용 경로)
        Api.DeviceTokens fresh = api.newUserWithDevice();
        api.postE2eImage(fresh.accessToken(), null, Api.random(100), Api.random(10), 10, 10, Api.hash('e'))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :backend:test --tests '*E2eClipApiTest'`
Expected: FAIL

- [ ] **Step 4: multipart 한도 설정** — 두 `application.yml`의 `spring:` 아래(`jpa:`와 같은 들여쓰기)에 추가:

```yaml
  servlet:
    multipart:
      # e2e 이미지 업로드(원본 암호문 ≤ 10MB + 28바이트, 썸네일 ≤ 1MB). 한도 검사는 컨트롤러가 다시 정확히 한다.
      max-file-size: 11MB
      max-request-size: 12MB
```

- [ ] **Step 5: `GlobalExceptionHandler`에 핸들러 추가** (기존 핸들러들과 같은 `ErrorResponse` 형식):

```java
    /** multipart 업로드가 설정 한도(spring.servlet.multipart)를 넘음 → 413 */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> handle(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ErrorResponse(413, "Upload too large"));
    }

    /** multipart 파트나 필수 파라미터 누락 → 400 */
    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<ErrorResponse> handleMissing(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "Missing part or parameter"));
    }
```

(파일의 `ErrorResponse` 레코드 이름과 생성자 인자 순서 `(int status, String message)`를 그대로 쓴다.)

- [ ] **Step 6: `VaultGuard` 작성**

```java
package com.clipvault.vault;

import com.clipvault.auth.AuthUser;
import com.clipvault.auth.User;
import com.clipvault.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 업로드가 어느 방식인지 판정한다 (헤더 {@value #HEADER}와 사용자의 볼트 버전으로).
 *
 * <table>
 *   <tr><th>헤더</th><th>볼트</th><th>결과</th></tr>
 *   <tr><td>없음</td><td>없음</td><td>false = 옛 방식 (구버전 앱 호환: 서버가 평문을 받아 서버 암호화)</td></tr>
 *   <tr><td>없음</td><td>있음</td><td>426 "앱을 업데이트하세요"</td></tr>
 *   <tr><td>있음 = 현재 버전</td><td>있음</td><td>true = e2e (받은 그대로 저장)</td></tr>
 *   <tr><td>있음 ≠ 현재 버전</td><td>(무관)</td><td>409 "볼트가 바뀌었습니다" (다른 PC에서 초기화됨)</td></tr>
 * </table>
 */
@Component
public class VaultGuard {
    public static final String HEADER = "X-Vault-Version";

    private final UserRepository users;

    public VaultGuard(UserRepository users) {
        this.users = users;
    }

    /** @return true = e2e 업로드, false = 옛 방식. 그 밖은 예외(426/409). */
    public boolean e2e(AuthUser me, Integer header) {
        int current = users.findById(me.userId()).map(User::getVaultVersion).orElse(0);
        if (header == null) {
            if (current > 0) throw new ResponseStatusException(HttpStatus.UPGRADE_REQUIRED, "Update the app to use end-to-end encryption");
            return false;
        }
        if (current == 0 || header != current) throw new ResponseStatusException(HttpStatus.CONFLICT, "Vault changed");
        return true;
    }
}
```

- [ ] **Step 7: `E2eInput` 작성**

```java
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
```

- [ ] **Step 8: `Clip`에 e2e 컬럼** — `pinned` 필드 아래:

```java
    /**
     * 종단간 암호화 행인지. true = 앱이 암호화한 값을 서버가 그대로 저장한 행(서버는 풀 수 없다),
     * false = 서버가 AES로 암호화한 옛 행(볼트 이전 데이터, 앱이 {@code PUT /{id}/e2e}로 옮긴다).
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean e2e;
```

메서드 (`pin()` 위):

```java
    /** e2e 행으로 표시한다 (새 e2e 업로드). */
    public void markE2e() {
        this.e2e = true;
    }

    /** 옛 행을 같은 자리에서 e2e로 바꾼다 (기존 데이터 이전). id, 시각, 고정 여부, 이미지 키·크기는 그대로. */
    public void convertToE2e(String content, String contentHash) {
        this.content = content;
        this.contentHash = contentHash;
        this.e2e = true;
    }
```

getter: `public boolean isE2e() { return e2e; }`

- [ ] **Step 9: `ClipResponse`에 e2e** — 레코드 끝 인자에 `boolean e2e` 추가, 기존 `of(Clip, String)`은 `c.isE2e()`를 넘기도록 바꾸고 도우미 추가:

```java
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
```

(import `com.clipvault.common.AesCipher`. 레코드 Javadoc에 "e2e = 종단간 암호화 행이면 content가 base64 암호문" 한 줄 추가.)

- [ ] **Step 10: `ClipController` e2e 텍스트 업로드** — `UploadRequest`와 `upload`를 바꾼다:

```java
    /**
     * 업로드 요청. 옛 방식은 content = 평문(최대 10만 자), e2e는 content = base64 암호문 + contentHash(HMAC hex).
     * 길이 검사는 방식마다 달라서 컨트롤러에서 한다.
     */
    public record UploadRequest(@NotBlank String content, String contentHash) {
    }
```

`upload` 시그니처와 앞부분:

```java
    @PostMapping
    public ResponseEntity<ClipResponse> upload(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody UploadRequest req,
                                               @RequestHeader(value = VaultGuard.HEADER, required = false) Integer vaultVersion) {
        boolean e2e = vaultGuard.e2e(me, vaultVersion);
        String hash;
        String stored;
        if (e2e) {
            // 앱이 암호화한 값: 형식만 확인하고 그대로 저장 (서버는 풀 수 없다)
            E2eInput.text(req.content());
            E2eInput.hash(req.contentHash());
            hash = req.contentHash();
            stored = req.content();
        } else {
            // 옛 방식(구버전 앱, 볼트 없음): 평문 최대 10만 자, 서버가 해시·암호화
            if (req.content().length() > 100_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must be at most 100000 characters");
            }
            hash = HashUtil.sha256(req.content());
            stored = cipher.encrypt(req.content());
        }
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
            // 새 내용. 만료 시각 = 지금 + 보관 기간(사용자 설정, 기본 7일)
            Clip c = new Clip(me.userId(), me.deviceId(), stored, hash, now, now.plus(ttl));
            if (e2e) c.markE2e();
            clip = clips.save(c);
        }
        // 응답과 알림: e2e는 받은 암호문 그대로, 옛 방식은 받은 평문 그대로 (다시 복호화할 필요 없음)
        ClipResponse body = ClipResponse.of(clip, req.content());
        messaging.convertAndSend("/topic/clips/" + me.userId(), body);
        return ResponseEntity.status(duplicate ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }
```

생성자에 `VaultGuard vaultGuard` 주입(필드 `private final VaultGuard vaultGuard;`), import `com.clipvault.vault.VaultGuard`, `org.springframework.web.bind.annotation.RequestHeader`. `@Size` import가 안 쓰이면 지운다.
`list`의 `.map(c -> ClipResponse.of(c, cipher.decrypt(c.getContent())))`를 `.map(c -> ClipResponse.of(c, cipher))`로 바꾼다.

- [ ] **Step 11: `ImageClipController` e2e 업로드·조회**

생성자에 `VaultGuard vaultGuard` 주입. 기존 `upload`(image/png) 첫 줄에 헤더 판정 추가:

```java
    @PostMapping(path = "/image", consumes = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<ClipResponse> upload(@AuthenticationPrincipal AuthUser me, HttpServletRequest req,
                                               @RequestHeader(value = VaultGuard.HEADER, required = false) Integer vaultVersion)
            throws IOException {
        // 옛 방식 전용 경로. 볼트가 있으면 426, e2e 앱은 multipart 경로를 써야 한다
        if (vaultGuard.e2e(me, vaultVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use multipart upload for end-to-end encrypted images");
        }
        ... (기존 본문 그대로, 중복 응답의 ClipResponse.of(saved, cipher.decrypt(saved.getContent()))는 ClipResponse.of(saved, cipher)로)
```

새 multipart 업로드:

```java
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
```

`load`의 마지막 줄을 e2e면 그대로 돌려주게:

```java
        // e2e 행은 앱이 암호화한 바이트 그대로 (앱이 푼다), 옛 행은 서버 키로 복호화
        return c.isE2e() ? data : cipher.decryptBytes(data);
```

import: `java.time.Duration`, `org.springframework.web.bind.annotation.RequestHeader`, `RequestParam`, `RequestPart`, `org.springframework.web.multipart.MultipartFile`, `com.clipvault.vault.VaultGuard`. 클래스 Javadoc 목록에 multipart e2e 업로드 한 줄 추가.

- [ ] **Step 12: 테스트 통과 확인**

Run: `./gradlew :backend:test --tests '*E2eClipApiTest'`
Expected: PASS (5 tests)

- [ ] **Step 13: 전체 백엔드 테스트** (기존 테스트가 옛 방식 경로로 그대로 통과해야 한다)

Run: `./gradlew :backend:test`
Expected: 전부 PASS

- [ ] **Step 14: 커밋**

```bash
git add backend/
git commit -m "feat(backend): 종단간 암호화 업로드·조회 (X-Vault-Version, e2e 텍스트·이미지 multipart)"
```

---

### Task 3: 기존 데이터 이전 API (백엔드)

**Files:**
- Modify: `backend/src/main/java/com/clipvault/clip/ClipRepository.java`
- Modify: `backend/src/main/java/com/clipvault/clip/ClipController.java`
- Modify: `backend/src/main/java/com/clipvault/clip/ImageClipController.java`
- Modify: `backend/src/main/java/com/clipvault/clip/ClipCleanupJob.java`
- Test: `backend/src/test/java/com/clipvault/clip/MigrationApiTest.java`

**Interfaces:**
- Consumes: `VaultGuard.e2e`, `E2eInput.*`, `Clip.convertToE2e`, `ClipResponse.of(Clip, AesCipher)` (Task 2), `Api.createVault`, `Api.random`, `Api.hash` (Task 2)
- Produces: `GET /api/clips?legacy=true`, `PUT /api/clips/{id}/e2e` (JSON 텍스트 / multipart 이미지), `ClipRepository.findTop100ByUserIdAndE2eFalseOrderByCreatedAtDesc(UUID)`, `ClipRepository.countByE2eFalse()`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.clipvault.clip;

import com.clipvault.Api;
import com.clipvault.storage.ImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Base64;
import java.util.Map;

import static com.clipvault.Api.read;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기존 데이터 이전 테스트: 볼트 전에 옛 방식으로 올린 클립을 앱이 받아 암호화해서 같은 자리에 덮어쓴다.
 * id, 생성·만료 시각, 고정 여부는 그대로 두고 content·hash만 바꾸고 e2e=true.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MigrationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImageStore store;
    Api api;
    Api.DeviceTokens dev;

    static final String CT = Base64.getEncoder().encodeToString(Api.random(48));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select created_at, expires_at, pinned, e2e, content, content_hash from clips where cast(id as varchar) = ?", id);
    }

    private org.springframework.test.web.servlet.ResultActions putText(String id, int v, String content, String hash) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put("/api/clips/" + id + "/e2e")
                .header("Authorization", "Bearer " + dev.accessToken()).header("X-Vault-Version", v)
                .contentType("application/json").content(Api.json("content", content, "contentHash", hash)));
    }

    private org.springframework.test.web.servlet.ResultActions putImage(String id, int v, byte[] image, byte[] thumb, String hash) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.multipart(HttpMethod.PUT, "/api/clips/" + id + "/e2e")
                .file(new MockMultipartFile("image", "image", "application/octet-stream", image))
                .file(new MockMultipartFile("thumb", "thumb", "application/octet-stream", thumb))
                .param("contentHash", hash)
                .header("Authorization", "Bearer " + dev.accessToken()).header("X-Vault-Version", v));
    }

    /** legacy=true: 옛 행만(평문으로), 고정 포함 */
    @Test
    void legacyListReturnsOnlyOldRows() throws Exception {
        String old = read(api.createClip(dev.accessToken(), "old plain", 201), "$.id");
        api.put("/api/clips/" + old + "/pin", dev.accessToken()).andExpect(status().isNoContent());
        int v = api.createVault(dev.accessToken());
        api.postE2eText(dev.accessToken(), v, CT, Api.hash('1')).andExpect(status().isCreated());
        api.get("/api/clips?legacy=true", dev.accessToken())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(old))
                .andExpect(jsonPath("$[0].content").value("old plain"))
                .andExpect(jsonPath("$[0].e2e").value(false));
    }

    /** 텍스트 이전: 시각·고정 유지, e2e=true, 두 번 보내도 204 (두 번째는 아무것도 안 함) */
    @Test
    void textMigrationKeepsMetadata() throws Exception {
        String id = read(api.createClip(dev.accessToken(), "move me", 201), "$.id");
        api.put("/api/clips/" + id + "/pin", dev.accessToken()).andExpect(status().isNoContent());
        Map<String, Object> before = row(id);
        int v = api.createVault(dev.accessToken());

        putText(id, v, CT, Api.hash('2')).andExpect(status().isNoContent());
        Map<String, Object> after = row(id);
        assertEquals(before.get("created_at"), after.get("created_at"));
        assertEquals(before.get("expires_at"), after.get("expires_at"));
        assertEquals(Boolean.TRUE, after.get("pinned"));
        assertEquals(Boolean.TRUE, after.get("e2e"));
        assertEquals(CT, after.get("content"));
        assertEquals(Api.hash('2'), after.get("content_hash"));

        String other = Base64.getEncoder().encodeToString(Api.random(50));
        putText(id, v, other, Api.hash('3')).andExpect(status().isNoContent());
        assertEquals(CT, row(id).get("content"), "already e2e: second migration is a no-op");
        api.get("/api/clips?legacy=true", dev.accessToken()).andExpect(jsonPath("$.length()").value(0));
    }

    /** 이미지 이전: 같은 버킷 키에 암호문 덮어쓰기, e2e=true */
    @Test
    void imageMigrationOverwritesObjects() throws Exception {
        String id = read(api.postImage(dev.accessToken(), Api.png(8, 8, 0x223344)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        String key = jdbc.queryForObject("select image_key from clips where cast(id as varchar) = ?", String.class, id);
        int v = api.createVault(dev.accessToken());
        byte[] image = Api.random(3000), thumb = Api.random(300);

        putImage(id, v, image, thumb, Api.hash('4')).andExpect(status().isNoContent());
        assertArrayEquals(image, store.get("images/" + key));
        assertArrayEquals(thumb, store.get("thumbs/" + key));
        assertEquals(Boolean.TRUE, row(id).get("e2e"));
        assertEquals(Api.hash('4'), row(id).get("content_hash"));
    }

    @Test
    void rulesAndErrors() throws Exception {
        String text = read(api.createClip(dev.accessToken(), "t", 201), "$.id");
        String img = read(api.postImage(dev.accessToken(), Api.png(4, 4, 0x010203)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        int v = api.createVault(dev.accessToken());
        putText(text, v + 1, CT, Api.hash('5')).andExpect(status().isConflict());   // 버전 불일치
        putText(img, v, CT, Api.hash('5')).andExpect(status().isBadRequest());     // 이미지에 텍스트 형식
        putImage(text, v, Api.random(100), Api.random(50), Api.hash('5')).andExpect(status().isBadRequest()); // 텍스트에 이미지 형식
        putText(text, v, CT, "bad").andExpect(status().isBadRequest());
        Api.DeviceTokens stranger = api.newUserWithDevice();
        int sv = api.createVault(stranger.accessToken());
        mvc.perform(MockMvcRequestBuilders.put("/api/clips/" + text + "/e2e")
                        .header("Authorization", "Bearer " + stranger.accessToken()).header("X-Vault-Version", sv)
                        .contentType("application/json").content(Api.json("content", CT, "contentHash", Api.hash('6'))))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :backend:test --tests '*MigrationApiTest'`
Expected: FAIL

- [ ] **Step 3: `ClipRepository` 추가**

```java
    /** 사용자의 옛 행(서버 암호화, e2e=false) 최신순 최대 100개. 앱이 받아서 e2e로 옮긴다. */
    List<Clip> findTop100ByUserIdAndE2eFalseOrderByCreatedAtDesc(UUID userId);

    /** 전체 옛 행 개수 (서버 AES 키를 언제 없앨 수 있는지 보려고 매일 로그에 남긴다). */
    long countByE2eFalse();
```

- [ ] **Step 4: `ClipController` — legacy 목록과 텍스트 이전**

`list`에 파라미터 `@RequestParam(defaultValue = "false") boolean legacy` 추가, 조회 분기를 이렇게:

```java
        List<Clip> found = legacy
                ? clips.findTop100ByUserIdAndE2eFalseOrderByCreatedAtDesc(me.userId())
                : pinned
                ? clips.findByUserIdAndPinnedTrueOrderByCreatedAtDesc(me.userId())
                : clips.findVisible(me.userId(), Instant.now(), before == null ? FAR_FUTURE : before, Limit.of(Math.clamp(limit, 1, 100)));
```

(Javadoc에 `@param legacy true면 옛 행(서버 암호화)만 최대 100개 — 앱의 기존 데이터 이전용, 평문으로 돌려준다` 추가.)

새 메서드:

```java
    /**
     * 기존 데이터 이전(텍스트): 옛 행을 같은 자리에서 e2e로 바꾼다. 204.
     * id·시각·고정 여부는 그대로, content·contentHash를 앱이 암호화한 값으로 바꾼다. 이미 e2e면 아무것도 안 한다(두 PC 동시 이전 대비).
     *
     * @throws ResponseStatusException 404 남의 클립, 400 이미지 클립이거나 형식 오류, 426/409 헤더 규칙({@link VaultGuard})
     */
    @PutMapping(path = "/{id}/e2e", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void migrateText(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody UploadRequest req,
                            @RequestHeader(value = VaultGuard.HEADER, required = false) Integer vaultVersion) {
        if (!vaultGuard.e2e(me, vaultVersion)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vault required");
        Clip clip = owned(me, id);
        if (clip.isE2e()) return;
        if (clip.getType() == ClipType.IMAGE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use multipart for image clips");
        E2eInput.text(req.content());
        E2eInput.hash(req.contentHash());
        clip.convertToE2e(req.content(), req.contentHash());
        clips.save(clip);
    }
```

import `org.springframework.http.MediaType`.

- [ ] **Step 5: `ImageClipController` — 이미지 이전**

```java
    /**
     * 기존 데이터 이전(이미지, multipart: image, thumb, contentHash). 204.
     * 같은 버킷 키에 암호문을 먼저 덮어쓰고 DB를 바꾼다. 버킷 실패면 503, 행은 옛 상태 그대로(다음에 다시 시도).
     * 이미 e2e면 아무것도 안 한다.
     */
    @PutMapping(path = "/{id}/e2e", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void migrateImage(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id,
                             @RequestHeader(value = VaultGuard.HEADER, required = false) Integer vaultVersion,
                             @RequestPart("image") MultipartFile image, @RequestPart("thumb") MultipartFile thumb,
                             @RequestParam String contentHash) {
        if (!vaultGuard.e2e(me, vaultVersion)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vault required");
        Clip c = clips.findByIdAndUserId(id, me.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clip not found"));
        if (c.isE2e()) return;
        if (c.getImageKey() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not an image clip");
        E2eInput.hash(contentHash);
        byte[][] parts = E2eInput.image(image, thumb);
        try {
            store.put(IMAGES + c.getImageKey(), parts[0]);
            store.put(THUMBS + c.getImageKey(), parts[1]);
        } catch (RuntimeException e) {
            log.error("Image storage failed", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage unavailable");
        }
        c.convertToE2e("", contentHash);
        clips.save(c);
    }
```

import `org.springframework.web.bind.annotation.PutMapping`, `ResponseStatus`.

- [ ] **Step 6: `ClipCleanupJob.deleteExpired` 끝(`return` 앞)에 옛 행 개수 로그**

```java
        // 서버 AES 키(CLIP_ENCRYPTION_KEY)는 옛 행(e2e=false)을 읽는 데만 쓴다. 0이 되면 키와 옛 경로를 없앨 수 있다.
        log.info("Legacy (server-encrypted) clips remaining: {}", clips.countByE2eFalse());
```

- [ ] **Step 7: 테스트 통과 + 전체**

Run: `./gradlew :backend:test`
Expected: 전부 PASS (Migration 4개 포함)

- [ ] **Step 8: 커밋**

```bash
git add backend/
git commit -m "feat(backend): 기존 서버 암호화 클립을 e2e로 옮기는 API (legacy 목록, PUT /{id}/e2e)"
```

---

### Task 4: 암호 함수 `VaultCrypto` (트레이 앱)

**Files:**
- Create: `tray-client/src/main/java/com/clipvault/client/crypto/VaultCrypto.java`
- Test: `tray-client/src/test/java/com/clipvault/client/crypto/VaultCryptoTest.java`

**Interfaces:**
- Produces:
  - `record Wrapped(String salt, int iterations, String wrappedKey)`
  - `static byte[] newVaultKey()` (32바이트)
  - `static Wrapped wrap(byte[] vaultKey, char[] passphrase, String userId)` (새 salt, 600,000회)
  - `static byte[] unwrap(Wrapped w, char[] passphrase, String userId)` — 틀린 암호 `BadPassphraseException`, iterations < 100,000이면 `IllegalArgumentException`
  - `static byte[] subKey(byte[] vaultKey, String label)`
  - `static byte[] seal(byte[] key, byte[] plain, String aad)` / `static byte[] open(byte[] key, byte[] sealed, String aad)` — 실패 `IllegalArgumentException`
  - `static String hmacHex(byte[] key, byte[] data)`
  - 상수 `ITERATIONS = 600_000`, `MIN_ITERATIONS = 100_000`, `ENC = "clipvault-enc-v1"`, `HASH = "clipvault-hash-v1"`

- [ ] **Step 1: 실패하는 테스트**

```java
package com.clipvault.client.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class VaultCryptoTest {
    static final char[] PASS = "correct horse".toCharArray();

    @Test
    void wrapUnwrapRoundTrip() {
        byte[] vk = VaultCrypto.newVaultKey();
        assertEquals(32, vk.length);
        VaultCrypto.Wrapped w = VaultCrypto.wrap(vk, PASS, "user-1");
        assertEquals(VaultCrypto.ITERATIONS, w.iterations());
        assertEquals(16, java.util.Base64.getDecoder().decode(w.salt()).length);
        assertEquals(60, java.util.Base64.getDecoder().decode(w.wrappedKey()).length);
        assertArrayEquals(vk, VaultCrypto.unwrap(w, PASS, "user-1"));
    }

    @Test
    void wrongPassphraseOrUserFails() {
        VaultCrypto.Wrapped w = VaultCrypto.wrap(VaultCrypto.newVaultKey(), PASS, "user-1");
        assertThrows(VaultCrypto.BadPassphraseException.class, () -> VaultCrypto.unwrap(w, "wrong pass".toCharArray(), "user-1"));
        assertThrows(VaultCrypto.BadPassphraseException.class, () -> VaultCrypto.unwrap(w, PASS, "user-2"), "AAD binds userId");
    }

    @Test
    void rejectsWeakIterations() {
        VaultCrypto.Wrapped w = VaultCrypto.wrap(VaultCrypto.newVaultKey(), PASS, "u");
        VaultCrypto.Wrapped weak = new VaultCrypto.Wrapped(w.salt(), 99_999, w.wrappedKey());
        assertThrows(IllegalArgumentException.class, () -> VaultCrypto.unwrap(weak, PASS, "u"));
    }

    @Test
    void sealOpenWithAad() {
        byte[] key = VaultCrypto.subKey(VaultCrypto.newVaultKey(), VaultCrypto.ENC);
        byte[] plain = "회의 링크 https://meet".getBytes(StandardCharsets.UTF_8);
        byte[] a = VaultCrypto.seal(key, plain, "text-v1");
        byte[] b = VaultCrypto.seal(key, plain, "text-v1");
        assertEquals(plain.length + 28, a.length);
        assertFalse(java.util.Arrays.equals(a, b), "random IV");
        assertArrayEquals(plain, VaultCrypto.open(key, a, "text-v1"));
        assertThrows(IllegalArgumentException.class, () -> VaultCrypto.open(key, a, "image-v1"), "AAD mismatch");
        a[a.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> VaultCrypto.open(key, a, "text-v1"), "tampered");
    }

    @Test
    void hmacIsDeterministicAndKeyed() {
        byte[] vk = VaultCrypto.newVaultKey();
        byte[] k1 = VaultCrypto.subKey(vk, VaultCrypto.HASH);
        byte[] k2 = VaultCrypto.subKey(VaultCrypto.newVaultKey(), VaultCrypto.HASH);
        byte[] data = "same".getBytes(StandardCharsets.UTF_8);
        String h = VaultCrypto.hmacHex(k1, data);
        assertTrue(h.matches("[0-9a-f]{64}"));
        assertEquals(h, VaultCrypto.hmacHex(k1, data));
        assertNotEquals(h, VaultCrypto.hmacHex(k2, data));
        assertFalse(java.util.Arrays.equals(VaultCrypto.subKey(vk, VaultCrypto.ENC), k1), "enc/hash keys differ");
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :tray-client:test --tests '*VaultCryptoTest'` → FAIL (클래스 없음)

- [ ] **Step 3: 구현**

```java
package com.clipvault.client.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 종단간 암호화의 암호 함수 모음 (상태 없음, JDK 표준만 사용).
 *
 * <p><b>키 구조 (봉투 암호화)</b>: 사용자당 무작위 <i>볼트 키</i>(32바이트)가 있고, 클립은 볼트 키에서 만든 암호화 키로 암호화한다.
 * 볼트 키는 볼트 암호에서 PBKDF2로 만든 <i>잠금 키</i>로 감싸서(AES-GCM) 서버에 맡긴다. 그래서 암호를 바꿔도
 * 볼트 키를 다시 감싸기만 하면 되고 클립은 건드리지 않는다. 서버는 볼트 암호를 모르니 감싼 키를 풀 수 없다.</p>
 *
 * <p>암호문 형식은 모두 {@code IV(12바이트) ‖ 암호문 ‖ GCM 태그(16바이트)}. AAD(추가 인증 데이터)로 용도를 묶어서
 * 서버가 원본/썸네일/텍스트나 다른 사용자의 감싼 키를 바꿔치기하면 복호화가 실패하게 한다.</p>
 */
public final class VaultCrypto {
    /** 새 볼트를 만들 때의 PBKDF2 반복 횟수 (2023 OWASP 권장치) */
    public static final int ITERATIONS = 600_000;
    /** 이보다 약한 반복 횟수는 거부한다 (서버가 낮은 값을 내려줘도 따르지 않게) */
    public static final int MIN_ITERATIONS = 100_000;
    /** 하위 키 용도 이름: 클립 암호화 / 중복 판별 해시 */
    public static final String ENC = "clipvault-enc-v1";
    public static final String HASH = "clipvault-hash-v1";

    private static final SecureRandom RNG = new SecureRandom();
    private static final int IV = 12, TAG_BITS = 128;

    /** 서버에 맡기는 값: salt(base64), 반복 횟수, 감싼 볼트 키(base64). */
    public record Wrapped(String salt, int iterations, String wrappedKey) {
    }

    /** 볼트 암호가 틀렸다 (감싼 키의 GCM 태그 검증 실패). */
    public static final class BadPassphraseException extends RuntimeException {
        BadPassphraseException() {
            super("wrong vault passphrase");
        }
    }

    private VaultCrypto() {
    }

    /** 무작위 볼트 키 32바이트. */
    public static byte[] newVaultKey() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return k;
    }

    /** 볼트 키를 볼트 암호로 감싼다 (새 salt, {@link #ITERATIONS}회). AAD = "clipvault-vault-v1:" + userId. */
    public static Wrapped wrap(byte[] vaultKey, char[] passphrase, String userId) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] kek = kek(passphrase, salt, ITERATIONS);
        byte[] sealed = seal(kek, vaultKey, vaultAad(userId));
        Arrays.fill(kek, (byte) 0);
        Base64.Encoder b64 = Base64.getEncoder();
        return new Wrapped(b64.encodeToString(salt), ITERATIONS, b64.encodeToString(sealed));
    }

    /**
     * 감싼 볼트 키를 볼트 암호로 푼다.
     *
     * @throws BadPassphraseException 암호가 틀렸거나 다른 사용자의 값
     * @throws IllegalArgumentException 반복 횟수가 {@link #MIN_ITERATIONS} 미만이거나 형식 오류
     */
    public static byte[] unwrap(Wrapped w, char[] passphrase, String userId) {
        if (w.iterations() < MIN_ITERATIONS) throw new IllegalArgumentException("iterations too low: " + w.iterations());
        byte[] kek = kek(passphrase, Base64.getDecoder().decode(w.salt()), w.iterations());
        try {
            return open(kek, Base64.getDecoder().decode(w.wrappedKey()), vaultAad(userId));
        } catch (IllegalArgumentException e) {
            throw new BadPassphraseException();
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    /** 볼트 키에서 용도별 하위 키 (HMAC-SHA256(볼트 키, 용도 이름)). 한 키를 두 용도로 쓰지 않으려고 나눈다. */
    public static byte[] subKey(byte[] vaultKey, String label) {
        return hmac(vaultKey, label.getBytes(StandardCharsets.UTF_8));
    }

    /** AES-256-GCM 암호화. 결과 = IV ‖ 암호문 ‖ 태그 (평문 + 28바이트). */
    public static byte[] seal(byte[] key, byte[] plain, String aad) {
        try {
            byte[] iv = new byte[IV];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain);
            return ByteBuffer.allocate(IV + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@link #seal}의 반대. 키·AAD가 다르거나 값이 바뀌었으면 IllegalArgumentException. */
    public static byte[] open(byte[] key, byte[] sealed, String aad) {
        if (sealed.length < IV + TAG_BITS / 8) throw new IllegalArgumentException("ciphertext too short");
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, sealed, 0, IV));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(sealed, IV, sealed.length - IV);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("cannot decrypt", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("cannot decrypt", e);
        }
    }

    /** HMAC-SHA256 결과를 소문자 hex 64자로 (중복 판별 해시). */
    public static String hmacHex(byte[] key, byte[] data) {
        return HexFormat.of().formatHex(hmac(key, data));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 볼트 암호 → 잠금 키 (PBKDF2-HMAC-SHA256, 256비트). 0.3~0.5초 걸리므로 화면 스레드에서 부르지 말 것. */
    static byte[] kek(char[] passphrase, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String vaultAad(String userId) {
        return "clipvault-vault-v1:" + userId;
    }
}
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :tray-client:test --tests '*VaultCryptoTest'` → PASS (5 tests)

- [ ] **Step 5: 커밋**

```bash
git add tray-client/src/main/java/com/clipvault/client/crypto/VaultCrypto.java tray-client/src/test/java/com/clipvault/client/crypto/VaultCryptoTest.java
git commit -m "feat(tray-client): 종단간 암호화 함수 (PBKDF2 봉투 암호화, AES-GCM, HMAC)"
```

---

### Task 5: 볼트 상태 `Vault`(DPAPI) + 앱 썸네일 + multipart 도우미 (트레이 앱)

**Files:**
- Create: `tray-client/src/main/java/com/clipvault/client/crypto/Vault.java`
- Modify: `tray-client/src/main/java/com/clipvault/client/clipboard/Images.java`
- Create: `tray-client/src/main/java/com/clipvault/client/network/Multipart.java`
- Test: `tray-client/src/test/java/com/clipvault/client/crypto/VaultTest.java`, `tray-client/src/test/java/com/clipvault/client/clipboard/ImagesTest.java`(추가), `tray-client/src/test/java/com/clipvault/client/network/MultipartTest.java`

**Interfaces:**
- Consumes: `VaultCrypto.*` (Task 4)
- Produces:
  - `Vault`: `boolean ready()`, `int version()`, `byte[] vaultKey()`, `void unlock(byte[] vaultKey, int version)` (메모리 + DPAPI 저장), `boolean load()`, `void lock()`,
    `String sealText(String)`, `String openText(String base64)`, `byte[] seal(byte[], String aad)`, `byte[] open(byte[], String aad)`, `String hash(byte[])`,
    상수 `TEXT = "text-v1"`, `IMAGE = "image-v1"`, `THUMB = "thumb-v1"`; 테스트용 생성자 `Vault(String prefsKey)`
  - `Images.thumbnail(BufferedImage src, int max)` → PNG 바이트, `Images.THUMB = 240`
  - `Multipart`: `Multipart field(String name, String value)`, `Multipart file(String name, byte[] data)`, `byte[] body()`, `String contentType()`

- [ ] **Step 1: 실패하는 테스트 3개**

`VaultTest.java`:

```java
package com.clipvault.client.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class VaultTest {
    @Test
    void sealOpenAndHash() {
        Vault v = new Vault("test.vault." + System.nanoTime());
        assertFalse(v.ready());
        byte[] vk = VaultCrypto.newVaultKey();
        v.unlockInMemory(vk, 3);
        assertTrue(v.ready());
        assertEquals(3, v.version());
        assertEquals("안녕 hello", v.openText(v.sealText("안녕 hello")));
        byte[] png = {1, 2, 3};
        assertArrayEquals(png, v.open(v.seal(png, Vault.IMAGE), Vault.IMAGE));
        assertThrows(IllegalArgumentException.class, () -> v.open(v.seal(png, Vault.IMAGE), Vault.THUMB));
        assertEquals(VaultCrypto.hmacHex(VaultCrypto.subKey(vk, VaultCrypto.HASH), "x".getBytes(StandardCharsets.UTF_8)),
                v.hash("x".getBytes(StandardCharsets.UTF_8)));
        v.lock();
        assertFalse(v.ready());
    }

    /** DPAPI는 윈도우에만 있다 (CI는 리눅스라 건너뜀) */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void persistsWithDpapi() {
        String key = "test.vault." + System.nanoTime();
        Vault a = new Vault(key);
        byte[] vk = VaultCrypto.newVaultKey();
        a.unlock(vk, 7);
        Vault b = new Vault(key);
        assertTrue(b.load());
        assertEquals(7, b.version());
        assertArrayEquals(vk, b.vaultKey());
        b.lock();
        assertFalse(new Vault(key).load(), "lock() removes the stored key");
    }
}
```

`ImagesTest.java`에 추가 (기존 import에 `java.awt.image.BufferedImage`가 없으면 추가):

```java
    @Test
    void thumbnailFitsLongSide() {
        BufferedImage wide = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        BufferedImage t = Images.fromPng(Images.thumbnail(wide, 240));
        assertEquals(240, t.getWidth());
        assertEquals(135, t.getHeight());
        BufferedImage tall = new BufferedImage(100, 400, BufferedImage.TYPE_INT_RGB);
        BufferedImage t2 = Images.fromPng(Images.thumbnail(tall, 240));
        assertEquals(60, t2.getWidth());
        assertEquals(240, t2.getHeight());
        BufferedImage small = new BufferedImage(50, 30, BufferedImage.TYPE_INT_RGB);
        BufferedImage t3 = Images.fromPng(Images.thumbnail(small, 240));
        assertEquals(50, t3.getWidth(), "smaller than max: original size");
        assertEquals(30, t3.getHeight());
    }
```

`MultipartTest.java`:

```java
package com.clipvault.client.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MultipartTest {
    @Test
    void buildsFieldsAndFiles() {
        Multipart m = new Multipart().field("width", "12").file("image", new byte[]{0, (byte) 0xFF, 10});
        String ct = m.contentType();
        assertTrue(ct.startsWith("multipart/form-data; boundary="));
        String boundary = ct.substring(ct.indexOf('=') + 1);
        byte[] body = m.body();
        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("--" + boundary + "\r\n"));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"width\"\r\n\r\n12\r\n"));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"image\"; filename=\"image\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n\u0000ÿ\n\r\n"));
        assertTrue(text.endsWith("--" + boundary + "--\r\n"));
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :tray-client:test` → FAIL (클래스·메서드 없음)

- [ ] **Step 3: `Vault` 구현**

```java
package com.clipvault.client.crypto;

import com.sun.jna.platform.win32.Crypt32Util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.prefs.Preferences;

/**
 * 이 PC의 볼트 상태: 볼트 키(메모리)와 그 키로 만든 하위 키, 서버 볼트 버전.
 *
 * <p>볼트 키는 윈도우 DPAPI({@link Crypt32Util#cryptProtectData})로 잠가서 로그인 정보와 같은 Preferences(레지스트리)에 둔다.
 * DPAPI로 잠근 값은 같은 윈도우 계정으로 로그인했을 때만 풀리므로, 레지스트리 값만 빼 가서는 쓸 수 없다.
 * 그래서 볼트 암호는 PC마다 처음 한 번만 입력하면 된다. 로그아웃하면 {@link #lock}으로 지운다.</p>
 *
 * <p>여러 스레드(업로드, 받기, 화면)에서 읽으므로 키 묶음을 한 번에 바꿔 끼운다(volatile 참조 하나).</p>
 */
public final class Vault {
    /** 클립 종류별 AAD (서버가 바꿔치기하면 복호화 실패) */
    public static final String TEXT = "text-v1", IMAGE = "image-v1", THUMB = "thumb-v1";

    private static final Preferences PREFS = Preferences.userRoot().node("com/clipvault/client");

    /** 풀린 키 묶음. null = 잠김. */
    private record Keys(byte[] vaultKey, byte[] enc, byte[] hash, int version) {
    }

    private final String prefsKey;
    private volatile Keys keys;

    public Vault() {
        this("vault");
    }

    /** 테스트용: 저장 이름을 바꿔 실제 볼트와 섞이지 않게 한다. */
    Vault(String prefsKey) {
        this.prefsKey = prefsKey;
    }

    public boolean ready() { return keys != null; }

    /** 서버 볼트 버전 (잠김이면 0). 업로드 헤더 X-Vault-Version에 쓴다. */
    public int version() { Keys k = keys; return k == null ? 0 : k.version(); }

    /** 볼트 키 사본 (암호 변경 때 다시 감싸는 데 쓴다). 잠김이면 IllegalStateException. */
    public byte[] vaultKey() { return need().vaultKey().clone(); }

    /** 볼트 키를 받아 쓸 수 있게 하고 DPAPI로 잠가 저장한다. */
    public void unlock(byte[] vaultKey, int version) {
        unlockInMemory(vaultKey, version);
        PREFS.put(prefsKey + ".key", Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(vaultKey)));
        PREFS.putInt(prefsKey + ".version", version);
    }

    /** 저장 없이 메모리에만 (테스트, 그리고 unlock의 앞부분). */
    void unlockInMemory(byte[] vaultKey, int version) {
        keys = new Keys(vaultKey.clone(), VaultCrypto.subKey(vaultKey, VaultCrypto.ENC),
                VaultCrypto.subKey(vaultKey, VaultCrypto.HASH), version);
    }

    /** 저장된 볼트 키를 DPAPI로 풀어 불러온다. 없거나 못 풀면(다른 윈도우 계정, 손상) false. */
    public boolean load() {
        String stored = PREFS.get(prefsKey + ".key", null);
        if (stored == null) return false;
        try {
            unlockInMemory(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored)), PREFS.getInt(prefsKey + ".version", 0));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 잠근다: 메모리의 키를 지우고 저장된 값도 지운다 (로그아웃, 볼트 변경 감지). */
    public void lock() {
        Keys k = keys;
        keys = null;
        if (k != null) {
            Arrays.fill(k.vaultKey(), (byte) 0);
            Arrays.fill(k.enc(), (byte) 0);
            Arrays.fill(k.hash(), (byte) 0);
        }
        PREFS.remove(prefsKey + ".key");
        PREFS.remove(prefsKey + ".version");
    }

    /** 텍스트 → base64 암호문 (서버 content). */
    public String sealText(String text) {
        return Base64.getEncoder().encodeToString(seal(text.getBytes(StandardCharsets.UTF_8), TEXT));
    }

    /** base64 암호문 → 텍스트. 못 풀면 IllegalArgumentException. */
    public String openText(String base64) {
        return new String(open(Base64.getDecoder().decode(base64), TEXT), StandardCharsets.UTF_8);
    }

    public byte[] seal(byte[] plain, String aad) { return VaultCrypto.seal(need().enc(), plain, aad); }

    public byte[] open(byte[] sealed, String aad) { return VaultCrypto.open(need().enc(), sealed, aad); }

    /** 중복 판별 해시 (contentHash). */
    public String hash(byte[] plain) { return VaultCrypto.hmacHex(need().hash(), plain); }

    private Keys need() {
        Keys k = keys;
        if (k == null) throw new IllegalStateException("vault locked");
        return k;
    }
}
```

- [ ] **Step 4: `Images.thumbnail` 추가** (`MAX_BYTES` 아래에 상수, 메서드는 `toPng` 아래)

```java
    /** 썸네일 긴 변 (px). 서버가 옛 방식에서 만들던 크기와 같다. */
    public static final int THUMB = 240;

    /**
     * 긴 변이 max px가 되도록 비율을 유지해 줄인 PNG (원본이 더 작으면 원본 크기).
     * 종단간 암호화에서는 서버가 이미지를 볼 수 없으므로 썸네일을 앱이 만들어 암호화해서 올린다.
     */
    public static byte[] thumbnail(BufferedImage src, int max) {
        double scale = Math.min(1.0, (double) max / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return toPng(out);
    }
```

- [ ] **Step 5: `Multipart` 구현**

```java
package com.clipvault.client.network;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * multipart/form-data 본문 작성기 (JDK HttpClient에는 없어서 직접 만든다).
 * e2e 이미지 업로드에서 원본·썸네일 암호문(바이너리)과 가로·세로·해시(문자열)를 한 요청에 담는 데 쓴다.
 */
public final class Multipart {
    private final String boundary = "----clipvault" + UUID.randomUUID().toString().replace("-", "");
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** 문자열 필드. */
    public Multipart field(String name, String value) {
        write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
        return this;
    }

    /** 바이너리 파일 파트 (파일 이름 = 파트 이름). */
    public Multipart file(String name, byte[] data) {
        write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + name
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        out.writeBytes(data);
        write("\r\n");
        return this;
    }

    /** 끝 표시를 붙인 전체 본문. 한 번만 부를 것. */
    public byte[] body() {
        write("--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    private void write(String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 6: 통과 확인** — Run: `./gradlew :tray-client:test` → 전부 PASS (윈도우에서는 DPAPI 테스트 포함)

- [ ] **Step 7: 커밋**

```bash
git add tray-client/
git commit -m "feat(tray-client): 볼트 상태(DPAPI 저장), 앱 썸네일 생성, multipart 작성기"
```

---

### Task 6: `ApiClient` 볼트·e2e·이전 API (트레이 앱)

**Files:**
- Modify: `tray-client/src/main/java/com/clipvault/client/network/ApiClient.java`

**Interfaces:**
- Consumes: `VaultCrypto.Wrapped` (Task 4), `Multipart` (Task 5)
- Produces:
  - `public volatile int vaultVersion` (0이면 헤더 안 붙임)
  - `JsonNode getVault()` (없으면 null), `int createVault(Wrapped)`, `void changeVault(Wrapped, int version)`, `int resetVault(Wrapped)`
  - `void postClip(String sealedContent, String hash)` (e2e, 기존 `postClip(String)` 대체)
  - `JsonNode postImage(byte[] sealedImage, byte[] sealedThumb, int width, int height, String hash)` (기존 `postImage(byte[])` 대체)
  - `JsonNode listLegacy()`, `void putE2eText(String id, String sealedContent, String hash)`, `void putE2eImage(String id, byte[] sealedImage, byte[] sealedThumb, String hash)`

- [ ] **Step 1: 헤더** — 필드 추가(클래스 상단 `session` 필드 근처):

```java
    /**
     * 서버 볼트 버전. 0이 아니면 모든 요청에 X-Vault-Version 헤더로 붙인다. 서버는 업로드에서만 본다:
     * 헤더가 현재 버전과 다르면 409(다른 PC에서 초기화됨), 볼트가 있는데 헤더가 없으면 426(구버전 앱).
     */
    public volatile int vaultVersion;
```

`exchange`의 `if (token != null) ...` 다음 줄에:

```java
            if (vaultVersion > 0) b.header("X-Vault-Version", String.valueOf(vaultVersion));
```

- [ ] **Step 2: 볼트 API** (`// --- 설정 ---` 위에 섹션 추가)

```java
    // --- 볼트 (종단간 암호화) ---

    /** 서버의 감싼 볼트 키 {salt, iterations, wrappedKey, version}. 볼트가 없으면 null. */
    public JsonNode getVault() {
        try {
            return authed("GET", "/api/vault", null);
        } catch (ApiException e) {
            if (e.status == 404) return null;
            throw e;
        }
    }

    /** 볼트 처음 만들기. 만든 버전(1). 이미 있으면 409. */
    public int createVault(VaultCrypto.Wrapped w) {
        return authed("POST", "/api/vault", vaultBody(w, null)).path("version").asInt();
    }

    /** 볼트 암호 변경(다시 감싸기). version이 서버와 다르면 409. */
    public void changeVault(VaultCrypto.Wrapped w, int version) {
        authed("PUT", "/api/vault", vaultBody(w, version));
    }

    /** 볼트 초기화: 서버가 모든 클립을 지우고 새 키 저장. 새 버전. */
    public int resetVault(VaultCrypto.Wrapped w) {
        return authed("POST", "/api/vault/reset", vaultBody(w, null)).path("version").asInt();
    }

    private static Map<String, Object> vaultBody(VaultCrypto.Wrapped w, Integer version) {
        Map<String, Object> m = new java.util.HashMap<>(Map.of("salt", w.salt(), "iterations", w.iterations(), "wrappedKey", w.wrappedKey()));
        if (version != null) m.put("version", version);
        return m;
    }
```

import `com.clipvault.client.crypto.VaultCrypto`.

- [ ] **Step 3: e2e 업로드로 교체** — 기존 `postClip(String)`과 `postImage(byte[])`를 지우고:

```java
    /** e2e 텍스트 업로드 (content = 볼트로 암호화한 base64, hash = HMAC hex). */
    public void postClip(String sealedContent, String hash) {
        authed("POST", "/api/clips", Map.of("content", sealedContent, "contentHash", hash));
    }

    /** e2e 이미지 업로드 (multipart). 큰 파일이라 60초까지 기다린다. */
    public JsonNode postImage(byte[] sealedImage, byte[] sealedThumb, int width, int height, String hash) {
        Multipart m = new Multipart().file("image", sealedImage).file("thumb", sealedThumb)
                .field("width", String.valueOf(width)).field("height", String.valueOf(height)).field("contentHash", hash);
        return authed(t -> json(exchange("POST", "/api/clips/image", HttpRequest.BodyPublishers.ofByteArray(m.body()),
                m.contentType(), t, LONG)));
    }

    // --- 기존 데이터 이전 ---

    /** 서버 암호화로 저장된 옛 클립 최대 100개 (평문으로 온다). */
    public JsonNode listLegacy() { return authed("GET", "/api/clips?legacy=true", null); }

    /** 옛 텍스트 클립을 같은 자리에서 e2e로. */
    public void putE2eText(String id, String sealedContent, String hash) {
        authed("PUT", "/api/clips/" + enc(id) + "/e2e", Map.of("content", sealedContent, "contentHash", hash));
    }

    /** 옛 이미지 클립을 같은 자리에서 e2e로 (multipart). */
    public void putE2eImage(String id, byte[] sealedImage, byte[] sealedThumb, String hash) {
        Multipart m = new Multipart().file("image", sealedImage).file("thumb", sealedThumb).field("contentHash", hash);
        authed(t -> exchange("PUT", "/api/clips/" + enc(id) + "/e2e", HttpRequest.BodyPublishers.ofByteArray(m.body()),
                m.contentType(), t, LONG));
    }
```

- [ ] **Step 4: 컴파일 확인** — Run: `./gradlew :tray-client:compileJava`
Expected: `TrayApp`에서 `postClip(String)`/`postImage(byte[])` 호출이 컴파일 오류 → Task 8에서 고친다. 이 태스크에서는 `TrayApp`의 두 호출을 임시로 아래처럼 바꿔 컴파일만 통과시킨다(동작 연결은 Task 8):

`onLocalCopy`의 `async(() -> api.postClip(text));` → `async(() -> uploadText(text));`
`onLocalImage`의 `api.postImage(png);` → `uploadImage(img, png);`
그리고 TrayApp에 빈 껍데기 대신 **Task 8 Step 4의 `uploadText`/`uploadImage` 구현을 그대로** 넣는다(Vault 필드 포함). → 이 태스크와 Task 8을 한 사람이 이어서 하거나, Task 6 커밋을 Task 8 커밋과 합친다.

> 실행자 메모: Task 6과 Task 8은 같은 파일(`TrayApp`)의 호출부가 엮여 있어 **Task 6은 커밋하지 않고 Task 8과 함께 커밋**한다.

---

### Task 7: 볼트 창 `VaultDialog` (트레이 앱, UI 확인 필요)

**Files:**
- Create: `tray-client/src/main/java/com/clipvault/client/ui/VaultDialog.java`

**Interfaces:**
- Produces:
  - `@FunctionalInterface interface Check { String run(char[][] inputs); }` — 백그라운드에서 호출, null = 성공(창 닫힘), 문자열 = 오류 문구(창에 표시)
  - `static boolean create(Check check)` — 입력: [새 암호]
  - `static boolean unlock(String note, Check check, Runnable onForgot)` — 입력: [암호]. "암호를 잊었어요" 누르면 창을 닫고 onForgot 실행
  - `static boolean change(Check check)` — 입력: [현재 암호, 새 암호]
  - `static boolean reset(Check check)` — 입력: [새 암호]
  - 모두 EDT에서 호출, 모달, 성공 true / 취소 false. 새 암호는 창이 확인 칸 일치·8자 이상을 먼저 검사한다.

- [ ] **Step 1: 구현** (기존 `LoginDialog`/`HotKeyDialog`와 같은 `Theme` 스타일)

```java
package com.clipvault.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;

/**
 * 볼트(종단간 암호화) 창: 만들기, 입력, 변경, 초기화 확인.
 *
 * <p>모두 모달이고 화면 스레드(EDT)에서 부른다. [확인]을 누르면 입력값 검사(새 암호 = 확인 칸, 8자 이상) 뒤
 * {@link Check}를 <b>백그라운드</b>에서 실행한다(PBKDF2와 서버 요청이 0.5초 이상 걸리므로). 그동안 버튼은 "확인 중…".
 * Check가 null을 돌려주면 성공으로 창을 닫고, 문자열을 돌려주면 그 문구를 빨간 글씨로 보여 주고 다시 입력받는다.</p>
 */
public final class VaultDialog {
    /** 새 볼트 암호 최소 길이 */
    static final int MIN_LENGTH = 8;

    /** 입력값을 받아 실제 일을 하는 함수 (백그라운드). null = 성공, 아니면 사용자에게 보여 줄 오류 문구. */
    @FunctionalInterface
    public interface Check {
        String run(char[][] inputs);
    }

    private VaultDialog() {
    }

    /** 볼트 암호 만들기 (처음 한 번). inputs = [새 암호]. */
    public static boolean create(Check check) {
        return show("볼트 암호 만들기",
                "클립을 이 PC에서 암호화해서 올립니다. 서버도 내용을 볼 수 없습니다.",
                "볼트 암호를 잊으면 클립을 복구할 수 없고 초기화만 할 수 있습니다.",
                List.of(), true, "만들기", check, null, null);
    }

    /** 볼트 암호 입력 (새 PC, 다른 PC에서 초기화됨). inputs = [암호]. note가 있으면 설명 대신 보여 준다. */
    public static boolean unlock(String note, Check check, Runnable onForgot) {
        return show("볼트 암호 입력",
                note != null ? note : "이 계정의 클립을 열려면 볼트 암호를 입력하세요. 이 PC에서는 한 번만 입력하면 됩니다.",
                null, List.of("볼트 암호"), false, "열기", check, "암호를 잊었어요", onForgot);
    }

    /** 볼트 암호 변경. inputs = [현재 암호, 새 암호]. */
    public static boolean change(Check check) {
        return show("볼트 암호 변경", "다른 PC는 다시 입력하지 않아도 됩니다.", null,
                List.of("현재 볼트 암호"), true, "변경", check, null, null);
    }

    /** 볼트 초기화 (암호를 잊었을 때). inputs = [새 암호]. */
    public static boolean reset(Check check) {
        return show("볼트 초기화", "새 볼트 암호로 다시 시작합니다.",
                "모든 클립(고정한 클립 포함)이 삭제되고 되돌릴 수 없습니다. 다른 PC에서는 새 암호를 다시 입력해야 합니다.",
                List.of(), true, "초기화", check, null, null);
    }

    /**
     * 공통 창.
     *
     * @param plainLabels 그냥 입력 칸들 (확인 칸 없음)
     * @param withNew     true면 "새 볼트 암호" + "새 볼트 암호 확인" 칸을 뒤에 붙이고 일치·길이를 검사한다
     */
    private static boolean show(String titleText, String descText, String warningText, List<String> plainLabels,
                                boolean withNew, String okText, Check check, String linkText, Runnable onLink) {
        JDialog d = new JDialog((Frame) null, titleText, true);
        d.setIconImages(List.of(Theme.appIcon(16, false), Theme.appIcon(32, false)));
        boolean[] ok = {false};
        boolean[] link = {false};

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        java.util.List<JPasswordField> inputs = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>(plainLabels);
        if (withNew) {
            labels.add("새 볼트 암호 (" + MIN_LENGTH + "자 이상)");
            labels.add("새 볼트 암호 확인");
        }
        for (String l : labels) {
            JLabel label = new JLabel(l);
            label.setFont(Theme.font(12f, Font.PLAIN));
            g.insets = new Insets(8, 0, 4, 0);
            fields.add(label, g);
            JPasswordField f = new JPasswordField(22);
            g.insets = new Insets(0, 0, 0, 0);
            fields.add(f, g);
            inputs.add(f);
        }

        JLabel error = new JLabel(" ");
        error.setFont(Theme.font(12f, Font.PLAIN));
        error.setForeground(Theme.DANGER);

        JButton okButton = new JButton(okText);
        JButton cancel = new JButton("취소");
        cancel.addActionListener(e -> d.dispose());
        d.getRootPane().setDefaultButton(okButton);
        okButton.addActionListener(e -> {
            char[][] values = inputs.stream().map(JPasswordField::getPassword).toArray(char[][]::new);
            if (withNew) {
                char[] nw = values[values.length - 2], again = values[values.length - 1];
                if (nw.length < MIN_LENGTH) { error.setText("볼트 암호는 " + MIN_LENGTH + "자 이상이어야 합니다."); return; }
                if (!Arrays.equals(nw, again)) { error.setText("새 볼트 암호가 서로 다릅니다."); return; }
                values = Arrays.copyOf(values, values.length - 1); // 확인 칸은 넘기지 않는다
            }
            char[][] submit = values;
            okButton.setEnabled(false);
            cancel.setEnabled(false);
            okButton.setText("확인 중…");
            error.setText(" ");
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() {
                    try {
                        return check.run(submit);
                    } catch (RuntimeException ex) {
                        return "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.";
                    } finally {
                        for (char[] v : submit) Arrays.fill(v, '\0'); // 암호를 메모리에 오래 두지 않는다
                    }
                }

                @Override protected void done() {
                    String msg;
                    try { msg = get(); } catch (Exception ex) { msg = "알 수 없는 오류가 발생했습니다."; }
                    if (msg == null) { ok[0] = true; d.dispose(); return; }
                    error.setText(msg);
                    okButton.setText(okText);
                    okButton.setEnabled(true);
                    cancel.setEnabled(true);
                }
            }.execute();
        });

        JLabel title = new JLabel(titleText);
        title.setFont(Theme.font(17f, Font.BOLD));
        title.setIcon(new ImageIcon(Theme.appIcon(22, false)));
        title.setIconTextGap(8);
        JLabel desc = new JLabel("<html><div style='width:300px'>" + descText + "</div></html>");
        desc.setFont(Theme.font(12f, Font.PLAIN));
        desc.setForeground(Theme.muted());
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.add(title, BorderLayout.NORTH);
        header.add(desc, BorderLayout.CENTER);
        if (warningText != null) {
            JLabel warn = new JLabel("<html><div style='width:300px'>⚠ " + warningText + "</div></html>");
            warn.setFont(Theme.font(12f, Font.BOLD));
            warn.setForeground(Theme.DANGER);
            header.add(warn, BorderLayout.SOUTH);
        }

        JPanel buttons = new JPanel(new BorderLayout());
        if (linkText != null) {
            JLabel l = new JLabel("<html><u>" + linkText + "</u></html>");
            l.setFont(Theme.font(12f, Font.PLAIN));
            l.setForeground(Theme.ACCENT);
            l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            l.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { link[0] = true; d.dispose(); }
            });
            buttons.add(l, BorderLayout.WEST);
        }
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(cancel);
        right.add(okButton);
        buttons.add(right, BorderLayout.EAST);

        JPanel footer = new JPanel(new BorderLayout(0, 10));
        footer.add(error, BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
        root.add(header, BorderLayout.NORTH);
        root.add(fields, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(null);
        d.setVisible(true); // 모달: 닫힐 때까지 여기서 기다린다
        if (link[0] && onLink != null) onLink.run();
        return ok[0];
    }
}
```

- [ ] **Step 2: 컴파일** — Run: `./gradlew :tray-client:compileJava` (Task 6 메모대로 TrayApp 오류가 남아 있으면 Task 8 이후에 확인)

- [ ] **Step 3: 오프스크린 스크린샷** — 스크래치 폴더에 아래 하네스를 만들어 4종 × 라이트/다크 PNG 생성 (모달이라 `setVisible` 대신 내부 패널을 그린다: 하네스는 리플렉션 없이 `JDialog`를 띄우지 않고 렌더링하기 위해, 창이 뜨면 `Window.getWindows()`에서 찾아 `getRootPane().printAll(...)` 후 dispose하는 타이머를 먼저 건다)

```java
import com.clipvault.client.ui.Theme;
import com.clipvault.client.ui.VaultDialog;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** VaultDialog 4종을 printAll로 PNG 저장. args[0] = 출력 폴더 접두어 */
public class VaultShot {
    public static void main(String[] a) throws Exception {
        Theme.setup();
        String[] names = {"create", "unlock", "change", "reset"};
        for (String n : names) {
            Timer t = new Timer(400, e -> {
                for (Window w : Window.getWindows()) if (w instanceof JDialog d && d.isShowing()) {
                    try {
                        JRootPane root = d.getRootPane();
                        BufferedImage img = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
                        root.printAll(img.createGraphics());
                        ImageIO.write(img, "png", new File(a[0] + "-" + n + ".png"));
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                    d.dispose();
                }
            });
            t.setRepeats(false);
            t.start();
            SwingUtilities.invokeAndWait(() -> {
                switch (n) {
                    case "create" -> VaultDialog.create(x -> null);
                    case "unlock" -> VaultDialog.unlock(null, x -> null, () -> { });
                    case "change" -> VaultDialog.change(x -> null);
                    default -> VaultDialog.reset(x -> null);
                }
            });
        }
        System.exit(0);
    }
}
```

실행: `./gradlew :tray-client:installDist` 후 `javac -cp "tray-client/build/install/tray-client/lib/*" -d out VaultShot.java`, `java -Dclipvault.theme=light -cp "out;tray-client/build/install/tray-client/lib/*" VaultShot vault-light` (dark도).
결과 PNG 8장을 사용자에게 보내고 **승인받은 뒤** Task 8과 함께 커밋한다.

---

### Task 8: `TrayApp` 연결 — 볼트 확인, 암호화 업로드, 복호화 받기, 메뉴, 이전 (트레이 앱)

**Files:**
- Modify: `tray-client/src/main/java/com/clipvault/client/TrayApp.java`
- Modify: `tray-client/src/main/java/com/clipvault/client/ui/ClipListWindow.java`
- (Task 6, 7 변경과 함께 커밋)

**Interfaces:**
- Consumes: `Vault` (Task 5), `VaultCrypto` (Task 4), `ApiClient` 새 메서드·`vaultVersion` (Task 6), `VaultDialog` (Task 7), `Images.thumbnail`/`Images.THUMB` (Task 5)
- Produces: `ClipListWindow.Thumbs`가 `Image get(JsonNode clip, Runnable onReady)`로 바뀜. 받은 클립 JSON에 복호화 실패 시 `"locked": true`.

- [ ] **Step 1: `ClipListWindow.Thumbs` 시그니처 변경**

```java
    @FunctionalInterface
    public interface Thumbs {
        /** clip = 클립 JSON 전체 (e2e 여부를 보고 복호화해야 해서 id만으로는 부족하다). */
        Image get(JsonNode clip, Runnable onReady);
    }
```

렌더러의 `thumbs.get(clip.path("id").asText(), repaint)` → `thumbs.get(clip, repaint)`.

- [ ] **Step 2: 필드와 로그아웃 처리** — TrayApp 필드에:

```java
    /** 이 PC의 볼트(종단간 암호화 키). 잠겨 있으면 업로드하지 않고 받은 클립도 풀지 못한다. */
    private final Vault vault = new Vault();
```

`logout()`의 `session.clear();` 앞과 `localLogout()`의 `session.clear();` 앞에 각각:

```java
        vault.lock();          // 이 PC에 저장된 볼트 키도 지운다
        api.vaultVersion = 0;
```

import `com.clipvault.client.crypto.Vault`, `com.clipvault.client.crypto.VaultCrypto`, `com.clipvault.client.ui.VaultDialog`, `java.nio.charset.StandardCharsets`.

- [ ] **Step 3: 볼트 확인 흐름** — `onLoggedIn()` 끝에 `async(this::ensureVault);` 추가, `onConnected()`의 async 본문 맨 앞에 `ensureVault();` 추가. 새 메서드들:

```java
    // --- 볼트 (종단간 암호화) ---

    /**
     * (백그라운드) 볼트 상태를 서버와 맞춘다. 로그인 직후, 앱 시작, 실시간 연결이 다시 붙을 때마다 부른다.
     * 없으면 만들기 창, 이 PC에 키가 없거나 버전이 다르면 입력 창, 맞으면 남은 옛 클립을 옮긴다.
     */
    private void ensureVault() {
        JsonNode v;
        try {
            v = api.getVault();
        } catch (ApiClient.ApiException e) {
            if (e.status == 401) throw e;
            return;
        } catch (RuntimeException e) {
            // 오프라인: 저장된 키가 있으면 그대로 쓴다 (버전이 바뀌었으면 나중에 업로드할 때 409로 알게 된다)
            if (!vault.ready() && vault.load()) api.vaultVersion = vault.version();
            SwingUtilities.invokeLater(this::updateTooltip);
            return;
        }
        if (v == null) {
            SwingUtilities.invokeLater(this::createVault);
            return;
        }
        int serverVersion = v.path("version").asInt();
        if (!vault.ready()) vault.load();
        if (vault.ready() && vault.version() == serverVersion) {
            api.vaultVersion = serverVersion;
            SwingUtilities.invokeLater(this::updateTooltip);
            migrateLegacy();
            return;
        }
        String note = vault.ready() ? "다른 PC에서 볼트가 초기화되었습니다. 새 볼트 암호를 입력하세요." : null;
        vault.lock();
        api.vaultVersion = 0;
        SwingUtilities.invokeLater(() -> unlockVault(v, note));
    }

    /** (EDT) 볼트 만들기 창. 성공하면 남은 옛 클립을 옮긴다. */
    private void createVault() {
        boolean done = VaultDialog.create(in -> {
            byte[] vk = VaultCrypto.newVaultKey();
            try {
                int version = api.createVault(VaultCrypto.wrap(vk, in[0], session.userId));
                vault.unlock(vk, version);
                api.vaultVersion = version;
                return null;
            } catch (ApiClient.ApiException e) {
                return e.status == 409 ? "다른 PC에서 방금 볼트를 만들었습니다. 창을 닫고 그 암호를 입력하세요." : e.getMessage();
            }
        });
        updateTooltip();
        if (done) async(this::migrateLegacy); else if (!vault.ready()) async(this::ensureVault);
    }

    /** (EDT) 볼트 입력 창. v = 서버의 감싼 키, note = 안내 문구(없으면 기본). "암호를 잊었어요"면 초기화. */
    private void unlockVault(JsonNode v, String note) {
        VaultCrypto.Wrapped w = new VaultCrypto.Wrapped(v.path("salt").asText(), v.path("iterations").asInt(), v.path("wrappedKey").asText());
        int version = v.path("version").asInt();
        boolean done = VaultDialog.unlock(note, in -> {
            try {
                vault.unlock(VaultCrypto.unwrap(w, in[0], session.userId), version);
                api.vaultVersion = version;
                return null;
            } catch (VaultCrypto.BadPassphraseException e) {
                return "볼트 암호가 맞지 않습니다.";
            } catch (IllegalArgumentException e) {
                return "서버의 볼트 설정이 올바르지 않습니다.";
            }
        }, this::resetVault);
        updateTooltip();
        if (done) async(this::migrateLegacy);
    }

    /** (EDT) 볼트 초기화 (암호를 잊었을 때): 모든 클립 삭제 후 새 키. */
    private void resetVault() {
        boolean done = VaultDialog.reset(in -> {
            byte[] vk = VaultCrypto.newVaultKey();
            int version = api.resetVault(VaultCrypto.wrap(vk, in[0], session.userId));
            vault.unlock(vk, version);
            api.vaultVersion = version;
            thumbs.clear();
            thumbsFailed.clear();
            return null;
        });
        updateTooltip();
        if (done) icon.displayMessage("ClipVault", "볼트를 초기화했습니다. 다른 PC에서는 새 볼트 암호를 입력하세요.", TrayIcon.MessageType.INFO);
    }

    /** (EDT) 메뉴 "볼트 암호 변경…": 현재 암호로 확인한 뒤 같은 볼트 키를 새 암호로 다시 감싼다. */
    private void changeVault() {
        if (!loggedIn) { showLogin(); return; }
        if (!vault.ready()) { async(this::ensureVault); return; }
        boolean done = VaultDialog.change(in -> {
            JsonNode v = api.getVault();
            if (v == null) return "서버에 볼트가 없습니다.";
            VaultCrypto.Wrapped current = new VaultCrypto.Wrapped(v.path("salt").asText(), v.path("iterations").asInt(), v.path("wrappedKey").asText());
            try {
                VaultCrypto.unwrap(current, in[0], session.userId); // 현재 암호 확인 (자리 비운 PC에서 남이 바꾸지 못하게)
            } catch (VaultCrypto.BadPassphraseException e) {
                return "현재 볼트 암호가 맞지 않습니다.";
            }
            try {
                api.changeVault(VaultCrypto.wrap(vault.vaultKey(), in[1], session.userId), v.path("version").asInt());
                return null;
            } catch (ApiClient.ApiException e) {
                return e.status == 409 ? "다른 PC에서 볼트가 초기화되었습니다." : e.getMessage();
            }
        });
        if (done) icon.displayMessage("ClipVault", "볼트 암호를 바꿨습니다.", TrayIcon.MessageType.INFO);
    }

    /** (백그라운드) 업로드에서 409를 받음 = 다른 PC에서 초기화됨. 키를 버리고 다시 확인(입력 창). */
    private void onVaultChanged() {
        vault.lock();
        api.vaultVersion = 0;
        ensureVault();
    }
```

- [ ] **Step 4: 암호화 업로드** — `onLocalCopy`, `onLocalImage`를 바꾸고 도우미 추가:

```java
    private void onLocalCopy(String text) {
        if (paused || !loggedIn || !vault.ready() || text.length() > MAX_CLIP) return;
        if (guard.shouldUpload(text)) async(() -> uploadText(text));
    }

    private void onLocalImage(BufferedImage img, String key) {
        if (paused || !loggedIn || !vault.ready() || !guard.shouldUpload("img:" + key)) return;
        async(() -> {
            byte[] png = Images.toPng(img);
            if (png.length > Images.MAX_BYTES) {
                SwingUtilities.invokeLater(() -> icon.displayMessage("ClipVault",
                        "이미지가 10MB를 넘어 동기화하지 않았습니다.", TrayIcon.MessageType.WARNING));
                return;
            }
            uploadImage(img, png);
        });
    }

    /** (백그라운드) 텍스트를 볼트로 암호화해서 올린다. 409면 볼트가 바뀐 것 → 다시 확인. */
    private void uploadText(String text) {
        withVault(() -> api.postClip(vault.sealText(text), vault.hash(text.getBytes(StandardCharsets.UTF_8))));
    }

    /** (백그라운드) 이미지: 썸네일을 앱에서 만들고 원본·썸네일을 각각 암호화해서 올린다. */
    private void uploadImage(BufferedImage img, byte[] png) {
        byte[] thumb = Images.thumbnail(img, Images.THUMB);
        withVault(() -> api.postImage(vault.seal(png, Vault.IMAGE), vault.seal(thumb, Vault.THUMB),
                img.getWidth(), img.getHeight(), vault.hash(png)));
    }

    /** 볼트를 쓰는 서버 요청 실행. 409(볼트 바뀜)·426은 볼트를 다시 확인하고, 그 밖의 예외는 그대로 던진다(async가 처리). */
    private void withVault(Runnable call) {
        try {
            call.run();
        } catch (ApiClient.ApiException e) {
            if (e.status == 409) { onVaultChanged(); return; }
            throw e;
        } catch (IllegalStateException e) {
            // 업로드 직전에 볼트가 잠김 (로그아웃 등): 조용히 버린다
        }
    }
```

- [ ] **Step 5: 받은 클립 복호화** — 도우미:

```java
    /** 화면에 보여 줄 수 없는 클립의 표시 문구 */
    private static final String LOCKED_TEXT = "🔒 열 수 없는 클립";

    /**
     * 서버에서 받은 클립 JSON을 화면용으로 연다 (제자리 수정 후 그대로 돌려줌).
     * e2e 텍스트는 content를 복호화한 평문으로 바꾸고, 못 풀면 locked=true와 안내 문구.
     * 이미지는 바이트를 받을 때 푼다(여기서는 볼트가 잠겨 있으면 locked만 표시). 옛 행(e2e=false)은 서버가 이미 평문으로 준다.
     */
    private JsonNode open(JsonNode clip) {
        if (!clip.path("e2e").asBoolean()) return clip;
        com.fasterxml.jackson.databind.node.ObjectNode o = (com.fasterxml.jackson.databind.node.ObjectNode) clip;
        if (!vault.ready()) {
            o.put("locked", true);
            if (!"IMAGE".equals(clip.path("type").asText())) o.put("content", LOCKED_TEXT);
            return clip;
        }
        if ("IMAGE".equals(clip.path("type").asText())) return clip;
        try {
            o.put("content", vault.openText(clip.path("content").asText()));
        } catch (RuntimeException e) {
            o.put("locked", true);
            o.put("content", LOCKED_TEXT);
        }
        return clip;
    }

    /** 배열의 클립을 모두 연다. */
    private JsonNode openAll(JsonNode clips) {
        clips.forEach(this::open);
        return clips;
    }
```

적용 위치:
- `showClips()` 맨 앞(로그인 확인 다음)에 `if (!vault.ready()) { async(this::ensureVault); return; }`, 그리고 `JsonNode pinned = openAll(api.listPinned()); JsonNode clips = openAll(api.listClips(ClipListWindow.PAGE));`
- `loadMore`의 `page = api.listClips(ClipListWindow.PAGE, before);` → `page = openAll(api.listClips(ClipListWindow.PAGE, before));`
- `onPush(JsonNode clip)` 첫 줄에 `open(clip);` (미리보기 문구와 `ClipListWindow.push(clip)`가 평문을 쓰게). 미리보기: `clip.path("locked").asBoolean()`이면 `preview = LOCKED_TEXT`.

- [ ] **Step 6: 고르기·썸네일 복호화**

`pick(JsonNode clip)` 맨 앞:

```java
        if (clip.path("locked").asBoolean()) {
            icon.displayMessage("ClipVault", "열 수 없는 클립입니다 (볼트 암호가 바뀌었을 수 있습니다).", TrayIcon.MessageType.WARNING);
            return;
        }
```

이미지 분기 `pickImage(clip.path("id").asText())` → `pickImage(clip)`, `pickImage`를:

```java
    private void pickImage(JsonNode clip) {
        async(() -> {
            try {
                byte[] bytes = api.getImage(clip.path("id").asText());
                if (clip.path("e2e").asBoolean()) bytes = vault.open(bytes, Vault.IMAGE); // e2e는 앱이 푼다
                BufferedImage img = Images.fromPng(bytes);
                SwingUtilities.invokeLater(() -> guard.markApplied("img:" + watcher.writeImage(img)));
            } catch (RuntimeException e) {
                if (e instanceof ApiClient.ApiException a && a.status == 401) throw a;
                System.err.println("Image download failed: " + e);
                SwingUtilities.invokeLater(() -> icon.displayMessage("ClipVault",
                        "이미지를 가져오지 못했습니다.", TrayIcon.MessageType.ERROR));
            }
        });
    }
```

`thumbnail(String id, Runnable onReady)` → `thumbnail(JsonNode clip, Runnable onReady)`: 첫 줄 `String id = clip.path("id").asText();`, 받는 줄을

```java
                    byte[] bytes = api.getThumbnail(id);
                    if (clip.path("e2e").asBoolean()) bytes = vault.open(bytes, Vault.THUMB);
                    thumbs.put(id, Images.fromPng(bytes));
```

- [ ] **Step 7: 메뉴와 툴팁** — `buildMenu()`에서 `keys` 항목 아래:

```java
        JMenuItem vaultItem = new JMenuItem("볼트 암호 변경…");
        vaultItem.addActionListener(e -> changeVault());
```

`menu.add(keys);` 다음 줄에 `menu.add(vaultItem);`.
`updateTooltip()`에서 로그인 상태 문구를 만드는 곳에, `loggedIn && !vault.ready()`이면 툴팁을 `"ClipVault - 볼트 잠김 (메뉴에서 최근 클립을 열어 암호 입력)"`으로. (기존 updateTooltip의 문자열 조합 방식에 맞춰 한 분기 추가)

- [ ] **Step 8: 기존 데이터 이전**

```java
    /**
     * (백그라운드) 서버 암호화로 남은 옛 클립을 e2e로 옮긴다. 볼트를 쓸 수 있게 될 때마다 부른다(끊겼던 이전을 이어서).
     * 100개씩 받아 하나씩 암호화해 같은 자리에 덮어쓴다. 한 바퀴 동안 하나도 못 옮기면 다음 실행 때 다시 한다.
     */
    private void migrateLegacy() {
        int moved = 0;
        try {
            while (vault.ready()) {
                JsonNode page = api.listLegacy();
                if (page.isEmpty()) break;
                int before = moved;
                for (JsonNode c : page) {
                    String id = c.path("id").asText();
                    try {
                        if ("IMAGE".equals(c.path("type").asText())) {
                            byte[] png = api.getImage(id); // 옛 행이라 서버가 평문으로 준다
                            byte[] thumb = Images.thumbnail(Images.fromPng(png), Images.THUMB);
                            api.putE2eImage(id, vault.seal(png, Vault.IMAGE), vault.seal(thumb, Vault.THUMB), vault.hash(png));
                        } else {
                            String text = c.path("content").asText();
                            api.putE2eText(id, vault.sealText(text), vault.hash(text.getBytes(StandardCharsets.UTF_8)));
                        }
                        moved++;
                    } catch (ApiClient.ApiException e) {
                        if (e.status == 401 || e.status == 409 || e.status == 426) throw e;
                        System.err.println("Migration skipped " + id + ": " + e.getMessage());
                    } catch (RuntimeException e) {
                        System.err.println("Migration skipped " + id + ": " + e);
                    }
                }
                if (moved == before) break;
            }
        } catch (ApiClient.ApiException e) {
            if (e.status == 409) { onVaultChanged(); return; }
            throw e;
        } finally {
            int m = moved;
            if (m > 0) {
                thumbs.clear(); // 옮긴 이미지는 이제 암호문이므로 캐시를 비운다
                SwingUtilities.invokeLater(() -> icon.displayMessage("ClipVault",
                        "기존 클립 " + m + "개를 종단간 암호화로 옮겼습니다.", TrayIcon.MessageType.INFO));
            }
        }
    }
```

- [ ] **Step 9: 빌드와 테스트** — Run: `./gradlew :tray-client:test :tray-client:installDist` → PASS

- [ ] **Step 10: 기존 하네스 시그니처 맞추기** — 스크래치의 `PinShot`, `SearchShot`, `PinCheck`, `PolishCheck`, `SearchKeys`, `NoResultShot`, `ImgPinShot`에서 `Thumbs` 람다 `(id, r) -> ...`를 `(clip, r) -> ...`(id 비교는 `clip.path("id").asText()`)로 바꾸고 다시 돌려 모두 `ALL OK`인지 확인한다.

- [ ] **Step 11: 메뉴 스크린샷** — `TtlShot`(스크래치)을 다시 돌려 "볼트 암호 변경…"이 들어간 메뉴 PNG를 만들고, Task 7의 창 8장과 함께 사용자에게 보내 승인받는다.

- [ ] **Step 12: 커밋 (Task 6 + 7 + 8)**

```bash
git add tray-client/
git commit -m "feat(tray-client): 종단간 암호화 연결 (볼트 창, 암호화 업로드, 복호화 받기, 기존 데이터 이전)"
```

---

### Task 9: 실제 흐름 확인 + 문서

**Files:**
- Modify: `docs/API_CONTRACT.md`, `docs/TRD.md`, `docs/PRD.md`, `README.md`, `docs/TASKS.md`
- 스크래치: `E2eFlow.java` (커밋하지 않음)

- [ ] **Step 1: 로컬 Docker 재빌드** — `docker compose -f docker-compose.yml -f <scratch>/compose.port.yml up -d --build backend` 후 `curl http://localhost:18080/api/clips` → 401.

- [ ] **Step 2: 두 클라이언트 흐름 하네스** (스크래치, 앱 클래스 직접 사용, 서버 `http://localhost:18080`)

```java
import com.clipvault.client.auth.Session;
import com.clipvault.client.crypto.VaultCrypto;
import com.clipvault.client.network.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** A가 볼트를 만들고 B가 같은 암호로 열어, A의 e2e 텍스트를 B가 복호화하는지 + 초기화 후 B가 409를 받는지 확인. */
public class E2eFlow {
    public static void main(String[] a) throws Exception {
        String email = "e2e" + System.nanoTime() + "@test.com", pw = "password123";
        Session sa = new Session(); sa.server = "http://localhost:18080";
        ApiClient A = new ApiClient(sa);
        A.signup(email, pw);
        A.loginAndRegister(email, pw, "PC-A");
        // 옛 방식 클립 하나 (볼트 전, 구버전 앱 흉내: 헤더 없이)
        // → ApiClient에는 더 이상 옛 업로드가 없으므로 이 확인은 MigrationApiTest가 맡는다
        byte[] vk = VaultCrypto.newVaultKey();
        int v = A.createVault(VaultCrypto.wrap(vk, "vault pass 1".toCharArray(), sa.userId));
        A.vaultVersion = v;
        byte[] enc = VaultCrypto.subKey(vk, VaultCrypto.ENC), hk = VaultCrypto.subKey(vk, VaultCrypto.HASH);
        byte[] plain = "secret 회의 링크".getBytes(StandardCharsets.UTF_8);
        A.postClip(Base64.getEncoder().encodeToString(VaultCrypto.seal(enc, plain, "text-v1")), VaultCrypto.hmacHex(hk, plain));

        Session sb = new Session(); sb.server = sa.server;
        ApiClient B = new ApiClient(sb);
        B.loginAndRegister(email, pw, "PC-B");
        JsonNode wv = B.getVault();
        byte[] vkB = VaultCrypto.unwrap(new VaultCrypto.Wrapped(wv.path("salt").asText(), wv.path("iterations").asInt(),
                wv.path("wrappedKey").asText()), "vault pass 1".toCharArray(), sb.userId);
        B.vaultVersion = wv.path("version").asInt();
        JsonNode list = B.listClips(10);
        String got = new String(VaultCrypto.open(VaultCrypto.subKey(vkB, VaultCrypto.ENC),
                Base64.getDecoder().decode(list.get(0).path("content").asText()), "text-v1"), StandardCharsets.UTF_8);
        check(got.equals("secret 회의 링크"), "B decrypts A's clip: " + got);
        check(list.get(0).path("e2e").asBoolean(), "e2e flag");

        A.resetVault(VaultCrypto.wrap(VaultCrypto.newVaultKey(), "vault pass 2".toCharArray(), sa.userId));
        try {
            B.postClip(Base64.getEncoder().encodeToString(new byte[40]), "a".repeat(64));
            check(false, "B should get 409 after reset");
        } catch (ApiClient.ApiException e) {
            check(e.status == 409, "B gets 409 after reset: " + e.status);
        }
        System.out.println("ALL OK");
    }

    static void check(boolean ok, String m) { System.out.println((ok ? "PASS " : "FAIL ") + m); if (!ok) System.exit(1); }
}
```

(실행 전 `Session`의 저장 위치가 실제 로그인 정보와 같으므로, 하네스 실행 뒤 실제 앱 로그인 정보가 덮였으면 앱에서 다시 로그인한다. `Session` 생성자/필드 이름이 다르면 `auth/Session.java`를 보고 맞춘다.)

Run: `java -cp "out;tray-client/build/install/tray-client/lib/*" E2eFlow` → `ALL OK`

- [ ] **Step 3: 앱 실제 확인 (로컬 서버)** — 패키징 없이 `./gradlew :tray-client:run -Dclipvault.server=http://localhost:18080`으로 두 번 띄울 수 없으므로, 한 PC에서: 가입 → 볼트 만들기 창 → 텍스트·이미지 복사 → 목록에서 평문·썸네일 보이는지 → 메뉴 "볼트 암호 변경…" → 로그아웃/재로그인 시 입력 창 → "암호를 잊었어요" 초기화 → 목록 비어 있음. 옛 클립 이전은 볼트 만들기 전에 v1.5.0 앱(설치본)으로 클립을 몇 개 만든 뒤 개발 앱으로 로그인해 알림 "기존 클립 N개를…"과 목록 정상 표시로 확인.

- [ ] **Step 4: 문서 갱신**
  - `API_CONTRACT.md`: 볼트 API 절, `X-Vault-Version` 규칙 표, e2e 텍스트·multipart 이미지, `legacy=true`, `PUT /{id}/e2e`, `ClipResponse.e2e`, 트레이 앱 모듈 시그니처(`VaultCrypto`, `Vault`, `Multipart`, `Images.thumbnail`).
  - `TRD.md`: 데이터 모델(`users.vault_*`, `clips.e2e`), 6.4 보안(서버 측 AES → 종단간 암호화, 서버 키는 옛 행 전용), 9 리스크(오프라인 대입·메타데이터·옛 고정 클립).
  - `PRD.md`: 4.2의 "진짜 종단간 암호화(E2E)"를 v1.6.0 구현으로 표시.
  - `README.md`: 설계 결정 "서버 측 암호화 (E2E 아님)"을 "종단간 암호화(볼트 암호, 봉투 암호화)"로 바꾸고, 주요 기능에 한 줄, 로드맵에서 제거.
  - `TASKS.md`: 백로그 "종단간 암호화(E2E) 전환" 체크 + 완료 목록에 "(v1.6.0 예정)".

- [ ] **Step 5: 전체 테스트** — Run: `./gradlew test` → 전부 PASS

- [ ] **Step 6: 커밋**

```bash
git add docs/ README.md
git commit -m "docs: 종단간 암호화 반영 (API, 데이터 모델, 보안, 리스크)"
```

---

## Self-Review

- **스펙 커버리지**: 3.1 키 → Task 4·5 / 3.2 데이터 형식·AAD → Task 4·5·8 / 3.3 HMAC → Task 4·8 / 4.1 데이터 → Task 1·2 / 4.2 볼트 API → Task 1 / 4.3 업로드 규칙 → Task 2 / 4.4 조회 → Task 2·3 / 4.5 이전 API → Task 3 / 4.6 옛 행 로그 → Task 3 / 5.1 부품 → Task 4·5·7 / 5.2 볼트 확인 → Task 8 Step 3 / 5.3 메뉴·로그아웃 → Task 8 Step 2·7 / 5.4 잠김 → Task 8 Step 4·5·7 / 5.5 업로드·받기·409 → Task 8 Step 4~6 / 5.6 이전 → Task 8 Step 8 / 7 테스트 → Task 1~5·9 / 8 배포 → 실행 후 PR 단계.
- **타입 일치**: `Wrapped(salt, iterations, wrappedKey)` Task 4·6·8, `Vault.TEXT/IMAGE/THUMB` Task 5·8, `ApiClient.vaultVersion` Task 6·8, `Thumbs.get(JsonNode, Runnable)` Task 8, `VaultDialog.Check.run(char[][])` Task 7·8 일치.
- 주의: Task 6은 TrayApp 호출부 때문에 단독 커밋하지 않고 Task 8과 함께 커밋한다(Task 6 Step 4 메모).
