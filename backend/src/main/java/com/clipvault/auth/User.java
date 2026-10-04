package com.clipvault.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 회원(사용자) 엔티티. DB의 {@code users} 테이블 한 행과 1:1로 대응한다.
 *
 * <p>테이블 이름을 "user"가 아니라 "users"로 한 이유: PostgreSQL에서 {@code user}는 예약어라
 * 그대로 쓰면 SQL 오류가 난다.</p>
 */
@Entity
@Table(name = "users")
public class User {

    /** 기본키. 저장할 때 JPA가 UUID를 자동으로 만들어 넣는다. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 로그인 아이디로 쓰는 이메일. unique 제약으로 같은 이메일이 두 번 가입되는 것을 DB 차원에서도 막는다. */
    @Column(nullable = false, unique = true)
    private String email;

    /** 비밀번호 원문이 아니라 BCrypt로 해시한 값. 원문 비밀번호는 절대 저장하지 않는다. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** 가입 시각(UTC). */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 클립 보관 기간(일). {@link #TTL_CHOICES} 중 하나. 기존 행에는 기본값 7이 들어가도록 컬럼 기본값을 둔다
     * (ddl-auto: update가 컬럼을 추가할 때).
     */
    @Column(name = "clip_ttl_days", nullable = false, columnDefinition = "integer default 7")
    private int clipTtlDays = 7;

    /** 고를 수 있는 보관 기간(일). 짧게(민감한 내용)부터 한 달까지. */
    public static final java.util.Set<Integer> TTL_CHOICES = java.util.Set.of(1, 3, 7, 30);

    /** 볼트 salt (base64 16바이트). 볼트를 만들기 전에는 null. 서버는 이 값과 아래 감싼 키로 볼트 키를 풀 수 없다(볼트 암호가 없으므로). */
    @Column(name = "vault_salt", length = 32)
    private String vaultSalt;

    /** 볼트 암호 → 잠금 키 PBKDF2 반복 횟수. 앱이 이 값을 그대로 쓴다(나중에 올릴 수 있게 저장). */
    @Column(name = "vault_iterations")
    private Integer vaultIterations;

    /** 잠금 키로 감싼 볼트 키 (base64 60바이트 = IV 12 + 키 32 + 태그 16). */
    @Column(name = "vault_wrapped_key", length = 128)
    private String vaultWrappedKey;

    /** 볼트 버전. 0 = 볼트 없음, 만들면 1, 초기화할 때마다 +1. 옛 볼트 키를 가진 PC의 업로드를 409로 막는 데 쓴다. */
    @Column(name = "vault_version", nullable = false, columnDefinition = "integer default 0")
    private int vaultVersion;

    /** JPA가 DB에서 읽어 온 값으로 객체를 만들 때 쓰는 기본 생성자. 직접 호출하지 않도록 protected로 막아 둔다. */
    protected User() {
    }

    /**
     * 회원가입 시 새 사용자를 만든다.
     *
     * @param email        이메일
     * @param passwordHash 이미 BCrypt로 해시된 비밀번호 (원문 X)
     */
    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
    public int getClipTtlDays() { return clipTtlDays; }

    /** 보관 기간을 바꾼다. 값 검사(TTL_CHOICES)는 호출하는 쪽(SettingsController)이 한다. */
    public void setClipTtlDays(int days) { this.clipTtlDays = days; }

    public String getVaultSalt() { return vaultSalt; }
    public Integer getVaultIterations() { return vaultIterations; }
    public String getVaultWrappedKey() { return vaultWrappedKey; }
    public int getVaultVersion() { return vaultVersion; }

    /** 볼트가 있는지 (한 번이라도 만들었는지). */
    public boolean hasVault() { return vaultVersion > 0; }

    /** 감싼 볼트 키를 저장한다(만들기, 암호 변경, 초기화 공통). version은 바꾸지 않는다. 값 검사는 VaultController가 한다. */
    public void setVault(String salt, int iterations, String wrappedKey) {
        this.vaultSalt = salt;
        this.vaultIterations = iterations;
        this.vaultWrappedKey = wrappedKey;
    }

    /** 볼트 버전을 1 올린다 (만들기: 0→1, 초기화: n→n+1). */
    public void bumpVaultVersion() { this.vaultVersion++; }
}
