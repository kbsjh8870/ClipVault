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
import java.util.function.Function;

/**
 * 백엔드 REST API를 호출하는 클라이언트. 자바 기본 {@link HttpClient}를 쓴다(외부 HTTP 라이브러리 없음).
 *
 * <p><b>주의: 모든 메서드는 응답이 올 때까지 기다리는(blocking) 방식이다.</b>
 * 화면(Swing) 스레드(EDT)에서 호출하면 그동안 창이 멈추므로, 반드시 백그라운드 스레드에서 호출해야 한다.</p>
 *
 * <p><b>자동 토큰 갱신</b>: access 토큰은 15분이면 만료된다. 요청이 401을 받으면 refresh 토큰으로
 * 새 토큰을 받은 뒤 같은 요청을 한 번 더 보낸다. 사용자는 만료를 신경 쓸 필요가 없다.</p>
 */
public class ApiClient {
    /** JSON 변환기. 다른 클래스(ClipSocket 등)에서도 같이 쓴다. */
    public static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 서버가 2xx가 아닌 응답을 줬을 때 던지는 예외.
     * 토큰 갱신까지 실패한 뒤의 401은 "다시 로그인해야 함"을 뜻한다.
     */
    public static class ApiException extends RuntimeException {
        /** HTTP 상태 코드 (예: 401, 404, 409). */
        public final int status;

        ApiException(int status, String message) {
            super(status + " " + message);
            this.status = status;
        }
    }

    /** 연결 시도는 5초까지만 기다린다 (서버가 꺼져 있을 때 오래 멈추지 않도록). */
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** 서버 주소와 토큰이 들어 있는 로그인 상태. */
    private final Session session;

    public ApiClient(Session session) {
        this.session = session;
    }

    // --- 인증 / 기기 ---

    /** 회원가입. 실패하면(이메일 중복 409 등) ApiException. */
    public void signup(String email, String password) {
        send("POST", "/api/auth/signup", Map.of("email", email, "password", password), null);
    }

    /**
     * 로그인 + 현재 PC를 기기로 등록. 성공하면 기기 전용 토큰을 Session에 저장한다.
     *
     * <ol>
     *   <li>로그인 → 잠깐 쓰는 user 토큰을 받는다.</li>
     *   <li>그 토큰으로 기기 등록 → 이 PC 전용 device 토큰 쌍(access + refresh)을 받는다.</li>
     *   <li>device 토큰을 저장한다. 이후 모든 요청은 이 토큰으로 한다. (user 토큰은 버린다)</li>
     * </ol>
     */
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

    /**
     * refresh 토큰으로 새 토큰 쌍을 받는다. (서버가 refresh 토큰도 매번 새 것으로 바꿔 준다)
     *
     * <p>synchronized인 이유: 두 요청이 동시에 401을 받아 동시에 갱신하면, 먼저 갱신한 쪽이 refresh 토큰을 바꿔 버려서
     * 나중 쪽은 이미 폐기된 옛 토큰으로 요청하게 되고 실패한다. 한 번에 하나만 갱신하도록 막는다.</p>
     *
     * @return 성공하면 true. refresh 토큰이 거부되면(만료, 원격 로그아웃 등) false → 다시 로그인 필요
     */
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
            throw e; // 서버 오류(5xx) 등은 "로그인 필요"가 아니므로 그대로 던진다
        }
    }

    /** 내 기기 목록. */
    public JsonNode listDevices() { return authed("GET", "/api/devices", null); }

    /** 기기 원격 로그아웃. */
    public void deleteDevice(String id) { authed("DELETE", "/api/devices/" + enc(id), null); }

    // --- 클립 ---

    /** 최근 클립 목록 (최신순, 최대 limit개). */
    public JsonNode listClips(int limit) { return authed("GET", "/api/clips?limit=" + limit, null); }

    /** 클립 업로드. */
    public void postClip(String content) { authed("POST", "/api/clips", Map.of("content", content)); }

    /** 클립 한 건 삭제. */
    public void deleteClip(String id) { authed("DELETE", "/api/clips/" + enc(id), null); }

    /** 이미지 업로드 (PNG 바이트). 큰 파일이라 60초까지 기다린다. */
    public JsonNode postImage(byte[] png) {
        return authed(t -> json(exchange("POST", "/api/clips/image",
                HttpRequest.BodyPublishers.ofByteArray(png), "image/png", t, LONG)));
    }

    /** 이미지 클립의 원본 PNG. */
    public byte[] getImage(String id) {
        return authed(t -> exchange("GET", "/api/clips/" + enc(id) + "/image", HttpRequest.BodyPublishers.noBody(), null, t, LONG));
    }

    /** 이미지 클립의 썸네일 PNG (긴 변 최대 240px). */
    public byte[] getThumbnail(String id) {
        return authed(t -> exchange("GET", "/api/clips/" + enc(id) + "/thumbnail", HttpRequest.BodyPublishers.noBody(), null, t, SHORT));
    }

    // --- 내부 동작 ---

    /** 일반 요청 제한 시간 */
    private static final Duration SHORT = Duration.ofSeconds(15);
    /** 이미지 업로드/다운로드 제한 시간 */
    private static final Duration LONG = Duration.ofSeconds(60);

    /** 토큰이 필요한 JSON 요청. */
    private JsonNode authed(String method, String path, Object body) {
        return authed(t -> send(method, path, body, t));
    }

    /**
     * 토큰이 필요한 요청. 401을 받으면 토큰을 갱신하고 한 번만 다시 시도한다.
     *
     * @param call 토큰을 받아 실제 요청을 보내는 함수
     */
    private <T> T authed(Function<String, T> call) {
        String token = session.accessToken;
        try {
            return call.apply(token);
        } catch (ApiException e) {
            if (e.status != 401) throw e;
            synchronized (this) {
                // 기다리는 동안 다른 스레드가 이미 갱신했을 수 있다. 토큰이 그대로일 때만 갱신한다.
                // 갱신이 실패하면(로그인 필요) 원래의 401 예외를 그대로 던진다.
                if (Objects.equals(token, session.accessToken) && !refresh()) throw e;
            }
            return call.apply(session.accessToken);
        }
    }

    /** JSON 요청을 보내고 JSON 응답을 돌려준다. body가 null이면 본문 없음. */
    private JsonNode send(String method, String path, Object body, String token) {
        try {
            HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
            return json(exchange(method, path, pub, body == null ? null : "application/json", token, SHORT));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 실제 HTTP 요청. 응답 본문을 바이트로 돌려준다.
     *
     * @param contentType 본문 형식. null이면 Content-Type 헤더 없음
     * @param token       넣을 access 토큰. null이면 Authorization 헤더 없음
     * @throws ApiException 2xx가 아닌 응답. 서버가 준 에러 메시지({"message": ...})를 담는다
     */
    private byte[] exchange(String method, String path, HttpRequest.BodyPublisher body, String contentType,
                            String token, Duration timeout) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(session.server + path))
                    .timeout(timeout)
                    .method(method, body);
            if (contentType != null) b.header("Content-Type", contentType);
            if (token != null) b.header("Authorization", "Bearer " + token);
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) {
                // 서버의 에러 JSON에서 message만 꺼낸다. JSON이 아니면 본문 전체를 메시지로 쓴다.
                String text = new String(res.body(), StandardCharsets.UTF_8);
                String msg = text;
                try { msg = JSON.readTree(text).path("message").asText(text); } catch (IOException ignored) { }
                throw new ApiException(res.statusCode(), msg);
            }
            return res.body();
        } catch (IOException e) {
            // 네트워크 오류(서버 꺼짐, 연결 끊김 등)
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            // 스레드 중단 요청을 받으면 중단 표시를 복구하고 빠져나간다 (자바 관례)
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 응답 바이트를 JSON으로. 비어 있으면(204 등) JSON null 노드. */
    private static JsonNode json(byte[] body) {
        try {
            return body.length == 0 ? JSON.nullNode() : JSON.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** URL 경로에 넣을 값을 안전하게 인코딩한다. */
    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
