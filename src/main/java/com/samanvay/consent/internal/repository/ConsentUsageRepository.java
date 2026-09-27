package com.samanvay.consent.internal.repository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * consent_usage (V189): one check per (consent, document type, application). Plain SQL so the
 * claim is a single atomic statement that never aborts the surrounding transaction.
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

    /*
     * The ON CONFLICT target is the UNIQUE constraint from V189. A concurrent claimant blocks on
     * the other transaction's uncommitted row, then re-evaluates the WHERE against the committed
     * row, so exactly one of two racing claims gets a row back. The takeover only fires for a
     * PENDING row whose claimed_at is older than the stale window; a USED row is never taken.
     * (xmax = 0) is true for a freshly inserted row and false for an updated (taken-over) one.
     */
    static final String CLAIM = """
            INSERT INTO consent_usage (id, consent_id, document_type, application_id, grant_id, state, claimed_at)
            VALUES (?, ?, ?, ?, ?, 'PENDING', clock_timestamp())
            ON CONFLICT ON CONSTRAINT consent_usage_one_check DO UPDATE
               SET grant_id = EXCLUDED.grant_id, claimed_at = EXCLUDED.claimed_at
             WHERE consent_usage.state = 'PENDING'
               AND consent_usage.claimed_at < clock_timestamp() - make_interval(secs => ?)
            RETURNING (xmax = 0) AS inserted
            """;

    private final JdbcTemplate jdbc;

    public ConsentUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Claim claim(UUID consentId, String documentType, String applicationId, UUID grantId, Duration staleAfter) {
        List<Boolean> rows = jdbc.query(
                CLAIM,
                (rs, i) -> rs.getBoolean("inserted"),
                UUID.randomUUID(),
                consentId,
                documentType,
                applicationId,
                grantId,
                staleAfter.toMillis() / 1000.0);
        if (rows.isEmpty()) {
            return Claim.REFUSED;
        }
        return rows.get(0) ? Claim.CLAIMED : Claim.RECLAIMED;
    }

    /** PENDING -> USED for the row this grant holds; 0 if it holds none (no rule, or taken over). */
    public int markUsed(UUID grantId) {
        return jdbc.update(
                "UPDATE consent_usage SET state = 'USED', used_at = clock_timestamp()"
                        + " WHERE grant_id = ? AND state = 'PENDING'",
                grantId);
    }

    /** Deletes the PENDING row this grant holds; 0 if it holds none. */
    public int release(UUID grantId) {
        return jdbc.update("DELETE FROM consent_usage WHERE grant_id = ? AND state = 'PENDING'", grantId);
    }
}
