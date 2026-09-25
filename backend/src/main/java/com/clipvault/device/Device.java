package com.clipvault.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 기기(PC) 엔티티. DB의 {@code devices} 테이블 한 행.
 *
 * <p>트레이 앱이 로그인할 때마다 해당 PC가 기기로 등록된다(예: "회사PC", "집PC").
 * 한 사용자는 여러 기기를 가질 수 있다(User 1 : N Device).</p>
 *
 * <p>원격 로그아웃하면 행을 지우지 않고 {@code active=false}로만 바꾼다(soft delete).
 * 이렇게 하면 그 기기에서 올렸던 클립의 {@code sourceDeviceId}가 존재하지 않는 기기를 가리키는 일이 없다.</p>
 *
 * <p>참고: userId는 JPA 연관관계(@ManyToOne) 대신 UUID 값으로만 들고 있다.
 * 사용자 객체를 통째로 불러올 일이 없어서 이 편이 단순하다.</p>
 */
@Entity
@Table(name = "devices")
public class Device {

    /** 기본키(UUID, 자동 생성). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 이 기기의 주인(사용자 ID). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 사용자가 정한 기기 이름. 예: "회사PC". 트레이 앱에서는 기본값으로 컴퓨터 이름을 쓴다. */
    @Column(name = "device_name", nullable = false)
    private String deviceName;

    /** 운영체제 이름. 예: "Windows 11". 선택 값이라 null일 수 있다. */
    private String os;

    /** 마지막으로 서버에 접속한 시각. 기기 목록 정렬과 표시에 쓴다. (JwtService에서 1분 단위로 갱신) */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** 활성 여부. 원격 로그아웃되면 false가 되고, 이 기기의 토큰은 모두 거부된다. */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    /**
     * 현재 유효한 refresh 토큰의 SHA-256 해시.
     * 토큰 원문이 아니라 해시만 저장하므로 DB가 유출돼도 토큰을 쓸 수 없다.
     * 토큰을 갱신할 때마다 새 값으로 바뀌고(rotation), 로그아웃하면 null이 된다.
     */
    @Column(name = "refresh_token_hash")
    private String refreshTokenHash;

    /** JPA 전용 기본 생성자. */
    protected Device() {
    }

    /** 새 기기 등록. 처음엔 활성 상태이고 마지막 접속 시각은 지금이다. */
    public Device(UUID userId, String deviceName, String os) {
        this.userId = userId;
        this.deviceName = deviceName;
        this.os = os;
        this.lastSeenAt = Instant.now();
        this.active = true;
    }

    /** 마지막 접속 시각을 지금으로 갱신한다. */
    public void touch() {
        lastSeenAt = Instant.now();
    }

    /** refresh 토큰을 새 것으로 교체(해시 저장)한다. 이전 refresh 토큰은 이 순간부터 무효가 된다. */
    public void rotateRefreshToken(String hash) {
        refreshTokenHash = hash;
        touch();
    }

    /**
     * 원격 로그아웃 처리.
     * 비활성으로 바꾸고 refresh 토큰 해시를 지워서, access 토큰도 refresh 토큰도 더 이상 통하지 않게 한다.
     */
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
