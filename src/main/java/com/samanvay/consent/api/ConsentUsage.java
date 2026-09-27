package com.samanvay.consent.api;

/**
 * Settles the one-check usage claimed when an {@link AccessGrant} was issued under a consent
 * that allows one check per document per application. Call exactly one of these after the
 * connector call returns (or throws). For grants that claimed nothing both are no-ops.
 */
public interface ConsentUsage {

    /** The department answered: the check is spent (PENDING -> USED). */
    void markUsed(AccessGrant grant);

    /**
     * The check did not happen (timeout, 5xx, malformed reply, ...): the claim is deleted so the
     * citizen can retry, and the failed attempt is audited with {@code reason}.
     */
    void release(AccessGrant grant, String reason);
}
