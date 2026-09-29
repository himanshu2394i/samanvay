package com.samanvay.consent.internal.repository;

import com.samanvay.consent.internal.domain.ConsentArtifactEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * ACTIVE rows already past {@code valid_until}, oldest first. Locked with SKIP LOCKED so two
     * app instances running the expiry job never mark the same row twice.
     */
    @Query(
            value =
                    """
            SELECT * FROM consent_artifact
            WHERE status = 'ACTIVE' AND valid_until < :now
            ORDER BY valid_until
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """,
            nativeQuery = true)
    List<ConsentArtifactEntity> findActiveEndedBefore(Instant now, int limit);

    /*
     * A consent has ended once it is REVOKED (at revoked_at; updated_at for rows revoked before
     * V187, which have no revoked_at) or EXPIRED (at valid_until). Only rows already marked
     * ended qualify: an ACTIVE row past valid_until is left for the expiry job to mark first.
     */
    String ENDED_BEFORE_CUTOFF = """
            SELECT id FROM consent_artifact
            WHERE (status = 'REVOKED' AND COALESCE(revoked_at, updated_at) < :cutoff)
               OR (status = 'EXPIRED' AND valid_until < :cutoff)
            """;

    /*
     * consent_event, consent_access_grant and consent_usage reference consent_artifact(id)
     * with no ON DELETE CASCADE (V80, V189), so they go first, then the artifact. Audit rows
     * carry consent_id with no foreign key and are never deleted here. The four statements
     * select the same rows: an ended consent gains no new events, grants or usages.
     */
    @Modifying
    @Query(value = "DELETE FROM consent_usage WHERE consent_id IN (" + ENDED_BEFORE_CUTOFF + ")", nativeQuery = true)
    int deleteUsageOfEndedBefore(Instant cutoff);

    @Modifying
    @Query(
            value = "DELETE FROM consent_access_grant WHERE consent_id IN (" + ENDED_BEFORE_CUTOFF + ")",
            nativeQuery = true)
    int deleteGrantsOfEndedBefore(Instant cutoff);

    @Modifying
    @Query(value = "DELETE FROM consent_event WHERE consent_id IN (" + ENDED_BEFORE_CUTOFF + ")", nativeQuery = true)
    int deleteEventsOfEndedBefore(Instant cutoff);

    @Modifying
    @Query(value = "DELETE FROM consent_artifact WHERE id IN (" + ENDED_BEFORE_CUTOFF + ")", nativeQuery = true)
    int deleteEndedBefore(Instant cutoff);
}
