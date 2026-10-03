package com.clipvault.clip;

import com.clipvault.Api;
import com.clipvault.storage.ImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static com.clipvault.Api.read;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * e2e 업로드·조회 통합 테스트. 서버는 e2e 클립을 받은 그대로 저장하고 그대로 돌려준다(복호화·가공 없음).
 * 헤더 규칙: 없음+볼트 없음 = 옛 방식, 없음+볼트 있음 = 426, 버전 불일치 = 409.
 */
@SpringBootTest
@AutoConfigureMockMvc
class E2eClipApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImageStore store;
    Api api;
    Api.DeviceTokens dev;

    static final String CT = Base64.getEncoder().encodeToString(Api.random(40));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    @Test
    void headerRules() throws Exception {
        // 볼트 없음 + 헤더 없음 = 옛 방식 그대로 (구버전 앱)
        api.createClip(dev.accessToken(), "legacy ok", 201);
        // 볼트 없음 + 헤더 있음 = 409
        api.postE2eText(dev.accessToken(), 1, CT, Api.hash('a')).andExpect(status().isConflict());
        int v = api.createVault(dev.accessToken());
        // 볼트 있음 + 헤더 없음 = 426 (텍스트, 이미지 모두)
        api.post("/api/clips", dev.accessToken(), Api.json("content", "plain")).andExpect(status().is(426));
        api.postImage(dev.accessToken(), Api.png(4, 4, 0x111111)).andExpect(status().is(426));
        // 버전 불일치 = 409
        api.postE2eText(dev.accessToken(), v + 1, CT, Api.hash('a')).andExpect(status().isConflict());
        api.postE2eText(dev.accessToken(), v, CT, Api.hash('a')).andExpect(status().isCreated());
    }

    /** e2e 텍스트: DB에 보낸 값 그대로, 목록에도 그대로 + e2e=true. 같은 해시면 200(중복) */
    @Test
    void textStoredAsIs() throws Exception {
        int v = api.createVault(dev.accessToken());
        String body = api.postE2eText(dev.accessToken(), v, CT, Api.hash('b')).andExpect(status().isCreated())
                .andExpect(jsonPath("$.e2e").value(true)).andExpect(jsonPath("$.content").value(CT))
                .andReturn().getResponse().getContentAsString();
        String id = read(body, "$.id");
        assertEquals(CT, jdbc.queryForObject("select content from clips where cast(id as varchar) = ?", String.class, id));
        api.get("/api/clips", dev.accessToken()).andExpect(jsonPath("$[0].content").value(CT))
                .andExpect(jsonPath("$[0].e2e").value(true)).andExpect(jsonPath("$[0].contentHash").value(Api.hash('b')));
        api.postE2eText(dev.accessToken(), v, CT, Api.hash('b')).andExpect(status().isOk());
    }

    @Test
    void textValidation() throws Exception {
        int v = api.createVault(dev.accessToken());
        api.postE2eText(dev.accessToken(), v, "not base64!!", Api.hash('c')).andExpect(status().isBadRequest());
        api.postE2eText(dev.accessToken(), v, CT, "XYZ").andExpect(status().isBadRequest());
        api.postE2eText(dev.accessToken(), v, CT, Api.hash('C')).andExpect(status().isBadRequest()); // 대문자 hex 거부
        api.postE2eText(dev.accessToken(), v, Base64.getEncoder().encodeToString(Api.random(27)), Api.hash('c'))
                .andExpect(status().isBadRequest()); // IV+태그(28)보다 짧음
        api.postE2eText(dev.accessToken(), v, Base64.getEncoder().encodeToString(Api.random(300_029)), Api.hash('c'))
                .andExpect(status().isBadRequest());
    }

    /** e2e 이미지: 버킷에 보낸 바이트 그대로, /image·/thumbnail도 그대로 */
    @Test
    void imageStoredAsIs() throws Exception {
        int v = api.createVault(dev.accessToken());
        byte[] image = Api.random(5000), thumb = Api.random(700);
        String body = api.postE2eImage(dev.accessToken(), v, image, thumb, 1920, 1080, Api.hash('d'))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.e2e").value(true))
                .andExpect(jsonPath("$.type").value("IMAGE")).andExpect(jsonPath("$.width").value(1920))
                .andReturn().getResponse().getContentAsString();
        String id = read(body, "$.id");
        String key = jdbc.queryForObject("select image_key from clips where cast(id as varchar) = ?", String.class, id);
        assertArrayEquals(image, store.get("images/" + key));
        assertArrayEquals(thumb, store.get("thumbs/" + key));
        api.get("/api/clips/" + id + "/image", dev.accessToken()).andExpect(content().bytes(image));
        api.get("/api/clips/" + id + "/thumbnail", dev.accessToken()).andExpect(content().bytes(thumb));
        // 같은 해시 = 중복 200
        api.postE2eImage(dev.accessToken(), v, image, thumb, 1920, 1080, Api.hash('d')).andExpect(status().isOk());
    }

    @Test
    void imageValidation() throws Exception {
        int v = api.createVault(dev.accessToken());
        api.postE2eImage(dev.accessToken(), v, Api.random(10 * 1024 * 1024 + 29), Api.random(10), 10, 10, Api.hash('e'))
                .andExpect(status().isPayloadTooLarge());
        api.postE2eImage(dev.accessToken(), v, Api.random(100), Api.random(1024 * 1024 + 1), 10, 10, Api.hash('e'))
                .andExpect(status().isPayloadTooLarge());
        api.postE2eImage(dev.accessToken(), v, Api.random(100), Api.random(10), 0, 10, Api.hash('e'))
                .andExpect(status().isBadRequest());
        api.postE2eImage(dev.accessToken(), v, Api.random(100), Api.random(10), 10_000, 10_000, Api.hash('e'))
                .andExpect(status().isBadRequest());
        api.postE2eImage(dev.accessToken(), v, Api.random(100), Api.random(10), 10, 10, "bad")
                .andExpect(status().isBadRequest());
        // 볼트 없는 사용자의 multipart(헤더 없음) = 400 (e2e 전용 경로)
        Api.DeviceTokens fresh = api.newUserWithDevice();
        api.postE2eImage(fresh.accessToken(), null, Api.random(100), Api.random(10), 10, 10, Api.hash('e'))
                .andExpect(status().isBadRequest());
    }
}
