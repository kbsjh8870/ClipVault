package com.clipvault.config;

import com.clipvault.auth.AuthUser;
import com.clipvault.auth.JwtAuthFilter;
import com.clipvault.auth.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 스프링 시큐리티 설정: "어떤 URL에 어떤 토큰이 필요한가"를 정한다.
 *
 * <p>URL별 접근 규칙 요약</p>
 * <table>
 *   <tr><th>URL</th><th>필요한 것</th></tr>
 *   <tr><td>/api/auth/** (회원가입, 로그인, 토큰 갱신)</td><td>없음 (누구나)</td></tr>
 *   <tr><td>/ws (WebSocket)</td><td>HTTP 단계에선 없음. 연결 후 STOMP CONNECT 때 토큰 검사</td></tr>
 *   <tr><td>POST, GET /api/devices</td><td>로그인만 되어 있으면 됨 (user 토큰도 OK)</td></tr>
 *   <tr><td>그 외 전부 (/api/clips, DELETE /api/devices/..)</td><td>device 토큰 필수</td></tr>
 * </table>
 */
@Configuration
public class SecurityConfig {

    /** 비밀번호 해시에 BCrypt를 쓴다. BCrypt는 일부러 느리게 설계되어 무차별 대입 공격에 강하고, 자동으로 salt를 붙여 준다. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt) throws Exception {
        // CSRF: 브라우저 쿠키 세션을 노리는 공격인데, 우리는 쿠키 대신 Authorization 헤더의 토큰을 쓰므로 필요 없다.
        http.csrf(AbstractHttpConfigurer::disable)
                // 스프링 기본 로그인 방식들(HTTP Basic, 로그인 폼, 로그아웃 페이지)은 쓰지 않으니 모두 끈다.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // 서버에 세션을 만들지 않는다(stateless). 매 요청마다 토큰만 보고 사용자를 판별한다.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // 인증 없이 허용. /ws는 WebSocket이 열린 뒤 STOMP CONNECT 프레임에서 따로 인증한다(StompAuthInterceptor).
                        // /error는 스프링 내부 에러 페이지 경로라 열어 두지 않으면 에러가 401로 둔갑한다.
                        .requestMatchers("/api/auth/**", "/ws", "/error").permitAll()
                        // 로그인 직후의 user 토큰(아직 기기 없음)은 기기 등록/조회만 할 수 있다.
                        .requestMatchers(HttpMethod.POST, "/api/devices").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/devices").authenticated()
                        // 나머지는 전부 device 토큰(DEVICE 권한)이 있어야 한다.
                        .anyRequest().hasAuthority(AuthUser.DEVICE_AUTHORITY))
                .exceptionHandling(e -> e
                        // 토큰이 없거나 잘못됨 → 401
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, 401, "Unauthorized"))
                        // 토큰은 맞는데 권한 부족(user 토큰으로 클립 API 호출 등) → 403
                        .accessDeniedHandler((req, res, ex) -> writeError(res, 403, "Device token required")))
                // 우리 JWT 필터를 스프링 기본 로그인 필터보다 앞에 끼워 넣는다.
                .addFilterBefore(new JwtAuthFilter(jwt), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 시큐리티 단계의 에러를 GlobalExceptionHandler와 같은 JSON 형식({@code {status, message}})으로 쓴다.
     * 여기는 컨트롤러 밖이라 GlobalExceptionHandler가 동작하지 않기 때문에 직접 응답을 만든다.
     * (message는 코드에 고정된 문자열만 들어오므로 JSON 이스케이프가 필요 없다.)
     */
    private static void writeError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
