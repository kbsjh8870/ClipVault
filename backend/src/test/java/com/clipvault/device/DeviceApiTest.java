package com.clipvault.device;

import com.clipvault.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static com.clipvault.Api.json;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기기 API와 토큰 권한 규칙(user 토큰 vs device 토큰, 원격 로그아웃) 통합 테스트.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceApiTest {
    @Autowired MockMvc mvc;
    Api api;

    @BeforeEach
    void setUp() { api = new Api(mvc); }

    /** 가입 + 로그인만 하고 기기는 아직 등록하지 않은 사용자. */
    private Api.Tokens newUser() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        return api.login(email);
    }

    /** 기기 등록 → 201, 기기 정보와 device 토큰 쌍이 응답에 들어 있어야 한다 */
    @Test
    void registerReturnsDeviceAndTokens() throws Exception {
        Api.Tokens user = newUser();
        api.post("/api/devices", user.accessToken(), json("deviceName", "Home PC", "os", "Windows 11"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.deviceName").value("Home PC"))
                .andExpect(jsonPath("$.os").value("Windows 11"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.refreshToken", notNullValue()));
    }

    /** 토큰 없음 / 엉터리 토큰 → 401 */
    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        api.get("/api/devices", null).andExpect(status().isUnauthorized());
        api.get("/api/clips", "garbage-token").andExpect(status().isUnauthorized());
    }

    /** user 토큰(기기 등록 전)으로 클립 API 호출 → 403 (device 토큰 필수) */
    @Test
    void userTokenCannotUseClipApis() throws Exception {
        Api.Tokens user = newUser();
        api.post("/api/clips", user.accessToken(), json("content", "hi")).andExpect(status().isForbidden());
        api.get("/api/clips", user.accessToken()).andExpect(status().isForbidden());
    }

    /** 기기 목록에는 내 기기만 보여야 하고(다른 사람 기기 제외), 토큰 같은 민감 정보는 없어야 한다 */
    @Test
    void listReturnsOnlyMyActiveDevices() throws Exception {
        Api.Tokens user = newUser();
        Api.DeviceTokens a = api.registerDevice(user, "A");
        Api.DeviceTokens b = api.registerDevice(user, "B");
        api.newUserWithDevice(); // 방해용: 다른 사용자의 기기 (목록에 섞이면 안 됨)

        api.get("/api/devices", user.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(a.deviceId(), b.deviceId())))
                .andExpect(jsonPath("$[0].accessToken").doesNotExist());
        // device 토큰으로 조회해도 같은 결과
        api.get("/api/devices", a.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
    }

    /**
     * 원격 로그아웃: B가 A를 삭제하면 A의 access 토큰과 refresh 토큰이 즉시 401이 되어야 한다.
     * B는 계속 정상 동작하고, 목록에는 B만 남는다.
     */
    @Test
    void deleteDeviceRevokesItsTokensImmediately() throws Exception {
        Api.Tokens user = newUser();
        Api.DeviceTokens a = api.registerDevice(user, "A");
        Api.DeviceTokens b = api.registerDevice(user, "B");
        api.get("/api/clips", a.accessToken()).andExpect(status().isOk()); // 삭제 전에는 A도 정상

        api.delete("/api/devices/" + a.deviceId(), b.accessToken()).andExpect(status().isNoContent());

        api.get("/api/clips", a.accessToken()).andExpect(status().isUnauthorized());
        api.post("/api/auth/refresh", null, json("refreshToken", a.refreshToken())).andExpect(status().isUnauthorized());
        api.get("/api/clips", b.accessToken()).andExpect(status().isOk());
        api.get("/api/devices", b.accessToken())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(b.deviceId()));
    }

    /** 다른 사람의 기기를 삭제하려 하면 404, 그 기기는 아무 영향 없어야 한다 */
    @Test
    void deletingAnotherUsersDeviceReturns404() throws Exception {
        Api.DeviceTokens mine = api.newUserWithDevice();
        Api.DeviceTokens theirs = api.newUserWithDevice();
        api.delete("/api/devices/" + theirs.deviceId(), mine.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        api.get("/api/clips", theirs.accessToken()).andExpect(status().isOk());
    }
}
