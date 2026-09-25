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

/** REST calls go through MockMvc (same application context), WS through a real STOMP client on the random port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ClipWebSocketTest {
    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    Api api;
    WebSocketStompClient stomp;

    @BeforeEach
    void setUp() {
        api = new Api(mvc);
        stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new JacksonJsonMessageConverter());
    }

    @AfterEach
    void tearDown() { stomp.stop(); }

    private CompletableFuture<StompSession> connect(String token, StompSessionHandler handler) {
        StompHeaders headers = new StompHeaders();
        if (token != null) headers.add("Authorization", "Bearer " + token);
        return stomp.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers, handler);
    }

    /** Collects MESSAGE payloads and any error signal (ERROR frame, transport error, exception). */
    static class Recorder extends StompSessionHandlerAdapter implements StompFrameHandler {
        final BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        final CountDownLatch error = new CountDownLatch(1);

        @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }

        @Override @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders headers, Object payload) {
            if (payload instanceof Map<?, ?> m && headers.getDestination() != null) messages.add((Map<String, Object>) m);
            else error.countDown(); // session-level frame = ERROR
        }

        @Override public void handleException(StompSession s, StompCommand c, StompHeaders h, byte[] p, Throwable ex) { error.countDown(); }
        @Override public void handleTransportError(StompSession s, Throwable ex) { error.countDown(); }
    }

    @Test
    void connectWithoutTokenIsRejected() {
        assertThrows(Exception.class, () -> connect(null, new Recorder()).get(3, TimeUnit.SECONDS));
    }

    @Test
    void connectWithBadTokenIsRejected() {
        assertThrows(Exception.class, () -> connect("bad.token.value", new Recorder()).get(3, TimeUnit.SECONDS));
    }

    @Test
    void connectWithUserTokenIsRejected() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        Api.Tokens user = api.login(email);
        assertThrows(Exception.class, () -> connect(user.accessToken(), new Recorder()).get(3, TimeUnit.SECONDS));
    }

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
        Thread.sleep(300); // let SUBSCRIBE reach the broker

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

        // duplicate re-copy also pushes (contract §3)
        api.createClip(b.accessToken(), "pushed text", 200);
        assertNotNull(rec.messages.poll(3, TimeUnit.SECONDS), "duplicate upload must also be pushed");
        session.disconnect();
    }

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
}
