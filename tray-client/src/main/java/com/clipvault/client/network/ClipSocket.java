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
 * 서버와 WebSocket 연결을 유지하면서 새 클립 알림을 받는 클래스.
 *
 * <p><b>연결 순서</b></p>
 * <ol>
 *   <li>{@code ws://서버/ws}로 WebSocket 연결 (자바 기본 {@link java.net.http.WebSocket} 사용)</li>
 *   <li>STOMP CONNECT 프레임 전송 (헤더에 device access 토큰)</li>
 *   <li>서버가 CONNECTED로 응답하면 {@code /topic/clips/{내 userId}} 구독(SUBSCRIBE)</li>
 *   <li>이후 서버가 MESSAGE 프레임으로 새 클립을 보내 준다</li>
 * </ol>
 *
 * <p><b>자동 재연결</b>: 연결이 끊기면(노트북 이동, 와이파이 끊김, 서버 재시작 등) 1초 뒤 재연결을 시도하고,
 * 계속 실패하면 대기 시간을 2배씩 늘려 최대 30초 간격으로 재시도한다(지수 백오프 - 서버를 과하게 두드리지 않기 위함).
 * 다시 연결되면 {@code onConnected}를 호출해서, 끊겨 있던 동안 놓친 클립을 REST로 다시 조회하게 한다.</p>
 *
 * <p><b>generation(세대 번호)</b>: 재연결을 거듭하면 죽은 옛 소켓에서 뒤늦게 이벤트(onClose 등)가 날아올 수 있다.
 * 연결할 때마다 번호를 하나씩 올리고, 이벤트가 현재 번호의 소켓에서 온 게 아니면 무시한다.</p>
 *
 * <p>콜백들은 백그라운드 스레드에서 호출되므로, 화면을 건드리려면 호출하는 쪽에서 EDT로 넘겨야 한다.</p>
 */
public class ClipSocket implements WebSocket.Listener {
    private final Session session;
    private final ApiClient api;
    /** 다른 기기에서 온 새 클립 알림 (내가 올린 것은 걸러진 뒤 호출됨) */
    private final Consumer<JsonNode> onClip;
    /** 연결(또는 재연결) 성공할 때마다 호출: 호출한 쪽이 목록을 다시 조회해서 놓친 클립을 채운다 */
    private final Runnable onConnected;
    /** 토큰 갱신이 실패함(원격 로그아웃 등): 호출한 쪽이 로그인 화면을 띄운다 */
    private final Runnable onAuthLost;
    /** 재연결 예약과 ping 전송을 담당하는 백그라운드 타이머 스레드 (데몬: 앱 종료를 막지 않음) */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "clip-socket");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient http = HttpClient.newHttpClient();
    /** 큰 메시지는 여러 조각으로 나뉘어 도착할 수 있어서, 마지막 조각이 올 때까지 이어 붙여 두는 버퍼 */
    private final StringBuilder partial = new StringBuilder();
    /** 현재 열려 있는 소켓 (없으면 null) */
    private volatile WebSocket ws;
    /** start()~stop() 사이인지. false면 재연결하지 않는다. */
    private volatile boolean running;
    /** 다음 재연결까지 기다릴 시간(ms). 실패할 때마다 2배(최대 30초), 성공하면 1초로 리셋. */
    private long backoffMs = 1000;
    /** 세대 번호: 옛 소켓에서 늦게 온 이벤트를 무시하기 위한 값 */
    private int generation;

    public ClipSocket(Session session, ApiClient api, Consumer<JsonNode> onClip, Runnable onConnected, Runnable onAuthLost) {
        this.session = session;
        this.api = api;
        this.onClip = onClip;
        this.onConnected = onConnected;
        this.onAuthLost = onAuthLost;
        // 30초마다 ping을 보내 연결이 살아 있는지 확인한다 (죽은 연결을 빨리 알아채고 재연결하기 위함)
        timer.scheduleWithFixedDelay(this::ping, 30, 30, TimeUnit.SECONDS);
    }

    /** 연결을 시작한다. (로그인 성공 후 호출) */
    public synchronized void start() {
        running = true;
        backoffMs = 1000;
        connect();
    }

    /** 연결을 끊고 재연결도 멈춘다. (로그아웃, 종료 시 호출) */
    public synchronized void stop() {
        running = false;
        generation++; // 지금 소켓에서 오는 이벤트는 모두 무시
        WebSocket w = ws;
        ws = null;
        if (w != null) w.abort();
    }

    /** WebSocket을 열고, 열리면 STOMP CONNECT 프레임을 보낸다. (비동기: 결과를 기다리지 않고 바로 돌아온다) */
    private synchronized void connect() {
        if (!running) return;
        int gen = ++generation;
        // http://... → ws://..., https://... → wss://...
        URI uri = URI.create(session.server.replaceFirst("^http", "ws") + "/ws");
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .whenComplete((w, err) -> {
                    if (err != null) scheduleReconnect(gen, false); // 연결 실패 → 잠시 후 재시도
                    else synchronized (this) {
                        // 그사이 stop()이나 다른 재연결이 있었으면 이 소켓은 필요 없다
                        if (gen != generation) { w.abort(); return; }
                        ws = w;
                        Map<String, String> h = new LinkedHashMap<>();
                        h.put("accept-version", "1.2");
                        h.put("host", uri.getHost());
                        h.put("heart-beat", "0,0"); // STOMP 하트비트는 쓰지 않음 (대신 WebSocket ping 사용)
                        h.put("Authorization", "Bearer " + session.accessToken);
                        w.sendText(new StompFrame("CONNECT", h, "").encode(), true);
                    }
                });
    }

    /**
     * 현재 소켓을 버리고 잠시 후 다시 연결하도록 예약한다.
     *
     * @param gen          이 요청을 보낸 소켓의 세대 번호. 현재 세대가 아니면(이미 처리된 옛 소켓이면) 무시한다.
     * @param refreshFirst true면 재연결 전에 토큰을 먼저 갱신한다 (서버가 토큰 문제로 ERROR를 보낸 경우).
     *                     갱신이 거부되면 재연결을 멈추고 onAuthLost를 호출한다 → 로그인 화면.
     */
    private synchronized void scheduleReconnect(int gen, boolean refreshFirst) {
        if (!running || gen != generation) return;
        generation++; // 죽은 소켓에서 추가로 오는 이벤트(onClose, onError 등)는 무시
        WebSocket w = ws;
        ws = null;
        if (w != null) w.abort();
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, 30_000); // 1초 → 2초 → 4초 → ... → 최대 30초
        timer.schedule(() -> {
            if (refreshFirst) {
                try {
                    if (!api.refresh()) { stop(); onAuthLost.run(); return; }
                } catch (RuntimeException e) {
                    // 서버에 연결 자체가 안 됨: 로그인 문제가 아니므로 나중에 다시 시도
                }
            }
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** 연결 확인용 ping. 보내기 실패하면 연결이 죽은 것이므로 onError로 재연결을 유도한다. */
    private void ping() {
        WebSocket w = ws;
        if (w != null) w.sendPing(ByteBuffer.allocate(0)).exceptionally(e -> { onError(w, e); return null; });
    }

    /** 서버에서 받은 STOMP 프레임을 명령어별로 처리한다. */
    private void handle(WebSocket w, StompFrame f) {
        switch (f.command()) {
            case "CONNECTED" -> {
                // 인증 성공 → 내 topic 구독
                Map<String, String> h = new LinkedHashMap<>();
                h.put("id", "sub-0");
                h.put("destination", "/topic/clips/" + session.userId);
                w.sendText(new StompFrame("SUBSCRIBE", h, "").encode(), true);
                synchronized (this) { backoffMs = 1000; } // 연결 성공했으니 대기 시간 초기화
                onConnected.run();
            }
            case "MESSAGE" -> {
                // 새 클립 도착. 내 기기가 올린 것(echo)이면 무시한다.
                try {
                    JsonNode clip = ApiClient.JSON.readTree(f.body());
                    if (!clip.path("sourceDeviceId").asText().equals(session.deviceId)) onClip.accept(clip);
                } catch (Exception e) {
                    System.err.println("bad clip message: " + e);
                }
            }
            // ERROR는 거의 항상 CONNECT 때 토큰이 만료/무효라는 뜻이다 → 토큰 갱신 후 재연결
            case "ERROR" -> scheduleReconnect(genOf(w), true);
            default -> { }
        }
    }

    /** 이 소켓이 현재 소켓이면 현재 세대 번호, 옛 소켓이면 -1(= 무시됨)을 돌려준다. */
    private synchronized int genOf(WebSocket w) {
        return w == ws ? generation : -1;
    }

    // --- WebSocket.Listener: 자바 WebSocket이 이벤트가 생길 때 호출하는 메서드들 ---

    /** 텍스트 메시지(조각) 수신. last=true인 마지막 조각까지 모아서 한 프레임으로 해석한다. */
    @Override
    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String raw = partial.toString();
            partial.setLength(0);
            if (!raw.isBlank()) handle(w, StompFrame.decode(raw)); // 빈 메시지 = 서버 하트비트, 무시
        }
        w.request(1); // "다음 메시지 하나 더 받을 준비 됨" 신호 (자바 WebSocket은 이걸 해 줘야 다음 메시지를 준다)
        return null;
    }

    /** 서버가 연결을 닫음(서버 재시작, 원격 로그아웃 등) → 재연결 */
    @Override
    public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
        scheduleReconnect(genOf(w), false);
        return null;
    }

    /** 네트워크 오류 → 재연결 */
    @Override
    public void onError(WebSocket w, Throwable error) {
        scheduleReconnect(genOf(w), false);
    }
}
