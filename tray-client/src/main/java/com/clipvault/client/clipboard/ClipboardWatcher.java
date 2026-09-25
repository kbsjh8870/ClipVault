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
 * Detects local text copies. FlavorListener only fires on flavor changes (text -> text is silent),
 * so a 1s poll is the reliable path; the listener just makes some changes show up sooner.
 */
public class ClipboardWatcher {
    private final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
    private final Consumer<String> onChange;
    private String last;

    public ClipboardWatcher(Consumer<String> onChange) {
        this.onChange = onChange;
    }

    public void start() {
        last = read(); // don't upload whatever was on the clipboard before we started
        clipboard.addFlavorListener(e -> check());
        new Timer("clipboard-poll", true).schedule(new TimerTask() {
            @Override public void run() { check(); }
        }, 1000, 1000);
    }

    /** Writes text to the local clipboard (caller must EchoGuard.markApplied first). */
    public synchronized void write(String text) {
        clipboard.setContents(new StringSelection(text), null);
        last = text;
    }

    private synchronized void check() {
        String now = read();
        if (now == null || Objects.equals(now, last)) return;
        last = now;
        onChange.accept(now);
    }

    private String read() {
        try {
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null;
            return (String) clipboard.getData(DataFlavor.stringFlavor);
        } catch (Exception e) {
            return null; // clipboard busy (owned by another app) or non-text: try next tick
        }
    }
}
