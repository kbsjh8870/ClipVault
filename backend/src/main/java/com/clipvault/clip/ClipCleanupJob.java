package com.clipvault.clip;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ClipCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ClipCleanupJob.class);

    private final ClipRepository clips;

    public ClipCleanupJob(ClipRepository clips) {
        this.clips = clips;
    }

    @Scheduled(cron = "${clipvault.clip.cleanup-cron}")
    @Transactional
    public int deleteExpired() {
        int deleted = clips.deleteExpired(Instant.now());
        log.info("Deleted {} expired clips", deleted);
        return deleted;
    }
}
