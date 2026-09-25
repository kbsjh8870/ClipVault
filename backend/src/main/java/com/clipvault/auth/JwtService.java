package com.clipvault.auth;

import com.clipvault.device.DeviceRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static final String ACCESS = "access";
    public static final String REFRESH = "refresh";

    public record TokenPair(String accessToken, String refreshToken) {
    }

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final DeviceRepository devices;

    public JwtService(@Value("${clipvault.jwt.secret}") String secret,
                      @Value("${clipvault.jwt.access-ttl}") Duration accessTtl,
                      @Value("${clipvault.jwt.refresh-ttl}") Duration refreshTtl,
                      DeviceRepository devices) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.devices = devices;
    }

    public TokenPair issue(UUID userId, UUID deviceId) {
        return new TokenPair(build(userId, deviceId, ACCESS, accessTtl), build(userId, deviceId, REFRESH, refreshTtl));
    }

    private String build(UUID userId, UUID deviceId, String type, Duration ttl) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("typ", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        if (deviceId != null) {
            builder.claim("did", deviceId.toString());
        }
        return builder.signWith(key).compact();
    }

    /** Verifies signature, expiry and type. Throws JwtException / IllegalArgumentException when invalid. */
    public AuthUser parse(String token, String expectedType) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (!expectedType.equals(claims.get("typ", String.class))) {
            throw new JwtException("wrong token type");
        }
        String did = claims.get("did", String.class);
        return new AuthUser(UUID.fromString(claims.getSubject()), did == null ? null : UUID.fromString(did));
    }

    /**
     * Resolves an "Authorization: Bearer ..." header value into an Authentication, or null if missing/invalid.
     * Device tokens are rejected unless the device is still active, so remote logout takes effect immediately.
     */
    public Authentication authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        AuthUser user;
        try {
            user = parse(authorizationHeader.substring(7).trim(), ACCESS);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
        if (user.deviceId() != null) {
            var device = devices.findById(user.deviceId()).orElse(null);
            if (device == null || !device.isActive() || !device.getUserId().equals(user.userId())) {
                return null;
            }
            // ponytail: lastSeenAt write throttled to once a minute per device
            if (device.getLastSeenAt().isBefore(Instant.now().minusSeconds(60))) {
                device.touch();
                devices.save(device);
            }
        }
        return user.toAuthentication();
    }
}
