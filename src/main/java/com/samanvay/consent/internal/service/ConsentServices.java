package com.samanvay.consent.internal.service;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.PurposeCatalog;
import com.samanvay.consent.api.RequesterNotEntitledException;
import com.samanvay.consent.api.UnknownPurposeException;
import com.samanvay.catalog.api.Purpose;
import com.samanvay.consent.api.AccessAuthority;
import com.samanvay.consent.api.AccessDecision;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessRequest;
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
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.registry.api.DiscoveryRegistry;
import com.samanvay.registry.api.Sensitivity;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final GrantSigner signer;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    ConsentServices(
            ConsentRequestRepository requests,
            ConsentArtifactRepository artifacts,
            ConsentEventRepository eventsLog,
            AccessGrantRepository grants,
            IdentityLinking identityLinking,
            DiscoveryRegistry registry,
            JourneyCatalog journeys,
            PurposeCatalog purposes,
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
        this.signer = signer;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ConsentRequest request(ConsentRequestDraft draft) {
        // Text and categories come from the catalog purpose, never from the caller;
        // only the purpose's own department may request under it.
        Purpose purpose = purposes.byCode(draft.purposeCode())
                .filter(Purpose::active)
                .filter(p -> !p.dataCategories().isEmpty())
                .orElseThrow(() -> new UnknownPurposeException(draft.purposeCode()));
        if (draft.requesterId() == null || !draft.requesterId().equals(purpose.requesterDepartment())) {
            throw new RequesterNotEntitledException(draft.requesterId(), purpose.code());
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

    /** A pending request the citizen can grant - only if the requester may ask under this purpose at all. */
    private ConsentRequest remedy(AccessRequest req) {
        boolean entitled = purposes.byCode(req.purpose().code())
                .map(p -> req.requester().id().equals(p.requesterDepartment()) && !p.dataCategories().isEmpty())
                .orElse(false);
        if (!entitled) {
            return null;
        }
        return request(new ConsentRequestDraft(req.subject().citizenId(), req.requester().id(), req.purpose().code()));
    }

    @Override
    @Transactional
    public ConsentArtifact grant(UUID requestId, UUID citizenId, AuthProof proof) {
        ConsentRequestEntity req = requests.findById(requestId).orElseThrow(ConsentNotFoundException::new);
        if (!req.getSubjectCitizenId().equals(citizenId)) {
            throw new ConsentNotFoundException();
        }
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
        a.setValidFrom(clock.instant());
        a.setValidUntil(clock.instant().plus(Duration.ofDays(365)));
        a.setFrequencyLimit(20);
        a.setStatus("ACTIVE");
        a.setVersion(1);
        a.setCitizenAuthRef(proof.citizenAuthRef());
        a.setCreatedAt(clock.instant());
        a.setUpdatedAt(clock.instant());
        artifacts.save(a);
        logEvent(a.getId(), "GRANTED");
        for (String cat : a.getDataCategories()) {
            registry.allowDiscovery(citizenId, a.getRequesterId(), cat, a.getId());
        }
        events.publishEvent(new ConsentGranted(a.getId(), citizenId, a.getRequesterId(), Arrays.asList(a.getDataCategories())));
        return toArtifact(a);
    }

    @Override
    @Transactional
    public void revoke(UUID consentId, UUID citizenId, String reason) {
        ConsentArtifactEntity a = artifacts.findById(consentId).orElseThrow(ConsentNotFoundException::new);
        if (!a.getSubjectCitizenId().equals(citizenId)) {
            throw new ConsentNotFoundException();
        }
        a.setStatus("REVOKED");
        a.setVersion(a.getVersion() + 1);
        a.setUpdatedAt(clock.instant());
        artifacts.save(a);
        logEvent(a.getId(), "REVOKED");
        registry.revokeDiscovery(consentId);
        events.publishEvent(new ConsentRevoked(consentId, citizenId, a.getVersion()));
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
            return deny(req, DenialReason.CONSENT_REVOKED, null);
        }
        if (c.validUntil().isBefore(clock.instant())) {
            return deny(req, DenialReason.CONSENT_EXPIRED, null);
        }
        if (c.frequencyLimit() != null
                && grants.countByConsentIdAndIssuedAtAfter(c.id(), clock.instant().minus(Duration.ofHours(24)))
                        >= c.frequencyLimit()) {
            return deny(req, DenialReason.FREQUENCY_EXCEEDED, null);
        }
        var pointer = registry.locate(req.subject(), req.departmentCode(), req.category(), req.requester());
        if (pointer.isEmpty()) {
            return deny(req, DenialReason.NO_POINTER, null);
        }
        var p = pointer.get();
        if (p.validUntil() != null && p.validUntil().isBefore(java.time.LocalDate.now(clock))) {
            return deny(req, DenialReason.POINTER_EXPIRED, null);
        }
        if (!registry.hasClearance(req.requester(), p.sensitivity())) {
            return deny(req, DenialReason.INSUFFICIENT_CLEARANCE, null);
        }
        if (p.freshness().isStale() && req.journeyCode() != null && !journeys.policy(req.journeyCode()).acceptStale()) {
            return deny(req, DenialReason.STALE_NOT_ACCEPTED, null);
        }
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        Instant issued = clock.instant();
        UnsignedGrant unsigned = new UnsignedGrant(
                UUID.randomUUID(),
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
        audit.record(entry(req, Outcome.ALLOWED, "GRANT_ISSUED", grant.id(), null));
        return new AccessDecision.Granted(grant);
    }

    private AccessDecision.Denied deny(AccessRequest req, DenialReason reason, ConsentRequest remedy) {
        audit.record(entry(req, Outcome.DENIED, "GRANT_DENIED", null, reason.name()));
        return new AccessDecision.Denied(reason, Optional.ofNullable(remedy));
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

    private AuditEntry entry(AccessRequest req, Outcome outcome, String action, UUID grantId, String reason) {
        return new AuditEntry(
                ActorType.SYSTEM,
                req.requester().id(),
                action,
                req.subject().citizenId().toString(),
                req.category().code(),
                req.departmentCode(),
                null,
                grantId,
                outcome,
                reason,
                Map.of(
                        "purpose", req.purpose() == null ? "" : req.purpose().code(),
                        "principalType", req.principal().kind().name(),
                        "principalId", req.principal().id()));
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

    private ConsentArtifact toArtifact(ConsentArtifactEntity e) {
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
                e.getStatus(),
                e.getVersion(),
                e.getCitizenAuthRef());
    }
}
