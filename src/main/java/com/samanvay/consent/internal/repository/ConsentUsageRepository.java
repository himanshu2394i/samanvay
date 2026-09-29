package com.samanvay.consent.internal.repository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * consent_usage (V189, V190): one check per (consent, document type, scope key). The scope key
 * is the application id for ONCE and ONCE_PER_DOCUMENT_PER_APPLICATION (the only scopes today). Plain SQL so
 * the claim is a single atomic statement that never aborts the surrounding transaction.
 */
@Repository
public class ConsentUsageRepository {

    public enum Claim {
        /** No row existed: this grant now holds the one check (PENDING). */
        CLAIMED,
        /** A PENDING row older than the stale window was taken over by this grant. */
        RECLAIMED,
        /** The check is USED, or a fresh PENDING claim is in flight: refuse. */
        REFUSED
    }

    /** {@code id}/{@code token} are null when refused. */
    public record ClaimResult(Claim outcome, UUID id, UUID token) {}

    /*
     * The ON CONFLICT target is the UNIQUE constraint from V189. A concurrent claimant blocks on
     * the other transaction's uncommitted row, then re-evaluates the WHERE against the committed
     * row, so exactly one of two racing claims gets a row back. The takeover only fires for a
     * PENDING row whose claimed_at is older than the stale window; a USED row is never taken, and
     * a takeover writes a NEW claim_token (V190). (xmax = 0) is true for a fresh insert.
     */
    static final String CLAIM = """
            INSERT INTO consent_usage
                (id, consent_id, document_type, scope_key, grant_id, state, claimed_at, claim_token)
            VALUES (?, ?, ?, ?, ?, 'PENDING', clock_timestamp(), ?)
            ON CONFLICT ON CONSTRAINT consent_usage_one_check DO UPDATE
               SET grant_id = EXCLUDED.grant_id, claimed_at = EXCLUDED.claimed_at,
                   claim_token = EXCLUDED.claim_token
             WHERE consent_usage.state = 'PENDING'
               AND consent_usage.claimed_at < clock_timestamp() - make_interval(secs => ?)
            RETURNING id, (xmax = 0) AS inserted
            """;

    private final JdbcTemplate jdbc;

    public ConsentUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ClaimResult claim(
            UUID consentId, String documentType, String scopeKey, UUID grantId, Duration staleAfter) {
        UUID token = UUID.randomUUID();
        List<ClaimResult> rows = jdbc.query(
                CLAIM,
                (rs, i) -> new ClaimResult(
                        rs.getBoolean("inserted") ? Claim.CLAIMED : Claim.RECLAIMED, rs.getObject("id", UUID.class), token),
                UUID.randomUUID(),
                consentId,
                documentType,
                scopeKey,
                grantId,
                token,
                staleAfter.toMillis() / 1000.0);
        return rows.isEmpty() ? new ClaimResult(Claim.REFUSED, null, null) : rows.get(0);
    }

    /** PENDING -> USED only while this claim (id AND token) still holds the row; else 0. */
    public int markUsed(UUID id, UUID token) {
        return jdbc.update(
                "UPDATE consent_usage SET state = 'USED', used_at = clock_timestamp()"
                        + " WHERE id = ? AND claim_token = ? AND state = 'PENDING'",
                id, token);
    }

    /** Deletes the row only while this claim (id AND token) still holds it; else 0. */
    public int release(UUID id, UUID token) {
        return jdbc.update(
                "DELETE FROM consent_usage WHERE id = ? AND claim_token = ? AND state = 'PENDING'", id, token);
    }
}
