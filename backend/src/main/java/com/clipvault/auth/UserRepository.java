package com.clipvault.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link User} 테이블에 접근하는 저장소(Repository).
 *
 * <p>Spring Data JPA가 인터페이스만 보고 구현체를 자동으로 만들어 준다.
 * 메서드 이름 규칙(findBy..., existsBy...)만 지키면 SQL을 직접 쓰지 않아도 된다.
 * save, findById, delete 같은 기본 메서드는 {@link JpaRepository}에 이미 들어 있다.</p>
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** 이메일로 사용자를 찾는다. 로그인할 때 사용. 없으면 빈 Optional을 돌려준다. */
    Optional<User> findByEmail(String email);

    /** 이미 가입된 이메일인지 확인한다. 회원가입 시 중복 체크(409 응답)에 사용. */
    boolean existsByEmail(String email);

    /** 사용자의 클립 보관 기간. 사용자가 없으면(삭제 직후 등) 기본 7일. */
    default java.time.Duration clipTtl(UUID userId) {
        return java.time.Duration.ofDays(findById(userId).map(User::getClipTtlDays).orElse(7));
    }

    /**
     * 볼트 값을 바꾸고 버전을 1 올린다. 단, 현재 버전이 expected일 때만 (조건부 갱신).
     * 두 요청이 동시에 같은 버전에서 출발해도 한쪽만 1행을 갱신하고 다른 쪽은 0을 돌려받으므로,
     * 같은 버전에 서로 다른 키가 저장되는 조용한 분기를 막는다. 0이면 호출한 쪽이 409로 처리한다.
     */
    @Modifying
    @Query("update User u set u.vaultSalt = :salt, u.vaultIterations = :iterations, u.vaultWrappedKey = :key, "
            + "u.vaultVersion = u.vaultVersion + 1 where u.id = :id and u.vaultVersion = :expected")
    int replaceVaultIfVersion(@Param("id") UUID id, @Param("salt") String salt, @Param("iterations") int iterations,
                              @Param("key") String key, @Param("expected") int expected);
}
