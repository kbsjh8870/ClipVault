package com.clipvault.client.clipboard;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

/**
 * 로컬 PC의 클립보드를 지켜보다가, 사용자가 새 텍스트를 복사(Ctrl+C)하면 알려 주는 클래스.
 *
 * <p><b>감지 방법이 두 가지인 이유</b></p>
 * <ul>
 *   <li><b>FlavorListener</b>: 자바가 제공하는 클립보드 변경 리스너. 그런데 이건 데이터 "종류(flavor)"가 바뀔 때만 호출된다.
 *       예를 들어 텍스트를 복사한 뒤 또 다른 텍스트를 복사하면 종류는 둘 다 "텍스트"라서 호출되지 않는다.</li>
 *   <li><b>1초 폴링</b>: 그래서 1초마다 클립보드 내용을 직접 읽어서 이전 값과 비교한다. 이게 실제로 믿을 수 있는 방법이다.
 *       리스너는 일부 변경을 조금 더 빨리 잡아 주는 보조 역할이다.</li>
 * </ul>
 *
 * <p>텍스트만 처리하고 이미지/파일은 무시한다(v1 범위).</p>
 */
public class ClipboardWatcher {
    /** 운영체제의 시스템 클립보드 (Ctrl+C / Ctrl+V가 쓰는 그것). */
    private final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
    /** 새 텍스트가 감지되면 호출할 함수 (TrayApp이 넘겨준다: EchoGuard 확인 후 업로드). */
    private final Consumer<String> onChange;
    /** 마지막으로 본 클립보드 내용. 이것과 다를 때만 "바뀌었다"고 판단한다. */
    private String last;

    public ClipboardWatcher(Consumer<String> onChange) {
        this.onChange = onChange;
    }

    /** 감시를 시작한다. */
    public void start() {
        // 앱을 켜기 전부터 클립보드에 있던 내용은 업로드하지 않도록, 현재 내용을 "이미 본 것"으로 기록
        last = read();
        clipboard.addFlavorListener(e -> check());
        // 1초마다 check() 실행. true = 데몬 스레드(앱이 종료될 때 이 타이머 때문에 프로세스가 안 꺼지는 일이 없도록)
        new Timer("clipboard-poll", true).schedule(new TimerTask() {
            @Override public void run() { check(); }
        }, 1000, 1000);
    }

    /**
     * 로컬 클립보드에 텍스트를 넣는다. (사용자가 클립 목록에서 항목을 클릭했을 때)
     * 호출하는 쪽에서 먼저 {@code EchoGuard.markApplied}를 불러야 다시 업로드되지 않는다.
     * last도 같이 갱신해서 다음 폴링 때 "바뀌었다"고 착각하지 않게 한다.
     */
    public synchronized void write(String text) {
        clipboard.setContents(new StringSelection(text), null);
        last = text;
    }

    /** 클립보드를 읽어서 이전과 다르면 onChange를 호출한다. 리스너와 타이머가 동시에 부를 수 있어 synchronized. */
    private synchronized void check() {
        String now = read();
        if (now == null || Objects.equals(now, last)) return;
        last = now;
        onChange.accept(now);
    }

    /** 클립보드에서 텍스트를 읽는다. 텍스트가 아니거나 읽을 수 없으면 null. */
    private String read() {
        try {
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null;
            return (String) clipboard.getData(DataFlavor.stringFlavor);
        } catch (Exception e) {
            // 다른 프로그램이 클립보드를 잠깐 점유 중이면 예외가 날 수 있다. 다음 폴링(1초 뒤)에 다시 시도하면 된다.
            return null;
        }
    }
}
