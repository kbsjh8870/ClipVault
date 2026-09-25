package com.clipvault.client.network;

import com.clipvault.client.auth.Session;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * STOMP-over-WebSocket subscription to /topic/clips/{userId} with reconnect (1s..30s backoff).
 * Callbacks run on a background thread.
 */
public class ClipSocket implements WebSocket.Listener {
    private final Session session;
    private final ApiClient api;
    private final Consumer<JsonNode> onClip;      // pushes from other devices only
    private final Runnable onConnected;           // after every (re)connect: caller re-fetches to fill gaps
    private final Runnable onAuthLost;            // refresh failed: caller shows login
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "clip-socket");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient http = HttpClient.newHttpClient();
    private final StringBuilder partial = new StringBuilder();
    private volatile WebSocket ws;
    private volatile boolean running;
    private long backoffMs = 1000;
    private int generation; // guards against callbacks from stale sockets

    public ClipSocket(Session session, ApiClient api, Consumer<JsonNode> onClip, Runnable onConnected, Runnable onAuthLost) {
        this.session = session;
        this.api = api;
        this.onClip = onClip;
        this.onConnected = onConnected;
        this.onAuthLost = onAuthLost;
        timer.scheduleWithFixedDelay(this::ping, 30, 30, TimeUnit.SECONDS);
    }

    public synchronized void start() {
        running = true;
        backoffMs = 1000;
        connect();
    }

    public synchronized void stop() {
        running = false;
        generation++;
        WebSocket w = ws;
        ws = null;
        if (w != null) w.abort();
    }

    private synchronized void connect() {
        if (!running) return;
        int gen = ++generation;
        URI uri = URI.create(session.server.replaceFirst("^http", "ws") + "/ws");
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .whenComplete((w, err) -> {
                    if (err != null) scheduleReconnect(gen, false);
                    else synchronized (this) {
                        if (gen != generation) { w.abort(); return; }
                        ws = w;
                        Map<String, String> h = new LinkedHashMap<>();
                        h.put("accept-version", "1.2");
                        h.put("host", uri.getHost());
                        h.put("heart-beat", "0,0");
                        h.put("Authorization", "Bearer " + session.accessToken);
                        w.sendText(new StompFrame("CONNECT", h, "").encode(), true);
                    }
                });
    }

    private synchronized void scheduleReconnect(int gen, boolean refreshFirst) {
        if (!running || gen != generation) return;
        generation++; // ignore anything else from the dead socket
        WebSocket w = ws;
        ws = null;
        if (w != null) w.abort();
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, 30_000);
        timer.schedule(() -> {
            if (refreshFirst) {
                try {
                    if (!api.refresh()) { stop(); onAuthLost.run(); return; }
                } catch (RuntimeException e) {
                    // server unreachable: just retry later
                }
            }
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void ping() {
        WebSocket w = ws;
        if (w != null) w.sendPing(ByteBuffer.allocate(0)).exceptionally(e -> { onError(w, e); return null; });
    }

    private void handle(WebSocket w, StompFrame f) {
        switch (f.command()) {
            case "CONNECTED" -> {
                Map<String, String> h = new LinkedHashMap<>();
                h.put("id", "sub-0");
                h.put("destination", "/topic/clips/" + session.userId);
                w.sendText(new StompFrame("SUBSCRIBE", h, "").encode(), true);
                synchronized (this) { backoffMs = 1000; }
                onConnected.run();
            }
            case "MESSAGE" -> {
                try {
                    JsonNode clip = ApiClient.JSON.readTree(f.body());
                    if (!clip.path("sourceDeviceId").asText().equals(session.deviceId)) onClip.accept(clip);
                } catch (Exception e) {
                    System.err.println("bad clip message: " + e);
                }
            }
            // ERROR is almost always an expired/invalid token at CONNECT: refresh, then reconnect
            case "ERROR" -> scheduleReconnect(genOf(w), true);
            default -> { }
        }
    }

    private synchronized int genOf(WebSocket w) {
        return w == ws ? generation : -1;
    }

    // --- WebSocket.Listener ---

    @Override
    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String raw = partial.toString();
            partial.setLength(0);
            if (!raw.isBlank()) handle(w, StompFrame.decode(raw)); // blank = heart-beat
        }
        w.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
        scheduleReconnect(genOf(w), false);
        return null;
    }

    @Override
    public void onError(WebSocket w, Throwable error) {
        scheduleReconnect(genOf(w), false);
    }
}
