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
 * 현재 열려 있는 WebSocket 연결들을 기억해 두었다가, 원격 로그아웃된 기기의 연결을 즉시 끊는 클래스.
 *
 * <p><b>왜 필요한가?</b> 토큰 검사는 WebSocket을 연결(CONNECT)할 때 한 번만 한다.
 * 그래서 기기를 원격 로그아웃해도, 그 기기가 이미 연결해 둔 소켓은 계속 살아서 새 클립 알림을 받게 된다.
 * 이 클래스가 그 연결을 찾아서 강제로 닫는다.</p>
 *
 * <p><b>어떻게 기기를 구분하나?</b> {@link StompAuthInterceptor}가 CONNECT 때 세션 속성에 기기 ID를 넣어 둔다.
 * (STOMP 세션 속성과 WebSocketSession 속성은 같은 Map이라 여기서 그대로 읽을 수 있다.)</p>
 *
 * <p><b>동작 원리</b>: "데코레이터(decorator)"로 스프링의 WebSocket 처리기를 한 겹 감싼다.
 * 연결이 열릴 때 목록에 넣고, 닫힐 때 목록에서 뺀다. 나머지 처리는 원래 처리기에 그대로 맡긴다.</p>
 */
// ponytail(의도적 단순화): 연결 목록을 서버 메모리에만 들고 있으므로 서버 1대일 때만 동작한다.
// 서버를 여러 대로 늘리면 "이 기기 연결 끊어" 신호를 모든 서버에 전파해야 한다(예: 외부 브로커로 브로드캐스트).
@Component
public class DeviceSessions implements WebSocketHandlerDecoratorFactory {

    /** 세션 속성에 기기 ID를 저장할 때 쓰는 키 이름. StompAuthInterceptor와 공유한다. */
    static final String DEVICE_ID = "deviceId";

    /** 열려 있는 연결 목록 (세션 ID → 세션). 여러 스레드가 동시에 접근하므로 ConcurrentHashMap을 쓴다. */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** 스프링의 원래 WebSocket 처리기를 감싸서, 연결 열림/닫힘 시점에 목록을 갱신하도록 만든다. */
    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            /** 새 연결이 열림 → 목록에 추가 */
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            /** 연결이 닫힘(정상 종료든 끊김이든) → 목록에서 제거 */
            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    /**
     * 해당 기기의 모든 WebSocket 연결을 닫는다. 원격 로그아웃(DeviceController) 시 호출된다.
     *
     * <p>연결이 닫히면 트레이 앱은 재연결을 시도하지만, 기기가 이미 비활성이라 CONNECT가 거부되고
     * 토큰 갱신도 실패해서 결국 로그인 화면으로 돌아간다.</p>
     */
    public void closeDevice(UUID deviceId) {
        sessions.values().stream()
                .filter(s -> deviceId.equals(s.getAttributes().get(DEVICE_ID)))
                .forEach(s -> {
                    try {
                        // 1008 Policy Violation: "정책상 더 이상 연결을 허용하지 않음"이라는 의미의 종료 코드
                        s.close(CloseStatus.POLICY_VIOLATION.withReason("Device logged out"));
                    } catch (IOException ignored) {
                        // 이미 끊어진 연결이면 무시해도 된다
                    }
                });
    }
}
