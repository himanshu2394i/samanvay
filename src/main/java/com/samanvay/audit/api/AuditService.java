package com.samanvay.audit.api;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditService {

    /** Records an entry in the current transaction. Never swallows failure. */
    AuditRef record(AuditEntry entry);

    /** Verifies the chain between two checkpoints, or from the last checkpoint to head. */
    VerificationResult verify(long fromSeq, long toSeq);

    Optional<Checkpoint> latestCheckpoint();

    Page<AuditEntry> search(AuditQuery query, Pageable page);

    long headSeq();

    Page<AuditRecord> browse(AuditQuery query, Pageable page);
}
