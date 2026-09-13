package com.samanvay.consent.internal.repository;

import com.samanvay.consent.internal.domain.ConsentArtifactEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConsentArtifactRepository extends JpaRepository<ConsentArtifactEntity, UUID> {
    List<ConsentArtifactEntity> findBySubjectCitizenId(UUID citizenId);

    @Query(
            value =
                    """
            SELECT * FROM consent_artifact
            WHERE requester_id = :requesterId
              AND subject_citizen_id = :citizenId
              AND purpose_code = :purpose
              AND :category = ANY(data_categories)
            ORDER BY created_at DESC
            LIMIT 1
            """,
            nativeQuery = true)
    Optional<ConsentArtifactEntity> findMatching(String requesterId, UUID citizenId, String purpose, String category);
}
