# ClipVault — API & Module Contract (v1)

팀메이트(backend / tray-client / test) 공통 SSOT. 이 문서와 코드가 다르면 문서가 맞다.
변경이 필요하면 직접 고치지 말고 최종 보고에 "contract 변경 제안"으로 남길 것.

## 0. 빌드

- Gradle 멀티프로젝트, 루트 `./gradlew` 사용. JDK 21. Spring Boot 4.1.x.
- `:backend` — Spring Boot. 테스트는 H2(PostgreSQL 모드), 운영은 PostgreSQL.
- `:tray-client` — 순수 Java 21 + Swing. 외부 의존성은 `jackson-databind`(JSON), `flatlaf`(테마), `jna-platform`(전역 단축키, 레지스트리)만 허용.
  HTTP는 `java.net.http.HttpClient`, WebSocket은 `java.net.http.WebSocket` + 직접 구현한 최소 STOMP.

## 1. 설정 (backend `application.yml`)

| property | env | 기본값 |
|---|---|---|
| `spring.datasource.url` | `DB_URL` | 없음(필수) |
| `spring.datasource.username` | `DB_USERNAME` | |
| `spring.datasource.password` | `DB_PASSWORD` | |
| `clipvault.jwt.secret` | `JWT_SECRET` | 없음(필수, 32바이트 이상 문자열) |
| `clipvault.jwt.access-ttl` | | `15m` |
| `clipvault.jwt.refresh-ttl` | | `30d` |
| `clipvault.crypto.key` | `CLIP_ENCRYPTION_KEY` | 없음(필수, base64 인코딩된 32바이트 AES 키) |
| `clipvault.clip.cleanup-cron` | | `0 0 4 * * *` |

스키마는 `ddl-auto: update` (Flyway 없음).

## 2. 인증 모델

- 토큰 2종 모두 JWT(HS256). claim: `sub`=userId(UUID), `did`=deviceId(UUID, 기기 등록 전엔 없음), `typ`=`access`|`refresh`.
- 로그인 → **user accessToken**만 발급(did 없음, refresh 없음). 이 토큰으로 할 수 있는 건 `POST /api/devices` 와 `GET /api/devices` 뿐. 만료되면 다시 로그인.
- `POST /api/devices` → **device 토큰**(did 포함) 발급. 이후 모든 API/WS는 device 토큰.
- 모든 인증 요청마다 did가 있으면 해당 Device의 `active=true` 인지 확인한다 → 원격 로그아웃 즉시 반영.
- refresh 토큰은 Device의 `refreshTokenHash`(SHA-256)에 저장, `/api/auth/refresh` 시 일치 확인 후 새 쌍 발급(rotation). 불일치/비활성 → 401.
- 헤더: `Authorization: Bearer <accessToken>`

## 3. REST

에러 응답 공통: `{"status": 400, "message": "..."}`
- 400 검증 실패, 401 미인증/토큰 무효/비활성 기기, 403 남의 리소스 또는 device 토큰 필요 API에 user 토큰 사용, 404 없음, 409 이메일 중복.

### Auth
- `POST /api/auth/signup` `{email, password}` → `201` `{id, email}`
  - email 형식 검증, password 8자 이상. 중복 → 409.
- `POST /api/auth/login` `{email, password}` → `200` `{userId, accessToken}` (user accessToken). 실패 → 401.
- `POST /api/auth/refresh` `{refreshToken}` → `200` `{userId, accessToken, refreshToken}` (device refresh만 허용).

### Devices
- `POST /api/devices` `{deviceName, os}` → `201`
  `{id, deviceName, os, lastSeenAt, active, accessToken, refreshToken}`
- `GET /api/devices` → `200` `[{id, deviceName, os, lastSeenAt, active}]` (내 기기, active만, lastSeenAt 내림차순)
- `DELETE /api/devices/{id}` → `204` (active=false, refreshTokenHash=null). 남의 기기 → 404.

### Settings (device 토큰 필수, 사용자 단위)
- `GET /api/settings` → `200` `{clipTtlDays}` 클립 보관 기간(일). 기본 7.
- `PUT /api/settings` `{clipTtlDays}` → `204`. 허용 값 1·3·7·30, 그 밖은 `400`.
  - 기존 클립에도 적용: 고정 안 된 클립의 `expiresAt`을 `createdAt + 새 기간`으로 다시 계산 (줄이면 오래된 클립은 즉시 목록에서 빠짐). 고정 클립은 그대로.
  - 이미지 클립도 같은 기간을 따른다 (버킷 수명 주기 규칙은 최대 보관 기간 30일보다 긴 31일).

### Vault (device 토큰 필수, 종단간 암호화)
서버는 볼트 키를 볼트 암호로 감싼 값만 보관한다. 볼트 암호와 풀린 키는 서버가 알 수 없다.
- `GET /api/vault` → `200` `{salt, iterations, wrappedKey, version}` / 볼트 없음 `404`
- `POST /api/vault` `{salt, iterations, wrappedKey}` → `201` `{version: 1}` / 이미 있음 `409`
- `PUT /api/vault` `{salt, iterations, wrappedKey, version}` → `204` 다시 감싸기(암호 변경, 클립 그대로) / version 불일치 `409` / 볼트 없음 `404`
- `POST /api/vault/reset` `{salt, iterations, wrappedKey}` → `200` `{version}` 그 사용자의 클립 행과 버킷 객체를 모두 지우고 새 키 저장, version +1
- 입력 검사: salt는 base64 16바이트, iterations ≥ 100,000, wrappedKey는 base64 60바이트(IV 12 ‖ 키 32 ‖ 태그 16). 아니면 `400`.
- 감싼 키 `wrappedKey` = `base64(IV 12 ‖ AES-GCM(KEK, 볼트 키, AAD="clipvault-vault-v1:"+userId))`, KEK = `PBKDF2WithHmacSHA256(볼트 암호, salt, iterations, 256bit)`.

### `X-Vault-Version` 헤더 규칙 (클립 업로드·이전)
| 헤더 | 사용자 볼트 | 처리 |
|---|---|---|
| 없음 | 없음 | 옛 방식(구버전 앱 호환): 서버가 평문을 받아 서버 암호화, `e2e=false` |
| 없음 | 있음 | `426 Upgrade Required` (앱 업데이트 필요) |
| 있음, 현재 version과 같음 | 있음 | e2e 업로드 |
| 있음, 다름 (또는 볼트 없음) | — | `409` (볼트가 바뀜: 다른 PC에서 초기화) |

### Clips (device 토큰 필수, 아니면 403)
- `POST /api/clips` `{content}` → 신규 `201` / 중복 `200`, body = ClipResponse
  - content: 공백만 있는 문자열 불가, 최대 100_000자.
  - **e2e 업로드** (`X-Vault-Version` 있음): `{content, contentHash}`. content = base64(`IV 12 ‖ AES-GCM 암호문 ‖ 태그`, AAD `text-v1`), 디코딩 길이 ≤ 12 + 300,000 + 16. contentHash = 소문자 hex 64자(HMAC-SHA256). 아니면 `400`. 서버는 가공 없이 저장하고 `e2e=true`. 중복 판별은 아래와 같다.
  - 중복: 같은 user의 **가장 최근** 클립과 `contentHash`가 같으면 새 레코드 없이 `createdAt`,`expiresAt`, `sourceDeviceId` 갱신 후 200. `expiresAt` = 지금 + 사용자 보관 기간. 이 경우도 WS push 한다.
- `POST /api/clips/image` (PNG 바이트, `Content-Type: image/png`) → 신규 `201` / 중복 `200`, body = ClipResponse
  - 처리 순서: 크기 확인 (Content-Length ≤ 10MB = 10 × 1024 × 1024, 아니면 `413`) → 본문 읽기 (초과면 `413`) → 이미지 헤더 확인(PNG 형식, 픽셀 ≤ 5천만, 아니면 `400`) → 해시 계산 → 최근 클립과 같으면 시각 갱신 `200` 푸시 → 새 내용이면 썸네일 생성 → 원본·썸네일 암호화 → 버킷 저장(`503` 실패) → DB 저장(실패 시 객체 삭제 후 500) → `201` 푸시.
  - 이미지 한도: PNG 바이트 10MB, 픽셀 5천만(가로×세로).
  - 응답의 `content`는 `[이미지 W×H]` 안내 문구, `width/height/size`는 원본 픽셀·바이트.
  - **e2e 업로드** (`X-Vault-Version` 있음, `multipart/form-data`): 파트 `image`(암호문 바이트, AAD `image-v1`), `thumb`(암호문 바이트, AAD `thumb-v1`), `width`, `height`, `contentHash`(원본 PNG의 HMAC hex). image ≤ 10MB + 28바이트, thumb ≤ 1MB, 넘으면 `413`. width·height ≥ 1, 곱 ≤ 5천만, 아니면 `400`. PNG 검사·썸네일 생성 없이 받은 바이트를 그대로 버킷에 저장한다(저장 순서와 실패 처리는 위와 같다). 중복이면 객체를 다시 쓰고 시각 갱신 `200`. 응답의 `content`는 빈 문자열.
- `GET /api/clips/{id}/image` (image/png) → `200` 원본. 옛 행은 서버가 복호화한 PNG, e2e 행은 저장된 암호문 바이트 그대로(앱이 복호화). 남의 클립, 텍스트 클립, 객체 없음 → `404`.
- `GET /api/clips/{id}/thumbnail` (image/png) → `200` 썸네일 (긴 변 최대 240px, 비율 유지, 원본이 더 작으면 원본 크기). 옛 행은 서버가 복호화한 PNG, e2e 행은 암호문 바이트 그대로. 남의 클립, 텍스트 클립, 객체 없음 → `404`.
- `GET /api/clips?limit=20&before=<ISO 시각>` → `200` `[ClipResponse]` createdAt 내림차순, 만료 제외(고정 클립은 만료돼도 포함). limit 기본 20, 1~100으로 clamp.
  - `before`(선택): 그 시각보다 먼저 만들어진 것만. "더 보기"는 앞 페이지 마지막 항목의 `createdAt`을 넘긴다. 형식이 틀리면 `400`.
- `GET /api/clips?pinned=true` → `200` `[ClipResponse]` 고정한 클립 전체(최대 10개), createdAt 내림차순. limit/before 무시.
- `GET /api/clips?legacy=true` → `200` `[ClipResponse]` `e2e=false`인 내 클립(고정 포함)을 최신순으로 최대 100개. 평문으로 온다. limit/before/pinned 무시. 앱이 종단간 암호화로 옮길 대상을 찾을 때 쓴다.
- `PUT /api/clips/{id}/e2e` (`X-Vault-Version` 필수, 규칙은 위 표와 같음) → `204` 옛 클립을 같은 자리에서 e2e로 바꾼다.
  - 텍스트 클립: JSON `{content, contentHash}`. 이미지 클립: multipart `image`, `thumb`, `contentHash` (크기 검사는 e2e 업로드와 같음).
  - 이미 `e2e=true`면 아무것도 안 하고 `204` (두 PC가 동시에 이전해도 안전).
  - id, createdAt, expiresAt, pinned, sourceDeviceId, width/height는 그대로. content·contentHash만 바뀐다. 이미지는 같은 버킷 키에 암호문을 먼저 덮어쓰고 DB를 바꾼다(버킷 쓰기 실패 `503`, 행은 옛 상태).
  - 남의 클립 → `404`.
- `DELETE /api/clips/{id}` → `204`. 이미지 클립이면 버킷 객체(원본·썸네일)도 삭제(실패는 로그만). 남의 클립 → 404.
- `PUT /api/clips/{id}/pin` → `204` 고정(즐겨찾기). 이미 고정이면 그대로 `204`. 이미 10개 고정 → `409`, 남의 클립 → `404`.
  - 이미지 클립: 고정할 때 버킷 객체(원본·썸네일)를 다시 써서 생성 시각을 갱신한다(수명 주기 규칙 31일 대비). 객체가 이미 없으면 `404`, 저장소 오류 `503` (둘 다 고정 안 됨). 이후 `ClipCleanupJob.renewPinnedImages()`가 매일 다시 쓴다.
  - 고정한 클립은 만료 시각이 지나도 목록에 남고 `ClipCleanupJob`이 지우지 않는다.
- `DELETE /api/clips/{id}/pin` → `204` 고정 해제. 만료 시각을 지금 + 사용자 보관 기간으로 다시 잡는다. 이미지는 객체도 다시 쓴다(실패는 로그만). 고정 안 된 클립이면 변화 없이 `204`. 남의 클립 → `404`.

```
ClipResponse = {id, type, content, contentHash, sourceDeviceId, createdAt, expiresAt, width, height, size, pinned, e2e}
```
- `e2e`: true = 앱이 암호화한 행(`content`는 base64 암호문, 서버는 내용을 못 봄), false = 서버 암호화한 옛 행(`content`는 서버가 복호화한 평문).
- `type`: `"TEXT"` / `"IMAGE"`. 구버전 null 컬럼은 `"TEXT"`로 응답. 테스트 헬퍼 `Api.postImage(token, bytes)` 참고.
- 텍스트 클립: `width`, `height`, `size` = null. `content`는 업로드된 평문.
- 이미지 클립: `width`, `height`, `size`는 원본 픽셀·바이트. `content`는 `[이미지 W×H]` 안내 문구.
- `pinned`: 고정 여부 (boolean).
- 시각은 ISO-8601 UTC 문자열(`Instant`). id류는 UUID 문자열.
- `contentHash`: 옛 행은 SHA-256 소문자 hex(텍스트는 content의 UTF-8 바이트, 이미지는 업로드한 PNG 바이트). e2e 행은 `hex(HMAC-SHA256(해시 키, 평문 바이트))`로 앱이 계산해 보낸다(해시 키는 볼트 키에서 유도하므로 서버는 짐작한 평문과 대조할 수 없다).
- DB의 content: 옛 행은 서버 AES-GCM 암호문(base64, 앞 12바이트 IV)이고 API로는 평문으로 나간다. e2e 행은 앱이 만든 암호문(base64)이고 API로도 그대로 나간다.
- 이미지·썸네일 버킷 객체: 옛 행은 서버 암호화, e2e 행은 앱이 암호화한 바이트(`IV ‖ 암호문 ‖ 태그`) 그대로. 서버는 둘을 내용과 무관한 바이트로 다룬다.
- 에러 보충: `426` 볼트가 있는데 `X-Vault-Version` 없는 업로드, `409` 헤더 버전이 서버 볼트와 다름.

## 4. WebSocket (STOMP)

- 엔드포인트: `/ws` (순수 WebSocket, SockJS 없음). simple broker `/topic`.
- CONNECT 프레임 헤더 `Authorization: Bearer <device accessToken>` — 무효하면 연결 거부(ERROR 프레임).
- SUBSCRIBE `/topic/clips/{userId}` — 본인 userId가 아니면 거부.
- 텍스트 클립 생성/갱신, 이미지 클립 생성/갱신 시 서버가 해당 topic으로 ClipResponse JSON 전송. 푸시 메시지에는 항상 `type` 필드가 포함된다.
- 클라이언트는 `sourceDeviceId == 내 deviceId` 인 메시지를 무시(echo).
- 기기가 원격 로그아웃(`DELETE /api/devices/{id}`)되면 서버가 그 기기의 열린 WebSocket 세션을 즉시 닫는다. 재연결 CONNECT는 401 사유로 거부된다.

## 5. 모듈 시그니처 (테스트가 직접 호출하므로 정확히 지킬 것)

### backend
- `com.clipvault.common.HashUtil` — `public static String sha256(String text)` (소문자 hex), `public static String sha256(byte[] data)` (소문자 hex)
- `com.clipvault.common.AesCipher` — `public AesCipher(String base64Key)`, `public String encrypt(String plain)`, `public String decrypt(String cipherText)`, `public byte[] encryptBytes(byte[] plain)`, `public byte[] decryptBytes(byte[] data)`. 같은 평문도 매번 다른 암호문. encryptBytes/decryptBytes 형식: IV(12바이트) ‖ 암호문(GCM 태그 포함), base64 없이 바이트 그대로.
- `com.clipvault.storage.ImageStore` — `public void put(String key, byte[] data)` (없으면 덮어쓰고 실패 시 예외), `public byte[] get(String key)` (없으면 null), `public void delete(String key)` (없어도 예외 없음).
- `com.clipvault.clip.ClipCleanupJob` — Spring bean, `public int deleteExpired()` 삭제 건수 반환, `public int renewPinnedImages()` 다시 쓴 고정 이미지 수 반환, 둘 다 `@Scheduled(cron = "${clipvault.clip.cleanup-cron}")`.
- `com.clipvault.clip.ImageClipController` — `public static void deleteQuietly(ImageStore store, String imageKey)` (두 객체 images/key, thumbs/key 삭제, 실패는 로그만).
- 엔티티/리포지토리: `com.clipvault.auth.User`/`UserRepository`, `com.clipvault.device.Device`/`DeviceRepository`, `com.clipvault.clip.Clip`/`ClipRepository` (Spring Data JPA). `ClipRepository.findExpiredImageKeys(Instant now)` 반환 `List<String>`, `Clip.getImageKey()` 반환 `String` (null 가능).

### tray-client
- `com.clipvault.client.TrayApp` — `main`.
- `com.clipvault.client.ui.ClipListWindow` — `static boolean matches(JsonNode clip, String query)` (검색), `static void merge(List<JsonNode> all, Iterable<JsonNode> clips)` (ID 중복 제거 + 고정 먼저·최신순 정렬), `public static final int PAGE = 50`.
- `com.clipvault.client.hotkey.HotKeys` — `public static boolean valid(KeyStroke)` (Ctrl/Alt/Shift 하나 이상 + A–Z/0–9/F1–F12), `static int winModifiers(KeyStroke)` (MOD_ALT=1, MOD_CONTROL=2, MOD_SHIFT=4), `public static String text(KeyStroke)` (예: `Ctrl+Alt+Shift+C`, null이면 `없음`).
- `com.clipvault.client.update.Updater` — `static boolean isNewer(String latest, String current)` (`x.y.z` 형식만 비교), `static String sha256(Path)`, `static void unzip(Path zip, Path dest)` (zip-slip 방지).
- `com.clipvault.client.clipboard.Images` — `toArgb`, `key` (픽셀 해시), `toPng`, `fromPng`, `MAX_BYTES = 10MB`, `public static byte[] thumbnail(BufferedImage src, int max)` (긴 변 max px, 비율 유지, 작은 원본은 그대로, PNG 바이트).
- `com.clipvault.client.crypto.VaultCrypto` — 순수 함수. `record Wrapped(String salt, int iterations, String wrappedKey)`, `newVaultKey()` (32바이트), `wrap(byte[] vaultKey, char[] passphrase, String userId)` (새 salt, `ITERATIONS = 600_000`), `unwrap(Wrapped, char[] passphrase, String userId)` (`MIN_ITERATIONS = 100_000` 미만이면 `IllegalArgumentException`, 틀린 암호·다른 userId는 `BadPassphraseException`), `subKey(byte[] vaultKey, String label)` (`ENC = "clipvault-enc-v1"`, `HASH = "clipvault-hash-v1"`), `seal(byte[] key, byte[] plain, String aad)` / `open(byte[] key, byte[] sealed, String aad)` (`IV 12 ‖ 암호문 ‖ 태그`), `hmacHex(byte[] key, byte[] data)`.
- `com.clipvault.client.crypto.Vault` — 이 PC의 볼트 상태. `TEXT = "text-v1"`, `IMAGE = "image-v1"`, `THUMB = "thumb-v1"` (AAD), `ready()`, `version()`, `unlock(byte[] vaultKey, int version)`, `load()` / `lock()` (DPAPI로 잠가 Preferences에 저장·로드·삭제), `sealText`/`openText`, `seal`/`open(byte[], String aad)`, `hash(byte[] plain)`.
- `com.clipvault.client.network.Multipart` — multipart/form-data 본문 작성기. `field(name, value)`, `file(name, byte[])`, `body()`, `contentType()`.
- `com.clipvault.client.network.ApiClient` (볼트·e2e 관련) — `volatile int vaultVersion` (0이 아니면 모든 요청에 `X-Vault-Version`), `getVault()` (없으면 null), `createVault(Wrapped)`, `changeVault(Wrapped, int version)`, `resetVault(Wrapped)`, `postClip(sealedContent, hash)`, `postImage(sealedImage, sealedThumb, width, height, hash)`, `listLegacy()`, `putE2eText(id, sealedContent, hash)`, `putE2eImage(id, sealedImage, sealedThumb, hash)`.
- `com.clipvault.client.clipboard.EchoGuard`
  - `public EchoGuard(java.time.Clock clock, java.time.Duration window)`
  - `public void markApplied(String text)` — 서버 클립을 로컬에 반영했을 때 호출
  - `public boolean shouldUpload(String text)` — false 조건: null/blank, window 내 markApplied된 텍스트, 직전에 업로드 승인한 텍스트와 동일. true를 반환하면 그 텍스트를 "직전 업로드"로 기록.
- `com.clipvault.client.network.StompFrame` — `public record StompFrame(String command, java.util.Map<String,String> headers, String body)`
  - `public String encode()` — `COMMAND\nk:v\n...\n\nbody\0`
  - `public static StompFrame decode(String raw)` — 끝의 `\0`, 선행 heart-beat 개행 허용. body 없으면 `""`.
