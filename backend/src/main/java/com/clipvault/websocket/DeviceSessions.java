package com.clipvault.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Tracks open WebSocket sessions so a remotely logged-out device stops receiving pushes immediately.
 * StompAuthInterceptor stores the deviceId in the session attributes on CONNECT (same map as the WebSocketSession's).
 */
// ponytail: in-memory, single instance only; with multiple servers, broadcast the close via the broker
@Component
public class DeviceSessions implements WebSocketHandlerDecoratorFactory {

    static final String DEVICE_ID = "deviceId";

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    public void closeDevice(UUID deviceId) {
        sessions.values().stream()
                .filter(s -> deviceId.equals(s.getAttributes().get(DEVICE_ID)))
                .forEach(s -> {
                    try {
                        s.close(CloseStatus.POLICY_VIOLATION.withReason("Device logged out"));
                    } catch (IOException ignored) {
                        // already gone
                    }
                });
    }
}
