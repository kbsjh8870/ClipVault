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

/**
 * 클립 API 통합 테스트: 업로드, 중복 처리, 목록, 삭제, 저장 시 암호화, 만료(TTL) 삭제.
 *
 * <p>일부 검증은 API가 아니라 DB를 직접 SQL로 조회한다(JdbcTemplate).
 * 예를 들어 "DB에는 정말 암호문으로 저장됐는가"는 API로는 확인할 수 없기 때문이다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClipApiTest {
    /** 테스트 설정(application.yml)에 넣은 것과 같은 암호화 키. DB의 암호문을 직접 복호화해 볼 때 쓴다. */
    static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Autowired MockMvc mvc;
    @Autowired ClipRepository clipRepository;
    @Autowired ClipCleanupJob cleanupJob;
    /** DB에 SQL을 직접 실행하는 도구 */
    @Autowired JdbcTemplate jdbc;
    Api api;
    /** 각 테스트에서 쓸 기본 사용자 + 기기 */
    Api.DeviceTokens dev;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    /** 클립 테이블 이름을 DB 스키마에서 찾아온다. (content_hash 컬럼을 가진 테이블) → 엔티티의 @Table 이름이 바뀌어도 테스트가 깨지지 않는다 */
    private String clipTable() {
        return jdbc.queryForObject(
                "select table_name from information_schema.columns where lower(column_name) = 'content_hash'",
                String.class);
    }

    /** DB에 실제로 저장된 content 값(암호문)을 읽는다. */
    private String rawContent(String clipId) {
        return jdbc.queryForObject("select content from " + clipTable() + " where cast(id as varchar) = ?",
                String.class, clipId);
    }

    /** 해당 ID의 행이 DB에 몇 개 있는지 (0 또는 1). */
    private int rowCount(String clipId) {
        return jdbc.queryForObject("select count(*) from " + clipTable() + " where cast(id as varchar) = ?",
                Integer.class, clipId);
    }

    /** 7일을 기다릴 수 없으니, 만료 시각을 DB에서 직접 1시간 전으로 바꿔 "이미 만료된 클립"으로 만든다. */
    private void expire(String clipId) {
        int n = jdbc.update("update " + clipTable() + " set expires_at = ? where cast(id as varchar) = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), clipId);
        assertEquals(1, n);
    }

    /** 새 클립 업로드 → 201. 해시, 올린 기기, 만료 시각(생성 + 7일)이 맞는지 */
    @Test
    void createReturns201WithClipResponse() throws Exception {
        String content = "hello clip";
        String body = api.createClip(dev.accessToken(), content, 201);

        assertEquals(content, read(body, "$.content"));
        assertEquals(HashUtil.sha256(content), read(body, "$.contentHash"));
        assertEquals(dev.deviceId(), read(body, "$.sourceDeviceId"));
        Instant createdAt = Instant.parse(read(body, "$.createdAt"));
        Instant expiresAt = Instant.parse(read(body, "$.expiresAt"));
        // 만료 - 생성 = 7일(초 단위), 오차 2초 허용
        assertEquals(7 * 24 * 3600, expiresAt.getEpochSecond() - createdAt.getEpochSecond(), 2);
        assertNotNull(read(body, "$.id"));
        assertEquals("TEXT", read(body, "$.type"));
        assertNull(read(body, "$.width"));
    }

    /**
     * 가장 최근 클립과 같은 내용을 다시 올리면: 200, 같은 ID, 새 행 없음, 시각과 올린 기기만 갱신.
     * 다른 내용을 올리면 새 클립(201).
     */
    @Test
    void sameContentAsLatestUpdatesInsteadOfInserting() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        Api.Tokens user = api.login(email);
        dev = api.registerDevice(user, "PC-1");
        Api.DeviceTokens other = api.registerDevice(user, "PC-2");

        String first = api.createClip(dev.accessToken(), "dup", 201);
        long countAfterFirst = clipRepository.count();
        Thread.sleep(50); // 시각이 확실히 달라지도록 잠깐 대기

        // 같은 사용자의 다른 기기(PC-2)가 같은 텍스트를 다시 복사
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

    /** 중복 판단은 "가장 최근 1건"과만 한다: A → B → A 순서면 마지막 A는 새 클립(201) */
    @Test
    void duplicateOnlyComparesWithMostRecentClip() throws Exception {
        api.createClip(dev.accessToken(), "A", 201);
        api.createClip(dev.accessToken(), "B", 201);
        api.createClip(dev.accessToken(), "A", 201); // 최근 클립은 B라서 A는 새로 만들어진다
    }

    /** 목록은 최신순, 다른 사용자 클립은 안 보임, limit이 0 이하이면 1로 보정 */
    @Test
    void listIsNewestFirstAndLimitIsClamped() throws Exception {
        api.createClip(dev.accessToken(), "c1", 201);
        Thread.sleep(5);
        api.createClip(dev.accessToken(), "c2", 201);
        Thread.sleep(5);
        api.createClip(dev.accessToken(), "c3", 201);
        api.newUserWithDevice(); // 다른 사용자 (이 사람의 클립은 보이면 안 됨)

        api.get("/api/clips", dev.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].content", contains("c3", "c2", "c1")));
        api.get("/api/clips?limit=2", dev.accessToken()).andExpect(jsonPath("$", hasSize(2)));
        api.get("/api/clips?limit=0", dev.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        api.get("/api/clips?limit=-5", dev.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    /** limit 상한은 100, 지정하지 않으면 기본 20개 */
    @Test
    void limitUpperClampAndDefault() throws Exception {
        for (int i = 0; i < 101; i++) api.createClip(dev.accessToken(), "clip-" + i, 201);
        api.get("/api/clips?limit=1000", dev.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(100)));
        api.get("/api/clips", dev.accessToken()).andExpect(jsonPath("$", hasSize(20)));
    }

    /** 공백만 있는 내용 → 400, 10만 자 초과 → 400, 딱 10만 자는 허용 */
    @Test
    void contentValidation() throws Exception {
        api.post("/api/clips", dev.accessToken(), json("content", "   ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        api.post("/api/clips", dev.accessToken(), json("content", "x".repeat(100_001))).andExpect(status().isBadRequest());
        api.createClip(dev.accessToken(), "x".repeat(100_000), 201);
    }

    /** 다른 사람의 클립 삭제 → 404(삭제 안 됨), 내 클립 삭제 → 204(실제로 삭제됨) */
    @Test
    void deleteOwnClip204OthersClip404() throws Exception {
        String id = read(api.createClip(dev.accessToken(), "mine", 201), "$.id");
        Api.DeviceTokens stranger = api.newUserWithDevice();

        api.delete("/api/clips/" + id, stranger.accessToken()).andExpect(status().isNotFound());
        assertEquals(1, rowCount(id));

        api.delete("/api/clips/" + id, dev.accessToken()).andExpect(status().isNoContent());
        assertEquals(0, rowCount(id));
    }

    /** DB에는 평문이 아니라 암호문이 저장되고(설정된 키로 복호화 가능), API 응답은 평문이어야 한다 */
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

    /** 이미지 기능 이전에 저장된 행(type 컬럼이 비어 있음)은 TEXT로 응답해야 한다 */
    @Test
    void legacyRowWithoutTypeIsText() throws Exception {
        String id = read(api.createClip(dev.accessToken(), "old row", 201), "$.id");
        jdbc.update("update " + clipTable() + " set type = null where cast(id as varchar) = ?", id);
        api.get("/api/clips", dev.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].type").value("TEXT"));
    }

    /** 만료된 클립은 목록에서 숨겨지고, 정리 배치는 만료된 것만 삭제하고 삭제 건수를 돌려준다 */
    @Test
    void expiredClipsAreHiddenAndCleanupDeletesOnlyThem() throws Exception {
        cleanupJob.deleteExpired(); // 다른 테스트가 남긴 만료 클립을 먼저 정리 (건수를 정확히 세기 위해)
        String expired = read(api.createClip(dev.accessToken(), "old", 201), "$.id");
        String alive = read(api.createClip(dev.accessToken(), "new", 201), "$.id");
        expire(expired);

        // 배치가 돌기 전에도 목록에서는 이미 안 보인다
        api.get("/api/clips", dev.accessToken())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(alive));

        assertEquals(1, cleanupJob.deleteExpired());
        assertEquals(0, rowCount(expired));
        assertEquals(1, rowCount(alive));
        assertEquals(0, cleanupJob.deleteExpired()); // 두 번째 실행에서는 지울 게 없다
    }
}
