package com.clipvault.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
