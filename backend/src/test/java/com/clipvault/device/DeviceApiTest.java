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

@SpringBootTest
@AutoConfigureMockMvc
class DeviceApiTest {
    @Autowired MockMvc mvc;
    Api api;

    @BeforeEach
    void setUp() { api = new Api(mvc); }

    private Api.Tokens newUser() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        return api.login(email);
    }

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

    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        api.get("/api/devices", null).andExpect(status().isUnauthorized());
        api.get("/api/clips", "garbage-token").andExpect(status().isUnauthorized());
    }

    @Test
    void userTokenCannotUseClipApis() throws Exception {
        Api.Tokens user = newUser();
        api.post("/api/clips", user.accessToken(), json("content", "hi")).andExpect(status().isForbidden());
        api.get("/api/clips", user.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void listReturnsOnlyMyActiveDevices() throws Exception {
        Api.Tokens user = newUser();
        Api.DeviceTokens a = api.registerDevice(user, "A");
        Api.DeviceTokens b = api.registerDevice(user, "B");
        api.newUserWithDevice(); // noise: another user's device

        api.get("/api/devices", user.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(a.deviceId(), b.deviceId())))
                .andExpect(jsonPath("$[0].accessToken").doesNotExist());
        api.get("/api/devices", a.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void deleteDeviceRevokesItsTokensImmediately() throws Exception {
        Api.Tokens user = newUser();
        Api.DeviceTokens a = api.registerDevice(user, "A");
        Api.DeviceTokens b = api.registerDevice(user, "B");
        api.get("/api/clips", a.accessToken()).andExpect(status().isOk());

        api.delete("/api/devices/" + a.deviceId(), b.accessToken()).andExpect(status().isNoContent());

        api.get("/api/clips", a.accessToken()).andExpect(status().isUnauthorized());
        api.post("/api/auth/refresh", null, json("refreshToken", a.refreshToken())).andExpect(status().isUnauthorized());
        api.get("/api/clips", b.accessToken()).andExpect(status().isOk());
        api.get("/api/devices", b.accessToken())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(b.deviceId()));
    }

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
