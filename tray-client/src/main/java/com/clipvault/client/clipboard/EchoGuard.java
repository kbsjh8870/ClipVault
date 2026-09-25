package com.clipvault.client.clipboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Decides whether a local clipboard change should be uploaded (prevents ping-pong and duplicates). */
public class EchoGuard {
    private final Clock clock;
    private final Duration window;
    private final Map<String, Instant> applied = new HashMap<>();
    private String lastUploaded;

    public EchoGuard(Clock clock, Duration window) {
        this.clock = clock;
        this.window = window;
    }

    /** Call when a server clip was written into the local clipboard. */
    public synchronized void markApplied(String text) {
        if (text != null) applied.put(text, clock.instant());
    }

    public synchronized boolean shouldUpload(String text) {
        if (text == null || text.isBlank()) return false;
        Instant now = clock.instant();
        applied.values().removeIf(at -> now.isAfter(at.plus(window)));
        if (applied.containsKey(text)) return false;
        if (text.equals(lastUploaded)) return false;
        lastUploaded = text;
        return true;
    }
}
