package com.clipvault.clip;

import com.clipvault.Api;
import com.clipvault.storage.ImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.clipvault.Api.png;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 업로드 실패 경로 전용 테스트: 버킷/DB 저장을 가짜(mock/spy)로 바꿔치기해서 재현한다.
 * {@link ImageClipApiTest}와 빈을 다르게 오버라이드하므로 스프링 컨텍스트가 따로 뜬다 (별도 클래스로 분리한 이유).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImageClipFailureTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean ClipRepository clipRepository;
    @MockitoBean ImageStore store;
    Api api;
    Api.DeviceTokens dev;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    /** 버킷 저장 실패(put 예외) → 503, 새 클립 행이 생기지 않는다. */
    @Test
    void bucketFailureReturns503AndLeavesNoDbRow() throws Exception {
        doThrow(new RuntimeException("bucket down")).when(store).put(anyString(), any());
        long before = clipRepository.count();

        api.postImage(dev.accessToken(), png(20, 20, 0x123123))
                .andExpect(status().isServiceUnavailable());

        assertEquals(before, clipRepository.count());
    }

    /** DB 저장 실패(버킷에는 이미 올라간 뒤) → 요청은 실패(5xx)하고, 방금 올린 원본/썸네일 객체를 지워서 고아 파일을 남기지 않는다. */
    @Test
    void dbFailureCleansUpOrphanedBucketObjects() throws Exception {
        // 이미지 클립 저장만 실패시킨다 (imageKey가 있는 Clip만). 가입/로그인/기기 등록은 다른 리포지토리를 쓰므로 영향 없다.
        doThrow(new RuntimeException("db down")).when(clipRepository)
                .save(argThat((Clip c) -> c != null && c.getImageKey() != null));

        // 컨트롤러가 잡지 않은 RuntimeException이라 서블릿 계층까지 그대로 올라온다(운영에서는 500으로 응답).
        // MockMvc는 이런 미처리 예외를 감싸서 다시 던지므로 여기서 받아 원인을 확인한다.
        Exception thrown = assertThrows(Exception.class,
                () -> api.postImage(dev.accessToken(), png(15, 15, 0x456456)));
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        assertEquals("db down", cause.getMessage());

        // put()에 넘긴 키(images/{key}, thumbs/{key})를 캡처해서, delete()가 같은 키로 불렸는지 확인한다
        ArgumentCaptor<String> putKeys = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).put(putKeys.capture(), any());
        List<String> keys = putKeys.getAllValues();
        assertFalse(keys.isEmpty());
        String key = keys.get(0).substring(ImageClipController.IMAGES.length());

        verify(store).delete(ImageClipController.IMAGES + key);
        verify(store).delete(ImageClipController.THUMBS + key);
    }
}
