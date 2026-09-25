package com.clipvault.websocket;

import com.clipvault.auth.AuthUser;
import com.clipvault.auth.JwtService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * WebSocket(STOMP)으로 들어오는 모든 프레임을 가로채서 인증/권한을 검사하는 문지기.
 *
 * <p><b>STOMP란?</b> WebSocket 위에서 쓰는 간단한 메시지 규약이다. 클라이언트는 텍스트 프레임을 보내는데,
 * 첫 줄이 명령어(CONNECT, SUBSCRIBE, SEND 등)이고 그 아래에 헤더와 본문이 온다.</p>
 *
 * <p>명령어별 규칙</p>
 * <ul>
 *   <li><b>CONNECT</b>(연결 시작): 헤더 {@code Authorization: Bearer <device 토큰>}이 유효해야 한다.
 *       user 토큰(기기 없음)은 거부한다.</li>
 *   <li><b>SUBSCRIBE</b>(구독): 자기 자신의 topic({@code /topic/clips/{내 userId}})만 구독할 수 있다.
 *       남의 userId를 넣으면 거부 → 다른 사람의 클립을 엿들을 수 없다.</li>
 *   <li><b>SEND</b>(클라이언트가 메시지 발행): 항상 거부. 클라이언트는 알림을 받기만 하고,
 *       클립 업로드는 REST API로 한다. 이걸 막지 않으면 남의 topic에 가짜 메시지를 끼워 넣을 수 있다.</li>
 * </ul>
 *
 * <p>여기서 예외를 던지면 스프링이 클라이언트에게 STOMP ERROR 프레임을 보내고 연결을 끊는다.</p>
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwt;

    public StompAuthInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    /** 클라이언트가 보낸 프레임이 서버 내부로 전달되기 직전에 호출된다. */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        // 하트비트 같은 명령어 없는 프레임은 그냥 통과
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            case CONNECT -> {
                // REST와 같은 방식으로 토큰 검사 (서명, 만료, 기기 활성 여부)
                var auth = jwt.authenticate(accessor.getFirstNativeHeader("Authorization"));
                if (auth == null || ((AuthUser) auth.getPrincipal()).deviceId() == null) {
                    throw new MessageDeliveryException("Unauthorized: device access token required");
                }
                // 이 WebSocket 연결의 "사용자"로 등록. 이후 SUBSCRIBE 때 accessor.getUser()로 꺼내 쓴다.
                accessor.setUser(auth);
                // 세션 속성에 기기 ID를 기록해 둔다. 원격 로그아웃 시 DeviceSessions가 이 값으로 끊을 연결을 찾는다.
                accessor.getSessionAttributes().put(DeviceSessions.DEVICE_ID, ((AuthUser) auth.getPrincipal()).deviceId());
            }
            case SUBSCRIBE -> {
                // CONNECT에서 인증된 사용자가 없거나, 구독하려는 주소가 자기 topic이 아니면 거부
                if (!(accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth)
                        || !("/topic/clips/" + ((AuthUser) auth.getPrincipal()).userId()).equals(accessor.getDestination())) {
                    throw new MessageDeliveryException("Forbidden destination: " + accessor.getDestination());
                }
            }
            case SEND -> throw new MessageDeliveryException("SEND is not supported");
            default -> {
                // DISCONNECT, UNSUBSCRIBE 등은 그대로 허용
            }
        }
        return message;
    }
}
