package com.samanvay.audit.internal.service;

import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditQuery;
import com.samanvay.audit.api.AuditRecord;
import com.samanvay.audit.api.AuditRef;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.ChainGapException;
import com.samanvay.audit.api.Checkpoint;
import com.samanvay.audit.api.VerificationResult;
import com.samanvay.audit.internal.repository.AuditEntryRepository;
import com.samanvay.audit.internal.repository.AuditEntryRow;
import com.samanvay.audit.internal.repository.CheckpointRepository;
import com.samanvay.shared.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcAuditService implements AuditService {

    // ponytail: one global advisory-lock key serializes every audit
    // append across the whole application. Correct at any throughput
    // this system will see (audit writes, not payload transfer). If it
    // ever measurably contends, shard the key (e.g. by day) rather than
    // removing the lock — the hash chain has no correctness without a
    // total order over appends.
    static final long CHAIN_LOCK_KEY = 918_273_645L;
    static final byte[] GENESIS_HASH = sha256("SAMANVAY_AUDIT_GENESIS".getBytes(StandardCharsets.UTF_8));

    private final AuditEntryRepository entries;
    private final CheckpointRepository checkpoints;
    private final JdbcTemplate jdbc;
    private final CanonicalJson canonicalJson;

    JdbcAuditService(
            AuditEntryRepository entries,
            CheckpointRepository checkpoints,
            JdbcTemplate jdbc,
            CanonicalJson canonicalJson) {
        this.entries = entries;
        this.checkpoints = checkpoints;
        this.jdbc = jdbc;
        this.canonicalJson = canonicalJson;
    }

    @Override
    @Transactional
    public AuditRef record(AuditEntry entry) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, CHAIN_LOCK_KEY);

        byte[] prevHash = entries.findLatestHash().orElse(GENESIS_HASH);
        String canonical = canonicalJson.serialize(entry);
        byte[] hash = sha256(concat(prevHash, canonical.getBytes(StandardCharsets.UTF_8)));

        long seq = entries.insert(entry, prevHash, hash);
        return new AuditRef(seq, hash);
    }

    @Override
    public VerificationResult verify(long fromSeq, long toSeq) {
        List<AuditEntryRow> rows = entries.findRange(fromSeq, toSeq);
        if (rows.size() != toSeq - fromSeq + 1) {
            return VerificationResult.failed(fromSeq, "chain gap");
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).seq() != fromSeq + i) {
                return VerificationResult.failed(fromSeq + i, "chain gap");
            }
        }

        byte[] expectedPrev = fromSeq == 1
                ? GENESIS_HASH
                : entries.findHashAt(fromSeq - 1).orElseThrow(() -> new ChainGapException(fromSeq - 1));

        for (AuditEntryRow row : rows) {
            if (!Arrays.equals(row.prevHash(), expectedPrev)) {
                return VerificationResult.failed(row.seq(), "prev_hash does not match preceding entry");
            }
            byte[] recomputed = sha256(concat(
                    row.prevHash(),
                    canonicalJson.serialize(row.toEntry()).getBytes(StandardCharsets.UTF_8)));
            if (!Arrays.equals(recomputed, row.hash())) {
                return VerificationResult.failed(row.seq(), "hash does not match entry content — entry was modified");
            }
            expectedPrev = row.hash();
        }
        return VerificationResult.ok(fromSeq, toSeq);
    }

    @Override
    public Optional<Checkpoint> latestCheckpoint() {
        return checkpoints.findLatest();
    }

    @Override
    public Page<AuditEntry> search(AuditQuery query, Pageable page) {
        return entries.search(query, page).map(AuditEntryRow::toEntry);
    }

    @Override
    public long headSeq() {
        return entries.currentMaxSeq();
    }

    @Override
    public Page<AuditRecord> browse(AuditQuery query, Pageable page) {
        return entries.search(query, page)
                .map(r -> new AuditRecord(
                        r.seq(),
                        r.ts(),
                        r.actorId(),
                        r.action(),
                        r.subjectId(),
                        r.departmentId(),
                        r.outcome(),
                        r.reason(),
                        r.consentId()));
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] concat(byte[] left, byte[] right) {
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
