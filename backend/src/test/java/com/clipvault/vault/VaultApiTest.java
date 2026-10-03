package com.clipvault.vault;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 볼트 API 통합 테스트. 서버는 감싼 볼트 키를 내용을 모른 채 보관만 한다(풀 수 없음).
 * 값은 형식만 맞으면 되므로 테스트에서는 아무 바이트나 base64로 만들어 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VaultApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImageStore store;
    Api api;
    Api.DeviceTokens dev;

    static final String SALT = b64(16, 1);
    static final String KEY = b64(60, 2);

    static String b64(int n, int fill) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) fill);
        return Base64.getEncoder().encodeToString(b);
    }

    static String body(String salt, int iterations, String key, Integer version) {
        return "{\"salt\":\"" + salt + "\",\"iterations\":" + iterations + ",\"wrappedKey\":\"" + key + "\""
                + (version == null ? "" : ",\"version\":" + version) + "}";
    }

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    @Test
    void createThenGet() throws Exception {
        api.get("/api/vault", dev.accessToken()).andExpect(status().isNotFound());
        api.post("/api/vault", dev.accessToken(), body(SALT, 600_000, KEY, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(1));
        api.get("/api/vault", dev.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.salt").value(SALT))
                .andExpect(jsonPath("$.iterations").value(600_000))
                .andExpect(jsonPath("$.wrappedKey").value(KEY))
                .andExpect(jsonPath("$.version").value(1));
        api.post("/api/vault", dev.accessToken(), body(SALT, 600_000, KEY, null)).andExpect(status().isConflict());
    }

    /** 암호 변경 = 다시 감싸기. version이 현재와 같아야 한다 (다른 PC의 초기화와 엇갈리지 않게) */
    @Test
    void changeRequiresCurrentVersion() throws Exception {
        api.put("/api/vault", dev.accessToken(), body(SALT, 600_000, KEY, 1)).andExpect(status().isNotFound());
        api.post("/api/vault", dev.accessToken(), body(SALT, 600_000, KEY, null)).andExpect(status().isCreated());
        String newKey = b64(60, 3);
        api.put("/api/vault", dev.accessToken(), body(SALT, 600_000, newKey, 2)).andExpect(status().isConflict());
        api.put("/api/vault", dev.accessToken(), body(SALT, 700_000, newKey, 1)).andExpect(status().isNoContent());
        api.get("/api/vault", dev.accessToken())
                .andExpect(jsonPath("$.wrappedKey").value(newKey))
                .andExpect(jsonPath("$.iterations").value(700_000))
                .andExpect(jsonPath("$.version").value(1));
    }

    /** 초기화: 그 사용자의 클립 행과 버킷 객체를 모두 지우고 version +1 */
    @Test
    void resetDeletesClipsAndBumpsVersion() throws Exception {
        api.createClip(dev.accessToken(), "old text", 201);
        String img = api.postImage(dev.accessToken(), Api.png(6, 6, 0x101010))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String key = jdbc.queryForObject("select image_key from clips where cast(id as varchar) = ?", String.class,
                read(img, "$.id"));
        Api.DeviceTokens other = api.newUserWithDevice();
        api.createClip(other.accessToken(), "someone else", 201);
        api.post("/api/vault", dev.accessToken(), body(SALT, 600_000, KEY, null)).andExpect(status().isCreated());

        String resetKey = b64(60, 4);
        api.post("/api/vault/reset", dev.accessToken(), body(b64(16, 5), 600_000, resetKey, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        api.get("/api/clips", dev.accessToken()).andExpect(jsonPath("$.length()").value(0));
        assertNull(store.get("images/" + key));
        api.get("/api/clips", other.accessToken()).andExpect(jsonPath("$.length()").value(1));
        api.get("/api/vault", dev.accessToken()).andExpect(jsonPath("$.wrappedKey").value(resetKey))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void validatesInput() throws Exception {
        api.post("/api/vault", dev.accessToken(), body(b64(15, 1), 600_000, KEY, null)).andExpect(status().isBadRequest());
        api.post("/api/vault", dev.accessToken(), body(SALT, 99_999, KEY, null)).andExpect(status().isBadRequest());
        api.post("/api/vault", dev.accessToken(), body(SALT, 600_000, b64(59, 1), null)).andExpect(status().isBadRequest());
        api.post("/api/vault", dev.accessToken(), body("not base64!!", 600_000, KEY, null)).andExpect(status().isBadRequest());
        api.get("/api/vault", dev.accessToken()).andExpect(status().isNotFound());
    }

    /** 볼트는 사용자별: 다른 사용자의 볼트는 보이지 않는다 */
    @Test
    void perUser() throws Exception {
        api.post("/api/vault", dev.accessToken(), body(SALT, 600_000, KEY, null)).andExpect(status().isCreated());
        Api.DeviceTokens other = api.newUserWithDevice();
        api.get("/api/vault", other.accessToken()).andExpect(status().isNotFound());
    }
}
