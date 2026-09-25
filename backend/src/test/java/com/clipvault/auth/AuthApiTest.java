package com.clipvault.auth;

import com.clipvault.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static com.clipvault.Api.json;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증 API(회원가입, 로그인, 토큰 갱신) 통합 테스트.
 *
 * <p>{@code @SpringBootTest}: 실제 애플리케이션 전체를 띄운다(DB는 테스트용 H2 메모리 DB).
 * {@code @AutoConfigureMockMvc}: 서버에 HTTP 요청을 흉내 내 보낼 수 있는 MockMvc를 준비해 준다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest {
    @Autowired MockMvc mvc;
    Api api;

    /** 각 테스트 실행 전에 도우미 객체를 새로 만든다. */
    @BeforeEach
    void setUp() { api = new Api(mvc); }

    /** 회원가입 성공 → 201, 응답에 id와 email */
    @Test
    void signupReturns201WithIdAndEmail() throws Exception {
        String email = Api.uniqueEmail();
        api.post("/api/auth/signup", null, json("email", email, "password", Api.PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.email").value(email));
    }

    /** 같은 이메일로 두 번 가입 → 409, 공통 에러 형식 {status, message} */
    @Test
    void duplicateEmailReturns409() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        api.post("/api/auth/signup", null, json("email", email, "password", Api.PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    /** 이메일 형식이 틀리거나 비밀번호가 8자 미만 → 400 */
    @Test
    void invalidSignupReturns400() throws Exception {
        api.post("/api/auth/signup", null, json("email", "not-an-email", "password", Api.PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        api.post("/api/auth/signup", null, json("email", Api.uniqueEmail(), "password", "short7!"))
                .andExpect(status().isBadRequest());
    }

    /** 로그인 성공 → userId와 accessToken만 있고, refreshToken은 없어야 한다 (user 토큰은 갱신 대상이 아님) */
    @Test
    void loginReturnsUserTokens() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        api.post("/api/auth/login", null, json("email", email, "password", Api.PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", notNullValue()))
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    /** 비밀번호가 틀림 / 없는 이메일 → 둘 다 401 */
    @Test
    void wrongPasswordReturns401() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        api.post("/api/auth/login", null, json("email", email, "password", "wrong-password"))
                .andExpect(status().isUnauthorized());
        api.post("/api/auth/login", null, json("email", Api.uniqueEmail(), "password", Api.PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 토큰 갱신(rotation) 검증:
     * refresh 토큰을 쓰면 새 쌍이 나오고, 방금 쓴 옛 refresh 토큰은 다시 쓸 수 없으며, 새 토큰들은 정상 동작해야 한다.
     */
    @Test
    void refreshRotatesAndRejectsOldToken() throws Exception {
        Api.DeviceTokens dev = api.newUserWithDevice();
        // JWT 발급 시각(iat)은 초 단위라 같은 초에 다시 발급하면 비슷한 토큰이 될 수 있다. 확실히 다르게 1.1초 기다린다.
        Thread.sleep(1100);

        String body = api.post("/api/auth/refresh", null, json("refreshToken", dev.refreshToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(dev.userId()))
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String newRefresh = Api.read(body, "$.refreshToken");
        String newAccess = Api.read(body, "$.accessToken");
        assertNotEquals(dev.refreshToken(), newRefresh);

        // 옛 refresh 토큰은 이제 무효
        api.post("/api/auth/refresh", null, json("refreshToken", dev.refreshToken()))
                .andExpect(status().isUnauthorized());
        // 새 access 토큰으로 API 호출 가능, 새 refresh 토큰으로 다시 갱신 가능
        api.get("/api/clips", newAccess).andExpect(status().isOk());
        api.post("/api/auth/refresh", null, json("refreshToken", newRefresh))
                .andExpect(status().isOk());
    }

    /** access 토큰을 refresh 자리에 보내면 거부 (토큰 종류 typ 검사) */
    @Test
    void accessTokenIsRejectedAsRefreshToken() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        Api.Tokens user = api.login(email);
        api.post("/api/auth/refresh", null, json("refreshToken", user.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    /** JWT 형식조차 아닌 값 → 401 (500 서버 오류가 나면 안 된다) */
    @Test
    void garbageRefreshTokenIsRejected() throws Exception {
        api.post("/api/auth/refresh", null, json("refreshToken", "not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }
}
