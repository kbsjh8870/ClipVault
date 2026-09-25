package com.clipvault.clip;

import java.time.Instant;
import java.util.UUID;

/** REST body and WebSocket payload. content is always plaintext here. */
public record ClipResponse(UUID id, String content, String contentHash, UUID sourceDeviceId, Instant createdAt, Instant expiresAt) {
}
