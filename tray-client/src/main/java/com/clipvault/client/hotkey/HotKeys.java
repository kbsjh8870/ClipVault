package com.clipvault.client.hotkey;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * 전역 단축키. 다른 앱을 쓰는 중에도 키 조합으로 ClipVault 기능을 부른다.
 *
 * <p>자바는 자기 창에 포커스가 있을 때만 키 입력을 받을 수 있어서, 윈도우의 RegisterHotKey를 JNA로 직접 부른다.
 * RegisterHotKey는 "등록한 스레드"의 메시지 큐로 WM_HOTKEY를 보내므로, 전용 스레드에서 등록하고 그 스레드가
 * 메시지 루프(GetMessage)를 돌며 기다린다.</p>
 *
 * <p>단축키 설정은 로그인 정보와 같은 Preferences(레지스트리)에 저장한다. 값이 빈 문자열이면 "사용 안 함".</p>
 */
public final class HotKeys {
    /** 단축키로 부를 수 있는 기능과 기본 키 (Ctrl+Alt+Shift 조합: 다른 앱과 거의 겹치지 않는다) */
    public enum Action {
        CLIPS("최근 클립", "ctrl alt shift C"),
        PAUSE("일시정지 켜기/끄기", "ctrl alt shift P");

        public final String label;
        public final String defaultKey;

        Action(String label, String defaultKey) {
            this.label = label;
            this.defaultKey = defaultKey;
        }
    }

    private static final Preferences PREFS = Preferences.userRoot().node("com/clipvault/client");
    private static final int WM_HOTKEY = 0x0312;
    private static final int WM_QUIT = 0x0012;
    /** 키를 누르고 있어도 한 번만 발생 (Windows 7+) */
    private static final int MOD_NOREPEAT = 0x4000;

    private final Map<Action, Runnable> handlers;
    private final Consumer<String> onFail;
    private Thread thread;
    private volatile int threadId;

    /**
     * @param handlers 기능별로 실행할 일 (화면 스레드에서 실행된다)
     * @param onFail   등록에 실패한 키 조합의 표시 문자열을 받는다 (다른 앱이 이미 쓰는 중)
     */
    public HotKeys(Map<Action, Runnable> handlers, Consumer<String> onFail) {
        this.handlers = handlers;
        this.onFail = onFail;
    }

    // --- 설정 저장/조회 ---

    /** 기능에 지정된 키. 사용 안 함이면 null. */
    public static KeyStroke get(Action a) {
        String s = PREFS.get("hotkey." + a.name(), a.defaultKey);
        return s.isEmpty() ? null : KeyStroke.getKeyStroke(s);
    }

    /** 기능의 키를 저장한다. null이면 사용 안 함. */
    public static void set(Action a, KeyStroke k) {
        PREFS.put("hotkey." + a.name(), k == null ? "" : k.toString());
    }

    // --- 등록 ---

    /** 저장된 설정대로 (다시) 등록한다. 이미 등록돼 있으면 먼저 해제한다. */
    public synchronized void apply() {
        stop();
        CountDownLatch ready = new CountDownLatch(1);
        thread = new Thread(() -> loop(ready), "hotkeys");
        thread.setDaemon(true); // 앱 종료를 막지 않도록
        thread.start();
        try {
            ready.await(); // 스레드의 메시지 큐가 생긴 뒤에야 stop()의 WM_QUIT가 전달된다
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 모든 단축키를 해제한다 (설정 창에서 키를 입력받는 동안에도 쓴다: 등록된 키는 창에 전달되지 않으므로). */
    public synchronized void stop() {
        if (thread == null) return;
        User32.INSTANCE.PostThreadMessage(threadId, WM_QUIT, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    /** 전용 스레드: 등록 → WM_HOTKEY를 기다리며 해당 기능 실행 → WM_QUIT를 받으면 해제하고 끝. */
    private void loop(CountDownLatch ready) {
        threadId = Kernel32.INSTANCE.GetCurrentThreadId();
        Map<Integer, Action> ids = new HashMap<>();
        int id = 1;
        for (Action a : Action.values()) {
            KeyStroke k = get(a);
            if (k == null) continue;
            if (User32.INSTANCE.RegisterHotKey(null, id, winModifiers(k) | MOD_NOREPEAT, k.getKeyCode())) {
                ids.put(id++, a);
            } else {
                onFail.accept(text(k));
            }
        }
        ready.countDown();
        WinUser.MSG msg = new WinUser.MSG();
        while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
            if (msg.message == WM_HOTKEY) {
                Action a = ids.get(msg.wParam.intValue());
                Runnable r = a == null ? null : handlers.get(a);
                if (r != null) SwingUtilities.invokeLater(r);
            }
        }
        ids.keySet().forEach(i -> User32.INSTANCE.UnregisterHotKey(null, i));
    }

    // --- 키 조합 도우미 (단위 테스트 대상) ---

    /**
     * 단축키로 쓸 수 있는 조합인지. Ctrl/Alt/Shift 중 하나 이상 + 영문자, 숫자, F1~F12.
     * 수식 키 없이 글자만 등록하면 평범한 타이핑을 막아 버리고, 그 밖의 키는 자바와 윈도우의 키 코드가 달라서 제외한다.
     */
    public static boolean valid(KeyStroke k) {
        if (k == null || winModifiers(k) == 0) return false;
        int c = k.getKeyCode();
        return (c >= KeyEvent.VK_A && c <= KeyEvent.VK_Z)
                || (c >= KeyEvent.VK_0 && c <= KeyEvent.VK_9)
                || (c >= KeyEvent.VK_F1 && c <= KeyEvent.VK_F12);
    }

    /** 자바 수식 키를 RegisterHotKey 플래그로 (MOD_ALT=1, MOD_CONTROL=2, MOD_SHIFT=4). */
    static int winModifiers(KeyStroke k) {
        int m = k.getModifiers(), w = 0;
        if ((m & InputEvent.ALT_DOWN_MASK) != 0) w |= 1;
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0) w |= 2;
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) w |= 4;
        return w;
    }

    /** 화면에 보여 줄 문자열. 예: "Ctrl+Alt+Shift+C". null이면 "없음". */
    public static String text(KeyStroke k) {
        if (k == null) return "없음";
        StringBuilder sb = new StringBuilder();
        int w = winModifiers(k);
        if ((w & 2) != 0) sb.append("Ctrl+");
        if ((w & 1) != 0) sb.append("Alt+");
        if ((w & 4) != 0) sb.append("Shift+");
        return sb.append(KeyEvent.getKeyText(k.getKeyCode())).toString();
    }

    /** 기본값 설정 (설정 창의 [기본값] 버튼). */
    public static Map<Action, KeyStroke> defaults() {
        Map<Action, KeyStroke> m = new EnumMap<>(Action.class);
        for (Action a : Action.values()) m.put(a, KeyStroke.getKeyStroke(a.defaultKey));
        return m;
    }
}
