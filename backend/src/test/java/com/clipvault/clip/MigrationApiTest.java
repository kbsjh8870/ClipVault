package com.clipvault.clip;

import com.clipvault.Api;
import com.clipvault.storage.ImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Base64;
import java.util.Map;

import static com.clipvault.Api.read;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기존 데이터 이전 테스트: 볼트 전에 옛 방식으로 올린 클립을 앱이 받아 암호화해서 같은 자리에 덮어쓴다.
 * id, 생성·만료 시각, 고정 여부는 그대로 두고 content·hash만 바꾸고 e2e=true.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MigrationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImageStore store;
    Api api;
    Api.DeviceTokens dev;

    static final String CT = Base64.getEncoder().encodeToString(Api.random(48));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select created_at, expires_at, pinned, e2e, content, content_hash from clips where cast(id as varchar) = ?", id);
    }

    private org.springframework.test.web.servlet.ResultActions putText(String id, int v, String content, String hash) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put("/api/clips/" + id + "/e2e")
                .header("Authorization", "Bearer " + dev.accessToken()).header("X-Vault-Version", v)
                .contentType("application/json").content(Api.json("content", content, "contentHash", hash)));
    }

    private org.springframework.test.web.servlet.ResultActions putImage(String id, int v, byte[] image, byte[] thumb, String hash) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.multipart(HttpMethod.PUT, "/api/clips/" + id + "/e2e")
                .file(new MockMultipartFile("image", "image", "application/octet-stream", image))
                .file(new MockMultipartFile("thumb", "thumb", "application/octet-stream", thumb))
                .param("contentHash", hash)
                .header("Authorization", "Bearer " + dev.accessToken()).header("X-Vault-Version", v));
    }

    /** legacy=true: 옛 행만(평문으로), 고정 포함 */
    @Test
    void legacyListReturnsOnlyOldRows() throws Exception {
        String old = read(api.createClip(dev.accessToken(), "old plain", 201), "$.id");
        api.put("/api/clips/" + old + "/pin", dev.accessToken()).andExpect(status().isNoContent());
        int v = api.createVault(dev.accessToken());
        api.postE2eText(dev.accessToken(), v, CT, Api.hash('1')).andExpect(status().isCreated());
        api.get("/api/clips?legacy=true", dev.accessToken())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(old))
                .andExpect(jsonPath("$[0].content").value("old plain"))
                .andExpect(jsonPath("$[0].e2e").value(false));
    }

    /** 텍스트 이전: 시각·고정 유지, e2e=true, 두 번 보내도 204 (두 번째는 아무것도 안 함) */
    @Test
    void textMigrationKeepsMetadata() throws Exception {
        String id = read(api.createClip(dev.accessToken(), "move me", 201), "$.id");
        api.put("/api/clips/" + id + "/pin", dev.accessToken()).andExpect(status().isNoContent());
        Map<String, Object> before = row(id);
        int v = api.createVault(dev.accessToken());

        putText(id, v, CT, Api.hash('2')).andExpect(status().isNoContent());
        Map<String, Object> after = row(id);
        assertEquals(before.get("created_at"), after.get("created_at"));
        assertEquals(before.get("expires_at"), after.get("expires_at"));
        assertEquals(Boolean.TRUE, after.get("pinned"));
        assertEquals(Boolean.TRUE, after.get("e2e"));
        assertEquals(CT, after.get("content"));
        assertEquals(Api.hash('2'), after.get("content_hash"));

        String other = Base64.getEncoder().encodeToString(Api.random(50));
        putText(id, v, other, Api.hash('3')).andExpect(status().isNoContent());
        assertEquals(CT, row(id).get("content"), "already e2e: second migration is a no-op");
        api.get("/api/clips?legacy=true", dev.accessToken()).andExpect(jsonPath("$.length()").value(0));
    }

    /** 이미지 이전: 같은 버킷 키에 암호문 덮어쓰기, e2e=true */
    @Test
    void imageMigrationOverwritesObjects() throws Exception {
        String id = read(api.postImage(dev.accessToken(), Api.png(8, 8, 0x223344)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        String key = jdbc.queryForObject("select image_key from clips where cast(id as varchar) = ?", String.class, id);
        int v = api.createVault(dev.accessToken());
        byte[] image = Api.random(3000), thumb = Api.random(300);

        putImage(id, v, image, thumb, Api.hash('4')).andExpect(status().isNoContent());
        assertArrayEquals(image, store.get("images/" + key));
        assertArrayEquals(thumb, store.get("thumbs/" + key));
        assertEquals(Boolean.TRUE, row(id).get("e2e"));
        assertEquals(Api.hash('4'), row(id).get("content_hash"));
    }

    @Test
    void rulesAndErrors() throws Exception {
        String text = read(api.createClip(dev.accessToken(), "t", 201), "$.id");
        String img = read(api.postImage(dev.accessToken(), Api.png(4, 4, 0x010203)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        int v = api.createVault(dev.accessToken());
        putText(text, v + 1, CT, Api.hash('5')).andExpect(status().isConflict());   // 버전 불일치
        putText(img, v, CT, Api.hash('5')).andExpect(status().isBadRequest());     // 이미지에 텍스트 형식
        putImage(text, v, Api.random(100), Api.random(50), Api.hash('5')).andExpect(status().isBadRequest()); // 텍스트에 이미지 형식
        putText(text, v, CT, "bad").andExpect(status().isBadRequest());
        Api.DeviceTokens stranger = api.newUserWithDevice();
        int sv = api.createVault(stranger.accessToken());
        mvc.perform(MockMvcRequestBuilders.put("/api/clips/" + text + "/e2e")
                        .header("Authorization", "Bearer " + stranger.accessToken()).header("X-Vault-Version", sv)
                        .contentType("application/json").content(Api.json("content", CT, "contentHash", Api.hash('6'))))
                .andExpect(status().isNotFound());
    }
}
