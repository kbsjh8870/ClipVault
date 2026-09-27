package com.clipvault.clip;

import com.clipvault.Api;
import com.clipvault.storage.ImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static com.clipvault.Api.read;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이미지 고정 테스트.
 *
 * <p>버킷 수명 주기 규칙은 객체를 만든 지 31일 뒤에 지운다. 그래서 고정한 이미지는 객체를 다시 써서(생성 시각 갱신)
 * 살려 둔다: 고정할 때, 매일 정리 배치 때, 해제할 때(해제 후 보관 기간만큼, 최대 30일 더 보이므로). 저장소는 실제 로컬 저장소를
 * 감시(spy)해서 put 호출을 확인한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImagePinTest {
    @Autowired MockMvc mvc;
    @Autowired ClipCleanupJob cleanupJob;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ImageStore store;
    Api api;
    Api.DeviceTokens dev;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    /** 이미지를 올리고 [클립 id, 버킷 키]를 돌려준다. */
    private String[] image(int rgb) throws Exception {
        String body = api.postImage(dev.accessToken(), Api.png(12, 12, rgb))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = read(body, "$.id");
        String key = jdbc.queryForObject("select image_key from clips where cast(id as varchar) = ?", String.class, id);
        return new String[]{id, key};
    }

    private void verifyRenewed(String key) {
        verify(store).put(eq("images/" + key), any());
        verify(store).put(eq("thumbs/" + key), any());
    }

    /** 고정 → 204, 그 자리에서 두 객체를 다시 쓴다 (오래된 이미지를 고정해도 곧 지워지지 않게) */
    @Test
    void pinningImageRenewsObjects() throws Exception {
        String[] img = image(0x112233);
        clearInvocations(store);
        api.put("/api/clips/" + img[0] + "/pin", dev.accessToken()).andExpect(status().isNoContent());
        verifyRenewed(img[1]);
    }

    /** 매일 배치: 고정한 이미지만 다시 쓴다 */
    @Test
    void dailyJobRenewsOnlyPinnedImages() throws Exception {
        String[] pinned = image(0x223344);
        String[] normal = image(0x334455);
        api.put("/api/clips/" + pinned[0] + "/pin", dev.accessToken()).andExpect(status().isNoContent());
        clearInvocations(store);

        assertTrue(cleanupJob.renewPinnedImages() >= 1);
        verifyRenewed(pinned[1]);
        verify(store, never()).put(eq("images/" + normal[1]), any());
    }

    /** 해제 → 만료는 지금 + 사용자 보관 기간(30일), 객체도 다시 써서 그동안 살아 있게 */
    @Test
    void unpinningImageRenewsAndUsesUserTtl() throws Exception {
        api.put("/api/settings", dev.accessToken(), "{\"clipTtlDays\":30}").andExpect(status().isNoContent());
        String[] img = image(0x445566);
        api.put("/api/clips/" + img[0] + "/pin", dev.accessToken()).andExpect(status().isNoContent());
        clearInvocations(store);

        api.delete("/api/clips/" + img[0] + "/pin", dev.accessToken()).andExpect(status().isNoContent());
        verifyRenewed(img[1]);
        Instant expires = jdbc.queryForObject("select expires_at from clips where cast(id as varchar) = ?",
                java.sql.Timestamp.class, img[0]).toInstant();
        assertEquals(Instant.now().plus(Duration.ofDays(30)).getEpochSecond(), expires.getEpochSecond(), 5);
    }

    /** 객체가 이미 사라진 이미지는 고정할 수 없다 → 404 (고정 상태로 남지 않음) */
    @Test
    void pinningImageWithMissingObjects404() throws Exception {
        String[] img = image(0x556677);
        store.delete("images/" + img[1]);
        api.put("/api/clips/" + img[0] + "/pin", dev.accessToken()).andExpect(status().isNotFound());
        assertEquals(Boolean.FALSE, jdbc.queryForObject("select pinned from clips where cast(id as varchar) = ?", Boolean.class, img[0]));
    }

    /** 저장소가 죽어 있으면 → 503, 고정되지 않음 */
    @Test
    void pinningImageWhenStorageDown503() throws Exception {
        String[] img = image(0x667788);
        doThrow(new RuntimeException("bucket down")).when(store).get(any());
        try {
            api.put("/api/clips/" + img[0] + "/pin", dev.accessToken()).andExpect(status().isServiceUnavailable());
        } finally {
            reset(store);
        }
        assertEquals(Boolean.FALSE, jdbc.queryForObject("select pinned from clips where cast(id as varchar) = ?", Boolean.class, img[0]));
    }
}
