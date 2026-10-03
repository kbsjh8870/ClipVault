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
     * 사용자의 보이는 클립(만료 전이거나 고정된 것) 중 before보다 먼저 만들어진 것을 최신순으로 limit개 가져온다.
     * (클립 목록 화면용. before는 "더 보기"의 기준 시각 = 앞 페이지 마지막 항목의 생성 시각)
     * 배치가 아직 삭제하지 않은 만료 클립도 여기서 걸러지므로 사용자에게는 절대 보이지 않는다.
     */
    @Query("select c from Clip c where c.userId = :userId and (c.expiresAt > :now or c.pinned = true)"
            + " and c.createdAt < :before order by c.createdAt desc")
    List<Clip> findVisible(@Param("userId") UUID userId, @Param("now") Instant now, @Param("before") Instant before, Limit limit);

    /** 사용자의 고정 클립 전체 (최신순). 최대 10개라 페이지 넘김이 필요 없다. */
    List<Clip> findByUserIdAndPinnedTrueOrderByCreatedAtDesc(UUID userId);

    /** 사용자의 고정 안 된 클립 전체. 보관 기간을 바꿨을 때 만료 시각을 다시 계산하는 데 쓴다. */
    List<Clip> findByUserIdAndPinnedFalse(UUID userId);

    /** 사용자의 고정 클립 개수 (10개 제한 확인용). */
    long countByUserIdAndPinnedTrue(UUID userId);

    /** ID와 주인이 모두 일치하는 클립. 다른 사람의 클립을 삭제하려 하면 비어서 404가 된다. */
    Optional<Clip> findByIdAndUserId(UUID id, UUID userId);

    /**
     * 만료 시각이 지난 클립(고정한 것 제외)을 한 번의 DELETE 쿼리로 일괄 삭제하고, 삭제된 행 수를 돌려준다.
     * {@code @Modifying}: SELECT가 아니라 데이터를 바꾸는 쿼리라는 표시 (호출하는 쪽에 트랜잭션이 필요).
     */
    @Modifying
    @Query("delete from Clip c where c.expiresAt <= :now and c.pinned = false")
    int deleteExpired(@Param("now") Instant now);

    /** 고정한 이미지 클립들의 버킷 키. 매일 객체를 다시 써서 버킷 수명 주기 규칙에 지워지지 않게 하는 데 쓴다. */
    @Query("select c.imageKey from Clip c where c.pinned = true and c.imageKey is not null")
    List<String> findPinnedImageKeys();

    /** 만료된 이미지 클립들의 버킷 키. 행을 지우기 전에 버킷 객체부터 지우는 데 쓴다. */
    @Query("select c.imageKey from Clip c where c.expiresAt <= :now and c.pinned = false and c.imageKey is not null")
    List<String> findExpiredImageKeys(@Param("now") Instant now);
}
