package com.samanvay.audit.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditRef;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.audit.internal.repository.AuditEntryRepository;
import com.samanvay.audit.internal.repository.AuditEntryRow;
import com.samanvay.shared.CanonicalJson;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * An entry carrying the attribution fields (principal actor type/id and the
 * catalog purpose in meta) reads back from Postgres to exactly the bytes that
 * were hashed on write, so the stored hash recomputes byte-for-byte.
 */
@SpringBootTest(classes = SamanvayApplication.class)
class AuditPrincipalRoundTripIT extends PostgresIntegrationTest {

    @Autowired
    AuditService audit;

    @Autowired
    AuditEntryRepository entries;

    @Autowired
    CanonicalJson canonicalJson;

    @ParameterizedTest
    @EnumSource(
            value = ActorType.class,
            names = {"OFFICER", "DEPARTMENT", "CITIZEN", "ADMIN"})
    void attributedEntryRecomputesToStoredHash(ActorType actorType) {
        AuditEntry written = new AuditEntry(
                actorType,
                "principal-" + actorType.name().toLowerCase() + "-" + UUID.randomUUID(),
                "DATA_ACCESSED",
                UUID.randomUUID().toString(),
                "rev-income@1",
                "REVENUE",
                UUID.randomUUID(),
                UUID.randomUUID(),
                Outcome.ALLOWED,
                null,
                Map.of(
                        "purpose", "SCHOLARSHIP_ELIGIBILITY",
                        "journey", "POST_MATRIC_SCHOLARSHIP",
                        "status", 200,
                        // e.g. CANDIDATE_CONFIRMED's score: trailing zeros must survive jsonb.
                        "score", new BigDecimal("0.800")));
        AuditRef ref = audit.record(written);

        List<AuditEntryRow> rows = entries.findRange(ref.seq(), ref.seq());
        assertThat(rows).hasSize(1);
        AuditEntryRow row = rows.getFirst();
        assertThat(row.actorType()).isEqualTo(actorType);
        assertThat(row.actorId()).isEqualTo(written.actorId());
        assertThat(row.meta()).containsEntry("purpose", "SCHOLARSHIP_ELIGIBILITY");

        byte[] writtenCanonical = canonicalJson.serialize(written).getBytes(StandardCharsets.UTF_8);
        byte[] readCanonical = canonicalJson.serialize(row.toEntry()).getBytes(StandardCharsets.UTF_8);
        assertThat(readCanonical).as("canonical bytes after round trip").isEqualTo(writtenCanonical);

        byte[] recomputed = JdbcAuditService.sha256(JdbcAuditService.concat(row.prevHash(), readCanonical));
        assertThat(recomputed).as("recomputed hash").isEqualTo(row.hash()).isEqualTo(ref.hash());
        assertThat(audit.verify(ref.seq(), ref.seq()).valid()).isTrue();
    }
}
