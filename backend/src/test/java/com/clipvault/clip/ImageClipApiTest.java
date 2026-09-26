package com.clipvault.clip;

import com.clipvault.Api;
import com.clipvault.common.HashUtil;
import com.clipvault.storage.ImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static com.clipvault.Api.png;
import static com.clipvault.Api.read;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 이미지 클립 API 통합 테스트: 업로드, 중복, 목록, 원본/썸네일, 암호화 저장, 권한, 크기·형식 검사. */
@SpringBootTest
@AutoConfigureMockMvc
class ImageClipApiTest {
    @Autowired MockMvc mvc;
    @Autowired ClipRepository clipRepository;
    @Autowired ImageStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClipCleanupJob cleanupJob;
    Api api;
    Api.DeviceTokens dev;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    /** 이미지를 올리고 응답 본문을 돌려준다. */
    private String upload(byte[] png, int expectedStatus) throws Exception {
        return api.postImage(dev.accessToken(), png).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    /** 응답 본문의 바이트 (원본/썸네일 다운로드). */
    private byte[] bytes(String url, String token, int expectedStatus) throws Exception {
        return api.get(url, token).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsByteArray();
    }

    /** 업로드 → 201, 이미지 정보가 채워지고 content는 안내 문구 */
    @Test
    void uploadReturns201WithImageFields() throws Exception {
        byte[] png = png(300, 200, 0xFF0000);
        String body = upload(png, 201);
        assertEquals("IMAGE", read(body, "$.type"));
        assertEquals(300, (int) read(body, "$.width"));
        assertEquals(200, (int) read(body, "$.height"));
        assertEquals(png.length, ((Number) read(body, "$.size")).intValue());
        assertEquals(HashUtil.sha256(png), read(body, "$.contentHash"));
        assertEquals("[이미지 300×200]", read(body, "$.content"));
        assertEquals(dev.deviceId(), read(body, "$.sourceDeviceId"));
    }

    /** 가장 최근 클립과 같은 이미지 → 200, 같은 ID, 새 행 없음 */
    @Test
    void sameImageAsLatestReturns200() throws Exception {
        byte[] png = png(50, 50, 0x00FF00);
        String first = upload(png, 201);
        long count = clipRepository.count();
        String second = upload(png, 200);
        assertEquals(read(first, "$.id").toString(), read(second, "$.id").toString());
        assertEquals(count, clipRepository.count());
    }

    /** 목록에 텍스트와 이미지가 최신순으로 섞여 나온다 */
    @Test
    void listMixesTextAndImageNewestFirst() throws Exception {
        api.createClip(dev.accessToken(), "text first", 201);
        Thread.sleep(20);
        upload(png(10, 10, 0x0000FF), 201);
        String list = api.get("/api/clips", dev.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals("IMAGE", read(list, "$[0].type"));
        assertEquals("TEXT", read(list, "$[1].type"));
    }

    /** 원본 다운로드는 올린 바이트와 똑같고 image/png */
    @Test
    void originalMatchesUpload() throws Exception {
        byte[] png = png(64, 32, 0x123456);
        String id = read(upload(png, 201), "$.id");
        api.get("/api/clips/" + id + "/image", dev.accessToken())
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(png));
    }

    /** 썸네일은 긴 변 240px로 줄고 비율 유지 (300×200 → 240×160) */
    @Test
    void thumbnailIsScaledTo240() throws Exception {
        String id = read(upload(png(300, 200, 0xABCDEF), 201), "$.id");
        BufferedImage t = ImageIO.read(new ByteArrayInputStream(bytes("/api/clips/" + id + "/thumbnail", dev.accessToken(), 200)));
        assertEquals(240, t.getWidth());
        assertEquals(160, t.getHeight());
    }

    /** 원본이 240px보다 작으면 썸네일도 원본 크기 */
    @Test
    void smallImageThumbnailKeepsSize() throws Exception {
        String id = read(upload(png(100, 50, 0x777777), 201), "$.id");
        BufferedImage t = ImageIO.read(new ByteArrayInputStream(bytes("/api/clips/" + id + "/thumbnail", dev.accessToken(), 200)));
        assertEquals(100, t.getWidth());
        assertEquals(50, t.getHeight());
    }

    /** 저장소에는 암호문만 있다 (PNG 시그니처가 보이면 안 됨) */
    @Test
    void storedObjectsAreEncrypted() throws Exception {
        byte[] png = png(20, 20, 0x999999);
        String id = read(upload(png, 201), "$.id");
        String key = clipRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getImageKey();
        byte[] stored = store.get("images/" + key);
        assertNotNull(stored);
        assertNotNull(store.get("thumbs/" + key));
        assertFalse(Arrays.equals(png, stored));
        assertFalse(stored[1] == 'P' && stored[2] == 'N' && stored[3] == 'G');
    }

    /** 남의 이미지 → 404, 텍스트 클립의 /image → 404 */
    @Test
    void othersImageAndTextClipAre404() throws Exception {
        String id = read(upload(png(10, 10, 0x010101), 201), "$.id");
        Api.DeviceTokens stranger = api.newUserWithDevice();
        bytes("/api/clips/" + id + "/image", stranger.accessToken(), 404);
        bytes("/api/clips/" + id + "/thumbnail", stranger.accessToken(), 404);
        String textId = read(api.createClip(dev.accessToken(), "just text", 201), "$.id");
        bytes("/api/clips/" + textId + "/image", dev.accessToken(), 404);
    }

    /** 10MB 초과 → 413 */
    @Test
    void over10MbIs413() throws Exception {
        api.postImage(dev.accessToken(), new byte[10 * 1024 * 1024 + 1]).andExpect(status().isPayloadTooLarge());
    }

    /** 이미지가 아닌 바이트 → 400 */
    @Test
    void notAnImageIs400() throws Exception {
        api.postImage(dev.accessToken(), "hello".getBytes()).andExpect(status().isBadRequest());
    }

    /** 픽셀이 5천만 개를 넘는 이미지(압축 폭탄 방지) → 400. 1비트 흑백이라 파일은 작다 */
    @Test
    void tooManyPixelsIs400() throws Exception {
        BufferedImage huge = new BufferedImage(8000, 7000, BufferedImage.TYPE_BYTE_BINARY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(huge, "png", out);
        api.postImage(dev.accessToken(), out.toByteArray()).andExpect(status().isBadRequest());
    }

    /** 16비트 RGBA PNG(픽셀당 8바이트)도 전체를 풀지 않고 썸네일을 만든다 → 201, 썸네일 240×180 */
    @Test
    void sixteenBitRgbaImageUploads() throws Exception {
        java.awt.image.ColorModel cm = new java.awt.image.ComponentColorModel(
                java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB), true, false,
                java.awt.Transparency.TRANSLUCENT, java.awt.image.DataBuffer.TYPE_USHORT);
        BufferedImage img = new BufferedImage(cm, cm.createCompatibleWritableRaster(4000, 3000), false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        String id = read(upload(out.toByteArray(), 201), "$.id");
        BufferedImage t = ImageIO.read(new ByteArrayInputStream(bytes("/api/clips/" + id + "/thumbnail", dev.accessToken(), 200)));
        assertEquals(240, t.getWidth());
        assertEquals(180, t.getHeight());
    }

    /** 헤더는 멀쩡하지만 픽셀 데이터가 깨진 PNG → 400 (500이 아니라) */
    @Test
    void corruptPngDataIs400() throws Exception {
        byte[] png = png(300, 200, 0x336699);
        // PNG 시그니처(8) + IHDR 청크(25) 뒤부터 끝 12바이트(IEND) 앞까지 0으로 채운다
        Arrays.fill(png, 33, png.length - 12, (byte) 0);
        api.postImage(dev.accessToken(), png).andExpect(status().isBadRequest());
    }

    /** 같은 이미지 재업로드(200) 뒤에도 원본·썸네일 객체가 남아 있다 */
    @Test
    void duplicateKeepsObjects() throws Exception {
        byte[] png = png(40, 40, 0x445566);
        String id = read(upload(png, 201), "$.id");
        String key = clipRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getImageKey();
        upload(png, 200);
        assertNotNull(store.get("images/" + key));
        assertNotNull(store.get("thumbs/" + key));
    }

    /** 버킷에서 원본이 사라진 최근 클립과 같은 이미지 → 중복으로 보지 않고 새 클립(새 키)으로 저장 */
    @Test
    void duplicateWithMissingObjectStoresAgain() throws Exception {
        byte[] png = png(40, 40, 0x778899);
        String id = read(upload(png, 201), "$.id");
        String key = clipRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getImageKey();
        store.delete("images/" + key);
        String id2 = read(upload(png, 201), "$.id");
        assertNotEquals(id, id2);
        String key2 = clipRepository.findById(java.util.UUID.fromString(id2)).orElseThrow().getImageKey();
        assertNotEquals(key, key2);
        assertNotNull(store.get("images/" + key2));
        assertNotNull(store.get("thumbs/" + key2));
    }

    /** 토큰 없이 → 401 또는 403 (기존 Clips API와 같은 보안 규칙) */
    @Test
    void uploadWithoutTokenIsRejected() throws Exception {
        int s = api.postImage(null, png(5, 5, 0)).andReturn().getResponse().getStatus();
        assertTrue(s == 401 || s == 403, "status " + s);
    }

    /** 이미지 클립을 삭제하면 버킷의 원본·썸네일도 지워진다 */
    @Test
    void deleteRemovesObjects() throws Exception {
        String id = read(upload(png(10, 10, 0x0A0A0A), 201), "$.id");
        String key = clipRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getImageKey();
        api.delete("/api/clips/" + id, dev.accessToken()).andExpect(status().isNoContent());
        assertNull(store.get("images/" + key));
        assertNull(store.get("thumbs/" + key));
    }

    /** 만료 정리 작업이 이미지 행과 버킷 객체를 모두 지운다 */
    @Test
    void cleanupRemovesExpiredObjects() throws Exception {
        String id = read(upload(png(10, 10, 0x0B0B0B), 201), "$.id");
        String key = clipRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getImageKey();
        jdbc.update("update clips set expires_at = ? where cast(id as varchar) = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)), id);
        assertTrue(cleanupJob.deleteExpired() >= 1);
        assertTrue(clipRepository.findById(java.util.UUID.fromString(id)).isEmpty());
        assertNull(store.get("images/" + key));
        assertNull(store.get("thumbs/" + key));
    }
}
