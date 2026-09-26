# 이미지 클립보드 동기화 — 설계

- 작성일: 2026-09-26
- 상태: 설계 승인됨, 스펙 검토 중
- 대상 버전: 백엔드(main 머지 시 자동 배포), 트레이 앱 v1.2.0
- 관련 문서: PRD.md, TRD.md, API_CONTRACT.md

## 1. 목표와 범위

PC에서 복사한 **이미지**(스크린샷, 캡처 도구, 브라우저의 "이미지 복사" 등)를 텍스트와 같은 방식으로 다른 PC에 동기화한다.

- 텍스트와 이미지가 한 목록에 시간순으로 섞여 보인다.
- 알림 후 클릭 시 반영 원칙은 그대로다. 받은 쪽 클립보드를 자동으로 덮어쓰지 않는다.
- 7일 TTL, 중복 방지, 삭제, 원격 로그아웃 등 기존 규칙이 이미지에도 똑같이 적용된다.

**범위 밖**
- 탐색기에서 이미지 **파일**을 복사하는 경우 (클립보드에 파일 목록이 들어가며 이미지 데이터가 아님)
- 이미지 외 파일(문서, 동영상 등) 동기화
- 원본 이미지 편집/변환 (항상 PNG로 전송·저장)

## 2. 결정 사항

| 항목 | 결정 | 이유 |
|---|---|---|
| 저장 위치 | Oracle Object Storage (S3 호환 API) | 무료 20GB, VM과 분리. Neon 무료 0.5GB는 이미지에 부족 |
| 전송 경로 | 트레이 앱 ↔ 백엔드 ↔ 버킷 (서버 경유) | 텍스트와 같은 JWT 인증 + AES-GCM 암호화 모델 유지. 버킷 키는 서버에만 존재 |
| 목록 표시 | 썸네일 (최대 240px) | 여러 장 중 고를 때 필요. 원본은 클릭할 때만 받음 |
| 크기 한도 | PNG 기준 10MB | 일반 스크린샷과 대부분의 4K 캡처 수용 |
| 데이터 모델 | 기존 `clips` 테이블에 `type` 추가 | 목록·푸시·중복·TTL·삭제 흐름을 그대로 재사용. 별도 테이블은 코드가 두 벌이 됨 |

## 3. 서버

### 3.1 데이터

`clips` 테이블에 컬럼 추가 (`ddl-auto: update`로 자동 반영, 기존 행은 TEXT로 간주):

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `type` | varchar(10), null 허용 | `TEXT` / `IMAGE`. null은 TEXT(기존 행 호환) |
| `image_key` | varchar(64), null | 버킷 객체 이름용 무작위 UUID. 이미지 클립만 |
| `width`, `height` | int, null | 원본 픽셀 크기. 이미지 클립만 |
| `size` | bigint, null | 원본 PNG 바이트 수. 이미지 클립만 |

- 이미지 클립의 `content`: `[이미지 1920×1080]` 안내 문구를 AES 암호화해 저장한다. 구버전(v1.1.0) 앱이 목록에서 빈 줄 대신 이 문구를 보게 하기 위함.
- 이미지 클립의 `content_hash`: 업로드된 PNG 바이트의 SHA-256. 같은 이미지를 다시 복사하면 기존 중복 규칙대로 시각만 갱신된다.
- 중복 판정은 지금처럼 "같은 사용자의 가장 최근 클립"과만 비교한다 (타입 무관, 해시가 같으면 같은 내용).

**버킷 객체** (모두 `AesCipher`로 암호화한 바이트: IV 12바이트 ‖ 암호문):
- `images/{image_key}` — 원본 PNG
- `thumbs/{image_key}` — 썸네일 PNG (긴 변 최대 240px, 비율 유지, 원본이 더 작으면 원본 크기)

객체 이름을 해시가 아닌 무작위 키로 하는 이유: 중복 판정이 "가장 최근 클립"과만 비교하므로 과거 클립과 해시가 같은 새 행이 생길 수 있다. 해시로 이름을 지으면 두 행이 객체 하나를 공유하게 되어, 하나를 지울 때 다른 행의 이미지도 사라진다.

### 3.2 API

모두 device 토큰 필수 (기존 Clips API와 같은 규칙).

**`POST /api/clips/image`**
- 요청 본문: PNG 바이트 그대로, `Content-Type: image/png`
- 처리 순서
  1. `Content-Length`가 없거나 10MB(10 × 1024 × 1024) 초과 → `413` (본문을 읽기 전)
  2. 본문 읽기 (10MB 초과분이 오면 `413`)
  3. `ImageIO` 리더로 헤더만 읽어 가로·세로 확인. 이미지가 아니면 `400`, 5천만 픽셀 초과면 `400` (압축 폭탄 방지)
  4. 해시 계산 → 최근 클립과 같으면 기존 행 갱신 후 `200` + 푸시 (버킷 작업 없음)
  5. 디코딩 → 썸네일 생성 → 원본·썸네일 암호화 → 버킷 저장. 실패 시 `503`
  6. DB 저장. 실패 시 방금 올린 두 객체 삭제 후 오류 전파
  7. `201` + ClipResponse, `/topic/clips/{userId}` 푸시
- 이미지 형식은 PNG만 받는다 (트레이 앱이 항상 PNG로 변환해서 보냄)

**`GET /api/clips/{id}/image`** → `200`, `Content-Type: image/png`, 복호화한 원본
**`GET /api/clips/{id}/thumbnail`** → `200`, `Content-Type: image/png`, 복호화한 썸네일
- 남의 클립, 텍스트 클립, 버킷에 객체 없음 → `404`

**기존 API 변경**
- `GET /api/clips`, 푸시 메시지: ClipResponse에 필드 추가
  ```
  ClipResponse = {id, type, content, contentHash, sourceDeviceId, createdAt, expiresAt, width, height, size}
  ```
  - `type`: `"TEXT"` / `"IMAGE"` (null 행은 `"TEXT"`로 응답)
  - 텍스트 클립은 `width/height/size`가 null
- `DELETE /api/clips/{id}`: 이미지 클립이면 버킷의 두 객체도 삭제. 객체 삭제 실패는 로그만 남기고 행은 삭제 (남은 객체는 수명 주기 규칙이 정리)

### 3.3 저장소 (`ImageStore`)

```java
public interface ImageStore {
    void put(String key, byte[] data);   // 실패 시 예외
    byte[] get(String key);              // 없으면 null
    void delete(String key);             // 없어도 예외 없음
}
```

- `S3ImageStore` (운영): AWS SDK v2 S3 클라이언트, Oracle S3 호환 엔드포인트, path-style 접근. SDK가 기본으로 붙이는 추가 체크섬은 Oracle 호환을 위해 "필요할 때만"으로 설정한다 (구현 시 SDK 문서로 확인).
- `LocalImageStore` (로컬 개발·테스트): 지정 폴더에 파일로 저장. 키의 `/`는 하위 폴더.
- 선택 규칙: `clipvault.storage.s3.bucket`이 비어 있지 않으면 S3, 아니면 Local (`clipvault.storage.local-dir`, 기본 `./data/images`).

설정 (`application.yml`, 운영은 환경변수):

| 키 | 환경변수 | 예 |
|---|---|---|
| `clipvault.storage.s3.endpoint` | `S3_ENDPOINT` | `https://<namespace>.compat.objectstorage.<region>.oraclecloud.com` |
| `clipvault.storage.s3.region` | `S3_REGION` | `ap-chuncheon-1` |
| `clipvault.storage.s3.bucket` | `S3_BUCKET` | `clipvault-images` |
| `clipvault.storage.s3.access-key` | `S3_ACCESS_KEY` | (Customer Secret Key의 Access Key) |
| `clipvault.storage.s3.secret-key` | `S3_SECRET_KEY` | (Customer Secret Key의 Secret) |

### 3.4 만료 정리 (`ClipCleanupJob`)

1. 만료된 이미지 클립의 `image_key` 목록 조회
2. 각 키의 `images/`, `thumbs/` 객체 삭제 (실패는 로그만)
3. 기존처럼 만료 행 일괄 삭제

`deleteExpired()`의 시그니처와 반환값(삭제된 행 수)은 그대로다.

### 3.5 암호화

`AesCipher`에 바이트용 메서드 추가: `byte[] encryptBytes(byte[] plain)`, `byte[] decryptBytes(byte[] data)`. 형식은 기존과 같은 IV(12바이트) ‖ 암호문(GCM 태그 포함), base64 없이 바이트 그대로. 기존 문자열 메서드는 이 메서드를 사용하도록 정리할 수 있다.

## 4. 트레이 앱

### 4.1 감지 (`ClipboardWatcher`)

- 클립보드에 `stringFlavor`가 있으면 지금처럼 텍스트로 처리 (텍스트 우선).
- 텍스트가 없고 `imageFlavor`가 있으면 이미지를 읽어 `BufferedImage`(ARGB)로 변환하고, 픽셀 배열의 SHA-256을 "이미지 키"로 쓴다. 직전 키와 다를 때만 콜백 `onImage(BufferedImage)`를 호출한다.
- 확인 시점은 텍스트와 같다 (FlavorListener + 1초 폴링). 이미지가 클립보드에 남아 있는 동안 매초 다시 읽게 되므로, 구현 시 비용을 측정한다. 4K 스크린샷 기준 한 번에 50ms를 넘으면 이미지만 3초 간격으로 확인한다.
- `writeImage(BufferedImage)`: 이미지를 클립보드에 넣은 직후 다시 읽어서 그 키를 "직전 키"로 기록한다. 윈도우 클립보드를 거치며 픽셀(알파 등)이 바뀌어도 되돌려 보내기가 일어나지 않게 하기 위함.

### 4.2 업로드 (`TrayApp`)

- 일시정지·로그아웃 상태면 무시 (텍스트와 동일).
- PNG로 인코딩 후 10MB를 넘으면 업로드하지 않고 알림 "이미지가 10MB를 넘어 동기화하지 않았습니다" (같은 이미지에 대해 한 번만).
- `EchoGuard`에는 `"img:" + 이미지 키` 문자열로 기록·판정한다 (기존 클래스 그대로 사용).
- `ApiClient.postImage(byte[] png)`로 전송. 실패는 텍스트처럼 로그만.

### 4.3 알림

- 푸시 메시지의 `type`이 `IMAGE`면 "새 이미지 · 1920×1080" 알림. 클립보드는 건드리지 않는다.

### 4.4 목록 (`ClipListWindow`)

- 이미지 카드: 텍스트 미리보기 자리에 썸네일, 아래 줄에 "이미지 · 1920×1080 · 3분 전 · 다른 기기".
- 썸네일은 창이 열린 뒤 카드별로 비동기 요청(`GET /thumbnail`). 받기 전에는 회색 자리 표시. 앱 실행 중 메모리 캐시(클립 id → 이미지).
- 클릭: 창을 닫고 원본을 받아(`GET /image`) `writeImage`로 클립보드에 넣는다. 실패 시 알림 "이미지를 가져오지 못했습니다".
- 삭제(휴지통 아이콘, Delete 키)는 기존과 동일.
- 목록 UI 변경은 커밋 전에 오프스크린 렌더링 스크린샷으로 검토받는다.

### 4.5 `ApiClient`

- `JsonNode postImage(byte[] png)`
- `byte[] getImage(String id)`
- `byte[] getThumbnail(String id)`
- 기존과 같이 401이면 토큰 갱신 후 한 번 재시도.

## 5. 오류 처리 요약

| 상황 | 서버 | 트레이 앱 |
|---|---|---|
| 10MB 초과 | 413 | 업로드 전 차단 + 알림 1회 |
| 이미지 아님 / 5천만 픽셀 초과 | 400 | 로그 |
| 버킷 저장 실패 | 503, DB 변경 없음 | 로그 |
| DB 저장 실패 | 올린 객체 삭제 후 500 | 로그 |
| 남의 클립 / 텍스트 클립 / 객체 없음 | 404 | 목록: 썸네일 자리 유지, 클릭 시 알림 |
| 서버 비정상 종료로 남은 객체 | — | 버킷 수명 주기 규칙(8일)이 삭제 |

## 6. 테스트

**백엔드 통합 테스트** (`LocalImageStore` + 임시 폴더, H2)
- 업로드 → 201, type/width/height/size 확인
- 같은 이미지 재업로드 → 200, 행 수 그대로
- 목록에 IMAGE 타입으로 포함, 텍스트와 시간순 정렬
- 원본 다운로드가 업로드 바이트와 동일, 썸네일 긴 변 ≤ 240
- 남의 클립 → 404, 텍스트 클립에 /image → 404
- 10MB 초과 → 413, PNG 아닌 바이트 → 400
- 삭제 후 저장소에 객체 없음, 만료 정리 후 객체 없음
- WebSocket 푸시 메시지에 `type: IMAGE`
- `AesCipher` 바이트 암복호화 왕복, 같은 평문 → 다른 암호문

**트레이 앱 단위 테스트**
- 같은 픽셀 → 같은 이미지 키, 다른 픽셀 → 다른 키
- 10MB 판정

**실제 연동**
- E2E 하네스(2기기)에 이미지 시나리오 추가: 기기 A 업로드 → 기기 B 푸시 수신 → 썸네일/원본 다운로드 일치. 로컬 Docker와 배포 서버 모두.
- `S3ImageStore`는 자동 테스트 없이 실제 Oracle 버킷으로 한 번 수동 검증 (MinIO 테스트 컨테이너는 비용 대비 효과가 작아 제외).

## 7. 배포

**사용자 작업** (Oracle 콘솔, 단계별 안내는 구현 시 제공. 키 값은 사용자가 직접 VM에 입력)
1. 비공개 버킷 `clipvault-images` 생성 (Standard 티어)
2. 수명 주기 규칙: 객체 생성 8일 후 삭제
3. 사용자 프로필에서 Customer Secret Key 발급 (Access Key / Secret)
4. 네임스페이스와 리전 확인
5. VM의 `deploy/.env`에 `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` 추가

**순서**
- 위 설정을 먼저 넣고 PR을 머지한다. 설정 없이 배포되면 서버가 컨테이너 내부 폴더(`LocalImageStore`)에 저장하고, 재배포 때 이미지가 사라진다.
- 머지 → deploy.yml이 백엔드 자동 배포 → 배포 서버 대상 E2E 확인 → (요청 시) v1.2.0 태그 → v1.1.0 사용자는 자동 업데이트로 받음.
- 구버전 앱(v1.1.0)은 이미지 클립을 `[이미지 1920×1080]` 텍스트로 본다. 클릭하면 이 문구가 텍스트로 복사되지만 업데이트 전 과도기라 허용한다.

**변경 파일 (예상)**
- 백엔드: `clip/Clip`, `ClipController`(또는 이미지 전용 `ImageClipController`), `ClipResponse`, `ClipRepository`, `ClipCleanupJob`, `common/AesCipher`, 새 `storage/ImageStore`, `S3ImageStore`, `LocalImageStore`, `StorageConfig`, `application.yml`, `build.gradle`(AWS SDK)
- 트레이 앱: `ClipboardWatcher`, `TrayApp`, `ApiClient`, `ui/ClipListWindow`
- 배포: `deploy/.env.example`
- 문서: API_CONTRACT, PRD, TRD, TASKS, README

## 8. 넣지 않는 것 (YAGNI)

- 클라이언트 측 암호화/사전 서명 URL 직접 업로드 — 서버 경유로 결정
- 서버의 고아 객체 스캔 작업 — 버킷 수명 주기 규칙으로 대체
- 이미지 형식 보존(JPEG/GIF 원본 유지) — 클립보드 이미지는 PNG로 통일
- 썸네일 디스크 캐시 — 메모리 캐시로 충분 (최근 20개)
