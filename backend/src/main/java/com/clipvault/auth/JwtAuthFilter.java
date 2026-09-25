package com.clipvault.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 HTTP 요청마다 한 번씩 실행되어 JWT 토큰을 확인하는 필터.
 *
 * <p>동작 순서</p>
 * <ol>
 *   <li>요청 헤더 {@code Authorization: Bearer <토큰>}을 꺼낸다.</li>
 *   <li>{@link JwtService#authenticate}로 토큰이 유효한지(서명, 만료, 원격 로그아웃 여부) 검사한다.</li>
 *   <li>유효하면 "이 요청은 누구의 요청인지"를 SecurityContext에 기록한다.
 *       그러면 컨트롤러에서 {@code @AuthenticationPrincipal}로 꺼내 쓸 수 있다.</li>
 *   <li>토큰이 없거나 틀려도 여기서 바로 막지 않고 다음 단계로 넘긴다.
 *       실제 차단(401/403)은 SecurityConfig의 URL별 권한 규칙이 담당한다.
 *       이렇게 해야 로그인/회원가입처럼 토큰이 필요 없는 API도 자연스럽게 통과된다.</li>
 * </ol>
 *
 * <p>일부러 {@code @Component}를 붙이지 않았다. 붙이면 스프링이 이 필터를 일반 서블릿 필터로도
 * 한 번 더 등록해서 두 번 실행될 수 있다. 그래서 SecurityConfig에서 직접 new로 만들어 시큐리티 체인 안에만 넣는다.</p>
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 토큰이 없거나 잘못됐으면 null이 돌아온다.
        var auth = jwt.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (auth != null) {
            // 인증 성공: 이번 요청의 "로그인한 사용자"로 등록
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        // 인증 성공/실패와 상관없이 다음 필터로 넘긴다. (차단 여부는 SecurityConfig가 결정)
        chain.doFilter(request, response);
    }
}
