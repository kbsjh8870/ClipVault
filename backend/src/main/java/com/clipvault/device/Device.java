package com.clipvault.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "devices")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_name", nullable = false)
    private String deviceName;

    private String os;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "refresh_token_hash")
    private String refreshTokenHash;

    protected Device() {
    }

    public Device(UUID userId, String deviceName, String os) {
        this.userId = userId;
        this.deviceName = deviceName;
        this.os = os;
        this.lastSeenAt = Instant.now();
        this.active = true;
    }

    public void touch() {
        lastSeenAt = Instant.now();
    }

    public void rotateRefreshToken(String hash) {
        refreshTokenHash = hash;
        touch();
    }

    public void deactivate() {
        active = false;
        refreshTokenHash = null;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getDeviceName() { return deviceName; }
    public String getOs() { return os; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public boolean isActive() { return active; }
    public String getRefreshTokenHash() { return refreshTokenHash; }
}
