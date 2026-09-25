package com.clipvault.client.network;

import com.clipvault.client.auth.Session;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Blocking REST client. Call only off the EDT. On 401 it refreshes once and retries. */
public class ApiClient {
    public static final ObjectMapper JSON = new ObjectMapper();

    /** Non-2xx response. status 401 after a failed refresh means "log in again". */
    public static class ApiException extends RuntimeException {
        public final int status;

        ApiException(int status, String message) {
            super(status + " " + message);
            this.status = status;
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Session session;

    public ApiClient(Session session) {
        this.session = session;
    }

    // --- auth / devices ---

    public void signup(String email, String password) {
        send("POST", "/api/auth/signup", Map.of("email", email, "password", password), null);
    }

    /** Login + device registration; stores device tokens in the session. */
    public void loginAndRegister(String email, String password, String deviceName) {
        JsonNode login = send("POST", "/api/auth/login", Map.of("email", email, "password", password), null);
        JsonNode dev = send("POST", "/api/devices",
                Map.of("deviceName", deviceName, "os", System.getProperty("os.name")),
                login.get("accessToken").asText());
        session.userId = login.get("userId").asText();
        session.deviceId = dev.get("id").asText();
        session.accessToken = dev.get("accessToken").asText();
        session.refreshToken = dev.get("refreshToken").asText();
        session.save();
    }

    /** Rotates the token pair. Returns false if the refresh token is rejected. */
    public synchronized boolean refresh() {
        if (session.refreshToken == null) return false;
        try {
            JsonNode r = send("POST", "/api/auth/refresh", Map.of("refreshToken", session.refreshToken), null);
            session.accessToken = r.get("accessToken").asText();
            session.refreshToken = r.get("refreshToken").asText();
            session.save();
            return true;
        } catch (ApiException e) {
            if (e.status == 401 || e.status == 403) return false;
            throw e;
        }
    }

    public JsonNode listDevices() { return authed("GET", "/api/devices", null); }

    public void deleteDevice(String id) { authed("DELETE", "/api/devices/" + enc(id), null); }

    // --- clips ---

    public JsonNode listClips(int limit) { return authed("GET", "/api/clips?limit=" + limit, null); }

    public void postClip(String content) { authed("POST", "/api/clips", Map.of("content", content)); }

    // --- plumbing ---

    private JsonNode authed(String method, String path, Object body) {
        String token = session.accessToken;
        try {
            return send(method, path, body, token);
        } catch (ApiException e) {
            if (e.status != 401) throw e;
            synchronized (this) {
                // another thread may have refreshed already
                if (Objects.equals(token, session.accessToken) && !refresh()) throw e;
            }
            return send(method, path, body, session.accessToken);
        }
    }

    private JsonNode send(String method, String path, Object body, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(session.server + path))
                    .timeout(Duration.ofSeconds(15))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            if (body != null) b.header("Content-Type", "application/json");
            if (token != null) b.header("Authorization", "Bearer " + token);
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String text = res.body();
            if (res.statusCode() / 100 != 2) {
                String msg = text;
                try { msg = JSON.readTree(text).path("message").asText(text); } catch (IOException ignored) { }
                throw new ApiException(res.statusCode(), msg);
            }
            return text == null || text.isBlank() ? JSON.nullNode() : JSON.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
