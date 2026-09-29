package com.samanvay.orchestration.internal.repository;

import com.samanvay.orchestration.internal.domain.BankReviewEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BankReviewRepository extends JpaRepository<BankReviewEntity, java.util.UUID> {

    List<BankReviewEntity> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

    /** Undecided reviews whose uploaded file is older than the retention cutoff. */
    @Query("select r from BankReviewEntity r where r.documentPath is not null and r.documentUploadedAt < :cutoff")
    List<BankReviewEntity> withFileUploadedBefore(@Param("cutoff") Instant cutoff);
}
