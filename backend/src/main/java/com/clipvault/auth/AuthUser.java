package com.clipvault.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * "지금 요청을 보낸 사람이 누구인가"를 담는 인증 정보(principal).
 *
 * <p>JWT 토큰을 해석하면 이 객체가 만들어지고, 컨트롤러에서는
 * {@code @AuthenticationPrincipal AuthUser me} 형태로 받아서 사용한다.</p>
 *
 * <p>토큰은 두 종류가 있다.</p>
 * <ul>
 *   <li><b>user 토큰</b>: 로그인 직후 받는 토큰. 아직 기기 등록 전이라 {@code deviceId}가 {@code null}이다.
 *       이 토큰으로는 기기 등록/조회만 할 수 있다.</li>
 *   <li><b>device 토큰</b>: 기기 등록 후 받는 토큰. {@code deviceId}가 들어 있다.
 *       클립 업로드/조회, WebSocket 연결 등 나머지 모든 기능은 이 토큰이 있어야 한다.</li>
 * </ul>
 *
 * @param userId   로그인한 사용자의 ID
 * @param deviceId 요청을 보낸 기기의 ID (user 토큰이면 null)
 */
public record AuthUser(UUID userId, UUID deviceId) {

    /** device 토큰에만 부여되는 권한 이름. SecurityConfig에서 "이 API는 device 토큰 필수"를 검사할 때 쓴다. */
    public static final String DEVICE_AUTHORITY = "DEVICE";

    /**
     * 스프링 시큐리티가 이해하는 인증 객체로 변환한다.
     *
     * <p>모든 토큰에는 "USER" 권한을 주고, deviceId가 있는 토큰에는 "DEVICE" 권한을 추가로 준다.
     * 이렇게 해 두면 SecurityConfig에서 {@code hasAuthority("DEVICE")} 한 줄로 device 토큰 여부를 검사할 수 있다.</p>
     */
    public UsernamePasswordAuthenticationToken toAuthentication() {
        var authorities = deviceId == null
                ? List.of(new SimpleGrantedAuthority("USER"))
                : List.of(new SimpleGrantedAuthority("USER"), new SimpleGrantedAuthority(DEVICE_AUTHORITY));
        // 두 번째 인자(credentials)는 비밀번호 자리인데, 이미 토큰 검증이 끝났으므로 null로 둔다.
        return new UsernamePasswordAuthenticationToken(this, null, authorities);
    }
}
