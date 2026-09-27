package com.samanvay.identity.internal.repository;

import com.samanvay.identity.internal.domain.CitizenEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CitizenRepository extends JpaRepository<CitizenEntity, UUID> {

    Optional<CitizenEntity> findByAuthSubject(String authSubject);

    boolean existsByIdAndAuthSubject(UUID id, String authSubject);
}
