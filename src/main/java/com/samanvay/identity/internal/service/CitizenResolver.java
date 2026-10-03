package com.samanvay.identity.internal.service;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.CitizenActivity;
import com.samanvay.identity.api.DepartmentCitizens;
import com.samanvay.identity.api.DuplicateLocalIdException;
import com.samanvay.identity.api.LinkAsserted;
import com.samanvay.identity.api.LinkProofContext;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProvider;
import com.samanvay.identity.api.VerifiedLocalId;
import com.samanvay.identity.internal.domain.CitizenEntity;
import com.samanvay.identity.internal.domain.LinkEntity;
import com.samanvay.identity.internal.domain.ProfileEntity;
import com.samanvay.identity.internal.proof.DepartmentHomeLogin;
import com.samanvay.identity.internal.proof.VerifiedAssertion;
import com.samanvay.identity.internal.repository.CitizenRepository;
import com.samanvay.identity.internal.repository.LinkRepository;
import com.samanvay.identity.internal.repository.ProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Citizens known only by the department login they proved. ponytail: two simultaneous first sign ins of the same new person can
 * both try to create the link; one hits the unique key and gets a 409, the retry succeeds.
 */
@Service
class CitizenResolver implements DepartmentCitizens {

    static final String ORIGIN = "DEPT_HOME";
    /** Used when the department does not say the birth date; the profile then carries year precision so nothing treats it as real. */
    static final LocalDate UNKNOWN_DOB = LocalDate.of(1900, 1, 1);

    private final DepartmentHomeLogin homeLogin;
    private final List<LinkProofProvider> proofProviders;
    private final CitizenRepository citizens;
    private final ProfileRepository profiles;
    private final LinkRepository links;
    private final List<CitizenActivity> activities;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final Clock clock;

    CitizenResolver(
            DepartmentHomeLogin homeLogin,
            List<LinkProofProvider> proofProviders,
            CitizenRepository citizens,
            ProfileRepository profiles,
            LinkRepository links,
            List<CitizenActivity> activities,
            ApplicationEventPublisher events,
            AuditService audit,
            Clock clock) {
        this.homeLogin = homeLogin;
        this.proofProviders = proofProviders;
        this.citizens = citizens;
        this.profiles = profiles;
        this.links = links;
        this.activities = activities;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Resolution resolve(String departmentCode, String assertion, String actorId) {
        VerifiedAssertion a = homeLogin.verify(assertion, departmentCode);
        Optional<LinkEntity> existing = links.findByDepartmentCodeAndLocalIdTokenAndStatus(departmentCode, a.personId(), "ACTIVE");
        if (existing.isPresent()) {
            return new Resolution(existing.get().getCitizenId(), false);
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        CitizenEntity c = new CitizenEntity();
        c.setId(id);
        c.setStatus("ACTIVE");
        c.setOrigin(ORIGIN);
        c.setCreatedAt(now);
        citizens.save(c);
        ProfileEntity p = new ProfileEntity();
        p.setCitizenId(id);
        p.setNameLatin(a.name() != null ? a.name() : "Citizen " + a.personId());
        p.setDob(a.dob() != null ? a.dob() : UNKNOWN_DOB);
        p.setDobPrecision(a.dob() != null ? "DAY" : "YEAR");
        p.setUpdatedAt(now);
        profiles.save(p);
        saveLink(id, departmentCode, a.personIdType(), a.personId(), now);
        audit(actorId, "CITIZEN_CREATED_BY_DEPARTMENT", id, departmentCode, Map.of());
        return new Resolution(id, true);
    }

    @Override
    @Transactional
    public UUID link(UUID citizenId, String departmentCode, String assertion, String actorId) {
        CitizenEntity current = citizens.findById(citizenId).filter(c -> "ACTIVE".equals(c.getStatus())).orElseThrow(LinkProofInvalidException::new);
        VerifiedLocalId proven = proofProviders.stream().filter(p -> p.kind() == LinkProofKind.DEPT_ASSERTION).findFirst()
                .orElseThrow(LinkProofInvalidException::new)
                .verify(new AuthProof(LinkProofKind.DEPT_ASSERTION, assertion), new LinkProofContext(citizenId, departmentCode, null, null));
        if (links.findByCitizenIdAndDepartmentCodeAndStatus(citizenId, departmentCode, "ACTIVE").isPresent()) {
            return citizenId;
        }
        Optional<LinkEntity> owner = links.findByDepartmentCodeAndLocalIdTokenAndStatus(departmentCode, proven.localId(), "ACTIVE");
        if (owner.isEmpty()) {
            saveLink(citizenId, departmentCode, proven.localIdType(), proven.localId(), clock.instant());
            return citizenId;
        }
        UUID survivor = owner.get().getCitizenId();
        if (!ORIGIN.equals(current.getOrigin()) || activities.stream().anyMatch(a -> a.hasActivity(citizenId))) {
            throw new DuplicateLocalIdException();
        }
        merge(current, survivor, actorId, departmentCode);
        return survivor;
    }

    private void merge(CitizenEntity from, UUID into, String actorId, String departmentCode) {
        List<LinkEntity> moving = links.findByCitizenIdAndStatus(from.getId(), "ACTIVE");
        for (LinkEntity l : moving) {
            if (links.findByCitizenIdAndDepartmentCodeAndStatus(into, l.getDepartmentCode(), "ACTIVE").isPresent()) {
                throw new DuplicateLocalIdException(); // the survivor already has a different person at that department
            }
        }
        moving.forEach(l -> l.setCitizenId(into));
        links.saveAll(moving);
        from.setStatus("MERGED");
        citizens.save(from);
        audit(actorId, "CITIZEN_MERGED", from.getId(), departmentCode, Map.of("mergedInto", into.toString()));
    }

    private void saveLink(UUID citizenId, String departmentCode, String type, String personId, Instant now) {
        LinkEntity e = new LinkEntity();
        e.setId(UUID.randomUUID());
        e.setCitizenId(citizenId);
        e.setDepartmentCode(departmentCode);
        e.setLocalIdType(type);
        e.setLocalIdToken(personId);
        e.setProvenance("CITIZEN_ASSERTED");
        e.setStatus("ACTIVE");
        e.setVerifiedAt(now);
        e.setCreatedAt(now);
        try {
            links.saveAndFlush(e);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateLocalIdException();
        }
        events.publishEvent(new LinkAsserted(citizenId, departmentCode));
    }

    private void audit(String actorId, String action, UUID citizenId, String departmentCode, Map<String, Object> meta) {
        audit.record(new AuditEntry(ActorType.DEPARTMENT, actorId, action, citizenId.toString(), citizenId.toString(), departmentCode,
                null, null, Outcome.ALLOWED, null, meta));
    }
}
