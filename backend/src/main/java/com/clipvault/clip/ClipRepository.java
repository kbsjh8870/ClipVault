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

public interface ClipRepository extends JpaRepository<Clip, UUID> {

    Optional<Clip> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Clip> findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now, Limit limit);

    Optional<Clip> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("delete from Clip c where c.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
