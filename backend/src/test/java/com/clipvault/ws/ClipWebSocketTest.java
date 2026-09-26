package com.clipvault.ws;

import com.clipvault.Api;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSocket(STOMP) 실시간 알림 통합 테스트.
 *
 * <p>{@code RANDOM_PORT}: 실제 서버를 빈 포트에 띄운다(WebSocket은 진짜 네트워크 연결이 필요하므로).
 * REST 요청(가입, 클립 업로드 등)은 MockMvc로 같은 서버에 보내고,
 * WebSocket은 스프링의 STOMP 클라이언트로 진짜 연결해서 알림이 오는지 확인한다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ClipWebSocketTest {
    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    Api api;
    WebSocketStompClient stomp;

    @BeforeEach
    /** 각 테스트 전에 STOMP 클라이언트를 만든다. 메시지 본문(JSON)은 Map으로 변환해서 받는다. */
    void setUp() {
        api = new Api(mvc);
        stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new JacksonJsonMessageConverter());
    }

    @AfterEach
    /** 테스트가 끝나면 클라이언트를 정리한다. */
    void tearDown() { stomp.stop(); }

    /** 토큰을 CONNECT 헤더에 넣어 /ws에 연결한다. token이 null이면 헤더 없이 연결. */
    private CompletableFuture<StompSession> connect(String token, StompSessionHandler handler) {
        StompHeaders headers = new StompHeaders();
        if (token != null) headers.add("Authorization", "Bearer " + token);
        return stomp.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers, handler);
    }

    /** 받은 메시지를 큐에 모으고, 에러 신호(ERROR 프레임, 연결 끊김, 예외)가 오면 error 래치를 내린다. 테스트는 이걸 보고 판정한다. */
    static class Recorder extends StompSessionHandlerAdapter implements StompFrameHandler {
        final BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        final CountDownLatch error = new CountDownLatch(1);

        @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }

        @Override @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders headers, Object payload) {
            if (payload instanceof Map<?, ?> m && headers.getDestination() != null) messages.add((Map<String, Object>) m);
            else error.countDown(); // 구독 주소 없는 세션 단위 프레임 = ERROR 프레임
        }

        @Override public void handleException(StompSession s, StompCommand c, StompHeaders h, byte[] p, Throwable ex) { error.countDown(); }
        @Override public void handleTransportError(StompSession s, Throwable ex) { error.countDown(); }
    }

    /** 토큰 없이 연결 → 거부 */
    @Test
    void connectWithoutTokenIsRejected() {
        assertThrows(Exception.class, () -> connect(null, new Recorder()).get(3, TimeUnit.SECONDS));
    }

    /** 엉터리 토큰으로 연결 → 거부 */
    @Test
    void connectWithBadTokenIsRejected() {
        assertThrows(Exception.class, () -> connect("bad.token.value", new Recorder()).get(3, TimeUnit.SECONDS));
    }

    /** user 토큰(기기 등록 전)으로 연결 → 거부 (device 토큰만 허용) */
    @Test
    void connectWithUserTokenIsRejected() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        Api.Tokens user = api.login(email);
        assertThrows(Exception.class, () -> connect(user.accessToken(), new Recorder()).get(3, TimeUnit.SECONDS));
    }

    /**
     * 핵심 시나리오: 기기 A가 구독 중일 때 기기 B가 클립을 올리면 A가 3초 안에 알림을 받아야 한다 (PRD 성공 기준).
     * 같은 내용을 다시 올려도(중복) 알림은 다시 와야 한다.
     */
    @Test
    void clipUploadedByDeviceBIsPushedToDeviceAWithin3Seconds() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        Api.Tokens user = api.login(email);
        Api.DeviceTokens a = api.registerDevice(user, "A");
        Api.DeviceTokens b = api.registerDevice(user, "B");

        Recorder rec = new Recorder();
        StompSession session = connect(a.accessToken(), rec).get(3, TimeUnit.SECONDS);
        session.subscribe("/topic/clips/" + user.userId(), rec);
        Thread.sleep(300); // SUBSCRIBE가 서버 브로커에 등록될 시간을 잠깐 준다

        long start = System.nanoTime();
        String body = api.createClip(b.accessToken(), "pushed text", 201);
        Map<String, Object> msg = rec.messages.poll(3, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(msg, "device A did not receive the clip within 3s");
        assertTrue(elapsedMs < 3000);
        assertEquals(Api.read(body, "$.id"), msg.get("id"));
        assertEquals("pushed text", msg.get("content"));
        assertEquals(b.deviceId(), msg.get("sourceDeviceId"));
        assertNotNull(msg.get("contentHash"));
        assertNotNull(msg.get("createdAt"));
        assertNotNull(msg.get("expiresAt"));

        // 중복 재복사도 알림을 보낸다 (API 계약서 3장)
        api.createClip(b.accessToken(), "pushed text", 200);
        assertNotNull(rec.messages.poll(3, TimeUnit.SECONDS), "duplicate upload must also be pushed");
        session.disconnect();
    }

    /** 원격 로그아웃하면 그 기기의 열린 WebSocket이 즉시 끊기고, 이후 올라오는 클립 알림도 받지 못해야 한다 */
    @Test
    void remoteLogoutClosesThatDevicesOpenSocket() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        Api.Tokens user = api.login(email);
        Api.DeviceTokens a = api.registerDevice(user, "A");
        Api.DeviceTokens b = api.registerDevice(user, "B");

        Recorder rec = new Recorder();
        StompSession session = connect(a.accessToken(), rec).get(3, TimeUnit.SECONDS);
        session.subscribe("/topic/clips/" + user.userId(), rec);
        Thread.sleep(300);

        api.delete("/api/devices/" + a.deviceId(), b.accessToken())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        assertTrue(rec.error.await(3, TimeUnit.SECONDS), "logged-out device's socket should be closed");

        api.createClip(b.accessToken(), "after logout", 201);
        assertNull(rec.messages.poll(1, TimeUnit.SECONDS), "logged-out device must not receive pushes");
    }

    /** 다른 사용자의 topic을 구독하려 하면 거부되고, 그 사람의 클립 알림을 절대 받으면 안 된다 (보안) */
    @Test
    void subscribingToAnotherUsersTopicIsRejected() throws Exception {
        Api.DeviceTokens attacker = api.newUserWithDevice();
        Api.DeviceTokens victim = api.newUserWithDevice();

        Recorder rec = new Recorder();
        StompSession session = connect(attacker.accessToken(), rec).get(3, TimeUnit.SECONDS);
        session.subscribe("/topic/clips/" + victim.userId(), rec);
        Thread.sleep(300);

        api.createClip(victim.accessToken(), "victim secret", 201);

        assertNull(rec.messages.poll(1500, TimeUnit.MILLISECONDS), "must not receive another user's clips");
        assertTrue(rec.error.getCount() == 0 || !session.isConnected(),
                "subscription should be rejected with an ERROR frame / disconnect");
    }

    /** 이미지 업로드도 같은 topic으로 푸시되고, type/width/height가 담긴다 */
    @Test
    void imageUploadIsPushedWithType() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        Api.Tokens user = api.login(email);
        Api.DeviceTokens a = api.registerDevice(user, "A");
        Api.DeviceTokens b = api.registerDevice(user, "B");

        Recorder rec = new Recorder();
        StompSession session = connect(a.accessToken(), rec).get(3, TimeUnit.SECONDS);
        session.subscribe("/topic/clips/" + user.userId(), rec);
        Thread.sleep(300); // SUBSCRIBE가 서버 브로커에 등록될 시간

        api.postImage(b.accessToken(), Api.png(40, 30, 0x336699));
        Map<String, Object> msg = rec.messages.poll(3, TimeUnit.SECONDS);
        assertNotNull(msg, "image push not received within 3s");
        assertEquals("IMAGE", msg.get("type"));
        assertEquals(40, msg.get("width"));
        assertEquals(30, msg.get("height"));
        assertEquals(b.deviceId(), msg.get("sourceDeviceId"));
        session.disconnect();
    }
}
