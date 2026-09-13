package com.samanvay.identity.internal.service;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.CandidateNotFoundException;
import com.samanvay.identity.api.CandidateRef;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.DuplicateLocalIdException;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.IdentityResolution;
import com.samanvay.identity.api.Link;
import com.samanvay.identity.api.LinkAsserted;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkRevoked;
import com.samanvay.identity.api.Profile;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.identity.api.ReviewerRequiredException;
import com.samanvay.identity.internal.domain.CandidateMatchEntity;
import com.samanvay.identity.internal.domain.CitizenEntity;
import com.samanvay.identity.internal.domain.LinkEntity;
import com.samanvay.identity.internal.domain.ProfileEntity;
import com.samanvay.identity.internal.repository.CandidateMatchRepository;
import com.samanvay.identity.internal.repository.CitizenRepository;
import com.samanvay.identity.internal.repository.LinkRepository;
import com.samanvay.identity.internal.repository.ProfileRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
class IdentityServices implements IdentityLinking, IdentityResolution, CitizenProfiles {

    static final double NOISE_FLOOR = 0.60;

    private final CitizenRepository citizens;
    private final ProfileRepository profiles;
    private final LinkRepository links;
    private final CandidateMatchRepository candidates;
    private final CandidateScorer scorer;
    private final ReviewerAuth reviewerAuth;
    private final ApplicationEventPublisher events;
    private final AuditService audit;

    IdentityServices(
            CitizenRepository citizens,
            ProfileRepository profiles,
            LinkRepository links,
            CandidateMatchRepository candidates,
            CandidateScorer scorer,
            ReviewerAuth reviewerAuth,
            ApplicationEventPublisher events,
            AuditService audit) {
        this.citizens = citizens;
        this.profiles = profiles;
        this.links = links;
        this.candidates = candidates;
        this.scorer = scorer;
        this.reviewerAuth = reviewerAuth;
        this.events = events;
        this.audit = audit;
    }

    @Override
    @Transactional
    public UUID register(ProfileDraft draft) {
        UUID id = UUID.randomUUID();
        CitizenEntity c = new CitizenEntity();
        c.setId(id);
        c.setStatus("ACTIVE");
        c.setCreatedAt(Instant.now());
        citizens.save(c);
        ProfileEntity p = new ProfileEntity();
        p.setCitizenId(id);
        p.setNameLatin(draft.nameLatin());
        p.setNameDevanagari(draft.nameDevanagari());
        p.setGivenName(draft.givenName());
        p.setFamilyName(draft.familyName());
        p.setFatherName(draft.fatherName());
        p.setDob(draft.dob());
        p.setDobPrecision(draft.dobPrecision());
        p.setGender(draft.gender());
        p.setContactMasked(draft.contactMasked());
        p.setUpdatedAt(Instant.now());
        profiles.save(p);
        return id;
    }

    @Override
    public Profile profile(UUID citizenId) {
        ProfileEntity p = profiles.findById(citizenId).orElseThrow();
        return toProfile(p);
    }

    @Override
    @Transactional
    public Link assertLink(UUID citizenId, String departmentCode, String localIdType, String localId, AuthProof proof) {
        if (proof == null || proof.assertion() == null || proof.assertion().isBlank()) {
            throw new LinkProofInvalidException();
        }
        LinkEntity e = new LinkEntity();
        e.setId(UUID.randomUUID());
        e.setCitizenId(citizenId);
        e.setDepartmentCode(departmentCode);
        e.setLocalIdType(localIdType);
        e.setLocalIdToken(localId);
        e.setProvenance("CITIZEN_ASSERTED");
        e.setStatus("ACTIVE");
        e.setVerifiedAt(Instant.now());
        e.setCreatedAt(Instant.now());
        try {
            links.saveAndFlush(e);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateLocalIdException();
        }
        events.publishEvent(new LinkAsserted(citizenId, departmentCode));
        return toLink(e);
    }

    @Override
    public Optional<Link> activeLink(UUID citizenId, String departmentCode) {
        return links.findByCitizenIdAndDepartmentCodeAndStatus(citizenId, departmentCode, "ACTIVE").map(this::toLink);
    }

    @Override
    public List<Link> activeLinks(UUID citizenId) {
        return links.findByCitizenIdAndStatus(citizenId, "ACTIVE").stream().map(this::toLink).toList();
    }

    @Override
    @Transactional
    public void revokeLink(UUID linkId, String reason) {
        LinkEntity e = links.findById(linkId).orElseThrow();
        e.setStatus("REVOKED");
        links.save(e);
        events.publishEvent(new LinkRevoked(e.getCitizenId(), e.getDepartmentCode(), reason));
    }

    @Override
    @Transactional
    public CandidateRef submitCandidate(String departmentCode, JsonNode departmentRecord) {
        String localId = departmentRecord.get("localId") == null ? "" : departmentRecord.get("localId").asString();
        var existing = links.findByDepartmentCodeAndLocalIdTokenAndStatus(departmentCode, localId, "ACTIVE");
        if (existing.isPresent()) {
            return CandidateRef.alreadyLinked(existing.get().getCitizenId());
        }
        for (ProfileEntity profile : profiles.findAll()) {
            double score = scorer.score(toProfile(profile), departmentRecord);
            if (score < NOISE_FLOOR) {
                continue;
            }
            CandidateMatchEntity row = new CandidateMatchEntity();
            row.setId(UUID.randomUUID());
            row.setCitizenId(profile.getCitizenId());
            row.setDepartmentCode(departmentCode);
            row.setScore(BigDecimal.valueOf(score));
            row.setFeatures("{\"score\":" + score + "}");
            row.setStatus("PENDING");
            row.setCreatedAt(Instant.now());
            candidates.save(row);
        }
        return CandidateRef.queued();
    }

    @Override
    @Transactional
    public Link confirm(UUID candidateId, String reviewerId, String note) {
        if (!reviewerAuth.isReviewer()) {
            throw new ReviewerRequiredException();
        }
        CandidateMatchEntity c = candidates.findById(candidateId).orElseThrow(() -> new CandidateNotFoundException(candidateId));
        c.setStatus("CONFIRMED");
        c.setReviewedBy(reviewerId);
        c.setReviewedAt(Instant.now());
        candidates.save(c);
        LinkEntity e = new LinkEntity();
        e.setId(UUID.randomUUID());
        e.setCitizenId(c.getCitizenId());
        e.setDepartmentCode(c.getDepartmentCode());
        e.setLocalIdType("REVIEW");
        e.setLocalIdToken("officer:" + candidateId);
        e.setProvenance("OFFICER_CONFIRMED");
        e.setConfidence(c.getScore());
        e.setStatus("ACTIVE");
        e.setCreatedAt(Instant.now());
        links.save(e);
        audit.record(new AuditEntry(
                ActorType.OFFICER,
                reviewerId,
                "CANDIDATE_CONFIRMED",
                c.getCitizenId().toString(),
                candidateId.toString(),
                c.getDepartmentCode(),
                null,
                null,
                Outcome.ALLOWED,
                note,
                Map.of("score", c.getScore())));
        return toLink(e);
    }

    @Override
    @Transactional
    public void reject(UUID candidateId, String reviewerId, String note) {
        if (!reviewerAuth.isReviewer()) {
            throw new ReviewerRequiredException();
        }
        CandidateMatchEntity c = candidates.findById(candidateId).orElseThrow(() -> new CandidateNotFoundException(candidateId));
        c.setStatus("REJECTED");
        c.setReviewedBy(reviewerId);
        c.setReviewedAt(Instant.now());
        candidates.save(c);
    }

    private Profile toProfile(ProfileEntity p) {
        return new Profile(
                p.getCitizenId(),
                p.getNameLatin(),
                p.getNameDevanagari(),
                p.getFamilyName(),
                p.getFatherName(),
                p.getDob(),
                p.getDobPrecision());
    }

    private Link toLink(LinkEntity e) {
        return new Link(
                e.getId(),
                e.getCitizenId(),
                e.getDepartmentCode(),
                e.getLocalIdType(),
                e.getLocalIdToken(),
                e.getProvenance(),
                e.getStatus());
    }
}
