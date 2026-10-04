package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The expiry marker and retention purge against real Postgres: real FKs, real audit table. */
@SpringBootTest(classes = SamanvayApplication.class)
class ConsentLifecycleJobsIT extends PostgresIntegrationTest {

    static final Duration SEVEN_YEARS = Duration.ofDays(2555);

    @Autowired
    ConsentServices consents;

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void expiryMarksOnlyActiveConsentsPastValidUntil() {
        UUID due = seed("ACTIVE", days(-2), days(-1), null);
        UUID notYet = seed("ACTIVE", days(-2), days(30), null);
        UUID revoked = seed("REVOKED", days(-40), days(-30), days(-35));

        assertThat(consents.markExpired()).isGreaterThanOrEqualTo(1);

        assertThat(status(due)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT version FROM consent_artifact WHERE id = ?", Integer.class, due))
                .isEqualTo(2);
        assertThat(count("consent_event WHERE event_type = 'EXPIRED' AND consent_id", due)).isEqualTo(1);
        assertThat(count("audit.audit_entry WHERE action = 'CONSENT_EXPIRED' AND actor_type = 'SYSTEM' AND consent_id", due))
                .isEqualTo(1);
        assertThat(status(notYet)).isEqualTo("ACTIVE");
        assertThat(status(revoked)).isEqualTo("REVOKED");
        assertThat(count("audit.audit_entry WHERE consent_id", notYet)).isZero();
    }

    @Test
    void purgeDeletesEndedRecordsPastRetentionWithTheirChildrenAndKeepsAudit() {
        UUID revokedOld = seed("REVOKED", years(-9), years(-8), years(-8));
        UUID revokedNoTime = seed("REVOKED", years(-9), years(-8), null); // pre-V187: falls back to updated_at
        UUID expiredOld = seed("EXPIRED", years(-9), years(-8), null);
        UUID revokedRecent = seed("REVOKED", years(-2), years(-1), years(-1));
        UUID expiredRecent = seed("EXPIRED", years(-2), years(-1), null);
        UUID activeUnmarked = seed("ACTIVE", years(-9), years(-8), null);
        UUID activeLive = seed("ACTIVE", days(-1), days(300), null);
        for (UUID id : new UUID[] {revokedOld, expiredOld, revokedRecent}) {
            seedChildren(id);
        }
        jdbc.update(
                "UPDATE consent_artifact SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(years(-8)),
                revokedNoTime);
        insertAudit(revokedOld);

        assertThat(consents.purgeEndedRecords(SEVEN_YEARS)).isGreaterThanOrEqualTo(3);

        for (UUID gone : new UUID[] {revokedOld, revokedNoTime, expiredOld}) {
            assertThat(count("consent_artifact WHERE id", gone)).as("artifact %s", gone).isZero();
            assertThat(count("consent_event WHERE consent_id", gone)).isZero();
            assertThat(count("consent_access_grant WHERE consent_id", gone)).isZero();
            assertThat(count("consent_usage WHERE consent_id", gone)).isZero();
        }
        for (UUID kept : new UUID[] {revokedRecent, expiredRecent, activeUnmarked, activeLive}) {
            assertThat(count("consent_artifact WHERE id", kept)).as("artifact %s", kept).isEqualTo(1);
        }
        assertThat(count("consent_event WHERE consent_id", revokedRecent)).isEqualTo(1);
        assertThat(count("consent_access_grant WHERE consent_id", revokedRecent)).isEqualTo(1);
        assertThat(count("consent_usage WHERE consent_id", revokedRecent)).isEqualTo(1);
        // The audit record of a purged consent is permanent.
        assertThat(count("audit.audit_entry WHERE consent_id", revokedOld)).isEqualTo(1);
    }

    @Test
    void purgeSucceedsForADepartmentGrantedConsentWithEvidenceAndItsStatementNonce() {
        // A consent granted on a department portal keeps its signed statement in consent_evidence (V206, FK to the artifact);
        // the request keeps a one-time nonce row. Both must go with the consent, or the purge fails on the FK.
        UUID dept = seed("EXPIRED", years(-9), years(-8), null);
        UUID request = UUID.randomUUID();
        jdbc.update("INSERT INTO consent_request (id, subject_citizen_id, requester_id, purpose_code, purpose_text, data_categories,"
                + " status, created_at, responded_at) VALUES (?, ?, 'SCHOLARSHIP', 'SCHOLARSHIP_ELIGIBILITY', 'text',"
                + " ARRAY['INCOME_CERTIFICATE'], 'GRANTED', ?, ?)", request, UUID.randomUUID(),
                java.sql.Timestamp.from(years(-9)), java.sql.Timestamp.from(years(-9)));
        jdbc.update("INSERT INTO consent_statement_nonce (request_id, nonce, expires_at, used_at) VALUES (?, 'n', ?, ?)", request,
                java.sql.Timestamp.from(years(-9)), java.sql.Timestamp.from(years(-9)));
        jdbc.update("INSERT INTO consent_evidence (consent_id, statement, jti, department_code, key_thumbprint, created_at)"
                + " VALUES (?, 'a.b.c', ?, 'SCHOLARSHIP', 'thumb', ?)", dept, "jti-" + dept, java.sql.Timestamp.from(years(-9)));

        assertThat(consents.purgeEndedRecords(SEVEN_YEARS)).isGreaterThanOrEqualTo(1);

        assertThat(count("consent_artifact WHERE id", dept)).isZero();
        assertThat(count("consent_evidence WHERE consent_id", dept)).isZero();
        assertThat(count("consent_statement_nonce WHERE request_id", request)).isZero();
    }

    private static Instant days(int offset) {
        return Instant.now().plus(Duration.ofDays(offset));
    }

    private static Instant years(int offset) {
        return Instant.now().plus(Duration.ofDays(365L * offset));
    }

    private UUID seed(String status, Instant validFrom, Instant validUntil, Instant revokedAt) {
        UUID id = UUID.randomUUID();
        Instant updated = revokedAt != null ? revokedAt : validUntil;
        jdbc.update(
                """
                INSERT INTO consent_artifact (id, subject_citizen_id, requester_id, purpose_code, purpose_text,
                    data_categories, granularity, valid_from, valid_until, status, version, citizen_auth_ref,
                    created_at, updated_at, revoked_at, revoked_by)
                VALUES (?, ?, 'SCHOLARSHIP', 'SCHOLARSHIP_ELIGIBILITY', 'text', ARRAY['INCOME_CERTIFICATE'],
                    'RECURRING', ?, ?, ?, 1, 'jti', ?, ?, ?, ?)
                """,
                id,
                UUID.randomUUID(),
                java.sql.Timestamp.from(validFrom),
                java.sql.Timestamp.from(validUntil),
                status,
                java.sql.Timestamp.from(validFrom),
                java.sql.Timestamp.from(updated),
                revokedAt == null ? null : java.sql.Timestamp.from(revokedAt),
                revokedAt == null ? null : "citizen");
        return id;
    }

    private void seedChildren(UUID consentId) {
        jdbc.update(
                "INSERT INTO consent_event (id, consent_id, event_type, occurred_at) VALUES (?, ?, 'GRANTED', now())",
                UUID.randomUUID(),
                consentId);
        jdbc.update(
                """
                INSERT INTO consent_access_grant (id, nonce, consent_id, consent_version, subject_citizen_id,
                    requester_id, data_category, department_id, connector_ref, purpose_code, issued_at, expires_at,
                    signature)
                VALUES (?, ?, ?, 1, ?, 'SCHOLARSHIP', 'INCOME_CERTIFICATE', 'REVENUE', 'rev-income@1',
                    'SCHOLARSHIP_ELIGIBILITY', now(), now(), ?)
                """,
                UUID.randomUUID(),
                UUID.randomUUID().toString().getBytes(),
                consentId,
                UUID.randomUUID(),
                new byte[] {1});
        jdbc.update(
                """
                INSERT INTO consent_usage (id, consent_id, document_type, scope_key, grant_id, state, claimed_at,
                    claim_token)
                VALUES (?, ?, 'INCOME_CERTIFICATE', 'app-1', ?, 'PENDING', now(), ?)
                """,
                UUID.randomUUID(),
                consentId,
                UUID.randomUUID(),
                UUID.randomUUID());
    }

    /**
     * A real audit row for the consent, appended through the service so the shared database's hash chain stays valid
     * (a hand-made row with made-up hashes broke every later whole-chain verify, e.g. in AuditAttributionIT).
     */
    private void insertAudit(UUID consentId) {
        audit.record(new AuditEntry(
                ActorType.CITIZEN, "c", "CONSENT_REVOKED", null, null, null, consentId, null, Outcome.ALLOWED, null, Map.of()));
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM consent_artifact WHERE id = ?", String.class, id);
    }

    /** {@code from} is a table plus a WHERE ending in the id column; the id is bound. */
    private int count(String from, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + from + " = ?", Integer.class, id);
    }
}
