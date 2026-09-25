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
 * CONNECT: requires a valid device access token. SUBSCRIBE: only /topic/clips/{own userId}.
 * SEND: rejected, since clients never publish (otherwise a client could inject into another user's topic).
 * Throwing here makes Spring reply with a STOMP ERROR frame and close the session.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwt;

    public StompAuthInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            case CONNECT -> {
                var auth = jwt.authenticate(accessor.getFirstNativeHeader("Authorization"));
                if (auth == null || ((AuthUser) auth.getPrincipal()).deviceId() == null) {
                    throw new MessageDeliveryException("Unauthorized: device access token required");
                }
                accessor.setUser(auth);
            }
            case SUBSCRIBE -> {
                if (!(accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth)
                        || !("/topic/clips/" + ((AuthUser) auth.getPrincipal()).userId()).equals(accessor.getDestination())) {
                    throw new MessageDeliveryException("Forbidden destination: " + accessor.getDestination());
                }
            }
            case SEND -> throw new MessageDeliveryException("SEND is not supported");
            default -> {
            }
        }
        return message;
    }
}
