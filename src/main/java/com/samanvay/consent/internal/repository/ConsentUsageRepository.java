package com.samanvay.consent.internal.repository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * consent_usage (V189, V190, V196): one check per (consent, document type, scope key). The scope
 * key is the application id for ONCE and ONCE_PER_DOCUMENT_PER_APPLICATION, {@code YEAR:<year>} for
 * ONCE_PER_YEAR, and {@code PAYMENT:<keyed HMAC of the payment id>} for ONCE_PER_PAYMENT, whose
 * {@code scope_key_version} names the key that produced the hash. Plain SQL so the claim is a
 * single atomic statement that never aborts the surrounding transaction.
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
                (id, consent_id, document_type, scope_key, grant_id, state, claimed_at, claim_token,
                 scope_key_version)
            VALUES (?, ?, ?, ?, ?, 'PENDING', clock_timestamp(), ?, ?)
            ON CONFLICT ON CONSTRAINT consent_usage_one_check DO UPDATE
               SET grant_id = EXCLUDED.grant_id, claimed_at = EXCLUDED.claimed_at,
                   claim_token = EXCLUDED.claim_token
             WHERE consent_usage.state = 'PENDING'
               AND consent_usage.claimed_at < clock_timestamp() - make_interval(secs => ?)
            RETURNING id, (xmax = 0) AS inserted
            """;

    /*
     * Is this scope key held: USED, or PENDING and still fresh? Used to look for a check made
     * under a retired payment-scope key, without writing anything. A stale PENDING row is
     * released (a later claim would take it over), so it does not block.
     */
    static final String HELD = """
            SELECT EXISTS (
                SELECT 1 FROM consent_usage
                 WHERE consent_id = ? AND document_type = ? AND scope_key = ?
                   AND (state = 'USED'
                        OR claimed_at >= clock_timestamp() - make_interval(secs => ?)))
            """;

    private final JdbcTemplate jdbc;

    public ConsentUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code scopeKeyVersion} is the key that produced {@code scopeKey}; null when it is not a keyed hash. */
    public ClaimResult claim(
            UUID consentId, String documentType, String scopeKey, String scopeKeyVersion, UUID grantId,
            Duration staleAfter) {
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
                scopeKeyVersion,
                staleAfter.toMillis() / 1000.0);
        return rows.isEmpty() ? new ClaimResult(Claim.REFUSED, null, null) : rows.get(0);
    }

    /** True when the check for this scope key is used or in flight (a fresh PENDING claim). */
    public boolean isHeld(UUID consentId, String documentType, String scopeKey, Duration staleAfter) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                HELD, Boolean.class, consentId, documentType, scopeKey, staleAfter.toMillis() / 1000.0));
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
