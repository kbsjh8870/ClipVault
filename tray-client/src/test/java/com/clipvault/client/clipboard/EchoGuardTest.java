package com.clipvault.client.clipboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** {@link EchoGuard} 단위 테스트. 실제로 5초씩 기다리지 않도록, 시간을 마음대로 앞으로 돌릴 수 있는 가짜 시계를 쓴다. */
class EchoGuardTest {
    /** 테스트용 가짜 시계. advance()로 원하는 만큼 시간을 앞으로 돌린다. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static final Duration WINDOW = Duration.ofSeconds(5);
    MutableClock clock;
    EchoGuard guard;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        guard = new EchoGuard(clock, WINDOW);
    }

    /** null, 빈 문자열, 공백뿐인 문자열은 절대 업로드하지 않는다 */
    @Test
    void nullOrBlankIsNeverUploaded() {
        assertFalse(guard.shouldUpload(null));
        assertFalse(guard.shouldUpload(""));
        assertFalse(guard.shouldUpload("   \n\t"));
    }

    /** 직전에 올린 것과 같은 텍스트는 시간이 아무리 지나도 건너뛴다. 단, 비교 대상은 "바로 직전 1건"뿐이다 */
    @Test
    void sameTextAsLastApprovedUploadIsSkipped() {
        assertTrue(guard.shouldUpload("a"));
        assertFalse(guard.shouldUpload("a"));
        clock.advance(Duration.ofMinutes(10));
        assertFalse(guard.shouldUpload("a"), "last-upload check is not time bound");
        assertTrue(guard.shouldUpload("b"));
        assertTrue(guard.shouldUpload("a"), "only the immediately previous upload is compared");
    }

    /** 서버에서 받아 로컬에 넣은 텍스트는 window(5초) 안에는 업로드하지 않는다. 다른 텍스트는 영향 없음 */
    @Test
    void textAppliedFromServerIsSkippedWithinWindow() {
        guard.markApplied("from server");
        assertFalse(guard.shouldUpload("from server"));
        clock.advance(WINDOW.minusMillis(1)); // 4.999초 후: 아직 window 안
        assertFalse(guard.shouldUpload("from server"));
        assertTrue(guard.shouldUpload("other text"));
    }

    /** window가 지나면 같은 텍스트라도 다시 업로드할 수 있다 (사용자가 나중에 또 복사한 경우) */
    @Test
    void textAppliedFromServerIsUploadableAfterWindow() {
        guard.markApplied("from server");
        clock.advance(WINDOW.plusMillis(1)); // 5.001초 후: window 밖
        assertTrue(guard.shouldUpload("from server"));
    }
}
