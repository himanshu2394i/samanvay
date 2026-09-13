package com.samanvay.audit.internal.repository;

import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditQuery;
import com.samanvay.shared.CanonicalJson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

class InMemoryAuditEntryRepository extends AuditEntryRepository {

    final List<AuditEntryRow> rows = new ArrayList<>();

    InMemoryAuditEntryRepository() {
        super(null, new CanonicalJson());
    }

    @Override
    Optional<byte[]> findLatestHash() {
        return rows.stream().max(Comparator.comparingLong(AuditEntryRow::seq)).map(AuditEntryRow::hash);
    }

    @Override
    Optional<byte[]> findHashAt(long seq) {
        return rows.stream().filter(r -> r.seq() == seq).findFirst().map(AuditEntryRow::hash);
    }

    @Override
    long currentMaxSeq() {
        return rows.stream().mapToLong(AuditEntryRow::seq).max().orElse(0);
    }

    @Override
    long insert(AuditEntry entry, byte[] prevHash, byte[] hash, String canonical) {
        long seq = currentMaxSeq() + 1;
        rows.add(new AuditEntryRow(
                seq,
                Instant.parse("2026-09-05T00:00:00Z"),
                entry.actorType(),
                entry.actorId(),
                entry.action(),
                entry.subjectId(),
                entry.resource(),
                entry.departmentId(),
                entry.consentId(),
                entry.grantId(),
                entry.outcome(),
                entry.reason(),
                entry.meta(),
                prevHash,
                hash));
        return seq;
    }

    @Override
    List<AuditEntryRow> findRange(long fromSeqInclusive, long toSeqInclusive) {
        return rows.stream()
                .filter(r -> r.seq() >= fromSeqInclusive && r.seq() <= toSeqInclusive)
                .sorted(Comparator.comparingLong(AuditEntryRow::seq))
                .toList();
    }

    @Override
    Page<AuditEntryRow> search(AuditQuery query, Pageable page) {
        throw new UnsupportedOperationException();
    }

    void replace(int index, AuditEntryRow row) {
        rows.set(index, row);
    }
}
