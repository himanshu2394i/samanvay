package com.samanvay.identity.internal.repository;

import com.samanvay.identity.internal.domain.LinkEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkRepository extends JpaRepository<LinkEntity, UUID> {
    Optional<LinkEntity> findByCitizenIdAndDepartmentCodeAndStatus(UUID citizenId, String departmentCode, String status);

    List<LinkEntity> findByCitizenIdAndStatus(UUID citizenId, String status);

    Optional<LinkEntity> findByDepartmentCodeAndLocalIdTokenAndStatus(
            String departmentCode, String localIdToken, String status);
}
