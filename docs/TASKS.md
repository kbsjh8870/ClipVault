# ClipVault — TASKS

- 문서 버전: v0.2
- 작성일: 2026-09-25 (최초) / 수정: 2026-09-25
- 관련 문서: PRD.md, TRD.md

범위: PC(Windows) 기기 간 클립보드 자동 동기화 MVP. 모바일/macOS는 제외.

각 태스크는 코딩 에이전트가 하나씩 바로 실행할 수 있는 단위로 쪼갰다. 소요시간은 개인 개발(AI 코딩 에이전트 활용 포함) 기준 추정치이며, 실제로는 환경에 따라 달라질 수 있다.

예상 총 소요시간: 약 28~34시간 (Phase 0~5 합산)

## Phase 0. 준비 (예상 소계: 1~1.5시간)

- [ ] Git 리포지토리 생성 및 초기 커밋: `backend/`, `tray-client/`, `docs/` 폴더 구조 세팅 (예상 30분)
- [ ] Fly.io 또는 Railway 계정 생성, CLI 설치 및 로그인 확인 (예상 20분)
- [ ] Supabase 또는 Neon 프로젝트 생성, PostgreSQL 접속 정보(호스트/포트/DB명/계정) 확보 (예상 20분)
- [ ] 로컬 개발 환경 JDK/Gradle(또는 Maven) 설치 및 버전 확인 (예상 10분)

## Phase 1. 인증 & DB 스키마 (예상 소계: 4~5시간)

- [ ] Spring Boot 프로젝트 초기화 (Web, Security, Data JPA, PostgreSQL Driver 의존성 추가) (예상 30분)
- [ ] `application.yml`에 DB 연결 정보를 환경변수 기반으로 설정 (예상 20분)
- [ ] `User` 엔티티 작성 (id, email, password_hash, created_at) (예상 20분)
- [ ] `Device` 엔티티 작성 (id, user_id, device_name, os, last_seen_at, is_active) (예상 20분)
- [ ] `Clip` 엔티티 작성 (id, user_id, source_device_id, content, content_hash, created_at, expires_at) (예상 20분)
- [ ] `UserRepository`, `DeviceRepository`, `ClipRepository` 작성 (예상 20분)
- [ ] `POST /api/auth/signup` 구현: 이메일 중복 체크, bcrypt 해시 저장 (예상 40분)
- [ ] `POST /api/auth/login` 구현: JWT accessToken/refreshToken 발급 (예상 1시간)
- [ ] Spring Security 설정: JWT 필터, SecurityFilterChain, 인증 예외 처리 (예상 1시간)
- [ ] 인증 API 단위 테스트 작성 (signup 성공/중복 이메일 실패, login 성공/실패) (예상 40분)

## Phase 2. 클립 업로드/조회 API + 트레이 앱 기본 연동 (예상 소계: 8~9시간)

- [ ] `POST /api/devices` 구현: 인증 컨텍스트에서 user 식별 후 기기 등록 (예상 40분)
- [ ] `GET /api/devices` 구현: 내 기기 목록 조회 (예상 20분)
- [ ] `DELETE /api/devices/{id}` 구현: is_active=false 처리 (예상 30분)
- [ ] content_hash 계산 유틸(SHA-256) 작성 (예상 20분)
- [ ] `POST /api/clips` 구현: 동일 해시 클립은 새로 만들지 않고 timestamp만 갱신 (예상 1시간)
- [ ] `GET /api/clips?limit=` 구현: 최근순 정렬 조회 (예상 30분)
- [ ] `DELETE /api/clips/{id}` 구현 (예상 20분)
- [ ] 클립/기기 API 통합 테스트 작성 (예상 1시간)
- [ ] 트레이 앱 프로젝트 초기화: Java 시스템 트레이 아이콘 기본 골격 (예상 1시간)
- [ ] 트레이 앱: 로그인 다이얼로그 UI + `/api/auth/login` 연동 (예상 1시간)
- [ ] 트레이 앱: 로그인 성공 시 `/api/devices` 자동 등록 호출 연동 (예상 30분)
- [ ] 트레이 앱: `java.awt.Toolkit` 클립보드 리스너 구현, 텍스트 변경 감지 시 `/api/clips` 업로드 (예상 1.5시간)
- [ ] 트레이 앱: 클립 목록 팝업 UI 구현, `/api/clips` 조회 연동 (예상 1시간)
- [ ] 트레이 앱: 목록 항목 클릭 시 로컬 클립보드로 반영하는 로직 구현 (예상 30분)

## Phase 3. 실시간 동기화 - WebSocket (예상 소계: 4.5~5.5시간)

- [ ] 백엔드: Spring WebSocket(STOMP) 설정, `/ws` 엔드포인트 및 메시지 브로커 구성 (예상 1시간)
- [ ] 백엔드: WebSocket 핸드셰이크 시 JWT 인증 검증 인터셉터 구현 (예상 1시간)
- [ ] 백엔드: 클립 생성 시 `/topic/clips/{userId}`로 push하는 서비스 로직 구현 (예상 40분)
- [ ] 트레이 앱: WebSocket 클라이언트 연결 및 구독 로직 구현 (예상 1시간)
- [ ] 트레이 앱: 알림 수신 시 트레이 아이콘 뱃지/툴팁 표시(자동 클립보드 반영 없음) (예상 30분)
- [ ] source_device_id 기반 자기 echo 무시 로직 구현 (예상 20분)
- [ ] 트레이 앱: WebSocket 연결 끊김 감지 및 재연결 로직 구현 (예상 1시간)
- [ ] 재연결 시 `GET /api/clips` 재조회로 누락분 보정하는 로직 구현 (예상 30분)

## Phase 4. TTL, 기기 관리, UI 다듬기 (예상 소계: 3.5~4시간)

- [ ] Clip 생성 시 `expires_at` 계산 로직 반영 (기본 7일) (예상 20분)
- [ ] `@Scheduled` 배치 잡 구현: 매일 1회 만료 Clip 삭제 (예상 40분)
- [ ] 트레이 앱(또는 별도 설정 화면): 기기 목록 조회 UI 구현 (예상 1시간)
- [ ] 원격 로그아웃 연동: 기기 삭제 시 해당 기기 인증 무효화(refreshToken 폐기 등) (예상 1시간)
- [ ] `Clip.content` AES 암호화/복호화 로직 구현 (환경변수 기반 키) (예상 1시간)
- [ ] 전역 예외 처리 및 로깅(Logback) 설정 정비 (예상 30분)

## Phase 5. 배포 & 문서화 (예상 소계: 5.5~7시간)

- [ ] 백엔드 Dockerfile 작성 (예상 40분)
- [ ] Fly.io 또는 Railway에 백엔드 배포 (예상 1시간)
- [ ] 배포 환경에 환경변수(DB 접속정보, JWT 시크릿, 암호화 키) 등록 (예상 30분)
- [ ] 트레이 앱 jpackage로 실행 파일 패키징 (예상 1.5시간)
- [ ] 실제 2대 PC에서 통합 테스트: 동기화 지연, 중복 방지, TTL 동작 확인 (예상 1시간)
- [ ] README 작성: 설치/실행 방법, 아키텍처 다이어그램 포함 (예상 1시간)
- [ ] 기술 블로그(Velog) 포스팅 작성: 설계 트레이드오프 정리 (예상 1시간)

## 백로그 (v1 이후, 소요시간 미산정)

- [ ] 모바일 대응 (PWA, Android Web Share Target 검토, 정적 리소스는 Vercel/Cloudflare Pages 배포 검토)
- [ ] macOS 트레이 앱 지원
- [ ] 이미지 클립보드 지원
- [ ] 검색 / 태그 / 즐겨찾기 기능
- [ ] 종단간 암호화(E2E) 전환
- [ ] 사용자별 TTL 설정 옵션
- [ ] GitHub Actions 기반 CI/CD 구축
