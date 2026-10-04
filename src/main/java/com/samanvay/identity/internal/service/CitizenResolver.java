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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Citizens known only by the department login they proved. The assertion is verified BEFORE any transaction opens (verifying may
 * fetch the department's keys over the network, and a database connection must not be held while that waits); only the database
 * writes run in a {@link TransactionTemplate}. ponytail: two simultaneous first sign ins of the same new person can both try to
 * create the link; one hits the unique key and gets a 409, the retry succeeds.
 */
@Service
class CitizenResolver implements DepartmentCitizens {

    private static final Logger log = LoggerFactory.getLogger(CitizenResolver.class);

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
    private final TransactionTemplate tx;

    CitizenResolver(
            DepartmentHomeLogin homeLogin,
            List<LinkProofProvider> proofProviders,
            CitizenRepository citizens,
            ProfileRepository profiles,
            LinkRepository links,
            List<CitizenActivity> activities,
            ApplicationEventPublisher events,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.homeLogin = homeLogin;
        this.proofProviders = proofProviders;
        this.citizens = citizens;
        this.profiles = profiles;
        this.links = links;
        this.activities = activities;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public Resolution resolve(String departmentCode, String assertion, String actorId) {
        try {
            VerifiedAssertion a = homeLogin.check(assertion, departmentCode);
            return tx.execute(status -> resolveVerified(a, departmentCode, actorId));
        } catch (LinkProofInvalidException refused) {
            auditRefused(actorId, departmentCode, "resolve");
            throw refused;
        }
    }

    private Resolution resolveVerified(VerifiedAssertion a, String departmentCode, String actorId) {
        homeLogin.markUsed(a); // inside the transaction: if anything below fails, the assertion is not burned
        Optional<LinkEntity> existing = links.findByDepartmentCodeAndLocalIdTokenAndStatus(departmentCode, a.personId(), "ACTIVE");
        if (existing.isPresent()) {
            CitizenEntity c = citizens.findById(existing.get().getCitizenId()).orElseThrow(LinkProofInvalidException::new);
            if (!"ACTIVE".equals(c.getStatus())) {
                throw new AccessDeniedException("citizen is not active");
            }
            return new Resolution(c.getId(), false);
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
        p.setNameLatin(a.name()); // never the department's person ID (an identifier, not a name): null when the department sent none
        p.setDob(a.dob() != null ? a.dob() : UNKNOWN_DOB);
        p.setDobPrecision(a.dob() != null ? "DAY" : "YEAR");
        p.setUpdatedAt(now);
        profiles.save(p);
        saveLink(id, departmentCode, a.personIdType(), a.personId(), now);
        audit(actorId, "CITIZEN_CREATED_BY_DEPARTMENT", id, departmentCode, Map.of());
        return new Resolution(id, true);
    }

    @Override
    public UUID link(UUID citizenId, String departmentCode, String assertion, String actorId) {
        try {
            citizens.findById(citizenId).filter(c -> "ACTIVE".equals(c.getStatus())).orElseThrow(LinkProofInvalidException::new);
            VerifiedLocalId proven = proofProviders.stream().filter(p -> p.kind() == LinkProofKind.DEPT_ASSERTION).findFirst()
                    .orElseThrow(LinkProofInvalidException::new)
                    .verify(new AuthProof(LinkProofKind.DEPT_ASSERTION, assertion), new LinkProofContext(citizenId, departmentCode, null, null));
            return tx.execute(status -> linkProven(citizenId, departmentCode, proven, actorId));
        } catch (LinkProofInvalidException refused) {
            auditRefused(actorId, departmentCode, "link");
            throw refused;
        }
    }

    private UUID linkProven(UUID citizenId, String departmentCode, VerifiedLocalId proven, String actorId) {
        // Lock this citizen and the one that already holds the proven person, in id order so two merges never wait on each other.
        Optional<LinkEntity> holder = links.findByDepartmentCodeAndLocalIdTokenAndStatus(departmentCode, proven.localId(), "ACTIVE");
        List<UUID> ids = new ArrayList<>(List.of(citizenId));
        holder.map(LinkEntity::getCitizenId).filter(h -> !h.equals(citizenId)).ifPresent(ids::add);
        ids.sort(null);
        CitizenEntity current = null;
        CitizenEntity survivor = null;
        for (UUID id : ids) {
            CitizenEntity locked = citizens.lockById(id).orElse(null);
            if (id.equals(citizenId)) {
                current = locked;
            } else {
                survivor = locked;
            }
        }
        // Everything is re-read after the locks: whatever was decided before them may have changed while this call waited.
        if (current == null || !"ACTIVE".equals(current.getStatus())) {
            throw new LinkProofInvalidException();
        }
        Optional<LinkEntity> mine = links.findByCitizenIdAndDepartmentCodeAndStatus(citizenId, departmentCode, "ACTIVE");
        if (mine.isPresent()) {
            if (proven.localId().equals(mine.get().getLocalIdToken())) {
                return citizenId; // the same person again: nothing to do
            }
            throw new DuplicateLocalIdException(); // this citizen is already linked there to a DIFFERENT person
        }
        Optional<LinkEntity> owner = links.findByDepartmentCodeAndLocalIdTokenAndStatus(departmentCode, proven.localId(), "ACTIVE");
        if (owner.isEmpty()) {
            saveLink(citizenId, departmentCode, proven.localIdType(), proven.localId(), clock.instant());
            audit(actorId, "CITIZEN_LINKED", citizenId, departmentCode, Map.of());
            return citizenId;
        }
        UUID survivorId = owner.get().getCitizenId();
        if (survivor == null || !survivor.getId().equals(survivorId) || !"ACTIVE".equals(survivor.getStatus())) {
            throw new DuplicateLocalIdException(); // the holder changed meanwhile, or is not an active citizen: never merge into it
        }
        if (!ORIGIN.equals(current.getOrigin()) || activities.stream().anyMatch(a -> a.hasActivity(citizenId))) {
            throw new DuplicateLocalIdException();
        }
        merge(current, survivorId, actorId, departmentCode);
        return survivorId;
    }

    private void merge(CitizenEntity from, UUID into, String actorId, String departmentCode) {
        List<LinkEntity> moving = links.findByCitizenIdAndStatus(from.getId(), "ACTIVE");
        for (LinkEntity l : moving) {
            if (links.findByCitizenIdAndDepartmentCodeAndStatus(into, l.getDepartmentCode(), "ACTIVE").isPresent()) {
                throw new DuplicateLocalIdException(); // the survivor already has a different person at that department
            }
        }
        moving.forEach(l -> l.setCitizenId(into));
        links.saveAllAndFlush(moving);
        from.setStatus("MERGED");
        citizens.save(from);
        audit(actorId, "CITIZEN_MERGED", from.getId(), departmentCode, Map.of("mergedInto", into.toString()));
        // The survivor now holds these departments' links but not their discovery pointers: ask for them again, as a new link does.
        moving.forEach(l -> events.publishEvent(new LinkAsserted(into, l.getDepartmentCode())));
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
            if (UniqueViolation.is(ex)) {
                throw new DuplicateLocalIdException();
            }
            throw ex; // any other integrity error is a bug or bad data, not "this person is already linked"
        }
        events.publishEvent(new LinkAsserted(citizenId, departmentCode));
    }

    private void audit(String actorId, String action, UUID citizenId, String departmentCode, Map<String, Object> meta) {
        audit.record(new AuditEntry(ActorType.DEPARTMENT, actorId, action, citizenId.toString(), citizenId.toString(), departmentCode,
                null, null, Outcome.ALLOWED, null, meta));
    }

    /** A department's assertion did not verify. Written in its own transaction (none is open here); carries no person ID, name or token. */
    private void auditRefused(String actorId, String departmentCode, String operation) {
        try {
            audit.record(new AuditEntry(ActorType.DEPARTMENT, actorId == null || actorId.isBlank() ? String.valueOf(departmentCode) : actorId,
                    "DEPARTMENT_ASSERTION_REFUSED", null, "identity", departmentCode, null, null, Outcome.DENIED, "LINK_PROOF_INVALID",
                    Map.of("operation", operation)));
        } catch (RuntimeException e) {
            log.warn("could not audit a refused department assertion: {}", e.toString());
        }
    }
}
