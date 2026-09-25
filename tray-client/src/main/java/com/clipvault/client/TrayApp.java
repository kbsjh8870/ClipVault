package com.clipvault.client;

import com.clipvault.client.auth.LoginDialog;
import com.clipvault.client.auth.Session;
import com.clipvault.client.clipboard.ClipboardWatcher;
import com.clipvault.client.clipboard.EchoGuard;
import com.clipvault.client.network.ApiClient;
import com.clipvault.client.network.ClipSocket;
import com.clipvault.client.ui.ClipListWindow;
import com.clipvault.client.ui.DeviceDialog;
import com.clipvault.client.ui.Theme;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 트레이 앱의 시작점. 모든 부품을 만들어서 서로 연결(wiring)하는 중심 클래스다.
 *
 * <p><b>부품들</b></p>
 * <ul>
 *   <li>{@link Session}: 로그인 상태(토큰 등) 저장</li>
 *   <li>{@link ApiClient}: 서버 REST API 호출</li>
 *   <li>{@link ClipboardWatcher}: 로컬 Ctrl+C 감지 → {@link #onLocalCopy}</li>
 *   <li>{@link EchoGuard}: 업로드해도 되는지 판단 (서버에서 받은 걸 되돌려 보내지 않기)</li>
 *   <li>{@link ClipSocket}: 서버 실시간 알림 수신 → {@link #onPush}, 재연결 시 {@link #onConnected}</li>
 * </ul>
 *
 * <p><b>스레드 규칙</b> (Swing 프로그램의 기본 규칙)</p>
 * <ul>
 *   <li>화면(Swing/AWT) 조작은 반드시 화면 스레드(EDT)에서 → {@code SwingUtilities.invokeLater(...)}</li>
 *   <li>네트워크 요청은 절대 EDT에서 하지 않는다(화면이 멈춤) → {@link #async}로 가상 스레드에서 실행</li>
 * </ul>
 */
public class TrayApp {
    /** 이보다 긴 텍스트는 업로드하지 않는다 (서버 제한과 같은 10만 자). */
    private static final int MAX_CLIP = 100_000;

    private final Session session = new Session();
    private final ApiClient api = new ApiClient(session);
    /** 서버에서 받아 로컬에 넣은 텍스트는 5초 동안 다시 업로드하지 않는다. */
    private final EchoGuard guard = new EchoGuard(Clock.systemUTC(), Duration.ofSeconds(5));
    private final ClipboardWatcher watcher = new ClipboardWatcher(this::onLocalCopy);
    /** 인증을 잃으면(토큰 갱신 실패) 화면 스레드에서 로그아웃 처리 → 로그인 창 */
    private final ClipSocket socket = new ClipSocket(session, api, this::onPush, this::onConnected,
            () -> SwingUtilities.invokeLater(this::localLogout));
    /** 네트워크 작업용 스레드 풀. 자바 21 가상 스레드: 작업마다 가벼운 스레드를 새로 만들어 쓴다. */
    private final ExecutorService bg = Executors.newVirtualThreadPerTaskExecutor();

    /** 작업표시줄 트레이 아이콘 */
    private TrayIcon icon;
    /** 로그인(기기 등록) 되어 있는지 */
    private volatile boolean loggedIn;
    /** "일시정지" 메뉴가 켜져 있으면 복사해도 업로드하지 않는다 (비밀번호 복사 등 민감할 때 사용) */
    private volatile boolean paused;
    /** 로그인 창이 이미 떠 있는지 (중복으로 뜨지 않게). EDT에서만 접근. */
    private boolean loginOpen;
    /** 아직 확인하지 않은 새 클립 수 (아이콘의 빨간 점, 툴팁 표시용). EDT에서만 접근. */
    private int unread;
    /** 지금까지 본 클립 중 가장 최신 것의 createdAt. 재연결 시 "놓친 클립이 몇 개인지" 셀 때 기준으로 쓴다. */
    private volatile Instant newestSeen;

    public static void main(String[] args) {
        if (!SystemTray.isSupported()) {
            System.err.println("System tray is not supported on this platform.");
            System.exit(1);
        }
        Theme.setup(); // 화면을 만들기 전에 테마(FlatLaf)를 먼저 적용해야 모든 창에 반영된다
        new TrayApp().start();
    }

    /** 트레이 아이콘을 띄우고, 저장된 로그인 정보가 있으면 이어서 로그인, 없으면 로그인 창을 띄운다. */
    private void start() {
        SwingUtilities.invokeLater(() -> {
            try {
                icon = new TrayIcon(Theme.appIcon(32, false), "ClipVault", buildMenu());
                icon.setImageAutoSize(true);
                // 아이콘 왼쪽 클릭 = 최근 클립 목록 (오른쪽 클릭은 메뉴)
                icon.addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) {
                        if (SwingUtilities.isLeftMouseButton(e)) showClips();
                    }
                });
                SystemTray.getSystemTray().add(icon);
            } catch (AWTException e) {
                throw new IllegalStateException(e);
            }
            watcher.start();
        });
        async(() -> {
            // 지난번 로그인 정보가 저장되어 있으면 토큰을 갱신해 보고, 성공하면 바로 로그인 상태로 시작
            boolean ok;
            try {
                ok = session.hasDevice() && api.refresh();
            } catch (RuntimeException e) {
                // 서버에 연결이 안 됨(서버 꺼짐 등): 저장된 토큰을 그대로 믿고 시작한다. 소켓이 알아서 재연결을 시도한다.
                ok = session.hasDevice();
            }
            if (ok) onLoggedIn(); else SwingUtilities.invokeLater(this::showLogin);
        });
    }

    /** 트레이 아이콘 오른쪽 클릭 메뉴를 만든다. */
    private PopupMenu buildMenu() {
        PopupMenu menu = new PopupMenu();
        MenuItem clips = new MenuItem("최근 클립");
        clips.addActionListener(e -> showClips());
        MenuItem devices = new MenuItem("기기 관리");
        devices.addActionListener(e -> {
            if (!loggedIn) { showLogin(); return; }
            DeviceDialog.show(api, session.deviceId, this::localLogout);
        });
        CheckboxMenuItem pause = new CheckboxMenuItem("일시정지");
        pause.addItemListener(e -> { paused = pause.getState(); updateTooltip(); });
        MenuItem logout = new MenuItem("로그아웃");
        logout.addActionListener(e -> logout());
        MenuItem quit = new MenuItem("종료");
        quit.addActionListener(e -> {
            socket.stop();
            SystemTray.getSystemTray().remove(icon);
            System.exit(0);
        });
        menu.add(clips);
        menu.add(devices);
        menu.add(pause);
        menu.addSeparator();
        menu.add(logout);
        menu.add(quit);
        return menu;
    }

    // --- 로그인 / 로그아웃 ---

    /** 로그인 완료 → 실시간 알림 연결 시작 */
    private void onLoggedIn() {
        loggedIn = true;
        socket.start();
        SwingUtilities.invokeLater(this::updateTooltip);
    }

    /**
     * (EDT) 로그인 창을 띄운다. 이미 떠 있으면 또 띄우지 않는다.
     * 사용자가 창을 그냥 닫으면 앱은 로그아웃 상태로 트레이에 계속 남아 있다 (메뉴에서 다시 로그인 가능).
     */
    private void showLogin() {
        loggedIn = false;
        socket.stop();
        updateTooltip();
        if (loginOpen) return;
        loginOpen = true;
        try {
            if (LoginDialog.show(session, api)) onLoggedIn();
        } finally {
            loginOpen = false;
        }
    }

    /**
     * 메뉴의 "로그아웃". 서버에서도 이 기기를 로그아웃(기기 삭제)시켜 토큰을 무효화한 뒤, 로컬 정보를 지운다.
     */
    private void logout() {
        String myId = session.deviceId;
        loggedIn = false;
        socket.stop();
        async(() -> {
            try {
                if (myId != null) api.deleteDevice(myId); // 서버 쪽 토큰도 무효화
            } catch (RuntimeException ignored) {
                // 오프라인이거나 이미 로그아웃된 기기: 그래도 로컬 로그아웃은 진행한다
            }
            session.clear();
            SwingUtilities.invokeLater(this::showLogin);
        });
    }

    /** (EDT) 다른 기기에서 원격 로그아웃당했거나 토큰이 죽었을 때: 로컬 정보만 지우고 로그인 창으로. */
    private void localLogout() {
        session.clear();
        showLogin();
    }

    // --- 클립 ---

    /**
     * 로컬에서 새 텍스트가 복사됨 (ClipboardWatcher가 호출).
     * 일시정지 중이거나, 로그아웃 상태거나, 너무 길거나, EchoGuard가 막으면 업로드하지 않는다.
     */
    private void onLocalCopy(String text) {
        if (paused || !loggedIn || text.length() > MAX_CLIP) return;
        if (guard.shouldUpload(text)) async(() -> api.postClip(text));
    }

    /** 최근 클립 팝업을 띄운다. 목록을 보면 읽지 않은 수를 0으로 되돌린다. */
    private void showClips() {
        if (!loggedIn) { showLogin(); return; }
        async(() -> {
            JsonNode clips = api.listClips(20);
            SwingUtilities.invokeLater(() -> {
                unread = 0;
                updateTooltip();
                ClipListWindow.show(clips, session.deviceId, text -> {
                    // 순서가 중요: 먼저 EchoGuard에 기록한 뒤 클립보드에 쓴다.
                    // 그래야 감시기가 변화를 감지했을 때 "서버에서 받은 것"이라 업로드하지 않는다.
                    guard.markApplied(text);
                    watcher.write(text);
                });
            });
        });
    }

    /**
     * 다른 기기에서 새 클립이 올라왔다는 실시간 알림 (ClipSocket이 호출).
     * 알림만 띄우고 로컬 클립보드는 절대 건드리지 않는다.
     * (원치 않는 순간에 클립보드가 바뀌면 사용자가 붙여넣기할 때 당황하기 때문 - PRD의 "알림 후 클릭 시 반영" 결정)
     */
    private void onPush(JsonNode clip) {
        noteSeen(clip);
        String preview = clip.path("content").asText().strip();
        if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
        String p = preview; // 람다 안에서 쓰려면 값이 바뀌지 않는(effectively final) 변수여야 한다
        SwingUtilities.invokeLater(() -> {
            unread++;
            updateTooltip();
            icon.displayMessage("새 클립", p, TrayIcon.MessageType.INFO); // 윈도우 알림 풍선
        });
    }

    /**
     * WebSocket 연결(재연결 포함)이 성공할 때마다 호출된다.
     * 연결이 끊겨 있던 동안에는 실시간 알림을 못 받았으므로, 최근 목록을 다시 받아 와서
     * "마지막으로 본 클립보다 새롭고, 다른 기기에서 온 것"이 몇 개인지 세어 알려 준다.
     */
    private void onConnected() {
        async(() -> {
            JsonNode clips = api.listClips(20);
            Instant since = newestSeen;
            int missed = 0;
            for (JsonNode c : clips) {
                Instant at = createdAt(c);
                // since가 null이면(앱을 막 켰을 때) 비교할 기준이 없으므로 세지 않는다
                if (since != null && at != null && at.isAfter(since)
                        && !c.path("sourceDeviceId").asText().equals(session.deviceId)) missed++;
                noteSeen(c);
            }
            int m = missed;
            if (m > 0) SwingUtilities.invokeLater(() -> {
                unread += m;
                updateTooltip();
                icon.displayMessage("새 클립", "오프라인 동안 " + m + "개의 클립이 도착했습니다.", TrayIcon.MessageType.INFO);
            });
        });
    }

    /** 본 클립의 시각이 지금까지의 최신보다 새로우면 newestSeen을 갱신한다. */
    private synchronized void noteSeen(JsonNode clip) {
        Instant at = createdAt(clip);
        if (at != null && (newestSeen == null || at.isAfter(newestSeen))) newestSeen = at;
    }

    /** 클립 JSON의 createdAt(ISO-8601 문자열)을 Instant로 바꾼다. 형식이 이상하면 null. */
    private static Instant createdAt(JsonNode clip) {
        try {
            return Instant.parse(clip.path("createdAt").asText());
        } catch (Exception e) {
            return null;
        }
    }

    // --- 화면 도우미 ---

    /** (EDT) 트레이 아이콘의 툴팁 문구와 그림(새 클립이 있으면 빨간 점)을 현재 상태에 맞게 바꾼다. */
    private void updateTooltip() {
        if (icon == null) return;
        String t = !loggedIn ? "ClipVault — 로그인 필요"
                : "ClipVault" + (paused ? " (일시정지)" : "") + (unread > 0 ? " — 새 클립 " + unread + "개" : "");
        icon.setToolTip(t);
        icon.setImage(Theme.appIcon(32, unread > 0));
    }

    /**
     * 네트워크 작업을 EDT가 아닌 백그라운드(가상 스레드)에서 실행한다.
     * 토큰 갱신을 거쳤는데도 401이 나면(= 로그인이 완전히 끊김) 로그인 화면으로 보낸다.
     * 그 외 오류는 콘솔에 기록만 한다 (예: 서버가 잠깐 꺼져 있을 때 업로드 실패).
     */
    private void async(Runnable work) {
        bg.execute(() -> {
            try {
                work.run();
            } catch (ApiClient.ApiException e) {
                if (e.status == 401) {
                    SwingUtilities.invokeLater(this::localLogout);
                } else {
                    System.err.println("API error: " + e.getMessage());
                }
            } catch (RuntimeException e) {
                System.err.println("Network error: " + e);
            }
        });
    }
}
