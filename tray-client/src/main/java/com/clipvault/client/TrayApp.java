package com.clipvault.client;

import com.clipvault.client.auth.LoginDialog;
import com.clipvault.client.auth.Session;
import com.clipvault.client.clipboard.ClipboardWatcher;
import com.clipvault.client.clipboard.EchoGuard;
import com.clipvault.client.network.ApiClient;
import com.clipvault.client.network.ClipSocket;
import com.clipvault.client.ui.ClipListWindow;
import com.clipvault.client.ui.DeviceDialog;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tray entry point and wiring. Network work runs on virtual threads; UI work on the EDT. */
public class TrayApp {
    private static final int MAX_CLIP = 100_000;

    private final Session session = new Session();
    private final ApiClient api = new ApiClient(session);
    private final EchoGuard guard = new EchoGuard(Clock.systemUTC(), Duration.ofSeconds(5));
    private final ClipboardWatcher watcher = new ClipboardWatcher(this::onLocalCopy);
    private final ClipSocket socket = new ClipSocket(session, api, this::onPush, this::onConnected,
            () -> SwingUtilities.invokeLater(this::localLogout));
    private final ExecutorService bg = Executors.newVirtualThreadPerTaskExecutor();

    private TrayIcon icon;
    private volatile boolean loggedIn;
    private volatile boolean paused;
    private boolean loginOpen;          // EDT only
    private int unread;                 // EDT only
    private volatile Instant newestSeen; // createdAt of the newest clip we know about

    public static void main(String[] args) {
        if (!SystemTray.isSupported()) {
            System.err.println("System tray is not supported on this platform.");
            System.exit(1);
        }
        new TrayApp().start();
    }

    private void start() {
        SwingUtilities.invokeLater(() -> {
            try {
                icon = new TrayIcon(drawIcon(false), "ClipVault", buildMenu());
                icon.setImageAutoSize(true);
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
            boolean ok;
            try {
                ok = session.hasDevice() && api.refresh();
            } catch (RuntimeException e) {
                ok = session.hasDevice(); // server unreachable: keep stored tokens, socket will retry
            }
            if (ok) onLoggedIn(); else SwingUtilities.invokeLater(this::showLogin);
        });
    }

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

    // --- auth ---

    private void onLoggedIn() {
        loggedIn = true;
        socket.start();
        SwingUtilities.invokeLater(this::updateTooltip);
    }

    /** EDT. Shows the login dialog once; cancelling leaves the tray running in logged-out state. */
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

    private void logout() {
        String myId = session.deviceId;
        loggedIn = false;
        socket.stop();
        async(() -> {
            try {
                if (myId != null) api.deleteDevice(myId); // revoke server-side too
            } catch (RuntimeException ignored) {
                // offline or already revoked: local logout still proceeds
            }
            session.clear();
            SwingUtilities.invokeLater(this::showLogin);
        });
    }

    /** EDT. Current device was removed remotely or its tokens are dead. */
    private void localLogout() {
        session.clear();
        showLogin();
    }

    // --- clips ---

    private void onLocalCopy(String text) {
        if (paused || !loggedIn || text.length() > MAX_CLIP) return;
        if (guard.shouldUpload(text)) async(() -> api.postClip(text));
    }

    private void showClips() {
        if (!loggedIn) { showLogin(); return; }
        async(() -> {
            JsonNode clips = api.listClips(20);
            SwingUtilities.invokeLater(() -> {
                unread = 0;
                updateTooltip();
                ClipListWindow.show(clips, text -> {
                    guard.markApplied(text); // before writing, so the watcher's upload is suppressed
                    watcher.write(text);
                });
            });
        });
    }

    /** Push from another device: notify only, never touch the clipboard. */
    private void onPush(JsonNode clip) {
        noteSeen(clip);
        String preview = clip.path("content").asText().strip();
        if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
        String p = preview;
        SwingUtilities.invokeLater(() -> {
            unread++;
            updateTooltip();
            icon.displayMessage("새 클립", p, TrayIcon.MessageType.INFO);
        });
    }

    /** After every (re)connect: fetch recent clips and count anything we missed while offline. */
    private void onConnected() {
        async(() -> {
            JsonNode clips = api.listClips(20);
            Instant since = newestSeen;
            int missed = 0;
            for (JsonNode c : clips) {
                Instant at = createdAt(c);
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

    private synchronized void noteSeen(JsonNode clip) {
        Instant at = createdAt(clip);
        if (at != null && (newestSeen == null || at.isAfter(newestSeen))) newestSeen = at;
    }

    private static Instant createdAt(JsonNode clip) {
        try {
            return Instant.parse(clip.path("createdAt").asText());
        } catch (Exception e) {
            return null;
        }
    }

    // --- ui helpers ---

    private void updateTooltip() {
        if (icon == null) return;
        String t = !loggedIn ? "ClipVault — 로그인 필요"
                : "ClipVault" + (paused ? " (일시정지)" : "") + (unread > 0 ? " — 새 클립 " + unread + "개" : "");
        icon.setToolTip(t);
        icon.setImage(drawIcon(unread > 0));
    }

    private static Image drawIcon(boolean dot) {
        int s = 32;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x2B6CB0));
        g.fillRoundRect(3, 5, 26, 26, 8, 8);            // board
        g.setColor(new Color(0xE2E8F0));
        g.fillRoundRect(10, 1, 12, 8, 4, 4);            // clip
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("C", 10, 26);
        if (dot) {
            g.setColor(new Color(0xE53E3E));
            g.fillOval(20, 18, 12, 12);
        }
        g.dispose();
        return img;
    }

    /** Runs network work off the EDT; a 401 that survived refresh sends the user back to login. */
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
