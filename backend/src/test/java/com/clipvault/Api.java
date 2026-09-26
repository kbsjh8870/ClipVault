package com.clipvault;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 통합 테스트들이 공통으로 쓰는 API 호출 도우미.
 *
 * <p>{@link MockMvc}는 실제 네트워크 없이 스프링 서버 안으로 HTTP 요청을 흉내 내서 보내는 테스트 도구다.
 * 매 테스트마다 "가입 → 로그인 → 기기 등록" 같은 준비 과정을 반복 작성하지 않도록 여기에 모아 두었다.</p>
 */
public class Api {
    /** 테스트용 공통 비밀번호 (8자 이상 조건 충족). */
    public static final String PASSWORD = "password123";

    /** 로그인 결과: 사용자 ID + user accessToken. */
    public record Tokens(String userId, String accessToken) {}
    /** 기기 등록 결과: 사용자 ID, 기기 ID, device 토큰 쌍. */
    public record DeviceTokens(String userId, String deviceId, String accessToken, String refreshToken) {}

    private final MockMvc mvc;

    public Api(MockMvc mvc) { this.mvc = mvc; }

    /** 테스트끼리 이메일이 겹치지 않도록 매번 랜덤 이메일을 만든다. (DB를 테스트 간에 공유하기 때문) */
    public static String uniqueEmail() { return "u" + UUID.randomUUID().toString().substring(0, 8) + "@test.com"; }

    /**
     * 간단한 JSON 문자열 생성기. {@code json("email", "a@b.com", "password", "x")} → {@code {"email":"a@b.com","password":"x"}}.
     * 값 안의 역슬래시와 따옴표는 이스케이프한다.
     */
    public static String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":\"").append(kv[i + 1].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    /** 응답 JSON에서 값 하나를 꺼낸다. 예: {@code read(body, "$.accessToken")} */
    public static <T> T read(String body, String path) { return JsonPath.read(body, path); }

    /** POST 요청. token이 있으면 Authorization 헤더를 붙인다. */
    public ResultActions post(String url, String token, String body) throws Exception {
        var req = MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    /** GET 요청. */
    public ResultActions get(String url, String token) throws Exception {
        var req = MockMvcRequestBuilders.get(url);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    /** DELETE 요청. */
    public ResultActions delete(String url, String token) throws Exception {
        var req = MockMvcRequestBuilders.delete(url);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    /** 회원가입 (201이 아니면 테스트 실패). */
    public void signup(String email) throws Exception {
        post("/api/auth/signup", null, json("email", email, "password", PASSWORD)).andExpect(status().isCreated());
    }

    /** 로그인해서 user 토큰을 받는다. */
    public Tokens login(String email) throws Exception {
        String body = post("/api/auth/login", null, json("email", email, "password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Tokens(read(body, "$.userId"), read(body, "$.accessToken"));
    }

    /** user 토큰으로 기기를 등록해서 device 토큰을 받는다. */
    public DeviceTokens registerDevice(Tokens user, String name) throws Exception {
        String body = post("/api/devices", user.accessToken(), json("deviceName", name, "os", "Windows 11"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new DeviceTokens(user.userId(), read(body, "$.id"), read(body, "$.accessToken"), read(body, "$.refreshToken"));
    }

    /** 가입 + 로그인 + 기기 1대 등록을 한 번에. 가장 흔한 준비 과정. */
    public DeviceTokens newUserWithDevice() throws Exception {
        String email = uniqueEmail();
        signup(email);
        return registerDevice(login(email), "PC-1");
    }

    /** 클립을 올리고 응답 본문을 돌려준다. 상태 코드가 expectedStatus(201 신규 / 200 중복)가 아니면 실패. */
    public String createClip(String deviceToken, String content, int expectedStatus) throws Exception {
        return post("/api/clips", deviceToken, json("content", content))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
    }

    /** 이미지 업로드 (본문 = PNG 바이트, Content-Type: image/png). */
    public ResultActions postImage(String token, byte[] png) throws Exception {
        var req = MockMvcRequestBuilders.post("/api/clips/image").contentType(MediaType.IMAGE_PNG).content(png);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    /** 한 가지 색으로 칠한 w×h PNG를 만든다. 색이 다르면 다른 이미지(다른 해시). */
    public static byte[] png(int w, int h, int rgb) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, rgb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
