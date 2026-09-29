package com.samanvay.consent.internal.service;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.JourneyDefinition;
import com.samanvay.catalog.api.PurposeCatalog;
import com.samanvay.consent.api.RequesterNotEntitledException;
import com.samanvay.consent.api.UnknownPurposeException;
import com.samanvay.catalog.api.Purpose;
import com.samanvay.consent.api.AccessAuthority;
import com.samanvay.consent.api.AccessDecision;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.ApprovedAwards;
import com.samanvay.consent.api.NoPriorAwardException;
import com.samanvay.consent.api.NotAwardingDepartmentException;
import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentArtifact;
import com.samanvay.consent.api.ConsentGranted;
import com.samanvay.consent.api.ConsentNotFoundException;
import com.samanvay.consent.api.ConsentRequest;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentRequested;
import com.samanvay.consent.api.ConsentRevoked;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.consent.api.DenialReason;
import com.samanvay.consent.api.UnsignedGrant;
import com.samanvay.consent.internal.domain.AccessGrantEntity;
import com.samanvay.consent.internal.domain.ConsentArtifactEntity;
import com.samanvay.consent.internal.domain.ConsentEventEntity;
import com.samanvay.consent.internal.domain.ConsentRequestEntity;
import com.samanvay.consent.internal.repository.AccessGrantRepository;
import com.samanvay.consent.internal.repository.ConsentArtifactRepository;
import com.samanvay.consent.internal.repository.ConsentEventRepository;
import com.samanvay.consent.internal.repository.ConsentRequestRepository;
import com.samanvay.consent.api.UsageClaim;
import com.samanvay.consent.internal.repository.ConsentUsageRepository.Claim;
import com.samanvay.consent.internal.repository.ConsentUsageRepository.ClaimResult;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.registry.api.DiscoveryRegistry;
import com.samanvay.registry.api.Sensitivity;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PrincipalRef;
import com.samanvay.shared.SamanvayException;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ConsentServices implements ConsentService, AccessAuthority {

    private final ConsentRequestRepository requests;
    private final ConsentArtifactRepository artifacts;
    private final ConsentEventRepository eventsLog;
    private final AccessGrantRepository grants;
    private final IdentityLinking identityLinking;
    private final DiscoveryRegistry registry;
    private final JourneyCatalog journeys;
    private final PurposeCatalog purposes;
    private final ApprovedAwards awards;
    private final RefusalAuditor refusalAudit;
    private final ConsentUsageService usage;
    private final GrantSigner signer;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Consent lifetime when the catalog purpose sets no max (the pre-V186 behaviour). */
    static final Duration DEFAULT_MAX_DURATION = Duration.ofDays(365);

    ConsentServices(
            ConsentRequestRepository requests,
            ConsentArtifactRepository artifacts,
            ConsentEventRepository eventsLog,
            AccessGrantRepository grants,
            IdentityLinking identityLinking,
            DiscoveryRegistry registry,
            JourneyCatalog journeys,
            PurposeCatalog purposes,
            ApprovedAwards awards,
            RefusalAuditor refusalAudit,
            ConsentUsageService usage,
            GrantSigner signer,
            AuditService audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.requests = requests;
        this.artifacts = artifacts;
        this.eventsLog = eventsLog;
        this.grants = grants;
        this.identityLinking = identityLinking;
        this.registry = registry;
        this.journeys = journeys;
        this.purposes = purposes;
        this.awards = awards;
        this.refusalAudit = refusalAudit;
        this.usage = usage;
        this.signer = signer;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /**
     * A refusal of a known purpose is audited once, in its own transaction (so it survives this
     * one rolling back), and the exception is marked audited so no generic refused-call entry
     * is added for it.
     */
    @Override
    @Transactional
    public ConsentRequest request(ConsentRequestDraft draft) {
        // Text and categories come from the catalog purpose, never from the caller;
        // only the purpose's own department may request under it.
        Purpose purpose = purposes.byCode(draft.purposeCode())
                .filter(Purpose::active)
                .filter(p -> !p.dataCategories().isEmpty())
                .orElseThrow(() -> new UnknownPurposeException(draft.purposeCode()));
        if (purpose.requesterRule() == Purpose.RequesterRule.PRIOR_AWARD_DEPARTMENT) {
            requirePriorAwardBy(draft, purpose);
        }
        if (draft.requesterId() == null || !draft.requesterId().equals(purpose.requesterDepartment())) {
            throw refused(draft, purpose, new RequesterNotEntitledException(draft.requesterId(), purpose.code()));
        }
        ConsentRequestEntity e = new ConsentRequestEntity();
        e.setId(UUID.randomUUID());
        e.setSubjectCitizenId(draft.citizenId());
        e.setRequesterId(draft.requesterId());
        e.setPurposeCode(purpose.code());
        e.setPurposeText(purpose.text());
        e.setDataCategories(purpose.dataCategories().toArray(String[]::new));
        e.setStatus("PENDING");
        e.setCreatedAt(clock.instant());
        requests.save(e);
        events.publishEvent(new ConsentRequested(
                e.getId(),
                e.getSubjectCitizenId(),
                e.getRequesterId(),
                e.getPurposeCode(),
                Arrays.asList(e.getDataCategories())));
        return toRequest(e);
    }

    /**
     * The citizen needs an approved award in the academic year immediately before the current
     * one, decided by the requester. Both the academic year (its start month) and the deciding
     * department come from the award's own journey (scheme) configuration; a scheme without an
     * academic year never yields a prior award.
     */
    private void requirePriorAwardBy(ConsentRequestDraft draft, Purpose purpose) {
        Instant now = clock.instant();
        List<JourneyDefinition> priorAwardSchemes = awards.forCitizen(draft.citizenId()).stream()
                .filter(a -> a.decidedAt() != null)
                .flatMap(a -> scheme(a.journeyCode())
                        .filter(j -> j.academicYearStartMonth() != null
                                && AcademicYears.isPriorYear(a.decidedAt(), now, j.academicYearStartMonth()))
                        .stream())
                .toList();
        if (priorAwardSchemes.isEmpty()) {
            throw refused(draft, purpose, new NoPriorAwardException(purpose.code()));
        }
        boolean decidedByRequester = draft.requesterId() != null
                && priorAwardSchemes.stream().anyMatch(j -> draft.requesterId().equals(j.policy().requester()));
        if (!decidedByRequester) {
            throw refused(draft, purpose, new NotAwardingDepartmentException(draft.requesterId(), purpose.code()));
        }
    }

    private Optional<JourneyDefinition> scheme(String journeyCode) {
        try {
            return Optional.ofNullable(journeys.byCode(journeyCode));
        } catch (RuntimeException unknownJourney) {
            return Optional.empty();
        }
    }

    /**
     * Audits a refused consent request in its own transaction and returns the exception to
     * throw, marked as audited (one audit row per refusal).
     */
    private SamanvayException refused(ConsentRequestDraft draft, Purpose purpose, SamanvayException refusal) {
        PrincipalRef by = draft.principal();
        Map<String, Object> meta = new HashMap<>();
        meta.put("purpose", purpose.code());
        meta.put("requester", draft.requesterId() == null ? "" : draft.requesterId());
        if (by != null) {
            meta.put("principalType", by.kind().name());
            meta.put("principalId", by.id());
        }
        refusalAudit.record(new AuditEntry(
                by == null ? ActorType.SYSTEM : actorType(by),
                by == null ? String.valueOf(draft.requesterId()) : by.id(),
                "CONSENT_REQUEST_REFUSED",
                draft.citizenId() == null ? null : draft.citizenId().toString(),
                "consent",
                draft.requesterId(),
                null,
                null,
                Outcome.DENIED,
                refusal.reason(),
                meta));
        refusal.markAudited();
        return refusal;
    }

    /**
     * A pending request the citizen can grant - only if the requester may ask under this purpose
     * at all. Never for a separate opt-in or award-gated purpose: those are asked for explicitly,
     * not raised as a side effect of a fetch.
     */
    private ConsentRequest remedy(AccessRequest req) {
        boolean entitled = purposes.byCode(req.purpose().code())
                .map(p -> req.requester().id().equals(p.requesterDepartment())
                        && !p.dataCategories().isEmpty()
                        && !p.separateOptIn()
                        && p.requesterRule() == Purpose.RequesterRule.CATALOG_DEPARTMENT)
                .orElse(false);
        if (!entitled) {
            return null;
        }
        return request(new ConsentRequestDraft(req.subject().citizenId(), req.requester().id(), req.purpose().code()));
    }

    @Override
    @Transactional
    public ConsentArtifact grant(UUID requestId, UUID citizenId, AuthProof proof, PrincipalRef by) {
        ConsentRequestEntity req = requests.findById(requestId).orElseThrow(ConsentNotFoundException::new);
        if (!req.getSubjectCitizenId().equals(citizenId)) {
            throw new ConsentNotFoundException();
        }
        // Data types and the lifetime cap are read from the catalog now, at grant time.
        Purpose purpose = purposes.byCode(req.getPurposeCode())
                .filter(Purpose::active)
                .orElseThrow(() -> new UnknownPurposeException(req.getPurposeCode()));
        Instant now = clock.instant();
        Duration lifetime = purpose.maxDurationDays() == null
                ? DEFAULT_MAX_DURATION
                : min(DEFAULT_MAX_DURATION, Duration.ofDays(purpose.maxDurationDays()));
        req.setStatus("GRANTED");
        req.setRespondedAt(clock.instant());
        requests.save(req);
        ConsentArtifactEntity a = new ConsentArtifactEntity();
        a.setId(UUID.randomUUID());
        a.setSubjectCitizenId(citizenId);
        a.setRequesterId(req.getRequesterId());
        a.setPurposeCode(req.getPurposeCode());
        a.setPurposeText(req.getPurposeText());
        a.setDataCategories(req.getDataCategories());
        a.setGranularity("RECURRING");
        a.setDataTypes(purpose.dataTypes().toArray(String[]::new));
        a.setValidFrom(now);
        a.setValidUntil(now.plus(lifetime));
        a.setFrequencyLimit(20);
        a.setFrequency(purpose.frequency() == null ? null : purpose.frequency().name());
        a.setStatus("ACTIVE");
        a.setVersion(1);
        a.setCitizenAuthRef(proof.citizenAuthRef());
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        artifacts.save(a);
        logEvent(a.getId(), "GRANTED");
        audit.record(consentEntry(a, by, "CONSENT_GRANTED"));
        for (String cat : a.getDataCategories()) {
            registry.allowDiscovery(citizenId, a.getRequesterId(), cat, a.getId());
        }
        events.publishEvent(new ConsentGranted(a.getId(), citizenId, a.getRequesterId(), Arrays.asList(a.getDataCategories())));
        return toArtifact(a);
    }

    @Override
    @Transactional
    public void revoke(UUID consentId, UUID citizenId, String reason, PrincipalRef by) {
        ConsentArtifactEntity a = artifacts.findById(consentId).orElseThrow(ConsentNotFoundException::new);
        if (!a.getSubjectCitizenId().equals(citizenId)) {
            throw new ConsentNotFoundException();
        }
        if (!"ACTIVE".equals(a.getStatus())) {
            return; // already withdrawn or ended: keep the original revoked_at/revoked_by
        }
        Instant now = clock.instant();
        a.setStatus("REVOKED");
        a.setVersion(a.getVersion() + 1);
        a.setRevokedAt(now);
        a.setRevokedBy(by.id());
        a.setUpdatedAt(now);
        artifacts.save(a);
        logEvent(a.getId(), "REVOKED");
        audit.record(consentEntry(a, by, "CONSENT_REVOKED"));
        registry.revokeDiscovery(consentId);
        events.publishEvent(new ConsentRevoked(consentId, citizenId, a.getVersion()));
    }

    @Override
    public Optional<UUID> ownerOf(UUID consentId) {
        return consentId == null
                ? Optional.empty()
                : artifacts.findById(consentId).map(ConsentArtifactEntity::getSubjectCitizenId);
    }

    @Override
    public List<ConsentArtifact> forCitizen(UUID citizenId) {
        return artifacts.findBySubjectCitizenId(citizenId).stream().map(this::toArtifact).toList();
    }

    @Override
    public Optional<ConsentArtifact> find(RequesterRef requester, SubjectRef subject, DataCategory category, PurposeCode purpose) {
        return artifacts
                .findMatching(requester.id(), subject.citizenId(), purpose.code(), category.code())
                .map(this::toArtifact);
    }

    @Override
    @Transactional
    public AccessDecision authorize(AccessRequest req) {
        if (req.purpose() == null || !purposes.isActive(req.purpose().code())) {
            return deny(req, DenialReason.UNKNOWN_PURPOSE, null);
        }
        if (identityLinking.activeLink(req.subject().citizenId(), req.departmentCode()).isEmpty()) {
            return deny(req, DenialReason.NO_ACTIVE_LINK, null);
        }
        var consent = find(req.requester(), req.subject(), req.category(), req.purpose());
        if (consent.isEmpty()) {
            return deny(req, DenialReason.NO_CONSENT, remedy(req));
        }
        var c = consent.get();
        if ("REVOKED".equals(c.status())) {
            return deny(req, DenialReason.CONSENT_REVOKED, null, c.id(), null);
        }
        if ("EXPIRED".equals(c.status()) || !c.validUntil().isAfter(clock.instant())) {
            Instant now = clock.instant();
            Instant endedAt = c.validUntil().isAfter(now) ? now : c.validUntil();
            return deny(req, DenialReason.CONSENT_EXPIRED, null, c.id(), endedAt);
        }
        if (c.frequencyLimit() != null
                && grants.countByConsentIdAndIssuedAtAfter(c.id(), clock.instant().minus(Duration.ofHours(24)))
                        >= c.frequencyLimit()) {
            return deny(req, DenialReason.FREQUENCY_EXCEEDED, null);
        }
        UUID grantId = UUID.randomUUID();
        ClaimResult claim = null;
        Purpose.Frequency freq = c.frequency();
        String scopeKey = null;
        if (freq != null && freq.oneCheckPerApplication()) {
            // One check per document per application. Claimed after the consent checks and before
            // the registry lookup: that is before this transaction's first audit write, so no
            // audit chain lock is held while a racing claim waits on ours, and a refused repeat
            // check writes exactly one audit row. Committed with this transaction, i.e. before
            // the connector is called. A later denial in this method drops the claim again.
            if (req.applicationId() == null || req.applicationId().isBlank()) {
                return deny(req, DenialReason.APPLICATION_REQUIRED, null, c.id(), null);
            }
            scopeKey = req.applicationId();
        } else if (freq == Purpose.Frequency.ONCE_PER_YEAR) {
            // One check per document per calendar year (Asia/Kolkata). No extra request input needed.
            scopeKey = "YEAR:" + AcademicYears.startYearOf(clock.instant(), 1);
        }
        // ONCE_PER_PAYMENT and legacy (null) rely on the 24h limit above. Per-payment scoping needs a
        // payment/instalment id, which arrives with the disbursement flow (a separate PR, not built here).
        if (scopeKey != null) {
            claim = usage.claim(c.id(), req.category().code(), scopeKey, grantId);
            if (claim.outcome() == Claim.REFUSED) {
                return refuseRepeatCheck(req, c.id());
            }
        }
        var pointer = registry.locate(req.subject(), req.departmentCode(), req.category(), req.requester());
        if (pointer.isEmpty()) {
            return dropClaim(grantId, claim, deny(req, DenialReason.NO_POINTER, null));
        }
        var p = pointer.get();
        if (p.validUntil() != null && p.validUntil().isBefore(java.time.LocalDate.now(clock))) {
            return dropClaim(grantId, claim, deny(req, DenialReason.POINTER_EXPIRED, null));
        }
        if (!registry.hasClearance(req.requester(), p.sensitivity())) {
            return dropClaim(grantId, claim, deny(req, DenialReason.INSUFFICIENT_CLEARANCE, null));
        }
        if (p.freshness().isStale() && req.journeyCode() != null && !journeys.policy(req.journeyCode()).acceptStale()) {
            return dropClaim(grantId, claim, deny(req, DenialReason.STALE_NOT_ACCEPTED, null));
        }
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        Instant issued = clock.instant();
        UnsignedGrant unsigned = new UnsignedGrant(
                grantId,
                nonce,
                c.id(),
                c.version(),
                req.subject(),
                req.requester(),
                req.category(),
                req.departmentCode(),
                req.connectorRef(),
                req.purpose(),
                req.principal(),
                issued,
                issued.plusSeconds(60));
        AccessGrant grant = new AccessGrant(
                unsigned.id(),
                unsigned.nonce(),
                unsigned.consentId(),
                unsigned.consentVersion(),
                unsigned.subject(),
                unsigned.requester(),
                unsigned.category(),
                unsigned.departmentCode(),
                unsigned.connectorRef(),
                unsigned.purpose(),
                unsigned.principal(),
                unsigned.issuedAt(),
                unsigned.expiresAt(),
                signer.sign(unsigned));
        persistGrant(grant);
        AuditEntry issuedEntry = entry(req, Outcome.ALLOWED, "GRANT_ISSUED", null, grant.id(), null, null);
        if (claim != null) {
            issuedEntry.meta().put("usageClaim", claim.outcome().name());
            issuedEntry.meta().put("usageClaimId", claim.id().toString());
        }
        audit.record(issuedEntry);
        return new AccessDecision.Granted(grant, claim == null ? null : new UsageClaim(claim.id(), claim.token()));
    }

    /** A denial after the one-check claim: the check did not happen, so the claim goes too. */
    private AccessDecision.Denied dropClaim(UUID grantId, ClaimResult claim, AccessDecision.Denied denied) {
        if (claim != null) {
            usage.discard(new UsageClaim(claim.id(), claim.token()));
        }
        return denied;
    }

    /**
     * The one check this consent allows for (document, application) is used or in flight:
     * exactly one {@code CONSENT_FREQUENCY_REFUSED} row (no {@code GRANT_DENIED} as well), in
     * this transaction, with the officer copy as the message.
     */
    private AccessDecision.Denied refuseRepeatCheck(AccessRequest req, UUID consentId) {
        DenialReason reason = DenialReason.CHECK_ALREADY_USED;
        String copy = ConsentCopy.officerDenied(reason);
        audit.record(entry(req, Outcome.DENIED, "CONSENT_FREQUENCY_REFUSED", consentId, null, reason.name(), copy));
        return new AccessDecision.Denied(reason, Optional.empty(), copy);
    }

    private AccessDecision.Denied deny(AccessRequest req, DenialReason reason, ConsentRequest remedy) {
        return deny(req, reason, remedy, null, null);
    }

    /**
     * One {@code GRANT_DENIED} row per refused fetch, in the caller's transaction: a denial is a
     * return value, not an exception, so nothing rolls it back here, and a fetch may run inside a
     * transaction that already holds the audit chain lock (a REQUIRES_NEW append would block).
     */
    private AccessDecision.Denied deny(
            AccessRequest req, DenialReason reason, ConsentRequest remedy, UUID consentId, Instant endedAt) {
        String copy = ConsentCopy.denied(reason, endedAt);
        AccessDecision.Denied denied = new AccessDecision.Denied(reason, Optional.ofNullable(remedy), copy);
        audit.record(entry(req, Outcome.DENIED, "GRANT_DENIED", consentId, null, reason.name(),
                copy.equals(reason.name()) ? null : copy));
        return denied;
    }

    private AuditEntry consentEntry(ConsentArtifactEntity a, PrincipalRef by, String action) {
        return new AuditEntry(
                actorType(by),
                by.id(),
                action,
                a.getSubjectCitizenId().toString(),
                "consent",
                a.getRequesterId(),
                a.getId(),
                null,
                Outcome.ALLOWED,
                null,
                Map.of(
                        "purpose", a.getPurposeCode(),
                        "principalType", by.kind().name(),
                        "principalId", by.id(),
                        "consentVersion", String.valueOf(a.getVersion()),
                        "validUntil", a.getValidUntil().toString()));
    }

    private static ActorType actorType(PrincipalRef by) {
        return ActorType.valueOf(by.kind().name());
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private void persistGrant(AccessGrant grant) {
        AccessGrantEntity e = new AccessGrantEntity();
        e.setId(grant.id());
        e.setNonce(grant.nonce());
        e.setConsentId(grant.consentId());
        e.setConsentVersion(grant.consentVersion());
        e.setSubjectCitizenId(grant.subject().citizenId());
        e.setRequesterId(grant.requester().id());
        e.setDataCategory(grant.category().code());
        e.setDepartmentId(grant.departmentCode());
        e.setConnectorRef(grant.connectorRef());
        e.setPurposeCode(grant.purpose().code());
        e.setPrincipalType(grant.principal().kind().name());
        e.setPrincipalId(grant.principal().id());
        e.setIssuedAt(grant.issuedAt());
        e.setExpiresAt(grant.expiresAt());
        e.setSignature(grant.signature());
        grants.save(e);
    }

    private void logEvent(UUID consentId, String type) {
        ConsentEventEntity e = new ConsentEventEntity();
        e.setId(UUID.randomUUID());
        e.setConsentId(consentId);
        e.setEventType(type);
        e.setOccurredAt(clock.instant());
        e.setDetail("{}");
        eventsLog.save(e);
    }

    private AuditEntry entry(
            AccessRequest req, Outcome outcome, String action, UUID consentId, UUID grantId, String reason, String message) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("purpose", req.purpose() == null ? "" : req.purpose().code());
        meta.put("principalType", req.principal().kind().name());
        meta.put("principalId", req.principal().id());
        if (message != null) {
            meta.put("message", message);
        }
        if (req.applicationId() != null) {
            meta.put("applicationId", req.applicationId());
        }
        return new AuditEntry(
                ActorType.SYSTEM,
                req.requester().id(),
                action,
                req.subject().citizenId().toString(),
                req.category().code(),
                req.departmentCode(),
                consentId,
                grantId,
                outcome,
                reason,
                meta);
    }

    private ConsentRequest toRequest(ConsentRequestEntity e) {
        return new ConsentRequest(
                e.getId(),
                e.getSubjectCitizenId(),
                e.getRequesterId(),
                e.getPurposeCode(),
                e.getPurposeText(),
                Arrays.asList(e.getDataCategories()),
                e.getStatus());
    }

    /**
     * Status is worked out at read time: an ACTIVE row past {@code valid_until} is reported as
     * EXPIRED ("Ended"), even though nothing has marked the row yet.
     */
    private ConsentArtifact toArtifact(ConsentArtifactEntity e) {
        String status = "ACTIVE".equals(e.getStatus()) && !e.getValidUntil().isAfter(clock.instant())
                ? "EXPIRED"
                : e.getStatus();
        return new ConsentArtifact(
                e.getId(),
                e.getSubjectCitizenId(),
                e.getRequesterId(),
                e.getPurposeCode(),
                Arrays.asList(e.getDataCategories()),
                e.getGranularity(),
                e.getValidFrom(),
                e.getValidUntil(),
                e.getFrequencyLimit(),
                status,
                e.getVersion(),
                e.getCitizenAuthRef(),
                e.getDataTypes() == null ? List.of() : Arrays.asList(e.getDataTypes()),
                e.getCreatedAt(),
                e.getRevokedAt(),
                e.getRevokedBy(),
                ConsentCopy.statusLabel(status),
                Purpose.Frequency.fromCode(e.getFrequency()));
    }
}
