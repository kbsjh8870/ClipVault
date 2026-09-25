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

@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest {
    @Autowired MockMvc mvc;
    Api api;

    @BeforeEach
    void setUp() { api = new Api(mvc); }

    @Test
    void signupReturns201WithIdAndEmail() throws Exception {
        String email = Api.uniqueEmail();
        api.post("/api/auth/signup", null, json("email", email, "password", Api.PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void duplicateEmailReturns409() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        api.post("/api/auth/signup", null, json("email", email, "password", Api.PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    void invalidSignupReturns400() throws Exception {
        api.post("/api/auth/signup", null, json("email", "not-an-email", "password", Api.PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        api.post("/api/auth/signup", null, json("email", Api.uniqueEmail(), "password", "short7!"))
                .andExpect(status().isBadRequest());
    }

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

    @Test
    void wrongPasswordReturns401() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        api.post("/api/auth/login", null, json("email", email, "password", "wrong-password"))
                .andExpect(status().isUnauthorized());
        api.post("/api/auth/login", null, json("email", Api.uniqueEmail(), "password", Api.PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesAndRejectsOldToken() throws Exception {
        Api.DeviceTokens dev = api.newUserWithDevice();
        Thread.sleep(1100); // JWT iat has second precision; make sure the new pair differs

        String body = api.post("/api/auth/refresh", null, json("refreshToken", dev.refreshToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(dev.userId()))
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String newRefresh = Api.read(body, "$.refreshToken");
        String newAccess = Api.read(body, "$.accessToken");
        assertNotEquals(dev.refreshToken(), newRefresh);

        // old refresh token is no longer valid
        api.post("/api/auth/refresh", null, json("refreshToken", dev.refreshToken()))
                .andExpect(status().isUnauthorized());
        // new pair works
        api.get("/api/clips", newAccess).andExpect(status().isOk());
        api.post("/api/auth/refresh", null, json("refreshToken", newRefresh))
                .andExpect(status().isOk());
    }

    @Test
    void accessTokenIsRejectedAsRefreshToken() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        Api.Tokens user = api.login(email);
        api.post("/api/auth/refresh", null, json("refreshToken", user.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbageRefreshTokenIsRejected() throws Exception {
        api.post("/api/auth/refresh", null, json("refreshToken", "not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }
}
