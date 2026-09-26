package com.clipvault.client.auth;

import java.util.prefs.Preferences;

/**
 * 현재 로그인 상태(서버 주소, 토큰, 사용자/기기 ID)를 들고 있고, PC에 저장해 두는 클래스.
 *
 * <p>저장 위치는 자바 기본 {@link Preferences}다. 윈도우에서는 레지스트리
 * {@code HKEY_CURRENT_USER\Software\JavaSoft\Prefs\com\clipvault\client}에 저장된다.
 * 그래서 트레이 앱을 껐다 켜도 다시 로그인할 필요가 없다.</p>
 *
 * <p>필드가 {@code volatile}인 이유: 네트워크 스레드와 화면 스레드가 동시에 읽고 쓰기 때문에,
 * 한 스레드가 바꾼 값을 다른 스레드가 즉시 볼 수 있게 한다.</p>
 */
public class Session {
    /** 이 앱 전용 저장 공간(노드). */
    private static final Preferences PREFS = Preferences.userRoot().node("com/clipvault/client");

    /** API 호출에 쓰는 device access 토큰 (15분 수명). */
    public volatile String accessToken = PREFS.get("accessToken", null);
    /** access 토큰을 새로 받을 때 쓰는 refresh 토큰 (30일 수명, 쓸 때마다 교체됨). */
    public volatile String refreshToken = PREFS.get("refreshToken", null);
    /** 내 사용자 ID. WebSocket 구독 주소(/topic/clips/{userId})에 쓴다. */
    public volatile String userId = PREFS.get("userId", null);
    /** 이 PC의 기기 ID. 알림 중 내가 올린 것(echo)을 거를 때 쓴다. */
    public volatile String deviceId = PREFS.get("deviceId", null);
    /** 서버 주소. 예: https://161-33-167-228.sslip.io (로컬 개발 시 http://localhost:8080) */
    public volatile String server = defaultServer();
    /** "일시정지" 메뉴 상태. 앱을 다시 켜도 유지되도록 저장한다 (로그아웃해도 지우지 않음). */
    public volatile boolean paused = PREFS.getBoolean("paused", false);

    /**
     * 서버 주소를 정하는 우선순위:
     * ① 실행 옵션 {@code -Dclipvault.server=...} → ② 환경변수 {@code CLIPVAULT_SERVER}
     * → ③ 지난번에 저장한 값 → ④ 기본값: 배포 서버 {@code https://161-33-167-228.sslip.io}.
     * 끝에 붙은 "/"는 떼어 낸다 (뒤에 "/api/..."를 붙일 때 "//"가 되지 않도록).
     */
    private static String defaultServer() {
        String s = System.getProperty("clipvault.server");
        if (s == null) s = System.getenv("CLIPVAULT_SERVER");
        if (s == null) s = PREFS.get("server", "https://161-33-167-228.sslip.io");
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** 기기 등록까지 끝난 로그인 상태인지. (refresh 토큰이 있어야 앱 재시작 후에도 로그인을 이어 갈 수 있다) */
    public boolean hasDevice() {
        return refreshToken != null && deviceId != null && userId != null;
    }

    /** 현재 값들을 PC에 저장한다. 로그인, 토큰 갱신 후에 호출된다. */
    public synchronized void save() {
        put("accessToken", accessToken);
        put("refreshToken", refreshToken);
        put("userId", userId);
        put("deviceId", deviceId);
        put("server", server);
        PREFS.putBoolean("paused", paused);
    }

    /** 로그아웃: 토큰과 ID를 지운다. 서버 주소와 일시정지 설정은 다음 로그인 때 편하도록 남겨 둔다. */
    public synchronized void clear() {
        accessToken = refreshToken = userId = deviceId = null;
        save();
    }

    /** 값이 null이면 저장소에서 키를 지우고, 아니면 저장한다. */
    private static void put(String key, String value) {
        if (value == null) PREFS.remove(key); else PREFS.put(key, value);
    }
}
