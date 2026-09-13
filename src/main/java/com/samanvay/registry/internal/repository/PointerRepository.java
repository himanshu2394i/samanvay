package com.samanvay.registry.internal.repository;

import com.samanvay.registry.internal.domain.PointerEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointerRepository extends JpaRepository<PointerEntity, UUID> {
    Optional<PointerEntity> findBySubjectIdAndDepartmentCodeAndDataCategory(
            UUID subjectId, String departmentCode, String dataCategory);

    Optional<PointerEntity> findBySubjectIdAndDataCategory(UUID subjectId, String dataCategory);
}
