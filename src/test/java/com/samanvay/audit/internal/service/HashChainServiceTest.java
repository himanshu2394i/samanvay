package com.samanvay.audit.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditRef;
import com.samanvay.audit.api.Outcome;
import com.samanvay.audit.api.VerificationResult;
import com.samanvay.audit.internal.repository.AuditEntryRow;
import com.samanvay.audit.internal.repository.InMemoryAuditEntryRepository;
import com.samanvay.audit.internal.repository.InMemoryCheckpointRepository;
import com.samanvay.shared.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class HashChainServiceTest {

    private final InMemoryAuditEntryRepository entries = new InMemoryAuditEntryRepository();
    private final CanonicalJson canonicalJson = new CanonicalJson();
    private JdbcAuditService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any())).thenReturn(null);
        service = new JdbcAuditService(entries, new InMemoryCheckpointRepository(), jdbc, canonicalJson);
    }

    @Test
    void record_hashesPrevHashConcatCanonical() {
        AuditEntry entry = ping("s1");
        AuditRef ref = service.record(entry);

        byte[] expected = JdbcAuditService.sha256(JdbcAuditService.concat(
                JdbcAuditService.GENESIS_HASH, canonicalJson.serialize(entry).getBytes(StandardCharsets.UTF_8)));
        assertThat(ref.seq()).isEqualTo(1);
        assertThat(ref.hash()).isEqualTo(expected);
        assertThat(service.verify(1, 1).valid()).isTrue();
    }

    @Test
    void verify_detectsAlteredField() {
        service.record(ping("s1"));
        service.record(ping("s2"));
        AuditEntryRow original = entries.rows.get(1);
        entries.replace(
                1,
                new AuditEntryRow(
                        original.seq(),
                        original.ts(),
                        original.actorType(),
                        "tampered",
                        original.action(),
                        original.subjectId(),
                        original.resource(),
                        original.departmentId(),
                        original.consentId(),
                        original.grantId(),
                        original.outcome(),
                        original.reason(),
                        original.meta(),
                        original.prevHash(),
                        original.hash()));

        VerificationResult result = service.verify(1, 2);
        assertThat(result.valid()).isFalse();
        assertThat(result.failedAtSeq()).isEqualTo(2);
        assertThat(result.reason()).contains("modified");
    }

    @Test
    void verify_detectsDeletedRowGap() {
        service.record(ping("s1"));
        service.record(ping("s2"));
        service.record(ping("s3"));
        entries.rows.remove(1);

        VerificationResult result = service.verify(1, 3);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("gap");
    }

    @Test
    void verify_detectsSwappedPrevHash() {
        service.record(ping("s1"));
        service.record(ping("s2"));
        AuditEntryRow original = entries.rows.get(1);
        entries.replace(
                1,
                new AuditEntryRow(
                        original.seq(),
                        original.ts(),
                        original.actorType(),
                        original.actorId(),
                        original.action(),
                        original.subjectId(),
                        original.resource(),
                        original.departmentId(),
                        original.consentId(),
                        original.grantId(),
                        original.outcome(),
                        original.reason(),
                        original.meta(),
                        JdbcAuditService.GENESIS_HASH,
                        original.hash()));

        VerificationResult result = service.verify(1, 2);
        assertThat(result.valid()).isFalse();
        assertThat(result.failedAtSeq()).isEqualTo(2);
        assertThat(result.reason()).contains("prev_hash");
    }

    private static AuditEntry ping(String subject) {
        return new AuditEntry(
                ActorType.SYSTEM,
                "test",
                "PING",
                subject,
                null,
                null,
                null,
                null,
                Outcome.ALLOWED,
                null,
                Map.of());
    }
}
