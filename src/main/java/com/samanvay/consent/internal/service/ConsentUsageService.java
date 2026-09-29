package com.samanvay.consent.internal.service;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.catalog.api.Purpose;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.ConsentUsage;
import com.samanvay.consent.api.UsageClaim;
import com.samanvay.consent.internal.repository.ConsentUsageRepository;
import com.samanvay.consent.internal.repository.ConsentUsageRepository.ClaimResult;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One check per document per application (V189/V190 consent_usage).
 *
 * <p>Which consents it applies to: those whose frequency (copied from the catalog purpose at
 * grant time) is {@code ONCE} or {@code ONCE_PER_DOCUMENT_PER_APPLICATION} (scoped by the
 * application id), or {@code ONCE_PER_YEAR} (scoped by the calendar year, Asia/Kolkata).
 * {@code ONCE_PER_PAYMENT} and NULL (legacy purposes, consents granted before V189) are not
 * enforced here: those keep only the existing 24-hour frequency limit. Per-payment scoping
 * needs a payment/instalment id that arrives with the disbursement flow (a later PR). No other
 * value can exist (V190 CHECK constraints, and {@link Purpose.Frequency#fromCode} throws on load).
 *
 * <p>The claim is made inside the grant-check transaction and committed with it, before any
 * connector call. A PENDING claim older than {@link #staleAfter()} (2x the configured connector
 * timeout) counts as released and the next check takes it over atomically with a new token.
 */
@Service
class ConsentUsageService implements ConsentUsage {

    private final ConsentUsageRepository usage;
    private final AuditService audit;
    private final Duration staleAfter;

    ConsentUsageService(
            ConsentUsageRepository usage,
            AuditService audit,
            @Value("${samanvay.connector.timeout:PT10S}") Duration connectorTimeout,
            @Value("${samanvay.connector.total-timeout:PT10S}") Duration totalTimeout,
            @Value("${samanvay.connector.stale-margin:PT5S}") Duration staleMargin,
            @Value("${samanvay.connector.retry.max-attempts:3}") int retryMaxAttempts,
            @Value("${samanvay.connector.retry.wait:PT0.5S}") Duration retryWait) {
        this.usage = usage;
        this.audit = audit;
        this.staleAfter = connectorTimeout.multipliedBy(2);
        ConnectorTimingCheck.validate(staleAfter, totalTimeout, staleMargin, retryMaxAttempts, retryWait);
    }

    Duration staleAfter() {
        return staleAfter;
    }

    /**
     * Runs in the caller's (grant-check) transaction. {@code scopeKey} is what "one check" is
     * counted per: the application id for ONCE / ONCE_PER_DOCUMENT_PER_APPLICATION, or the year
     * for ONCE_PER_YEAR. The caller (AccessAuthority) picks it from the consent's frequency.
     */
    ClaimResult claim(UUID consentId, String documentType, String scopeKey, UUID grantId) {
        return usage.claim(consentId, documentType, scopeKey, grantId, staleAfter);
    }

    /** Drops this grant's claim inside the grant-check transaction (a later check refused it). */
    void discard(UsageClaim claim) {
        usage.release(claim.id(), claim.token());
    }

    @Override
    @Transactional
    public boolean markUsed(AccessGrant grant, UsageClaim claim) {
        if (usage.markUsed(claim.id(), claim.token()) == 1) {
            return true;
        }
        lostClaim(grant, claim, "MARK_USED", null);
        return false;
    }

    @Override
    @Transactional
    public boolean release(AccessGrant grant, UsageClaim claim, String reason) {
        if (usage.release(claim.id(), claim.token()) == 0) {
            lostClaim(grant, claim, "RELEASE", reason);
            return false;
        }
        audit.record(entry(grant, "CONSENT_CHECK_RELEASED", reason, Map.of("usageClaimId", claim.id().toString())));
        return true;
    }

    /** Exactly one row: the claim was taken over while the department call ran. */
    private void lostClaim(AccessGrant grant, UsageClaim claim, String stage, String failure) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("usageClaimId", claim.id().toString());
        meta.put("stage", stage);
        if (failure != null) {
            meta.put("failure", failure);
        }
        audit.record(entry(grant, "CONSENT_CHECK_LOST_CLAIM", "RESULT_DISCARDED", meta));
    }

    private static AuditEntry entry(AccessGrant grant, String action, String reason, Map<String, Object> extra) {
        Map<String, Object> meta = new HashMap<>(extra);
        meta.put("purpose", grant.purpose().code());
        meta.put("principalType", grant.principal().kind().name());
        meta.put("principalId", grant.principal().id());
        return new AuditEntry(
                ActorType.SYSTEM,
                grant.requester().id(),
                action,
                grant.subject().citizenId().toString(),
                grant.category().code(),
                grant.departmentCode(),
                grant.consentId(),
                grant.id(),
                Outcome.ERROR,
                reason,
                meta);
    }
}
