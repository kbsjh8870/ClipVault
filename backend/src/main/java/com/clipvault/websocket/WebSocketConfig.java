package com.clipvault.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * WebSocket + STOMP 설정. 실시간 클립 알림의 뼈대다.
 *
 * <p>전체 흐름</p>
 * <ol>
 *   <li>트레이 앱이 {@code ws://서버/ws}로 WebSocket을 연다.</li>
 *   <li>STOMP CONNECT 프레임에 device 토큰을 실어 보낸다 → {@link StompAuthInterceptor}가 검사.</li>
 *   <li>{@code /topic/clips/{userId}}를 구독(SUBSCRIBE)한다.</li>
 *   <li>누군가 클립을 올리면 ClipController가 그 topic으로 메시지를 보내고,
 *       서버 내장 브로커가 구독 중인 모든 연결(= 같은 사용자의 모든 PC)에 전달한다.</li>
 * </ol>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor stompAuthInterceptor;
    private final DeviceSessions deviceSessions;

    public WebSocketConfig(StompAuthInterceptor stompAuthInterceptor, DeviceSessions deviceSessions) {
        this.stompAuthInterceptor = stompAuthInterceptor;
        this.deviceSessions = deviceSessions;
    }

    /**
     * WebSocket 접속 주소를 {@code /ws}로 연다. (브라우저용 SockJS 대체 방식은 쓰지 않는 순수 WebSocket)
     *
     * <p>모든 Origin을 허용한 이유: Origin 검사는 브라우저에서 다른 사이트가 몰래 연결하는 것을 막는 장치인데,
     * 데스크톱 트레이 앱은 Origin 헤더를 보내지 않는다. 대신 CONNECT 단계의 JWT 토큰으로 인증한다.</p>
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    /**
     * 서버 메모리에서 도는 간단한 메시지 브로커를 켠다. {@code /topic}으로 시작하는 주소가 브로커 담당이다.
     * Redis 같은 외부 브로커 없이 동작하므로 서버 한 대 운영에는 이걸로 충분하다.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    /** 열려 있는 WebSocket 연결을 추적하는 장치(DeviceSessions)를 끼워 넣는다. 원격 로그아웃 시 연결을 끊기 위함. */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(deviceSessions);
    }

    /** 클라이언트가 보내는 모든 STOMP 프레임이 인증 검사기(StompAuthInterceptor)를 거치도록 등록한다. */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthInterceptor);
    }
}
