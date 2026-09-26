package com.clipvault.client.clipboard;

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 로컬 PC의 클립보드를 지켜보다가, 사용자가 새 텍스트나 이미지를 복사하면 알려 주는 클래스.
 *
 * <p><b>감지 방법이 두 가지인 이유</b></p>
 * <ul>
 *   <li><b>FlavorListener</b>: 자바가 제공하는 클립보드 변경 리스너. 그런데 이건 데이터 "종류(flavor)"가 바뀔 때만 호출된다.
 *       예를 들어 텍스트를 복사한 뒤 또 다른 텍스트를 복사하면 종류는 둘 다 "텍스트"라서 호출되지 않는다.</li>
 *   <li><b>1초 폴링</b>: 그래서 1초마다 클립보드 내용을 직접 읽어서 이전 값과 비교한다. 이게 실제로 믿을 수 있는 방법이다.
 *       리스너는 일부 변경을 조금 더 빨리 잡아 주는 보조 역할이다.</li>
 * </ul>
 *
 * <p>텍스트가 없고 이미지만 있으면 이미지로 처리한다. 둘 다 있으면 텍스트를 우선한다.</p>
 */
public class ClipboardWatcher {
    /** 운영체제의 시스템 클립보드 (Ctrl+C / Ctrl+V가 쓰는 그것). */
    private final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
    /** 새 텍스트가 감지되면 호출할 함수 (TrayApp이 넘겨준다: EchoGuard 확인 후 업로드). */
    private final Consumer<String> onText;
    /** 새 이미지가 감지되면 호출할 함수. (ARGB 이미지, 이미지 키) */
    private final BiConsumer<BufferedImage, String> onImage;
    /** 마지막으로 본 내용의 키. 텍스트는 "t:" + 내용, 이미지는 "i:" + 이미지 키. 같으면 변화 없음으로 본다. */
    private String last;
    /** 이미지 읽기를 몇 번의 폴링 뒤에 할지. 큰 이미지는 읽는 데 오래 걸리므로 간격을 늘린다. */
    private int imageSkip;

    /** 클립보드에서 읽은 새 내용. text와 image 중 하나만 채워진다. */
    private record Content(String key, String text, BufferedImage image) {}

    public ClipboardWatcher(Consumer<String> onText, BiConsumer<BufferedImage, String> onImage) {
        this.onText = onText;
        this.onImage = onImage;
    }

    /** 감시를 시작한다. */
    public void start() {
        // 앱을 켜기 전부터 클립보드에 있던 내용은 업로드하지 않도록, 현재 내용을 "이미 본 것"으로 기록
        Content now = read();
        last = now == null ? null : now.key();
        clipboard.addFlavorListener(e -> check());
        // 1초마다 check() 실행. true = 데몬 스레드(앱이 종료될 때 이 타이머 때문에 프로세스가 안 꺼지는 일이 없도록)
        new Timer("clipboard-poll", true).schedule(new TimerTask() {
            @Override public void run() { check(); }
        }, 1000, 1000);
    }

    /** 텍스트를 클립보드에 넣는다. 우리가 넣은 것이므로 "이미 본 것"으로 기록해 다시 업로드하지 않는다. */
    public synchronized void write(String text) {
        clipboard.setContents(new StringSelection(text), null);
        last = "t:" + text;
    }

    /**
     * 이미지를 클립보드에 넣고, 넣은 직후 다시 읽은 이미지의 키를 "이미 본 것"으로 기록한다.
     * 윈도우 클립보드를 거치며 픽셀(투명도 등)이 달라져도 되돌려 보내지 않기 위해 다시 읽은 값을 쓴다.
     *
     * @return 기록한 이미지 키 (EchoGuard에도 같은 키를 기록하는 데 쓴다)
     */
    public synchronized String writeImage(BufferedImage img) {
        clipboard.setContents(new ImageSelection(img), null);
        String key = Images.key(img);
        try {
            if (clipboard.getData(DataFlavor.imageFlavor) instanceof Image back) key = Images.key(Images.toArgb(back));
        } catch (Exception ignored) {
            // 다시 읽기에 실패하면 넣은 이미지의 키를 그대로 쓴다
        }
        last = "i:" + key;
        return key;
    }

    /** 클립보드를 읽어서 이전과 다르면 onText/onImage를 호출한다. 리스너와 타이머가 동시에 부를 수 있어 synchronized. */
    private synchronized void check() {
        Content now = read();
        if (now == null || now.key().equals(last)) return;
        last = now.key();
        if (now.text() != null) onText.accept(now.text());
        else onImage.accept(now.image(), now.key().substring(2));
    }

    /**
     * 지금 클립보드 내용. 텍스트가 있으면 텍스트(우선), 없고 이미지가 있으면 이미지.
     * 읽을 게 없거나, 이번 폴링에서 이미지 읽기를 건너뛰거나, 다른 프로그램이 점유 중이면 null.
     */
    private Content read() {
        try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                String text = (String) clipboard.getData(DataFlavor.stringFlavor);
                return text == null ? null : new Content("t:" + text, text, null);
            }
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null;
            // 이미지가 클립보드에 남아 있는 동안 매번 다시 읽게 되므로, 읽기가 50ms를 넘으면 3번에 1번만 읽는다
            if (--imageSkip > 0) return null;
            long start = System.nanoTime();
            BufferedImage img = Images.toArgb((Image) clipboard.getData(DataFlavor.imageFlavor));
            String key = Images.key(img);
            imageSkip = System.nanoTime() - start > 50_000_000L ? 3 : 1;
            return new Content("i:" + key, null, img);
        } catch (Exception e) {
            // 다른 프로그램이 클립보드를 잠깐 점유 중이면 예외가 날 수 있다. 다음 폴링(1초 뒤)에 다시 시도하면 된다.
            return null;
        }
    }

    /** 이미지를 클립보드에 넣기 위한 포장. 자바는 텍스트용(StringSelection)만 기본 제공한다. */
    private record ImageSelection(Image image) implements Transferable {
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }

        @Override public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.imageFlavor.equals(f); }

        @Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
            return image;
        }
    }
}
