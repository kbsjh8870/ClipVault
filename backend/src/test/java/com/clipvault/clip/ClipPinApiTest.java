package com.clipvault.clip;

import com.clipvault.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;

import static com.clipvault.Api.read;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 즐겨찾기(고정)와 목록 페이지 넘김(before) 통합 테스트.
 *
 * <p>고정한 클립은 만료되지 않고(목록에서 안 빠지고, 정리 배치가 안 지움), 해제하면 지금부터 7일 뒤 만료로 돌아간다.
 * 사용자당 최대 10개, 이미지는 고정할 수 없다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClipPinApiTest {
    @Autowired MockMvc mvc;
    @Autowired ClipCleanupJob cleanupJob;
    @Autowired JdbcTemplate jdbc;
    Api api;
    Api.DeviceTokens dev;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    private String clip(String content) throws Exception {
        return read(api.createClip(dev.accessToken(), content, 201), "$.id");
    }

    /** 만료 시각을 DB에서 직접 1시간 전으로 바꾼다 (7일을 기다릴 수 없으니). */
    private void expire(String clipId) {
        String table = jdbc.queryForObject(
                "select table_name from information_schema.columns where lower(column_name) = 'content_hash'", String.class);
        assertEquals(1, jdbc.update("update " + table + " set expires_at = ? where cast(id as varchar) = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), clipId));
    }

    /** 고정 → 204, 목록과 고정 목록에 pinned=true. 만료 시각이 지나도 목록에 남고 정리 배치가 지우지 않는다 */
    @Test
    void pinnedClipNeverExpires() throws Exception {
        String id = clip("keep me");
        api.put("/api/clips/" + id + "/pin", dev.accessToken()).andExpect(status().isNoContent());
        expire(id);

        cleanupJob.deleteExpired();
        api.get("/api/clips", dev.accessToken())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].pinned").value(true));
        api.get("/api/clips?pinned=true", dev.accessToken())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id));
    }

    /** 해제 → 204, pinned=false, 만료 시각이 지금부터 7일 뒤로 다시 잡힌다 (오래된 클립이 바로 사라지지 않게) */
    @Test
    void unpinResetsExpiry() throws Exception {
        String id = clip("was pinned");
        api.put("/api/clips/" + id + "/pin", dev.accessToken()).andExpect(status().isNoContent());
        expire(id);
        api.delete("/api/clips/" + id + "/pin", dev.accessToken()).andExpect(status().isNoContent());

        String body = api.get("/api/clips", dev.accessToken()).andReturn().getResponse().getContentAsString();
        assertEquals(id, read(body, "$[0].id"));
        assertEquals(false, read(body, "$[0].pinned"));
        long left = Instant.parse(read(body, "$[0].expiresAt")).getEpochSecond() - Instant.now().getEpochSecond();
        assertEquals(7 * 24 * 3600, left, 5);
        api.get("/api/clips?pinned=true", dev.accessToken()).andExpect(jsonPath("$", hasSize(0)));
    }

    /** 사용자당 10개까지. 11번째는 409. 이미 고정한 클립을 다시 고정하는 건 개수에 안 들어간다(204) */
    @Test
    void atMostTenPins() throws Exception {
        String first = null;
        for (int i = 0; i < 10; i++) {
            String id = clip("pin-" + i);
            if (first == null) first = id;
            api.put("/api/clips/" + id + "/pin", dev.accessToken()).andExpect(status().isNoContent());
        }
        api.put("/api/clips/" + clip("eleventh") + "/pin", dev.accessToken()).andExpect(status().isConflict());
        api.put("/api/clips/" + first + "/pin", dev.accessToken()).andExpect(status().isNoContent());
    }

    /** 이미지 클립은 고정 불가 (버킷 수명 주기 규칙이 파일을 지우므로) → 400 */
    @Test
    void imageCannotBePinned() throws Exception {
        String body = api.postImage(dev.accessToken(), Api.png(10, 10, 0x123456))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        api.put("/api/clips/" + read(body, "$.id") + "/pin", dev.accessToken()).andExpect(status().isBadRequest());
    }

    /** 남의 클립 고정/해제 → 404 */
    @Test
    void othersClip404() throws Exception {
        String id = clip("mine");
        Api.DeviceTokens stranger = api.newUserWithDevice();
        api.put("/api/clips/" + id + "/pin", stranger.accessToken()).andExpect(status().isNotFound());
        api.delete("/api/clips/" + id + "/pin", stranger.accessToken()).andExpect(status().isNotFound());
    }

    /** before=마지막 항목의 createdAt 을 주면 그보다 오래된 것만 이어서 나온다 (더 보기) */
    @Test
    void beforePagesOlderClips() throws Exception {
        for (int i = 1; i <= 5; i++) {
            clip("c" + i);
            Thread.sleep(5);
        }
        String page1 = api.get("/api/clips?limit=2", dev.accessToken())
                .andExpect(jsonPath("$[*].content", contains("c5", "c4")))
                .andReturn().getResponse().getContentAsString();
        String cursor = read(page1, "$[1].createdAt"); // MockMvc가 URL 인코딩을 해 준다
        api.get("/api/clips?limit=2&before=" + cursor, dev.accessToken())
                .andExpect(jsonPath("$[*].content", contains("c3", "c2")));
    }
}
