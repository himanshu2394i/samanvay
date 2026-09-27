package com.samanvay.consent.api;

/**
 * Settles the one-check claim ({@link AccessDecision.Granted#claim()}) taken when a grant was
 * issued under a consent that allows one check per document per application. Call exactly one of
 * these after the connector call returns (or throws), and only when the grant holds a claim.
 *
 * <p>Both return {@code false} when the claim was lost (a later check took it over after it went
 * stale, writing a new token): nothing is changed, exactly one {@code CONSENT_CHECK_LOST_CLAIM}
 * audit row is written, and the caller must throw the fetched result away.
 */
public interface ConsentUsage {

    /** The department answered: the check is spent (PENDING -> USED). */
    boolean markUsed(AccessGrant grant, UsageClaim claim);

    /**
     * The check did not happen (timeout, 5xx, malformed reply, ...): the claim is deleted so the
     * citizen can retry, and the failed attempt is audited with {@code reason}.
     */
    boolean release(AccessGrant grant, UsageClaim claim, String reason);
}
