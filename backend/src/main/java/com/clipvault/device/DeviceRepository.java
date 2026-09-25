package com.clipvault.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link Device} 테이블 저장소. 메서드 이름만으로 Spring Data JPA가 쿼리를 자동 생성한다.
 */
public interface DeviceRepository extends JpaRepository<Device, UUID> {

    /**
     * 특정 사용자의 활성 기기 목록을 최근 접속 순으로 가져온다. (기기 목록 화면용)
     * 이름 풀이: findBy UserId And Active=True OrderBy LastSeenAt Desc
     */
    List<Device> findByUserIdAndActiveTrueOrderByLastSeenAtDesc(UUID userId);

    /**
     * ID와 주인이 모두 일치하는 기기를 찾는다.
     * 다른 사람의 기기를 삭제하려고 하면 결과가 비어서 404가 되도록, 조회 단계에서 주인까지 함께 확인한다.
     */
    Optional<Device> findByIdAndUserId(UUID id, UUID userId);
}
