# ClipVault — TRD (Technical Requirements Document)

- 문서 버전: v0.2
- 작성일: 2026-09-25 (최초) / 수정: 2026-09-25
- 관련 문서: PRD.md, TASKS.md

## 1. 아키텍처 개요

```
[Windows 트레이 앱 A] <--REST/WS--> [Spring Boot 백엔드] <--REST/WS--> [Windows 트레이 앱 B]
                                        |
                                     [PostgreSQL]
```

- 클라이언트(트레이 앱)는 로컬 클립보드 변경을 감지해 REST API로 서버에 업로드한다.
- 서버는 저장 후 같은 사용자의 다른 활성 기기에 WebSocket(STOMP)으로 새 클립 도착을 push한다.
- 클라이언트는 알림을 받으면 UI(트레이 아이콘/목록)만 갱신하고, 사용자가 항목을 클릭했을 때 로컬 클립보드에 반영한다.

이 프로젝트는 별도의 SPA/정적 프론트엔드 없이 "백엔드 API 서버 + 데스크톱 트레이 클라이언트" 2개 컴포넌트로만 구성된다는 점이 일반적인 웹 서비스 구조와 다르다. 이 차이는 배포 방법(6장)에도 영향을 준다.

## 2. 기술 스택 및 선택 이유

| 영역 | 선택 | 선택 이유 |
|---|---|---|
| 백엔드 프레임워크 | Spring Boot | 기존에 학습/사용 중인 스택이라 러닝커브 없이 바로 구현 속도를 낼 수 있고, 백엔드 개발자 포트폴리오 방향과 직접 일치 |
| 인증 | Spring Security + JWT | 세션 저장소 없이 stateless로 동작해 서버 확장이 쉽고, 브라우저 쿠키 세션이 없는 데스크톱 클라이언트(트레이 앱)에도 자연스럽게 적용 가능 |
| 실시간 통신 | Spring WebSocket (STOMP) | Spring 생태계에 내장되어 있어 별도 인프라(Redis Pub/Sub 등) 없이 시작 가능하고, topic 기반 pub/sub 구조가 "한 사용자의 여러 기기에 동시 브로드캐스트"하는 요구사항과 잘 맞음 |
| ORM | Spring Data JPA | Spring Boot와 통합이 쉽고, 엔티티 3종(User/Device/Clip) 수준의 단순한 관계형 모델에 적합 |
| DB | PostgreSQL | 관계형 구조(User-Device-Clip)가 명확하고, Supabase/Neon 등 무료 티어 선택지가 풍부해 예산 최소화 목표에 부합 |
| 클라이언트(데스크톱) | Java (Swing 트레이 아이콘) | 백엔드와 동일 언어로 개발해 새 스택을 익힐 필요가 없고, `java.awt.Toolkit`의 클립보드 API가 OS 종속적이지 않아 추후 macOS 확장 시 재사용 가능성이 높음 |
| 배포(백엔드) | Fly.io 또는 Railway | 상시 구동되는 서버(WebSocket 유지 포함)를 무료/저비용으로 운영 가능하고 Docker 기반 배포가 간단함 |
| 배포(DB) | Supabase 또는 Neon | 관리형 PostgreSQL을 무료 티어로 제공하며, 별도 DB 서버 운영 부담이 없음 |

## 3. 폴더 구조

```
clipvault/
├── docs/
│   ├── PRD.md
│   ├── TRD.md
│   └── TASKS.md
├── backend/
│   ├── src/main/java/com/clipvault/
│   │   ├── auth/           # 회원가입/로그인, JWT 발급/검증
│   │   ├── device/         # 기기 등록/조회/삭제
│   │   ├── clip/           # 클립 업로드/조회/삭제, 중복 처리, TTL 배치
│   │   ├── websocket/      # STOMP 설정, 인증 인터셉터, push 로직
│   │   ├── config/         # SecurityConfig, WebSocketConfig 등
│   │   └── common/         # 공통 예외처리, 유틸(해시 등)
│   ├── src/main/resources/
│   │   └── application.yml
│   ├── src/test/java/com/clipvault/
│   └── build.gradle
├── tray-client/
│   ├── src/main/java/com/clipvault/client/
│   │   ├── clipboard/      # ClipboardListener (java.awt.Toolkit 기반)
│   │   ├── network/        # REST 클라이언트, WebSocket 클라이언트
│   │   ├── ui/             # 트레이 아이콘, 목록 팝업, 로그인 다이얼로그
│   │   └── auth/           # 로그인 상태/토큰 저장
│   └── build.gradle
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
| last_seen_at | timestamp | 마지막 접속 시각 |
| is_active | boolean | 로그아웃된 기기 구분 |

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

관계: `User 1—N Device`, `User 1—N Clip`, `Device 1—N Clip(source)`

## 5. API 명세 (초안)

### 5.1 인증
- `POST /api/auth/signup` — { email, password } → 201
- `POST /api/auth/login` — { email, password } → { accessToken, refreshToken }

### 5.2 기기
- `POST /api/devices` — { deviceName, os } → 기기 등록, 인증 필요
- `GET /api/devices` — 내 기기 목록 조회
- `DELETE /api/devices/{deviceId}` — 원격 로그아웃

### 5.3 클립
- `POST /api/clips` — { content } → 클립 업로드 (source_device는 인증 컨텍스트에서 식별)
- `GET /api/clips?limit=20` — 최근 클립 목록 조회
- `DELETE /api/clips/{clipId}` — 개별 삭제

### 5.4 실시간(WebSocket, STOMP)
- 연결: `WS /ws` (JWT를 CONNECT 헤더로 인증)
- 구독: `/topic/clips/{userId}` — 새 클립 도착 시 서버가 payload push
- 클라이언트는 자신이 업로드한 클립의 echo를 무시하도록 source_device_id로 필터링

## 6. 핵심 로직 설계

### 6.1 클립보드 감지 (트레이 앱)
- `java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()`에 `FlavorListener` 등록
- 텍스트(`DataFlavor.stringFlavor`)만 우선 처리, 이미지/파일은 무시
- 자기 자신이 방금 반영한 클립(서버에서 받아 로컬에 붙여넣은 경우)이 다시 캡처되어 무한 루프로 재업로드되지 않도록, 직전에 반영한 content_hash는 짧은 시간 동안 업로드 대상에서 제외

### 6.2 중복 방지
- 클립 업로드 시 content의 해시(SHA-256 등)를 계산
- 같은 사용자의 가장 최근 클립과 해시가 같으면 새 레코드 생성 대신 `created_at`/`expires_at`만 갱신

### 6.3 TTL 및 배치 삭제
- `expires_at`은 생성 시 `created_at + 기본 보관기간`으로 계산
- `@Scheduled` 배치 잡(예: 매일 1회)이 만료된 Clip을 일괄 삭제

### 6.4 보안
- 비밀번호: bcrypt 해시 저장
- 클립 내용: 서버 저장 시 대칭키(AES) 암호화. 키는 환경변수/시크릿 매니저로 관리 (v1은 단일 서버 키, 향후 사용자별 키 파생 검토)
- 통신: 전 구간 HTTPS/WSS
- JWT: accessToken 단기 만료 + refreshToken 로테이션

## 7. 외부 API/서비스 의존성

이 프로젝트는 결제, 지도, 소셜로그인 등 복잡한 서드파티 API 의존성이 없는 것이 특징이다. 필요한 외부 의존성은 다음과 같다.

| 구분 | 의존 대상 | 용도 | 비고 |
|---|---|---|---|
| 인프라 | Fly.io 또는 Railway | 백엔드 서버 호스팅 | 무료 티어 사용, WebSocket 상시 연결 지원 확인 필요 |
| 인프라 | Supabase 또는 Neon | 관리형 PostgreSQL | 무료 티어, 커넥션 제한 확인 필요 |
| 라이브러리 | Spring Boot Starter (Web, Security, WebSocket, Data JPA) | 백엔드 핵심 프레임워크 | |
| 라이브러리 | JJWT (또는 Nimbus JOSE) | JWT 발급/검증 | |
| 라이브러리 | Spring Security 내장 BCryptPasswordEncoder | 비밀번호 해시 | 별도 서비스 불필요 |
| 라이브러리 | Java-WebSocket 또는 Spring WebSocket Client | 트레이 앱의 서버 실시간 연결 | |
| (선택, v1 제외 가능) | 이메일 발송 서비스 | 회원가입 인증 메일 | v1은 이메일 인증 없이 즉시 가입 처리, 필요 시 추후 추가 |

## 8. 배포 방법

### 8.1 백엔드(API 서버)
- Fly.io 또는 Railway에 Docker 컨테이너로 배포
- Vercel, Cloudflare Pages는 정적 사이트/서버리스 함수에 최적화된 플랫폼이라, WebSocket 연결을 상시 유지해야 하는 Spring Boot 서버(장시간 구동 프로세스)에는 적합하지 않다. 따라서 이 프로젝트의 백엔드는 Fly.io/Railway처럼 컨테이너를 상시 구동할 수 있는 플랫폼을 사용한다.
- 환경변수로 DB 접속 정보, JWT 시크릿, 클립 암호화 키를 주입

### 8.2 프론트엔드(정적 사이트) — 현재 범위에는 없음
- v1은 별도의 웹 프론트엔드/랜딩페이지가 없다. 만약 향후 소개용 랜딩페이지나 모바일 웹(PWA) 대시보드를 추가한다면, 그 정적 리소스는 Vercel 또는 Cloudflare Pages에 배포하는 것이 적합하다(무료 티어, 자동 배포, CDN 제공). 이는 PRD의 향후 로드맵(모바일 PWA) 항목과 연결되는 백로그다.

### 8.3 데스크톱 클라이언트(트레이 앱)
- jpackage로 실행 가능한 배포 산출물(jar 또는 OS별 실행 파일) 생성
- GitHub Releases를 통한 다운로드 배포, 또는 별도 다운로드 페이지 운영

### 8.4 CI/CD (백로그)
- GitHub Actions로 백엔드 빌드/테스트/배포 자동화 검토

## 9. 리스크 및 미해결 이슈

- Java 트레이 앱의 배포/패키징(exe화) 난이도 검증 필요
- WebSocket 연결 끊김 시 재연결 및 놓친 클립 재동기화 로직 필요 (재접속 시 `GET /api/clips`로 최근 목록을 다시 받아오는 방식으로 보완)
- 서버 측 암호화 키 관리 방식은 MVP 이후 개선 필요
- 민감정보(비밀번호 매니저에서 복사한 값 등) 필터링은 v1에서 별도 처리하지 않음 — 필요 시 "일시정지" 토글 정도로 완화
- Fly.io/Railway 무료 티어의 WebSocket 유휴 연결 정책(슬립/타임아웃) 사전 확인 필요

## 10. 확장 고려사항 (백로그, 설계에 영향 주는 부분만)

- 모바일(PWA) 추가 시: REST API는 그대로 재사용 가능, WebSocket 구독 로직도 웹 클라이언트에서 재사용 가능하도록 설계. 정적 리소스는 Vercel/Cloudflare Pages 배포 고려
- macOS 지원 시: `java.awt.Toolkit` 기반 클립보드 로직은 크로스플랫폼이므로 트레이 앱 코드 재사용 가능성 높음, OS별 트레이 아이콘 처리만 별도 검증 필요
