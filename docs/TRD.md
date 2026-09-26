# ClipVault — TRD (Technical Requirements Document)

- 문서 버전: v0.4
- 작성일: 2026-09-25 (최초) / 수정: 2026-09-26 (이미지 클립보드 동기화 반영: Object Storage, storage/ 폴더, clips 컬럼 추가, S3_* 환경변수)
- 관련 문서: PRD.md, TASKS.md, API_CONTRACT.md (API 상세 명세의 기준 문서)

## 1. 아키텍처 개요

```
[Windows 트레이 앱 A] <--HTTPS/WSS--> [Caddy] --> [Spring Boot 백엔드] <-- ... --> [Windows 트레이 앱 B]
                                       (Oracle Cloud VM, Docker)   |
                                                              [PostgreSQL (Neon)]
```

- 클라이언트(트레이 앱)는 로컬 클립보드 변경을 감지해 REST API로 서버에 업로드한다.
- 서버는 저장 후 같은 사용자의 다른 활성 기기에 WebSocket(STOMP)으로 새 클립 도착을 push한다.
- 클라이언트는 알림을 받으면 UI(트레이 아이콘/목록)만 갱신하고, 사용자가 항목을 클릭했을 때 로컬 클립보드에 반영한다.

이 프로젝트는 별도의 SPA/정적 프론트엔드 없이 "백엔드 API 서버 + 데스크톱 트레이 클라이언트" 2개 컴포넌트로만 구성된다는 점이 일반적인 웹 서비스 구조와 다르다. 이 차이는 배포 방법(8장)에도 영향을 준다.

## 2. 기술 스택 및 선택 이유

| 영역 | 선택 | 선택 이유 |
|---|---|---|
| 백엔드 프레임워크 | Spring Boot | 기존에 학습/사용 중인 스택이라 러닝커브 없이 바로 구현 속도를 낼 수 있고, 백엔드 개발자 포트폴리오 방향과 직접 일치 |
| 인증 | Spring Security + JWT | 세션 저장소 없이 stateless로 동작해 서버 확장이 쉽고, 브라우저 쿠키 세션이 없는 데스크톱 클라이언트(트레이 앱)에도 자연스럽게 적용 가능 |
| 실시간 통신 | Spring WebSocket (STOMP) | Spring 생태계에 내장되어 있어 별도 인프라(Redis Pub/Sub 등) 없이 시작 가능하고, topic 기반 pub/sub 구조가 "한 사용자의 여러 기기에 동시 브로드캐스트"하는 요구사항과 잘 맞음 |
| ORM | Spring Data JPA | Spring Boot와 통합이 쉽고, 엔티티 3종(User/Device/Clip) 수준의 단순한 관계형 모델에 적합 |
| DB | PostgreSQL | 관계형 구조(User-Device-Clip)가 명확하고, Supabase/Neon 등 무료 티어 선택지가 풍부해 예산 최소화 목표에 부합 |
| 이미지 저장소 | Oracle Object Storage (S3 호환, AWS SDK v2) | 무료 20GB, 서버 경유·AES-GCM 암호화 |
| 클라이언트(데스크톱) | Java 21 (Swing/AWT SystemTray) + FlatLaf | 백엔드와 동일 언어로 개발해 새 스택을 익힐 필요가 없고, `java.awt.Toolkit`의 클립보드 API가 OS 종속적이지 않아 추후 macOS 확장 시 재사용 가능성이 높음. FlatLaf로 윈도우 다크/라이트 모드를 따르는 모던 UI |
| 클라이언트 통신 | `java.net.http` (HttpClient, WebSocket) + STOMP 직접 구현 | 외부 라이브러리 없이 JDK 기본 기능으로 REST/WebSocket 처리. STOMP는 필요한 프레임(CONNECT/SUBSCRIBE/MESSAGE/ERROR)만 구현 |
| 배포(백엔드) | Oracle Cloud Always Free VM (ARM) + Docker Compose + Caddy | WebSocket을 상시 유지해야 해서 잠드는 무료 호스팅(Render 등)은 부적합, Fly.io/Railway는 유료. Always Free VM은 영구 무료이면서 항상 켜져 있음. Caddy가 HTTPS 인증서를 자동 발급 |
| 배포(DB) | Neon | 관리형 PostgreSQL을 무료 티어로 제공하며, 별도 DB 서버 운영 부담이 없음 |
| 배포(클라이언트) | jpackage 포터블 zip + GitHub Releases | Java 런타임을 포함해 사용자 PC에 JDK가 없어도 실행 |
| CI/CD | GitHub Actions | 테스트, 태그 기반 릴리스(exe 빌드), VM 자동 배포 |

## 3. 폴더 구조

```
clipvault/
├── .github/workflows/      # ci.yml, release.yml, deploy.yml
├── docs/
│   ├── PRD.md
│   ├── TRD.md
│   ├── TASKS.md
│   ├── API_CONTRACT.md     # API/모듈 상세 명세 (팀 공통 기준)
│   └── images/             # README 스크린샷
├── backend/
│   ├── src/main/java/com/clipvault/
│   │   ├── auth/           # 회원가입/로그인, JWT 발급/검증
│   │   ├── device/         # 기기 등록/조회/삭제
│   │   ├── clip/           # 클립 업로드/조회/삭제, 중복 처리, TTL 배치
│   │   ├── websocket/      # STOMP 설정, 인증 인터셉터, push 로직
│   │   ├── storage/        # ImageStore(S3ImageStore/LocalImageStore), StorageConfig
│   │   ├── config/         # SecurityConfig, WebSocketConfig 등
│   │   └── common/         # 공통 예외처리, 유틸(해시 등)
│   ├── src/main/resources/
│   │   └── application.yml
│   ├── src/test/java/com/clipvault/
│   ├── Dockerfile
│   └── build.gradle
├── tray-client/
│   ├── src/main/java/com/clipvault/client/
│   │   ├── clipboard/      # ClipboardWatcher, EchoGuard(재업로드 방지)
│   │   ├── network/        # ApiClient(REST), ClipSocket(WebSocket), StompFrame
│   │   ├── ui/             # Theme, 클립 목록 팝업, 기기 관리 창
│   │   ├── auth/           # 로그인 창, Session(토큰/설정 저장)
│   │   └── update/         # Updater(새 버전 확인/설치), update.ps1(폴더 교체 스크립트)
│   ├── packaging/          # ClipVault.ico
│   └── build.gradle        # packageApp/packageZip (jpackage)
├── deploy/                 # 운영용 docker-compose.prod.yml, Caddyfile, .env.example
├── docker-compose.yml      # 로컬 개발용 (PostgreSQL + backend)
├── settings.gradle         # Gradle 멀티프로젝트
└── README.md
```

## 4. 데이터 모델 (ERD 초안)

### User
| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID/PK | |
| email | varchar, unique | 로그인 아이디 |
| password_hash | varchar | bcrypt 등 |
| created_at | timestamp | |

### Device
| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID/PK | |
| user_id | FK -> User | |
| device_name | varchar | 사용자 지정 가능 (예: "회사PC") |
| os | varchar | 자동 감지 |
| last_seen_at | timestamp | 마지막 접속 시각 (1분 단위로 갱신) |
| is_active | boolean | 로그아웃된 기기 구분 |
| refresh_token_hash | varchar | 현재 유효한 refresh 토큰의 SHA-256 (rotation, 로그아웃 시 null) |

### Clip
| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID/PK | |
| user_id | FK -> User | |
| source_device_id | FK -> Device | 어느 기기에서 복사됐는지 |
| content | text (암호화 저장) | 클립 내용 |
| content_hash | varchar | 중복 방지용 (동일 내용 재복사 시 timestamp만 갱신) |
| created_at | timestamp | |
| expires_at | timestamp | TTL 계산값 |
| type | varchar(10), null 허용 | `TEXT` / `IMAGE`. null은 TEXT(기존 행 호환) |
| image_key | varchar(64), null | 버킷 객체 이름용 무작위 UUID. 이미지 클립만 |
| width, height | int, null | 원본 픽셀 크기. 이미지 클립만 |
| image_size | bigint, null | 원본 PNG 바이트 수. 이미지 클립만 (`size`는 예약어 충돌을 피하려고 `image_size`로 둔다) |

관계: `User 1—N Device`, `User 1—N Clip`, `Device 1—N Clip(source)`

구현 메모: FK 제약 대신 UUID 컬럼으로 참조한다. 기기는 삭제하지 않고 비활성화만 하므로 클립이 없는 기기를 가리키는 일은 없다. 스키마는 JPA `ddl-auto: update`로 생성한다(Flyway 미사용).

## 5. API 명세

요청/응답 필드, 에러 코드, 토큰 규칙의 상세는 [API_CONTRACT.md](API_CONTRACT.md)가 기준이다. 여기에는 요약만 적는다.

### 5.1 인증
- `POST /api/auth/signup` — { email, password } → 201
- `POST /api/auth/login` — { email, password } → { userId, accessToken } (기기 등록 전용 user 토큰, refresh 없음)
- `POST /api/auth/refresh` — { refreshToken } → 새 토큰 쌍 (refresh 토큰도 매번 교체)

### 5.2 기기
- `POST /api/devices` — { deviceName, os } → 기기 등록 + 기기 전용 토큰 쌍(access/refresh) 발급
- `GET /api/devices` — 내 기기 목록 조회
- `DELETE /api/devices/{deviceId}` — 원격 로그아웃 (토큰 무효화 + 열린 WebSocket 즉시 종료)

### 5.3 클립
- `POST /api/clips` — { content } → 신규 201 / 직전 클립과 같은 내용이면 200 (source_device는 인증 컨텍스트에서 식별)
- `GET /api/clips?limit=20` — 최근 클립 목록 조회
- `DELETE /api/clips/{clipId}` — 개별 삭제

### 5.4 실시간(WebSocket, STOMP)
- 연결: `WS /ws` (JWT를 CONNECT 헤더로 인증)
- 구독: `/topic/clips/{userId}` — 새 클립 도착 시 서버가 payload push (본인 topic만 구독 가능, 클라이언트 SEND 금지)
- 클라이언트는 자신이 업로드한 클립의 echo를 무시하도록 source_device_id로 필터링

## 6. 핵심 로직 설계

### 6.1 클립보드 감지 (트레이 앱)
- `java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()`에 `FlavorListener` 등록
- FlavorListener는 데이터 종류가 바뀔 때만 호출되므로(텍스트→텍스트 복사는 감지 못 함) 1초 간격 폴링을 함께 사용
- 텍스트(`DataFlavor.stringFlavor`)만 우선 처리, 이미지/파일은 무시
- 자기 자신이 방금 반영한 클립(서버에서 받아 로컬에 붙여넣은 경우)이 다시 캡처되어 무한 루프로 재업로드되지 않도록, 서버에서 받아 반영한 텍스트는 5초 동안 업로드 대상에서 제외(`EchoGuard`). 직전에 업로드한 텍스트와 같아도 제외
- "일시정지" 중에는 업로드하지 않음 (상태는 재시작 후에도 유지)

### 6.2 중복 방지
- 클립 업로드 시 content의 해시(SHA-256 등)를 계산
- 같은 사용자의 가장 최근 클립과 해시가 같으면 새 레코드 생성 대신 `created_at`/`expires_at`만 갱신

### 6.3 TTL 및 배치 삭제
- `expires_at`은 생성 시 `created_at + 기본 보관기간`으로 계산
- `@Scheduled` 배치 잡(예: 매일 1회)이 만료된 Clip을 일괄 삭제

### 6.4 보안
- 비밀번호: bcrypt 해시 저장
- 클립 내용: 서버 저장 시 AES-256-GCM 암호화(매번 랜덤 IV, 위변조 감지). 키는 환경변수로 관리 (v1은 단일 서버 키, 향후 사용자별 키 파생 검토)
- 통신: 전 구간 HTTPS/WSS (Caddy가 Let's Encrypt 인증서 자동 발급/갱신)
- JWT(HS256): accessToken 15분 + refreshToken 30일 로테이션. 로그인 토큰(user)과 기기 토큰(device) 분리
- 원격 로그아웃: 요청마다 기기 활성 여부를 확인해 즉시 반영, 열린 WebSocket 세션도 서버가 종료

## 7. 외부 API/서비스 의존성

이 프로젝트는 결제, 지도, 소셜로그인 등 복잡한 서드파티 API 의존성이 없는 것이 특징이다. 필요한 외부 의존성은 다음과 같다.

| 구분 | 의존 대상 | 용도 | 비고 |
|---|---|---|---|
| 인프라 | Oracle Cloud Always Free (VM.Standard.A1.Flex) | 백엔드 서버 호스팅 | 영구 무료, 항상 켜짐 |
| 인프라 | Neon | 관리형 PostgreSQL | 무료 티어. 유휴 시 잠들어 첫 요청이 1~2초 느림 |
| 인프라 | sslip.io | IP 기반 도메인 (`161-33-167-228.sslip.io`) | 도메인 구매 없이 HTTPS 인증서 발급용 |
| 인프라 | Caddy (Docker 이미지) | HTTPS 종료 + 리버스 프록시 | Let's Encrypt 자동 인증서, WebSocket 전달 |
| 라이브러리 | Spring Boot Starter (Web, Security, WebSocket, Data JPA) | 백엔드 핵심 프레임워크 | |
| 라이브러리 | JJWT 0.12 | JWT 발급/검증 | |
| 라이브러리 | Spring Security 내장 BCryptPasswordEncoder | 비밀번호 해시 | 별도 서비스 불필요 |
| 라이브러리 | JDK `java.net.http` | 트레이 앱의 REST/WebSocket 통신 | 외부 라이브러리 없음 |
| 라이브러리 | Jackson Databind | 트레이 앱 JSON 처리 | |
| 라이브러리 | FlatLaf 3.7 | 트레이 앱 UI 테마 | |
| (선택, v1 제외 가능) | 이메일 발송 서비스 | 회원가입 인증 메일 | v1은 이메일 인증 없이 즉시 가입 처리, 필요 시 추후 추가 |

## 8. 배포 방법

### 8.1 백엔드(API 서버)
- Oracle Cloud Always Free VM(ARM, Ubuntu)에서 `deploy/docker-compose.prod.yml`로 백엔드 + Caddy 컨테이너 실행
- 서버 주소: `https://161-33-167-228.sslip.io` (sslip.io로 공인 IP를 도메인처럼 사용, Caddy가 HTTPS 인증서 자동 발급)
- DB는 외부 관리형 PostgreSQL(Neon)을 사용하므로 VM에는 DB 컨테이너가 없음
- 환경변수(`DOMAIN`, DB 접속 정보, `JWT_SECRET`, `CLIP_ENCRYPTION_KEY`)는 VM의 `deploy/.env`에 두고 git에는 올리지 않음
- 서버 VM 방화벽(Oracle Security List + VM iptables)에서 80/443 허용 필요 (80은 인증서 발급용)
- 이미지 저장: 비공개 Oracle Object Storage 버킷(`clipvault-images`)을 사용하며, 객체 생성 8일 후 삭제하는 수명 주기 규칙을 걸어 서버 비정상 종료로 남은 객체를 정리한다. 접속 정보는 `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` 환경변수로 VM의 `deploy/.env`에 둔다. `S3_BUCKET`이 비어 있으면 로컬 폴더(`IMAGE_LOCAL_DIR`, 기본 `./data/images`)에 저장한다
- 선정 과정: Vercel/Cloudflare Pages는 상시 구동 서버에 부적합, Render/Koyeb 무료 티어는 유휴 시 잠들어 WebSocket 알림이 지연, Fly.io/Railway는 유료 → 영구 무료이면서 항상 켜져 있는 Oracle VM 선택

### 8.2 프론트엔드(정적 사이트) — 현재 범위에는 없음
- v1은 별도의 웹 프론트엔드/랜딩페이지가 없다. 만약 향후 소개용 랜딩페이지나 모바일 웹(PWA) 대시보드를 추가한다면, 그 정적 리소스는 Vercel 또는 Cloudflare Pages에 배포하는 것이 적합하다(무료 티어, 자동 배포, CDN 제공). 이는 PRD의 향후 로드맵(모바일 PWA) 항목과 연결되는 백로그다.

### 8.3 데스크톱 클라이언트(트레이 앱)
- `./gradlew :tray-client:packageZip` → jpackage로 Java 런타임을 포함한 포터블 폴더(`ClipVault.exe`)를 만들고 zip으로 압축
- GitHub Releases로 배포. 고정 다운로드 링크: `https://github.com/kbsjh8870/ClipVault/releases/latest/download/ClipVault-windows.zip`
- 기본 서버 주소는 배포 서버. 로그인 창의 "서버 설정"에서 변경 가능
- 코드 서명이 없어 첫 실행 시 SmartScreen 경고가 뜸 (추가 정보 → 실행)
- **자동 업데이트** (v1.1.0~): 켜질 때와 24시간마다 GitHub `releases/latest`의 태그를 exe의 앱 버전(`jpackage.app-version`)과 비교한다. 새 버전이 있으면 트레이 알림 + 메뉴에 "업데이트 (vX.Y.Z)" 표시.
  - 클릭 → 확인 → zip을 앱 폴더 옆(`ClipVault.update/`)에 받아 릴리스의 `.sha256`으로 검증하고 풀어 둔 뒤, `update.ps1`을 띄우고 앱 종료
  - 스크립트: 앱 프로세스(실행기+자바) 종료 대기 → 기존 폴더를 `.old`로 → 새 폴더를 제자리로 → 재실행 → `.old` 삭제. 실패하면 `.old`를 되돌린다
  - 로그인/설정은 레지스트리(Preferences)에 있어 폴더 교체 후에도 유지. 앱 폴더 상위에 쓰기 권한이 없으면(Program Files 등) 릴리스 페이지를 연다
  - 체크섬은 손상만 막는다. 서명 검증은 코드 서명 도입 시 추가

### 8.4 CI/CD (GitHub Actions)
- `ci.yml`: 모든 push/PR에서 전체 테스트
- `release.yml`: `v*` 태그 푸시 시 Windows 러너에서 exe 빌드 후 GitHub 릴리스 생성 (앱 버전은 태그에서 추출). 자동 업데이트용 `ClipVault-windows.zip.sha256`도 함께 올린다
- `deploy.yml`: main의 `backend/`, `deploy/`, Gradle 설정 변경 시 백엔드 테스트 후 VM에 SSH 접속해 `git reset --hard origin/main` + `docker compose up -d --build`, 이후 외부 헬스 체크(401 응답 확인). SSH 정보는 GitHub Secrets(`DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`)

## 9. 리스크 및 미해결 이슈

- ~~Java 트레이 앱의 배포/패키징(exe화) 난이도 검증 필요~~ → jpackage 포터블 zip으로 해결 (설치 마법사형은 WiX 필요, 미적용)
- ~~WebSocket 연결 끊김 시 재연결 및 놓친 클립 재동기화 로직 필요~~ → 지수 백오프 재연결 + 재연결 시 `GET /api/clips` 재조회로 구현
- ~~Fly.io/Railway 무료 티어의 WebSocket 유휴 연결 정책 확인 필요~~ → Oracle VM 사용으로 해당 없음
- 서버 측 암호화 키 관리 방식은 MVP 이후 개선 필요. `CLIP_ENCRYPTION_KEY` 분실 시 기존 클립 복호화 불가 → 별도 백업 필수
- 민감정보(비밀번호 매니저에서 복사한 값 등) 필터링은 v1에서 별도 처리하지 않음 — "일시정지" 토글로 완화 (구현 완료)
- 공인 IP가 바뀌면 서버 주소(sslip.io)도 바뀜 → Oracle Reserved Public IP로 고정 권장. 주소 변경 시 exe 재배포 필요
- 원격 로그아웃 시 WebSocket 종료는 서버 메모리 기반이라 서버 1대에서만 동작 (다중 서버 시 브로드캐스트 필요)
- 같은 내용이 정확히 동시에 업로드되면 중복 저장될 수 있음 (중복 확인과 저장 사이 잠금 없음, 실사용 영향 미미)
- 코드 서명 인증서가 없어 SmartScreen 경고 발생

## 10. 확장 고려사항 (백로그, 설계에 영향 주는 부분만)

- 모바일(PWA) 추가 시: REST API는 그대로 재사용 가능, WebSocket 구독 로직도 웹 클라이언트에서 재사용 가능하도록 설계. 정적 리소스는 Vercel/Cloudflare Pages 배포 고려
- macOS 지원 시: `java.awt.Toolkit` 기반 클립보드 로직은 크로스플랫폼이므로 트레이 앱 코드 재사용 가능성 높음, OS별 트레이 아이콘 처리만 별도 검증 필요
