package com.clipvault.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Authenticated principal. deviceId is null for a user token (before device registration). */
public record AuthUser(UUID userId, UUID deviceId) {

    public static final String DEVICE_AUTHORITY = "DEVICE";

    public UsernamePasswordAuthenticationToken toAuthentication() {
        var authorities = deviceId == null
                ? List.of(new SimpleGrantedAuthority("USER"))
                : List.of(new SimpleGrantedAuthority("USER"), new SimpleGrantedAuthority(DEVICE_AUTHORITY));
        return new UsernamePasswordAuthenticationToken(this, null, authorities);
    }
}
