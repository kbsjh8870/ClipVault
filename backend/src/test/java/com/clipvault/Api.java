package com.clipvault;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Thin MockMvc wrapper shared by the integration tests. */
public class Api {
    public static final String PASSWORD = "password123";

    public record Tokens(String userId, String accessToken) {}
    public record DeviceTokens(String userId, String deviceId, String accessToken, String refreshToken) {}

    private final MockMvc mvc;

    public Api(MockMvc mvc) { this.mvc = mvc; }

    public static String uniqueEmail() { return "u" + UUID.randomUUID().toString().substring(0, 8) + "@test.com"; }

    public static String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":\"").append(kv[i + 1].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    public static <T> T read(String body, String path) { return JsonPath.read(body, path); }

    public ResultActions post(String url, String token, String body) throws Exception {
        var req = MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    public ResultActions get(String url, String token) throws Exception {
        var req = MockMvcRequestBuilders.get(url);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    public ResultActions delete(String url, String token) throws Exception {
        var req = MockMvcRequestBuilders.delete(url);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    public void signup(String email) throws Exception {
        post("/api/auth/signup", null, json("email", email, "password", PASSWORD)).andExpect(status().isCreated());
    }

    public Tokens login(String email) throws Exception {
        String body = post("/api/auth/login", null, json("email", email, "password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Tokens(read(body, "$.userId"), read(body, "$.accessToken"));
    }

    public DeviceTokens registerDevice(Tokens user, String name) throws Exception {
        String body = post("/api/devices", user.accessToken(), json("deviceName", name, "os", "Windows 11"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new DeviceTokens(user.userId(), read(body, "$.id"), read(body, "$.accessToken"), read(body, "$.refreshToken"));
    }

    /** signup + login + register one device. */
    public DeviceTokens newUserWithDevice() throws Exception {
        String email = uniqueEmail();
        signup(email);
        return registerDevice(login(email), "PC-1");
    }

    /** Returns response body; caller asserts status. */
    public String createClip(String deviceToken, String content, int expectedStatus) throws Exception {
        return post("/api/clips", deviceToken, json("content", content))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
    }
}
