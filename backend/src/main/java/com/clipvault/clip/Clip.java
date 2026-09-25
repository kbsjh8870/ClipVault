package com.clipvault.clip;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clips", indexes = @Index(columnList = "user_id, created_at"))
public class Clip {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "source_device_id")
    private UUID sourceDeviceId;

    /** AES-GCM ciphertext (base64). 100k chars of UTF-8 plaintext stays well under this after base64. */
    @Column(nullable = false, length = 600_000)
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected Clip() {
    }

    public Clip(UUID userId, UUID sourceDeviceId, String content, String contentHash, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.sourceDeviceId = sourceDeviceId;
        this.content = content;
        this.contentHash = contentHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** Duplicate upload of the latest clip: bump timestamps instead of inserting a new row. */
    public void refresh(UUID sourceDeviceId, Instant createdAt, Instant expiresAt) {
        this.sourceDeviceId = sourceDeviceId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getSourceDeviceId() { return sourceDeviceId; }
    public String getContent() { return content; }
    public String getContentHash() { return contentHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
