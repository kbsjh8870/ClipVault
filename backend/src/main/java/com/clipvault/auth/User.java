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
}
