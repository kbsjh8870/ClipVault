package com.clipvault.settings;

import com.clipvault.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static com.clipvault.Api.read;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자별 보관 기간 설정 통합 테스트.
 *
 * <p>허용 값은 1·3·7·30일(기본 7일). 바꾸면 새 클립뿐 아니라 기존 클립의 만료 시각도 "생성 시각 + 새 기간"으로
 * 다시 계산한다(고정 클립 제외). 이미지는 버킷 수명 주기 규칙 때문에 최대 7일.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettingsApiTest {
    @Autowired MockMvc mvc;
    Api api;
    Api.DeviceTokens dev;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    private void setTtl(int days, int expectedStatus) throws Exception {
        api.put("/api/settings", dev.accessToken(), "{\"clipTtlDays\":" + days + "}").andExpect(status().is(expectedStatus));
    }

    /** 만료 - 생성 (초) */
    private static long life(String clipJson) {
        return Instant.parse(read(clipJson, "$.expiresAt")).getEpochSecond()
                - Instant.parse(read(clipJson, "$.createdAt")).getEpochSecond();
    }

    /** 목록에서 id 클립의 (만료 - 생성) 초 */
    private long lifeOf(String id) throws Exception {
        String body = api.get("/api/clips?limit=100", dev.accessToken()).andReturn().getResponse().getContentAsString();
        java.util.List<String> created = read(body, "$[?(@.id=='" + id + "')].createdAt");
        java.util.List<String> expires = read(body, "$[?(@.id=='" + id + "')].expiresAt");
        return Instant.parse(expires.get(0)).getEpochSecond() - Instant.parse(created.get(0)).getEpochSecond();
    }

    @Test
    void defaultIsSevenDays() throws Exception {
        api.get("/api/settings", dev.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$.clipTtlDays").value(7));
    }

    /** 1·3·7·30만 허용, 그 밖의 값은 400 (설정은 그대로) */
    @Test
    void onlyAllowedValues() throws Exception {
        for (int d : new int[]{1, 3, 30, 7}) setTtl(d, 204);
        for (int d : new int[]{0, 2, 14, 365, -1}) setTtl(d, 400);
        api.get("/api/settings", dev.accessToken()).andExpect(jsonPath("$.clipTtlDays").value(7));
    }

    /** 바꾼 뒤 새 클립은 새 기간으로 만료된다 */
    @Test
    void newClipsUseUserTtl() throws Exception {
        setTtl(30, 204);
        assertEquals(Duration.ofDays(30).toSeconds(), life(api.createClip(dev.accessToken(), "long", 201)), 2);
        setTtl(1, 204);
        assertEquals(Duration.ofDays(1).toSeconds(), life(api.createClip(dev.accessToken(), "short", 201)), 2);
    }

    /** 바꾸면 기존 클립도 다시 계산된다. 고정 클립은 그대로 */
    @Test
    void existingClipsAreRetimedExceptPinned() throws Exception {
        String normal = read(api.createClip(dev.accessToken(), "normal", 201), "$.id");
        String pinned = read(api.createClip(dev.accessToken(), "pinned", 201), "$.id");
        api.put("/api/clips/" + pinned + "/pin", dev.accessToken()).andExpect(status().isNoContent());

        setTtl(3, 204);
        assertEquals(Duration.ofDays(3).toSeconds(), lifeOf(normal), 2);
        assertEquals(Duration.ofDays(7).toSeconds(), lifeOf(pinned), 2, "pinned clip keeps its original expiry");
    }

    /** 이미지는 30일로 해도 최대 7일 (새 이미지, 기존 이미지 다시 계산 모두) */
    @Test
    void imagesAreCappedAtSevenDays() throws Exception {
        setTtl(30, 204);
        String img = api.postImage(dev.accessToken(), Api.png(8, 8, 0x445566))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(Duration.ofDays(7).toSeconds(), life(img), 2);

        setTtl(1, 204);
        setTtl(30, 204); // 다시 늘려도 이미지는 7일
        assertEquals(Duration.ofDays(7).toSeconds(), lifeOf(read(img, "$.id")), 2);
    }

    /** 사용자별: 다른 사람 설정은 영향 없음 */
    @Test
    void settingIsPerUser() throws Exception {
        setTtl(1, 204);
        Api.DeviceTokens other = api.newUserWithDevice();
        api.get("/api/settings", other.accessToken()).andExpect(jsonPath("$.clipTtlDays").value(7));
    }
}
