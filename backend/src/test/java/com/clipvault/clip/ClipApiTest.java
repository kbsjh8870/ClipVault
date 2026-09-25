package com.clipvault.clip;

import com.clipvault.Api;
import com.clipvault.common.AesCipher;
import com.clipvault.common.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;

import static com.clipvault.Api.json;
import static com.clipvault.Api.read;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ClipApiTest {
    static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Autowired MockMvc mvc;
    @Autowired ClipRepository clipRepository;
    @Autowired ClipCleanupJob cleanupJob;
    @Autowired JdbcTemplate jdbc;
    Api api;
    Api.DeviceTokens dev;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    /** Clip table name, resolved from the schema so the test doesn't depend on @Table naming. */
    private String clipTable() {
        return jdbc.queryForObject(
                "select table_name from information_schema.columns where lower(column_name) = 'content_hash'",
                String.class);
    }

    private String rawContent(String clipId) {
        return jdbc.queryForObject("select content from " + clipTable() + " where cast(id as varchar) = ?",
                String.class, clipId);
    }

    private int rowCount(String clipId) {
        return jdbc.queryForObject("select count(*) from " + clipTable() + " where cast(id as varchar) = ?",
                Integer.class, clipId);
    }

    private void expire(String clipId) {
        int n = jdbc.update("update " + clipTable() + " set expires_at = ? where cast(id as varchar) = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), clipId);
        assertEquals(1, n);
    }

    @Test
    void createReturns201WithClipResponse() throws Exception {
        String content = "hello clip";
        String body = api.createClip(dev.accessToken(), content, 201);

        assertEquals(content, read(body, "$.content"));
        assertEquals(HashUtil.sha256(content), read(body, "$.contentHash"));
        assertEquals(dev.deviceId(), read(body, "$.sourceDeviceId"));
        Instant createdAt = Instant.parse(read(body, "$.createdAt"));
        Instant expiresAt = Instant.parse(read(body, "$.expiresAt"));
        assertEquals(7 * 24 * 3600, expiresAt.getEpochSecond() - createdAt.getEpochSecond(), 2);
        assertNotNull(read(body, "$.id"));
    }

    @Test
    void sameContentAsLatestUpdatesInsteadOfInserting() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        Api.Tokens user = api.login(email);
        dev = api.registerDevice(user, "PC-1");
        Api.DeviceTokens other = api.registerDevice(user, "PC-2");

        String first = api.createClip(dev.accessToken(), "dup", 201);
        long countAfterFirst = clipRepository.count();
        Thread.sleep(50);

        // second device of same user re-copies the same text
        String second = api.createClip(other.accessToken(), "dup", 200);

        assertEquals(read(first, "$.id").toString(), read(second, "$.id").toString());
        assertEquals(countAfterFirst, clipRepository.count(), "no new row on duplicate");
        assertTrue(Instant.parse(read(second, "$.createdAt")).isAfter(Instant.parse(read(first, "$.createdAt"))));
        assertTrue(Instant.parse(read(second, "$.expiresAt")).isAfter(Instant.parse(read(first, "$.expiresAt"))));
        assertEquals(other.deviceId(), read(second, "$.sourceDeviceId"));

        String third = api.createClip(dev.accessToken(), "different", 201);
        assertNotEquals(read(first, "$.id").toString(), read(third, "$.id").toString());
        assertEquals(countAfterFirst + 1, clipRepository.count());
    }

    @Test
    void duplicateOnlyComparesWithMostRecentClip() throws Exception {
        api.createClip(dev.accessToken(), "A", 201);
        api.createClip(dev.accessToken(), "B", 201);
        api.createClip(dev.accessToken(), "A", 201); // A is not the latest anymore → new record
    }

    @Test
    void listIsNewestFirstAndLimitIsClamped() throws Exception {
        api.createClip(dev.accessToken(), "c1", 201);
        Thread.sleep(5);
        api.createClip(dev.accessToken(), "c2", 201);
        Thread.sleep(5);
        api.createClip(dev.accessToken(), "c3", 201);
        api.newUserWithDevice(); // another user, no clips visible

        api.get("/api/clips", dev.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].content", contains("c3", "c2", "c1")));
        api.get("/api/clips?limit=2", dev.accessToken()).andExpect(jsonPath("$", hasSize(2)));
        api.get("/api/clips?limit=0", dev.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        api.get("/api/clips?limit=-5", dev.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void limitUpperClampAndDefault() throws Exception {
        for (int i = 0; i < 101; i++) api.createClip(dev.accessToken(), "clip-" + i, 201);
        api.get("/api/clips?limit=1000", dev.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(100)));
        api.get("/api/clips", dev.accessToken()).andExpect(jsonPath("$", hasSize(20)));
    }

    @Test
    void contentValidation() throws Exception {
        api.post("/api/clips", dev.accessToken(), json("content", "   ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        api.post("/api/clips", dev.accessToken(), json("content", "x".repeat(100_001))).andExpect(status().isBadRequest());
        api.createClip(dev.accessToken(), "x".repeat(100_000), 201);
    }

    @Test
    void deleteOwnClip204OthersClip404() throws Exception {
        String id = read(api.createClip(dev.accessToken(), "mine", 201), "$.id");
        Api.DeviceTokens stranger = api.newUserWithDevice();

        api.delete("/api/clips/" + id, stranger.accessToken()).andExpect(status().isNotFound());
        assertEquals(1, rowCount(id));

        api.delete("/api/clips/" + id, dev.accessToken()).andExpect(status().isNoContent());
        assertEquals(0, rowCount(id));
    }

    @Test
    void contentIsEncryptedAtRestButPlainInApi() throws Exception {
        String plain = "secret password 1234";
        String id = read(api.createClip(dev.accessToken(), plain, 201), "$.id");

        String raw = rawContent(id);
        assertNotEquals(plain, raw);
        assertFalse(raw.contains(plain));
        assertEquals(plain, new AesCipher(KEY).decrypt(raw), "DB value must be AES-GCM(base64) with the configured key");

        api.get("/api/clips", dev.accessToken()).andExpect(jsonPath("$[0].content").value(plain));
    }

    @Test
    void expiredClipsAreHiddenAndCleanupDeletesOnlyThem() throws Exception {
        cleanupJob.deleteExpired(); // clear leftovers from other tests
        String expired = read(api.createClip(dev.accessToken(), "old", 201), "$.id");
        String alive = read(api.createClip(dev.accessToken(), "new", 201), "$.id");
        expire(expired);

        api.get("/api/clips", dev.accessToken())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(alive));

        assertEquals(1, cleanupJob.deleteExpired());
        assertEquals(0, rowCount(expired));
        assertEquals(1, rowCount(alive));
        assertEquals(0, cleanupJob.deleteExpired());
    }
}
