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

/**
 * JWT 토큰을 만들고(발급) 검사(검증)하는 서비스.
 *
 * <p><b>JWT란?</b> 서버가 비밀키로 서명한 문자열 토큰이다. 안에 "누구인지(userId)", "언제 만료되는지" 같은 정보(claim)가 들어 있고,
 * 서명 덕분에 클라이언트가 내용을 위조하면 바로 들통난다. 서버에 세션을 저장할 필요가 없어서(stateless) 구조가 단순해진다.</p>
 *
 * <p><b>이 프로젝트의 토큰에 들어가는 claim</b></p>
 * <ul>
 *   <li>{@code sub}: 사용자 ID (UUID)</li>
 *   <li>{@code did}: 기기 ID (device 토큰에만 있음)</li>
 *   <li>{@code typ}: "access" 또는 "refresh" - refresh 토큰을 access 자리에 쓰는 것(또는 반대)을 막기 위함</li>
 *   <li>{@code jti}: 토큰마다 다른 랜덤 ID - 같은 초에 발급해도 토큰 문자열이 겹치지 않게 함</li>
 *   <li>{@code iat}/{@code exp}: 발급 시각 / 만료 시각</li>
 * </ul>
 *
 * <p>서명 알고리즘은 HS256(HMAC-SHA256)이며 비밀키는 환경변수 {@code JWT_SECRET}(32바이트 이상)로 주입한다.</p>
 */
@Service
public class JwtService {

    /** access 토큰의 typ 값. API 호출에 쓰는 짧은 수명(기본 15분) 토큰. */
    public static final String ACCESS = "access";
    /** refresh 토큰의 typ 값. access 토큰을 새로 받을 때만 쓰는 긴 수명(기본 30일) 토큰. */
    public static final String REFRESH = "refresh";

    /** 한 번에 발급되는 access + refresh 토큰 묶음. */
    public record TokenPair(String accessToken, String refreshToken) {
    }

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final DeviceRepository devices;

    /**
     * 설정값은 application.yml에서 주입받는다.
     * {@code 15m}, {@code 30d} 같은 문자열은 스프링이 자동으로 Duration으로 바꿔 준다.
     */
    public JwtService(@Value("${clipvault.jwt.secret}") String secret,
                      @Value("${clipvault.jwt.access-ttl}") Duration accessTtl,
                      @Value("${clipvault.jwt.refresh-ttl}") Duration refreshTtl,
                      DeviceRepository devices) {
        // 비밀키가 32바이트보다 짧으면 여기서 예외가 나서 서버가 시작되지 않는다(약한 키 방지).
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.devices = devices;
    }

    /** access + refresh 토큰 쌍을 발급한다. 기기 등록 시와 토큰 갱신 시에 쓴다. */
    public TokenPair issue(UUID userId, UUID deviceId) {
        return new TokenPair(build(userId, deviceId, ACCESS, accessTtl), build(userId, deviceId, REFRESH, refreshTtl));
    }

    /** 사용자 전용 access 토큰(기기 없음, refresh 없음). 기기 등록/조회에만 쓸 수 있다. 로그인 시 발급. */
    public String issueAccess(UUID userId) {
        return build(userId, null, ACCESS, accessTtl);
    }

    /** 실제로 토큰 문자열을 만드는 부분. */
    private String build(UUID userId, UUID deviceId, String type, Duration ttl) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())      // jti: 토큰 고유 ID
                .subject(userId.toString())            // sub: 사용자 ID
                .claim("typ", type)                    // access / refresh 구분
                .issuedAt(Date.from(now))              // iat: 발급 시각
                .expiration(Date.from(now.plus(ttl))); // exp: 만료 시각
        if (deviceId != null) {
            builder.claim("did", deviceId.toString()); // did: 기기 ID (device 토큰만)
        }
        return builder.signWith(key).compact();        // 비밀키로 서명해서 문자열로 만든다
    }

    /**
     * 토큰을 해석해서 누구의 토큰인지 돌려준다.
     *
     * <p>서명이 맞는지, 만료되지 않았는지, 기대한 종류(access/refresh)인지 검사한다.
     * 하나라도 틀리면 {@link JwtException} 또는 {@link IllegalArgumentException}을 던진다.</p>
     *
     * <p>주의: 여기서는 "기기가 아직 활성 상태인지"는 보지 않는다. 그건 {@link #authenticate}에서 한다.</p>
     */
    public AuthUser parse(String token, String expectedType) {
        // 서명 검증 + 만료 검사는 jjwt 라이브러리가 parseSignedClaims 안에서 해 준다.
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (!expectedType.equals(claims.get("typ", String.class))) {
            throw new JwtException("wrong token type");
        }
        String did = claims.get("did", String.class);
        return new AuthUser(UUID.fromString(claims.getSubject()), did == null ? null : UUID.fromString(did));
    }

    /**
     * {@code "Authorization: Bearer <토큰>"} 헤더 값을 받아 스프링 시큐리티 인증 객체로 바꾼다.
     * REST 요청(JwtAuthFilter)과 WebSocket 연결(StompAuthInterceptor) 양쪽에서 공통으로 쓴다.
     *
     * <p><b>원격 로그아웃 즉시 반영</b>: device 토큰이면 매 요청마다 DB에서 기기를 조회해
     * 비활성(원격 로그아웃됨)이면 거부한다. 그래서 토큰 자체는 아직 만료 전이라도, 기기를 삭제하는 순간 바로 못 쓰게 된다.</p>
     *
     * @return 인증 객체. 헤더가 없거나, 토큰이 틀렸거나, 기기가 비활성이면 {@code null}
     */
    public Authentication authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        AuthUser user;
        try {
            // "Bearer " (7글자) 뒤의 토큰 부분만 잘라서 검사. access 토큰만 허용.
            user = parse(authorizationHeader.substring(7).trim(), ACCESS);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
        if (user.deviceId() != null) {
            var device = devices.findById(user.deviceId()).orElse(null);
            // 기기가 없거나, 원격 로그아웃되었거나, 다른 사람의 기기라면 거부
            if (device == null || !device.isActive() || !device.getUserId().equals(user.userId())) {
                return null;
            }
            // ponytail(의도적 단순화): 마지막 접속 시각(lastSeenAt)은 기기당 1분에 한 번만 DB에 쓴다.
            // 매 요청마다 쓰면 클립 복사할 때마다 불필요한 DB 쓰기가 생기기 때문. 기기 목록에서 "대략 언제 접속했는지"만 보여 주면 충분하다.
            if (device.getLastSeenAt().isBefore(Instant.now().minusSeconds(60))) {
                device.touch();
                devices.save(device);
            }
        }
        return user.toAuthentication();
    }
}
