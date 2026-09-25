package com.clipvault.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findByUserIdAndActiveTrueOrderByLastSeenAtDesc(UUID userId);

    Optional<Device> findByIdAndUserId(UUID id, UUID userId);
}
