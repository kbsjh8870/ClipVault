# ClipVault — API & Module Contract (v1)

팀메이트(backend / tray-client / test) 공통 SSOT. 이 문서와 코드가 다르면 문서가 맞다.
변경이 필요하면 직접 고치지 말고 최종 보고에 "contract 변경 제안"으로 남길 것.

## 0. 빌드

- Gradle 멀티프로젝트, 루트 `./gradlew` 사용. JDK 21. Spring Boot 4.1.x.
- `:backend` — Spring Boot. 테스트는 H2(PostgreSQL 모드), 운영은 PostgreSQL.
- `:tray-client` — 순수 Java 21 + Swing. 외부 의존성은 `com.fasterxml.jackson.core:jackson-databind` 하나만 허용.
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
| `clipvault.clip.ttl` | | `7d` |
| `clipvault.clip.cleanup-cron` | | `0 0 4 * * *` |

스키마는 `ddl-auto: update` (Flyway 없음).

## 2. 인증 모델

- 토큰 2종 모두 JWT(HS256). claim: `sub`=userId(UUID), `did`=deviceId(UUID, 기기 등록 전엔 없음), `typ`=`access`|`refresh`.
- 로그인 → **user 토큰**(did 없음). 이 토큰으로 할 수 있는 건 `POST /api/devices` 와 `GET /api/devices` 뿐.
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
- `POST /api/auth/login` `{email, password}` → `200` `{userId, accessToken, refreshToken}` (user 토큰). 실패 → 401.
- `POST /api/auth/refresh` `{refreshToken}` → `200` `{userId, accessToken, refreshToken}` (device refresh만 허용).

### Devices
- `POST /api/devices` `{deviceName, os}` → `201`
  `{id, deviceName, os, lastSeenAt, active, accessToken, refreshToken}`
- `GET /api/devices` → `200` `[{id, deviceName, os, lastSeenAt, active}]` (내 기기, active만, lastSeenAt 내림차순)
- `DELETE /api/devices/{id}` → `204` (active=false, refreshTokenHash=null). 남의 기기 → 404.

### Clips (device 토큰 필수, 아니면 403)
- `POST /api/clips` `{content}` → 신규 `201` / 중복 `200`, body = ClipResponse
  - content: 공백만 있는 문자열 불가, 최대 100_000자.
  - 중복: 같은 user의 **가장 최근** 클립과 `contentHash`가 같으면 새 레코드 없이 `createdAt`,`expiresAt`, `sourceDeviceId` 갱신 후 200. 이 경우도 WS push 한다.
- `GET /api/clips?limit=20` → `200` `[ClipResponse]` createdAt 내림차순, 만료 제외. limit 기본 20, 1~100으로 clamp.
- `DELETE /api/clips/{id}` → `204`. 남의 클립 → 404.

```
ClipResponse = {id, content, contentHash, sourceDeviceId, createdAt, expiresAt}
```
- 시각은 ISO-8601 UTC 문자열(`Instant`). id류는 UUID 문자열.
- `contentHash` = content UTF-8의 SHA-256 소문자 hex.
- DB의 content는 AES-GCM 암호문(base64, 앞 12바이트 IV). API로는 항상 평문.

## 4. WebSocket (STOMP)

- 엔드포인트: `/ws` (순수 WebSocket, SockJS 없음). simple broker `/topic`.
- CONNECT 프레임 헤더 `Authorization: Bearer <device accessToken>` — 무효하면 연결 거부(ERROR 프레임).
- SUBSCRIBE `/topic/clips/{userId}` — 본인 userId가 아니면 거부.
- 클립 생성/갱신 시 서버가 해당 topic으로 ClipResponse JSON 전송.
- 클라이언트는 `sourceDeviceId == 내 deviceId` 인 메시지를 무시(echo).

## 5. 모듈 시그니처 (테스트가 직접 호출하므로 정확히 지킬 것)

### backend
- `com.clipvault.common.HashUtil` — `public static String sha256(String text)` (소문자 hex)
- `com.clipvault.common.AesCipher` — `public AesCipher(String base64Key)`, `public String encrypt(String plain)`, `public String decrypt(String cipherText)`. 같은 평문도 매번 다른 암호문.
- `com.clipvault.clip.ClipCleanupJob` — Spring bean, `public int deleteExpired()` 삭제 건수 반환, `@Scheduled(cron = "${clipvault.clip.cleanup-cron}")`.
- 엔티티/리포지토리: `com.clipvault.auth.User`/`UserRepository`, `com.clipvault.device.Device`/`DeviceRepository`, `com.clipvault.clip.Clip`/`ClipRepository` (Spring Data JPA).

### tray-client
- `com.clipvault.client.TrayApp` — `main`.
- `com.clipvault.client.clipboard.EchoGuard`
  - `public EchoGuard(java.time.Clock clock, java.time.Duration window)`
  - `public void markApplied(String text)` — 서버 클립을 로컬에 반영했을 때 호출
  - `public boolean shouldUpload(String text)` — false 조건: null/blank, window 내 markApplied된 텍스트, 직전에 업로드 승인한 텍스트와 동일. true를 반환하면 그 텍스트를 "직전 업로드"로 기록.
- `com.clipvault.client.network.StompFrame` — `public record StompFrame(String command, java.util.Map<String,String> headers, String body)`
  - `public String encode()` — `COMMAND\nk:v\n...\n\nbody\0`
  - `public static StompFrame decode(String raw)` — 끝의 `\0`, 선행 heart-beat 개행 허용. body 없으면 `""`.
