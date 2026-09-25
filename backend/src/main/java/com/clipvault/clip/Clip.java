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

/**
 * 클립(복사한 텍스트 한 건) 엔티티. DB의 {@code clips} 테이블 한 행.
 *
 * <p>(user_id, created_at) 인덱스: "이 사용자의 최근 클립 N개"를 조회하는 쿼리가 가장 많아서,
 * 이 두 컬럼으로 인덱스를 걸어 전체 테이블을 훑지 않고 빠르게 찾을 수 있게 했다.</p>
 */
@Entity
@Table(name = "clips", indexes = @Index(columnList = "user_id, created_at"))
public class Clip {

    /** 기본키(UUID, 자동 생성). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 클립 주인(사용자 ID). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 이 클립을 복사한 기기 ID. 클라이언트는 이 값이 자기 기기면 알림을 무시한다(자기 복사본의 메아리 방지). */
    @Column(name = "source_device_id")
    private UUID sourceDeviceId;

    /**
     * 클립 내용. 평문이 아니라 AES-GCM으로 암호화한 base64 문자열이 저장된다.
     * 평문 최대 10만 자(한글은 UTF-8로 글자당 3바이트)를 암호화 + base64로 늘려도 60만 자 안에 들어오도록 길이를 잡았다.
     */
    @Column(nullable = false, length = 600_000)
    private String content;

    /**
     * 원문의 SHA-256 해시(64자 16진수). 암호문은 매번 달라서 비교할 수 없으므로,
     * "방금 복사한 것과 같은 내용인가?"는 이 해시로 판단한다.
     */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 생성 시각. 같은 내용을 다시 복사하면 이 값이 지금 시각으로 갱신되어 목록 맨 위로 올라간다. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 만료 시각(= 생성 시각 + 7일). 지나면 조회에서 빠지고, 매일 도는 배치가 실제로 삭제한다. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** JPA 전용 기본 생성자. */
    protected Clip() {
    }

    /**
     * 새 클립 생성.
     *
     * @param content     이미 암호화된 내용 (평문 X)
     * @param contentHash 평문의 SHA-256 해시
     */
    public Clip(UUID userId, UUID sourceDeviceId, String content, String contentHash, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.sourceDeviceId = sourceDeviceId;
        this.content = content;
        this.contentHash = contentHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 가장 최근 클립과 같은 내용을 다시 복사했을 때 호출한다.
     * 새 행을 만들지 않고 시각(생성/만료)과 복사한 기기만 갱신한다. → 목록에 같은 내용이 중복으로 쌓이지 않는다.
     */
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
