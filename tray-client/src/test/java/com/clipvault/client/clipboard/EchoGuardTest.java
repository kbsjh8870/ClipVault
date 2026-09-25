package com.clipvault.client.clipboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class EchoGuardTest {
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

    @Test
    void nullOrBlankIsNeverUploaded() {
        assertFalse(guard.shouldUpload(null));
        assertFalse(guard.shouldUpload(""));
        assertFalse(guard.shouldUpload("   \n\t"));
    }

    @Test
    void sameTextAsLastApprovedUploadIsSkipped() {
        assertTrue(guard.shouldUpload("a"));
        assertFalse(guard.shouldUpload("a"));
        clock.advance(Duration.ofMinutes(10));
        assertFalse(guard.shouldUpload("a"), "last-upload check is not time bound");
        assertTrue(guard.shouldUpload("b"));
        assertTrue(guard.shouldUpload("a"), "only the immediately previous upload is compared");
    }

    @Test
    void textAppliedFromServerIsSkippedWithinWindow() {
        guard.markApplied("from server");
        assertFalse(guard.shouldUpload("from server"));
        clock.advance(WINDOW.minusMillis(1));
        assertFalse(guard.shouldUpload("from server"));
        assertTrue(guard.shouldUpload("other text"));
    }

    @Test
    void textAppliedFromServerIsUploadableAfterWindow() {
        guard.markApplied("from server");
        clock.advance(WINDOW.plusMillis(1));
        assertTrue(guard.shouldUpload("from server"));
    }
}
