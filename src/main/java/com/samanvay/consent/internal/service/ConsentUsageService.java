package com.samanvay.consent.internal.service;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.ConsentUsage;
import com.samanvay.consent.internal.repository.ConsentUsageRepository;
import com.samanvay.consent.internal.repository.ConsentUsageRepository.Claim;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One check per document per application (V189 consent_usage).
 *
 * <p>Which consents it applies to: those whose frequency (copied from the catalog purpose at
 * grant time) is {@code ONCE} or {@code ONCE_PER_DOCUMENT_PER_APPLICATION}; both mean "one
 * check of a document for an application". Every other value ({@code ONCE_PER_PAYMENT},
 * {@code ONCE_PER_YEAR}, anything unknown) and NULL (legacy purposes, consents granted before
 * V189) is not enforced here: those consents keep only the existing 24-hour frequency limit.
 *
 * <p>The claim is made inside the grant-check transaction and committed with it, before any
 * connector call. A PENDING claim older than {@link #staleAfter()} (2x the configured connector
 * timeout) counts as released and the next check takes it over atomically.
 */
@Service
class ConsentUsageService implements ConsentUsage {

    private static final Logger log = LoggerFactory.getLogger(ConsentUsageService.class);

    static final Set<String> ONE_CHECK_FREQUENCIES = Set.of("ONCE", "ONCE_PER_DOCUMENT_PER_APPLICATION");

    private final ConsentUsageRepository usage;
    private final AuditService audit;
    private final Duration staleAfter;

    ConsentUsageService(
            ConsentUsageRepository usage,
            AuditService audit,
            @Value("${samanvay.connector.timeout:PT10S}") Duration connectorTimeout) {
        this.usage = usage;
        this.audit = audit;
        this.staleAfter = connectorTimeout.multipliedBy(2);
    }

    static boolean oneCheck(String frequency) {
        return frequency != null && ONE_CHECK_FREQUENCIES.contains(frequency);
    }

    Duration staleAfter() {
        return staleAfter;
    }

    /** Runs in the caller's (grant-check) transaction. */
    Claim claim(UUID consentId, String documentType, String applicationId, UUID grantId) {
        return usage.claim(consentId, documentType, applicationId, grantId, staleAfter);
    }

    /** Drops this grant's claim inside the grant-check transaction (a later check refused it). */
    void discard(UUID grantId) {
        usage.release(grantId);
    }

    @Override
    @Transactional
    public void markUsed(AccessGrant grant) {
        if (usage.markUsed(grant.id()) == 0) {
            // Either no one-check rule applied, or this call outlived the stale window and another
            // check took the claim over (see the PR risks). Nothing to settle.
            log.debug("no pending usage claim for grant {}", grant.id());
        }
    }

    @Override
    @Transactional
    public void release(AccessGrant grant, String reason) {
        if (usage.release(grant.id()) == 0) {
            return; // no claim held: no rule applied (unchanged behaviour), or already taken over
        }
        audit.record(new AuditEntry(
                ActorType.SYSTEM,
                grant.requester().id(),
                "CONSENT_CHECK_RELEASED",
                grant.subject().citizenId().toString(),
                grant.category().code(),
                grant.departmentCode(),
                grant.consentId(),
                grant.id(),
                Outcome.ERROR,
                reason,
                Map.of(
                        "purpose", grant.purpose().code(),
                        "principalType", grant.principal().kind().name(),
                        "principalId", grant.principal().id())));
    }
}
