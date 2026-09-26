package com.clipvault.clip;

import com.clipvault.storage.ImageStore;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된 클립을 DB에서 실제로 지우는 배치 작업.
 *
 * <p>클립은 7일이 지나면 조회 결과에서는 바로 빠지지만(ClipRepository의 조회 조건),
 * DB에는 남아 있다. 이 작업이 하루에 한 번 돌면서 그런 행들을 물리적으로 삭제한다.
 * 민감한 복사 내용이 서버에 무기한 쌓이지 않게 하기 위한 것이다(PRD: TTL 경과 후 24시간 이내 삭제).</p>
 *
 * <p>실행 시각은 application.yml의 {@code clipvault.clip.cleanup-cron} (기본 {@code "0 0 4 * * *"} = 매일 새벽 4시).
 * cron 형식: 초 분 시 일 월 요일.</p>
 */
@Component
public class ClipCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ClipCleanupJob.class);

    private final ClipRepository clips;
    private final ImageStore store;

    public ClipCleanupJob(ClipRepository clips, ImageStore store) {
        this.clips = clips;
        this.store = store;
    }

    /**
     * 만료 시각이 지금보다 이전(같거나 이전)인 클립을 모두 삭제한다.
     *
     * <p><b>이미지 클립 처리</b>: DB 행을 지우기 전에 만료된 이미지 클립의 버킷 객체(원본, 썸네일)부터 지운다.
     * 행을 지우면 imageKey를 더는 알 수 없기 때문이다. 버킷 작업이 실패해도 행 삭제는 계속 진행되며,
     * 남은 객체는 버킷 수명 주기 규칙이 나중에 정리한다.</p>
     *
     * <p>{@code @Scheduled}: 정해진 cron 시각에 스프링이 자동으로 호출한다(BackendApplication의 @EnableScheduling 필요).
     * {@code @Transactional}: DELETE 쿼리는 트랜잭션 안에서 실행되어야 한다.</p>
     *
     * @return 삭제한 클립 수 (테스트에서 직접 호출해 확인할 수 있도록 반환한다)
     */
    @Scheduled(cron = "${clipvault.clip.cleanup-cron}")
    @Transactional
    public int deleteExpired() {
        Instant now = Instant.now();
        // 행을 지우면 버킷 키를 알 수 없으므로 버킷 객체부터 지운다
        for (String key : clips.findExpiredImageKeys(now)) ImageClipController.deleteQuietly(store, key);
        int deleted = clips.deleteExpired(now);
        log.info("Deleted {} expired clips", deleted);
        return deleted;
    }
}
