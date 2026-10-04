package com.clipvault.client;

import com.clipvault.client.auth.LoginDialog;
import com.clipvault.client.auth.Session;
import com.clipvault.client.clipboard.ClipboardWatcher;
import com.clipvault.client.clipboard.EchoGuard;
import com.clipvault.client.clipboard.Images;
import com.clipvault.client.crypto.Vault;
import com.clipvault.client.crypto.VaultCrypto;
import com.clipvault.client.network.ApiClient;
import com.clipvault.client.network.ClipSocket;
import com.clipvault.client.ui.ClipListWindow;
import com.clipvault.client.ui.DeviceDialog;
import com.clipvault.client.ui.Theme;
import com.clipvault.client.ui.VaultDialog;
import com.clipvault.client.update.Updater;
import com.clipvault.client.hotkey.HotKeys;
import com.clipvault.client.ui.HotKeyDialog;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 트레이 앱의 시작점. 모든 부품을 만들어서 서로 연결(wiring)하는 중심 클래스다.
 *
 * <p><b>부품들</b></p>
 * <ul>
 *   <li>{@link Session}: 로그인 상태(토큰 등) 저장</li>
 *   <li>{@link ApiClient}: 서버 REST API 호출</li>
 *   <li>{@link ClipboardWatcher}: 로컬 Ctrl+C 감지 → {@link #onLocalCopy}(텍스트), {@link #onLocalImage}(이미지)</li>
 *   <li>{@link EchoGuard}: 업로드해도 되는지 판단 (서버에서 받은 걸 되돌려 보내지 않기)</li>
 *   <li>{@link ClipSocket}: 서버 실시간 알림 수신 → {@link #onPush}, 재연결 시 {@link #onConnected}</li>
 *   <li>{@link Updater}: 하루에 한 번 새 버전 확인 → 메뉴에 "업데이트" 항목 표시</li>
 *   <li>{@link HotKeys}: 전역 단축키 (최근 클립, 일시정지)</li>
 *   <li>{@link AutoStart}: 윈도우 시작 시 자동 실행 (레지스트리 Run 키)</li>
 *   <li>{@link ClipListWindow}: 최근 클립 팝업 (검색, 고정, 더 보기, 떠 있는 동안 실시간 추가 → {@link ClipListWindow#push})</li>
 * </ul>
 *
 * <p>보관 기간과 고정은 서버에 저장되는 사용자 설정이라 모든 PC에 같이 적용된다. 트레이 메뉴는 서버 값을 보여 주고 바꿀 뿐이다.</p>
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
    /** 이 PC의 볼트(종단간 암호화 키). 잠겨 있으면 업로드하지 않고 받은 클립도 풀지 못한다. */
    private final Vault vault = new Vault();
    /** 볼트 창(만들기/입력/초기화)이 떠 있거나 뜨는 중인지. 동시에 여러 번 확인이 돌아도(로그인 직후 + 소켓 연결) 창은 하나만. */
    private final AtomicBoolean vaultPrompting = new AtomicBoolean();
    /** 옛 클립 이전이 돌고 있는지. 한 번에 하나만 돌려서 같은 클립을 두 번 옮기지 않는다. */
    private final AtomicBoolean migrating = new AtomicBoolean();
    /** 서버에서 받아 로컬에 넣은 텍스트는 5초 동안 다시 업로드하지 않는다. */
    private final EchoGuard guard = new EchoGuard(Clock.systemUTC(), Duration.ofSeconds(5));
    private final ClipboardWatcher watcher = new ClipboardWatcher(this::onLocalCopy, this::onLocalImage);
    /**
     * 받아 온 썸네일 (클립 id → 이미지). 목록 창은 화면에 보이는 칸의 썸네일만 요청하므로
     * 최근 50개만 메모리에 두고 가장 오래 안 쓴 것부터 버린다 (더 보기로 넘어가면 다시 받는다).
     */
    private final Map<String, Image> thumbs = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > 50;
        }
    });
    /** 지금 받는 중인 썸네일 id (같은 썸네일을 동시에 여러 번 요청하지 않도록) */
    private final Set<String> thumbsLoading = ConcurrentHashMap.newKeySet();
    /** 받기에 실패한 썸네일 id. 다시 그릴 때마다(마우스 호버 등) 재요청하지 않도록 기억해 두고 회색 칸만 보인다 */
    private final Set<String> thumbsFailed = ConcurrentHashMap.newKeySet();
    /** 인증을 잃으면(토큰 갱신 실패) 화면 스레드에서 로그아웃 처리 → 로그인 창 */
    private final ClipSocket socket = new ClipSocket(session, api, this::onPush, this::onConnected,
            () -> SwingUtilities.invokeLater(this::localLogout));
    /** 네트워크 작업용 스레드 풀. 자바 21 가상 스레드: 작업마다 가벼운 스레드를 새로 만들어 쓴다. */
    private final ExecutorService bg = Executors.newVirtualThreadPerTaskExecutor();
    /** 새 버전 확인/설치 (exe로 실행할 때만 동작) */
    private final Updater updater = new Updater();
    /** 전역 단축키 (Ctrl+Alt+Shift+C = 최근 클립, Ctrl+Alt+Shift+P = 일시정지). 등록 실패하면 알림만 띄운다. */
    private final HotKeys hotKeys = new HotKeys(
            java.util.Map.of(HotKeys.Action.CLIPS, this::showClips, HotKeys.Action.PAUSE, () -> setPaused(!this.paused)),
            key -> SwingUtilities.invokeLater(() -> this.icon.displayMessage("ClipVault",
                    "단축키 " + key + "는 다른 앱이 쓰고 있어 등록하지 못했습니다. 트레이 메뉴 > 단축키 설정에서 바꿔 주세요.",
                    TrayIcon.MessageType.WARNING)));

    /** 작업표시줄 트레이 아이콘 */
    private TrayIcon icon;
    /**
     * 트레이 오른쪽 클릭 메뉴. 새 버전이 나오면 맨 위에 업데이트 항목을 끼워 넣는다. EDT에서만 접근.
     * AWT 기본 메뉴(PopupMenu)는 단축키를 오른쪽에 정렬해 보여 주지 못하고 테마도 안 먹어서 Swing 메뉴를 쓴다.
     */
    private JPopupMenu menu;
    /** Swing 메뉴는 창에 붙어야 뜰 수 있어서, 마우스 위치에 1px짜리 보이지 않는 창을 띄워 메뉴의 주인으로 쓴다. */
    private JDialog menuOwner;
    /** 단축키 표시를 갱신하려고 기억해 두는 항목들. EDT에서만 접근. */
    private JMenuItem clipsItem;
    private JCheckBoxMenuItem pauseItem;
    /** "보관 기간" 하위 메뉴의 항목들 (일 수 → 항목). 서버 설정을 받으면 해당 항목에 체크한다. EDT에서만 접근. */
    private final Map<Integer, JRadioButtonMenuItem> ttlItems = new LinkedHashMap<>();
    private final ButtonGroup ttlGroup = new ButtonGroup();
    /** 서버에 저장된 보관 기간(일). 아직 모르면 0. EDT에서만 접근. */
    private int ttlDays;
    /** "업데이트 (v1.2.3)" 메뉴 항목. 새 버전이 없으면 null. EDT에서만 접근. */
    private JMenuItem updateItem;
    /** 설치할 새 버전 정보. EDT에서만 접근. */
    private Updater.Release pending;
    /** 로그인(기기 등록) 되어 있는지 */
    private volatile boolean loggedIn;
    /** "일시정지" 메뉴가 켜져 있으면 복사해도 업로드하지 않는다 (비밀번호 복사 등 민감할 때 사용). 저장된 값으로 시작한다. */
    private volatile boolean paused = session.paused;
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
        try {
            AutoStart.refresh(); // 앱 폴더를 옮겼으면 등록된 경로를 지금 위치로 고친다
        } catch (RuntimeException ignored) {
            // 레지스트리를 못 고쳐도 앱 실행에는 지장 없다
        }
        SwingUtilities.invokeLater(() -> {
            try {
                menu = buildMenu();
                icon = new TrayIcon(Theme.appIcon(32, false), "ClipVault");
                icon.setImageAutoSize(true);
                // 아이콘 왼쪽 클릭 = 최근 클립 목록, 오른쪽 클릭 = 메뉴 (윈도우는 버튼을 뗄 때가 메뉴 시점)
                icon.addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) {
                        if (SwingUtilities.isLeftMouseButton(e)) showClips();
                    }

                    @Override public void mouseReleased(MouseEvent e) {
                        if (e.isPopupTrigger()) showMenu();
                    }
                });
                SystemTray.getSystemTray().add(icon);
                hotKeys.apply();
                refreshMenuLabels();
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
        if (updater.enabled()) {
            // 켜질 때 한 번, 그 뒤로 24시간마다 새 버전 확인
            Thread.ofVirtual().start(() -> {
                while (true) {
                    Updater.Release r = updater.check();
                    if (r != null) SwingUtilities.invokeLater(() -> offerUpdate(r));
                    try {
                        Thread.sleep(Duration.ofHours(24));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        }
    }

    /** 트레이 아이콘 오른쪽 클릭 메뉴를 만든다. 단축키는 {@link #refreshMenuLabels}가 항목 오른쪽에 표시한다. */
    private JPopupMenu buildMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem clips = clipsItem = new JMenuItem("최근 클립");
        clips.addActionListener(e -> showClips());
        JMenuItem devices = new JMenuItem("기기 관리");
        devices.addActionListener(e -> {
            if (!loggedIn) { showLogin(); return; }
            DeviceDialog.show(api, session.deviceId, this::localLogout);
        });
        JCheckBoxMenuItem pause = pauseItem = new JCheckBoxMenuItem("일시정지", paused); // 지난번 상태 복원
        pause.addActionListener(e -> setPaused(pause.isSelected()));
        // 윈도우 시작 시 실행. 체크 상태는 레지스트리에서 읽는다 (IDE 실행이면 등록할 exe가 없어 비활성)
        JCheckBoxMenuItem autoStart = new JCheckBoxMenuItem("윈도우 시작 시 실행", AutoStart.enabled());
        autoStart.setEnabled(AutoStart.available());
        autoStart.addActionListener(e -> {
            try {
                AutoStart.set(autoStart.isSelected());
            } catch (RuntimeException ex) {
                autoStart.setSelected(AutoStart.enabled()); // 실패하면 실제 상태로 되돌린다
                icon.displayMessage("ClipVault", "자동 실행 설정을 바꾸지 못했습니다: " + ex.getMessage(), TrayIcon.MessageType.WARNING);
            }
        });
        // 보관 기간: 서버의 사용자 설정. 로그인하면 현재 값에 체크된다 (텍스트, 이미지 모두)
        JMenu ttl = new JMenu("보관 기간");
        for (int days : new int[]{1, 3, 7, 30}) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(days + "일");
            item.addActionListener(e -> changeTtl(days));
            ttlGroup.add(item);
            ttlItems.put(days, item);
            ttl.add(item);
        }
        JMenuItem keys = new JMenuItem("단축키 설정…");
        keys.addActionListener(e -> HotKeyDialog.show(hotKeys, this::refreshMenuLabels));
        JMenuItem vaultItem = new JMenuItem("볼트 암호 변경…");
        vaultItem.addActionListener(e -> changeVault());
        // 현재 버전 (누를 수 없는 회색 항목). IDE에서 실행하면 버전 정보가 없다.
        JMenuItem version = new JMenuItem("ClipVault " + (updater.current == null ? "(개발)" : "v" + updater.current));
        version.setEnabled(false);
        JMenuItem logout = new JMenuItem("로그아웃");
        logout.addActionListener(e -> logout());
        JMenuItem quit = new JMenuItem("종료");
        quit.addActionListener(e -> quit());
        menu.add(clips);
        menu.add(devices);
        menu.add(pause);
        menu.add(ttl);
        menu.add(autoStart);
        menu.add(keys);
        menu.add(vaultItem);
        menu.addSeparator();
        menu.add(logout);
        menu.add(version);
        menu.add(quit);
        return menu;
    }

    /** (EDT) 마우스 위치(트레이 아이콘)에 메뉴를 띄운다. 작업표시줄 위로 올라가도록 메뉴 높이만큼 위에 띄운다. */
    private void showMenu() {
        if (menuOwner == null) {
            menuOwner = new JDialog((Frame) null);
            menuOwner.setUndecorated(true);
            menuOwner.setType(Window.Type.UTILITY); // 작업표시줄에 창으로 나타나지 않게
            menuOwner.setAlwaysOnTop(true);
            menuOwner.setSize(1, 1);
            // 메뉴가 닫히면 주인 창도 숨기고, 다른 곳을 클릭해 주인 창이 포커스를 잃으면 메뉴를 닫는다
            menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { }
                @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { menuOwner.setVisible(false); }
                @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
            });
            menuOwner.addWindowFocusListener(new java.awt.event.WindowAdapter() {
                @Override public void windowLostFocus(java.awt.event.WindowEvent e) { menu.setVisible(false); }
            });
        }
        Point p = MouseInfo.getPointerInfo() != null ? MouseInfo.getPointerInfo().getLocation() : new Point(0, 0);
        menuOwner.setLocation(p);
        menuOwner.setVisible(true);
        menuOwner.toFront();
        menu.show(menuOwner, 0, -menu.getPreferredSize().height);
    }

    /** (EDT) 일시정지 켜기/끄기. 메뉴와 단축키가 함께 쓴다. 단축키로 바꿨을 땐 화면에 보이는 게 없으니 알림으로 알려 준다. */
    private void setPaused(boolean value) {
        boolean fromHotKey = pauseItem.isSelected() != value;
        paused = value;
        pauseItem.setSelected(value);
        session.paused = value;
        session.save(); // 앱을 다시 켜도 유지되도록 저장
        updateTooltip();
        if (fromHotKey) icon.displayMessage("ClipVault", value ? "일시정지: 복사해도 동기화하지 않습니다." : "동기화를 다시 시작합니다.",
                TrayIcon.MessageType.INFO);
    }

    /** (EDT) 지정된 단축키를 메뉴 항목 오른쪽에 회색으로 표시한다 (Swing 메뉴의 accelerator 표시). */
    private void refreshMenuLabels() {
        clipsItem.setAccelerator(HotKeys.get(HotKeys.Action.CLIPS));
        pauseItem.setAccelerator(HotKeys.get(HotKeys.Action.PAUSE));
    }

    /** 앱 종료: 실시간 연결을 끊고 트레이 아이콘을 치운다. */
    private void quit() {
        socket.stop();
        SystemTray.getSystemTray().remove(icon);
        System.exit(0);
    }

    // --- 업데이트 ---

    /** (EDT) 새 버전 발견: 메뉴 맨 위에 업데이트 항목을 넣고 알림을 띄운다. 같은 버전은 한 번만 알린다. */
    private void offerUpdate(Updater.Release r) {
        if (pending != null && pending.version().equals(r.version())) return;
        pending = r;
        if (updateItem == null) {
            updateItem = new JMenuItem();
            updateItem.addActionListener(e -> installUpdate());
            menu.insert(updateItem, 0);
            menu.insert(new JPopupMenu.Separator(), 1);
        }
        updateItem.setText("업데이트 (v" + r.version() + ")");
        icon.displayMessage("새 버전", "ClipVault v" + r.version() + "이 나왔습니다. 트레이 메뉴에서 업데이트하세요.",
                TrayIcon.MessageType.INFO);
    }

    /**
     * (EDT) 메뉴의 "업데이트" 클릭. 확인을 받은 뒤 새 버전을 받아 설치하고 앱을 종료한다 (교체 스크립트가 다시 켠다).
     * 앱 폴더에 쓰기 권한이 없으면(Program Files 등) 릴리스 페이지를 열어 직접 받게 한다.
     */
    private void installUpdate() {
        Updater.Release r = pending;
        if (r == null) return;
        if (!updater.canReplace()) {
            try {
                Desktop.getDesktop().browse(r.page());
            } catch (Exception e) {
                System.err.println("Cannot open browser: " + e);
            }
            return;
        }
        int ok = JOptionPane.showConfirmDialog(null,
                "ClipVault v" + r.version() + "으로 업데이트할까요?\n앱이 재시작됩니다.",
                "업데이트", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, new ImageIcon(Theme.appIcon(48, false)));
        if (ok != JOptionPane.OK_OPTION) return;
        updateItem.setEnabled(false); // 다운로드 중 중복 클릭 방지
        icon.setToolTip("ClipVault — 업데이트 다운로드 중…");
        bg.execute(() -> {
            try {
                updater.install(r);
                SwingUtilities.invokeLater(this::quit); // 스크립트가 종료를 기다렸다가 교체 후 다시 켠다
            } catch (Exception e) {
                System.err.println("Update failed: " + e);
                SwingUtilities.invokeLater(() -> {
                    updateItem.setEnabled(true);
                    updateTooltip();
                    icon.displayMessage("업데이트 실패", "잠시 후 다시 시도해 주세요. (" + e.getMessage() + ")",
                            TrayIcon.MessageType.ERROR);
                });
            }
        });
    }

    // --- 로그인 / 로그아웃 ---

    /** 로그인 완료 → 실시간 알림 연결 시작 */
    private void onLoggedIn() {
        loggedIn = true;
        socket.start();
        SwingUtilities.invokeLater(this::updateTooltip);
        // 보관 기간 설정을 받아 메뉴에 체크
        async(() -> {
            int days = api.getSettings().path("clipTtlDays").asInt();
            SwingUtilities.invokeLater(() -> selectTtl(days));
        });
        async(this::ensureVault);
    }

    /** (EDT) 보관 기간 메뉴에서 days 항목에 체크한다. 모르는 값(0 등)이면 체크를 모두 푼다. */
    private void selectTtl(int days) {
        ttlDays = days;
        JRadioButtonMenuItem item = ttlItems.get(days);
        if (item != null) item.setSelected(true); else ttlGroup.clearSelection();
    }

    /**
     * (EDT) 메뉴에서 보관 기간을 골랐을 때. 서버에 저장하면 기존 클립에도 바로 적용된다.
     * 줄이는 경우에는 그보다 오래된 클립이 곧바로 사라지므로 먼저 확인을 받는다.
     */
    private void changeTtl(int days) {
        int prev = ttlDays;
        if (!loggedIn) { selectTtl(prev); showLogin(); return; }
        if (days == prev) return;
        if (prev > 0 && days < prev && JOptionPane.showConfirmDialog(null,
                days + "일보다 오래된 클립은 바로 사라집니다 (고정한 클립은 그대로).\n보관 기간을 " + days + "일로 줄일까요?",
                "보관 기간 변경", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            selectTtl(prev); // 취소: 체크를 원래대로
            return;
        }
        selectTtl(days);
        async(() -> {
            try {
                api.putSettings(days);
            } catch (ApiClient.ApiException e) {
                if (e.status == 401) throw e;
                SwingUtilities.invokeLater(() -> {
                    selectTtl(prev);
                    icon.displayMessage("ClipVault", "보관 기간을 바꾸지 못했습니다: " + e.getMessage(), TrayIcon.MessageType.WARNING);
                });
            } catch (RuntimeException e) {
                SwingUtilities.invokeLater(() -> {
                    selectTtl(prev);
                    icon.displayMessage("ClipVault", "서버에 연결하지 못해 보관 기간을 바꾸지 못했습니다.", TrayIcon.MessageType.WARNING);
                });
            }
        });
    }

    /**
     * (EDT) 로그인 창을 띄운다. 이미 떠 있으면 또 띄우지 않는다.
     * 사용자가 창을 그냥 닫으면 앱은 로그아웃 상태로 트레이에 계속 남아 있다 (메뉴에서 다시 로그인 가능).
     */
    private void showLogin() {
        loggedIn = false;
        socket.stop();
        updateTooltip();
        selectTtl(0); // 로그아웃 상태에서는 설정을 모른다
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
            vault.lock();          // 이 PC에 저장된 볼트 키도 지운다
            api.vaultVersion = 0;
            session.clear();
            SwingUtilities.invokeLater(this::showLogin);
        });
    }

    /** (EDT) 다른 기기에서 원격 로그아웃당했거나 토큰이 죽었을 때: 로컬 정보만 지우고 로그인 창으로. */
    private void localLogout() {
        vault.lock();          // 이 PC에 저장된 볼트 키도 지운다
        api.vaultVersion = 0;
        session.clear();
        // 401로 실패한 썸네일도 있으므로 다시 로그인하면 다시 받아 보게 한다
        thumbsFailed.clear();
        showLogin();
    }

    // --- 클립 ---

    /**
     * 로컬에서 새 텍스트가 복사됨 (ClipboardWatcher가 호출).
     * 일시정지 중이거나, 로그아웃 상태거나, 너무 길거나, EchoGuard가 막으면 업로드하지 않는다.
     */
    private void onLocalCopy(String text) {
        if (paused || !loggedIn || !vault.ready() || text.length() > MAX_CLIP) return;
        if (guard.shouldUpload(text)) async(() -> uploadText(text));
    }

    /**
     * 로컬에서 새 이미지가 복사됨 (ClipboardWatcher가 호출, key = 이미지 키).
     * PNG로 바꿔 10MB를 넘으면 올리지 않고 한 번 알린다 (같은 이미지는 EchoGuard가 다시 거르므로 알림도 한 번).
     */
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

    /** 볼트를 쓰는 서버 요청 실행. 409(볼트 바뀜)는 볼트를 다시 확인하고, 그 밖의 예외는 그대로 던진다(async가 처리). */
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
            // 서버 재시작 중 502/503 등: 오프라인과 같게 저장된 키를 그대로 쓴다 (버전이 바뀌었으면 업로드 때 409로 알게 된다)
            if (!vault.ready() && vault.load()) api.vaultVersion = vault.version();
            SwingUtilities.invokeLater(this::updateTooltip);
            return;
        } catch (RuntimeException e) {
            // 오프라인: 저장된 키가 있으면 그대로 쓴다 (버전이 바뀌었으면 나중에 업로드할 때 409로 알게 된다)
            if (!vault.ready() && vault.load()) api.vaultVersion = vault.version();
            SwingUtilities.invokeLater(this::updateTooltip);
            return;
        }
        if (v == null) {
            if (vaultPrompting.compareAndSet(false, true)) SwingUtilities.invokeLater(this::createVault);
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
        if (vaultPrompting.compareAndSet(false, true)) SwingUtilities.invokeLater(() -> unlockVault(v, note));
    }

    /** (EDT) 볼트 만들기 창. 성공하면 남은 옛 클립을 옮긴다. */
    private void createVault() {
        boolean[] conflict = {false}; // 409를 만났는지 (다른 PC가 먼저 만듦)
        boolean done;
        try {
            done = VaultDialog.create(in -> {
                byte[] vk = VaultCrypto.newVaultKey();
                try {
                    int version = api.createVault(VaultCrypto.wrap(vk, in[0], session.userId));
                    vault.unlock(vk, version);
                    api.vaultVersion = version;
                    return null;
                } catch (ApiClient.ApiException e) {
                    if (e.status == 409) {
                        conflict[0] = true;
                        return "다른 PC에서 방금 볼트를 만들었습니다. 창을 닫고 그 암호를 입력하세요.";
                    }
                    return e.getMessage();
                }
            });
        } finally {
            vaultPrompting.set(false);
        }
        updateTooltip();
        if (done) {
            clearThumbCaches();
            async(this::migrateLegacy);
        } else if (conflict[0]) {
            async(this::ensureVault); // 서버에 볼트가 생겼으니 입력 창으로. 그냥 닫았으면 잠긴 채로 둔다(최근 클립을 열 때 다시 묻는다)
        }
    }

    /** 잠겨 있는 동안 실패로 기록된 썸네일과 옛 캐시를 비워, 볼트가 열린 뒤 다시 받게 한다. */
    private void clearThumbCaches() {
        thumbs.clear();
        thumbsFailed.clear();
    }

    /** (EDT) 볼트 입력 창. v = 서버의 감싼 키, note = 안내 문구(없으면 기본). "암호를 잊었어요"면 초기화. */
    private void unlockVault(JsonNode v, String note) {
        VaultCrypto.Wrapped w = new VaultCrypto.Wrapped(v.path("salt").asText(), v.path("iterations").asInt(), v.path("wrappedKey").asText());
        int version = v.path("version").asInt();
        boolean done;
        try {
            done = VaultDialog.unlock(note, in -> {
                try {
                    vault.unlock(VaultCrypto.unwrap(w, in[0], session.userId), version);
                    api.vaultVersion = version;
                    return null;
                } catch (VaultCrypto.BadPassphraseException e) {
                    return "볼트 암호가 맞지 않습니다.";
                } catch (IllegalArgumentException e) {
                    return "서버의 볼트 설정이 올바르지 않습니다.";
                }
            }, this::resetVault); // 초기화 창도 이 흐름 안이라 끝날 때까지 vaultPrompting이 유지된다
        } finally {
            vaultPrompting.set(false);
        }
        updateTooltip();
        if (done) {
            clearThumbCaches();
            async(this::migrateLegacy);
        }
    }

    /** (EDT) 볼트 초기화 (암호를 잊었을 때): 모든 클립 삭제 후 새 키. */
    private void resetVault() {
        boolean done = VaultDialog.reset(in -> {
            byte[] vk = VaultCrypto.newVaultKey();
            int version = api.resetVault(VaultCrypto.wrap(vk, in[0], session.userId));
            vault.unlock(vk, version);
            api.vaultVersion = version;
            clearThumbCaches();
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
            int serverVersion = v.path("version").asInt();
            // 이 PC의 키가 옛 버전이면(다른 PC에서 초기화됨) 그 키를 새 암호 밑에 저장하면 안 된다
            if (vault.version() != serverVersion)
                return "다른 PC에서 볼트가 초기화되었습니다. 메뉴에서 최근 클립을 열어 새 암호를 입력하세요.";
            byte[] vk;
            try {
                vk = VaultCrypto.unwrap(current, in[0], session.userId); // 현재 암호 확인 (자리 비운 PC에서 남이 바꾸지 못하게)
            } catch (VaultCrypto.BadPassphraseException e) {
                return "현재 볼트 암호가 맞지 않습니다.";
            }
            try {
                api.changeVault(VaultCrypto.wrap(vk, in[1], session.userId), serverVersion);
                return null;
            } catch (ApiClient.ApiException e) {
                return e.status == 409 ? "다른 PC에서 볼트가 초기화되었습니다." : e.getMessage();
            } finally {
                java.util.Arrays.fill(vk, (byte) 0); // 풀어 낸 키를 메모리에 오래 두지 않는다
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

    /**
     * (백그라운드) 서버 암호화로 남은 옛 클립을 e2e로 옮긴다. 볼트를 쓸 수 있게 될 때마다 부른다(끊겼던 이전을 이어서).
     * 100개씩 받아 하나씩 암호화해 같은 자리에 덮어쓴다. 한 바퀴 동안 하나도 못 옮기면 다음 실행 때 다시 한다.
     */
    private void migrateLegacy() {
        if (!migrating.compareAndSet(false, true)) return; // 이미 도는 중이면 그쪽에 맡긴다
        try {
            migrateLegacyOnce();
        } finally {
            migrating.set(false);
        }
    }

    private void migrateLegacyOnce() {
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

    /** 최근 클립 팝업을 띄운다. 목록을 보면 읽지 않은 수를 0으로 되돌린다. */
    private void showClips() {
        if (!loggedIn) { showLogin(); return; }
        if (!vault.ready()) { async(this::ensureVault); return; } // 잠겨 있으면 목록 대신 볼트 확인(입력 창)
        async(() -> {
            // 고정한 클립(맨 위에 모임) + 최근 첫 페이지
            JsonNode pinned = openAll(api.listPinned());
            JsonNode clips = openAll(api.listClips(ClipListWindow.PAGE));
            SwingUtilities.invokeLater(() -> {
                unread = 0;
                updateTooltip();
                ClipListWindow.show(pinned, clips, session.deviceId, this::pick,
                        clip -> async(() -> api.deleteClip(clip.path("id").asText())), // 삭제는 서버에 요청만 보낸다
                        this::pin, this::loadMore, this::thumbnail);
            });
        });
    }

    /** 목록에서 핀을 눌렀을 때: 서버에 고정/해제 요청. 실패하면(개수 초과 등) 알림으로 알려 준다 (목록은 다음에 열 때 바로잡힌다). */
    private void pin(JsonNode clip, boolean on) {
        async(() -> {
            try {
                api.pin(clip.path("id").asText(), on);
            } catch (ApiClient.ApiException e) {
                if (e.status == 401) throw e;
                SwingUtilities.invokeLater(() -> icon.displayMessage("ClipVault",
                        (on ? "고정" : "고정 해제") + "하지 못했습니다: " + e.getMessage(), TrayIcon.MessageType.WARNING));
            }
        });
    }

    /** 목록의 "더 보기": before 이전 클립 한 페이지를 받아 화면 스레드로 넘긴다. 실패하면 null을 넘긴다. */
    private void loadMore(String before, java.util.function.Consumer<JsonNode> onPage) {
        bg.execute(() -> {
            JsonNode page = null;
            try {
                page = openAll(api.listClips(ClipListWindow.PAGE, before));
            } catch (RuntimeException e) {
                System.err.println("Load more failed: " + e);
            }
            JsonNode p = page;
            SwingUtilities.invokeLater(() -> onPage.accept(p));
        });
    }

    /** 목록에서 고른 클립을 로컬 클립보드에 넣는다. 텍스트는 바로, 이미지는 원본을 받아 온 뒤. */
    private void pick(JsonNode clip) {
        if (clip.path("locked").asBoolean()) {
            icon.displayMessage("ClipVault", "열 수 없는 클립입니다 (볼트 암호가 바뀌었을 수 있습니다).", TrayIcon.MessageType.WARNING);
            return;
        }
        if ("IMAGE".equals(clip.path("type").asText())) {
            pickImage(clip);
            return;
        }
        String text = clip.path("content").asText();
        // 순서가 중요: 먼저 EchoGuard에 기록한 뒤 클립보드에 쓴다.
        // 그래야 감시기가 변화를 감지했을 때 "서버에서 받은 것"이라 업로드하지 않는다.
        guard.markApplied(text);
        watcher.write(text);
    }

    /** 이미지 원본을 받아(e2e면 풀어서) 클립보드에 넣는다. 실패하면 알림 (401은 async가 로그인 화면으로 보낸다). */
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

    /** 목록 창의 썸네일 공급자 ({@link ClipListWindow.Thumbs}). 없으면 백그라운드로 받고 다 받으면 onReady. */
    private Image thumbnail(JsonNode clip, Runnable onReady) {
        String id = clip.path("id").asText();
        Image t = thumbs.get(id);
        if (t == null && !thumbsFailed.contains(id) && thumbsLoading.add(id)) {
            async(() -> {
                try {
                    byte[] bytes = api.getThumbnail(id);
                    if (clip.path("e2e").asBoolean()) bytes = vault.open(bytes, Vault.THUMB);
                    thumbs.put(id, Images.fromPng(bytes));
                    SwingUtilities.invokeLater(onReady);
                } catch (RuntimeException e) {
                    // 기록만 하고 다시 던진다 (로그·401 로그아웃 처리는 async가 한다)
                    thumbsFailed.add(id);
                    throw e;
                } finally {
                    thumbsLoading.remove(id);
                }
            });
        }
        return t;
    }

    /**
     * 다른 기기에서 새 클립이 올라왔다는 실시간 알림 (ClipSocket이 호출).
     * 알림만 띄우고 로컬 클립보드는 절대 건드리지 않는다.
     * (원치 않는 순간에 클립보드가 바뀌면 사용자가 붙여넣기할 때 당황하기 때문 - PRD의 "알림 후 클릭 시 반영" 결정)
     */
    private void onPush(JsonNode clip) {
        open(clip); // e2e 텍스트는 여기서 풀어서 미리보기·목록이 평문을 쓰게 한다
        noteSeen(clip);
        String preview;
        if (clip.path("locked").asBoolean()) {
            preview = LOCKED_TEXT;
        } else if ("IMAGE".equals(clip.path("type").asText())) {
            preview = "새 이미지 · " + clip.path("width").asInt() + "×" + clip.path("height").asInt();
        } else {
            preview = clip.path("content").asText().strip();
            if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
        }
        String p = preview; // 람다 안에서 쓰려면 값이 바뀌지 않는(effectively final) 변수여야 한다
        SwingUtilities.invokeLater(() -> {
            // 최근 클립 창이 떠 있으면 그 목록에 바로 넣는다 (보고 있으니 "읽지 않음"으로 세지 않는다)
            if (!ClipListWindow.push(clip)) {
                unread++;
                updateTooltip();
            }
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
            ensureVault();
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
                : !vault.ready() ? "ClipVault — 볼트 잠김 (메뉴에서 최근 클립을 열어 암호 입력)"
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
