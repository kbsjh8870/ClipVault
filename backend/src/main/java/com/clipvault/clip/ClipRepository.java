package com.clipvault.clip;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link Clip} 테이블 저장소.
 */
public interface ClipRepository extends JpaRepository<Clip, UUID> {

    /** 사용자의 가장 최근 클립 1건. 업로드 시 "직전 클립과 같은 내용인지(중복)" 확인하는 데 쓴다. */
    Optional<Clip> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * 사용자의 아직 만료되지 않은 클립을 최신순으로 limit개 가져온다. (클립 목록 화면용)
     * 이름 풀이: findBy UserId And ExpiresAt 이 now 보다 After(이후) OrderBy CreatedAt Desc
     * 배치가 아직 삭제하지 않은 만료 클립도 여기서 걸러지므로 사용자에게는 절대 보이지 않는다.
     */
    List<Clip> findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now, Limit limit);

    /** ID와 주인이 모두 일치하는 클립. 다른 사람의 클립을 삭제하려 하면 비어서 404가 된다. */
    Optional<Clip> findByIdAndUserId(UUID id, UUID userId);

    /**
     * 만료 시각이 지난 클립을 한 번의 DELETE 쿼리로 일괄 삭제하고, 삭제된 행 수를 돌려준다.
     * {@code @Modifying}: SELECT가 아니라 데이터를 바꾸는 쿼리라는 표시 (호출하는 쪽에 트랜잭션이 필요).
     */
    @Modifying
    @Query("delete from Clip c where c.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
