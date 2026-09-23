package com.samanvay.registry.internal.service;

import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.identity.api.LinkAsserted;
import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.registry.api.DiscoveryPolicy;
import com.samanvay.registry.api.DiscoveryRegistry;
import com.samanvay.registry.api.FreshnessMode;
import com.samanvay.registry.api.Pointer;
import com.samanvay.registry.api.PointerUpsert;
import com.samanvay.registry.api.PointerUpserted;
import com.samanvay.registry.api.Sensitivity;
import com.samanvay.registry.internal.domain.CategoryPolicyEntity;
import com.samanvay.registry.internal.domain.DiscoveryGrantEntity;
import com.samanvay.registry.internal.domain.PointerEntity;
import com.samanvay.registry.internal.repository.CategoryPolicyRepository;
import com.samanvay.registry.internal.repository.ClearanceRepository;
import com.samanvay.registry.internal.repository.DiscoveryGrantRepository;
import com.samanvay.registry.internal.repository.PointerRepository;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DiscoveryRegistryService implements DiscoveryRegistry {

    private final PointerRepository pointers;
    private final CategoryPolicyRepository policies;
    private final ClearanceRepository clearances;
    private final DiscoveryGrantRepository discoveryGrants;
    private final ConnectorCatalog connectors;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    DiscoveryRegistryService(
            PointerRepository pointers,
            CategoryPolicyRepository policies,
            ClearanceRepository clearances,
            DiscoveryGrantRepository discoveryGrants,
            ConnectorCatalog connectors,
            AuditService audit,
            ApplicationEventPublisher events) {
        this.pointers = pointers;
        this.policies = policies;
        this.clearances = clearances;
        this.discoveryGrants = discoveryGrants;
        this.connectors = connectors;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Pointer> discover(SubjectRef subject, RequesterRef requester, Set<DataCategory> categories) {
        List<Pointer> visible = new ArrayList<>();
        for (DataCategory category : categories) {
            CategoryPolicyEntity policy = policies.findById(category.code()).orElse(null);
            if (policy == null) {
                continue;
            }
            if (!clearances.existsByRequesterIdAndSensitivity(requester.id(), policy.getSensitivity())) {
                continue;
            }
            if (DiscoveryPolicy.CONSENT_REQUIRED_TO_DISCOVER.name().equals(policy.getDiscoveryPolicy())
                    && !discoveryGrants.existsBySubjectIdAndRequesterIdAndDataCategory(
                            subject.citizenId(), requester.id(), category.code())) {
                continue;
            }
            pointers.findBySubjectIdAndDataCategory(subject.citizenId(), category.code())
                    .filter(p -> "AVAILABLE".equals(p.getStatus()))
                    .map(p -> toPointer(p, policy))
                    .ifPresent(visible::add);
        }
        audit.record(new AuditEntry(
                ActorType.SYSTEM,
                requester.id(),
                "DISCOVERY",
                subject.citizenId().toString(),
                "registry",
                null,
                null,
                null,
                Outcome.ALLOWED,
                null,
                Map.of("visible", visible.size())));
        return visible;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Pointer> locate(SubjectRef subject, String departmentCode, DataCategory category, RequesterRef requester) {
        CategoryPolicyEntity policy = policies.findById(category.code()).orElse(null);
        Optional<Pointer> found = pointers
                .findBySubjectIdAndDepartmentCodeAndDataCategory(subject.citizenId(), departmentCode, category.code())
                .filter(p -> "AVAILABLE".equals(p.getStatus()))
                .map(p -> toPointer(p, policy));
        audit.record(new AuditEntry(
                ActorType.SYSTEM,
                requester.id(),
                "LOCATE",
                subject.citizenId().toString(),
                category.code(),
                departmentCode,
                null,
                null,
                found.isPresent() ? Outcome.ALLOWED : Outcome.DENIED,
                null,
                Map.of()));
        return found;
    }

    @Override
    @Transactional
    public void upsert(PointerUpsert p) {
        PointerEntity e = pointers
                .findBySubjectIdAndDepartmentCodeAndDataCategory(
                        p.subject().citizenId(), p.departmentCode(), p.category().code())
                .orElseGet(PointerEntity::new);
        if (e.getId() == null) {
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
        }
        e.setSubjectId(p.subject().citizenId());
        e.setSubjectType(p.subjectType());
        e.setDepartmentCode(p.departmentCode());
        e.setDataCategory(p.category().code());
        e.setSourceRef(p.sourceRef());
        e.setIssuedAt(p.issuedAt());
        e.setValidUntil(p.validUntil());
        e.setAsOf(p.asOf());
        e.setFreshnessMode(p.freshnessMode().name());
        e.setStatus("AVAILABLE");
        pointers.save(e);
        events.publishEvent(new PointerUpserted(e.getId(), e.getSubjectId(), e.getDepartmentCode(), e.getDataCategory()));
    }

    @ApplicationModuleListener
    void onLinkAsserted(LinkAsserted event) {
        connectors.published().stream()
                .filter(c -> connectors.dataSourceFor(c).departmentCode().equals(event.departmentCode()))
                .forEach(c -> {
                    String protocol = connectors.dataSourceFor(c).protocol();
                    FreshnessMode mode = "SFTP_CSV".equals(protocol) || "JDBC".equals(protocol)
                            ? FreshnessMode.BATCH
                            : FreshnessMode.REALTIME;
                    Instant asOf = mode == FreshnessMode.BATCH ? Instant.now().minusSeconds(3600) : Instant.now();
                    upsert(new PointerUpsert(
                            new SubjectRef(event.citizenId()),
                            "PERSON",
                            event.departmentCode(),
                            c.category(),
                            "{\"endpoint\":\"fetch\",\"key\":\"linked\"}",
                            java.time.LocalDate.now(),
                            java.time.LocalDate.now().plusYears(2),
                            asOf,
                            mode));
                });
    }

    @Override
    public boolean hasClearance(RequesterRef requester, Sensitivity sensitivity) {
        return clearances.existsByRequesterIdAndSensitivity(requester.id(), sensitivity.name());
    }

    @Override
    @Transactional
    public void withdraw(UUID pointerId, String reason) {
        pointers.findById(pointerId).ifPresent(p -> {
            p.setStatus("WITHDRAWN");
            pointers.save(p);
        });
    }

    @Override
    @Transactional
    public void allowDiscovery(UUID subjectId, String requesterId, String category, UUID consentId) {
        DiscoveryGrantEntity e = new DiscoveryGrantEntity();
        e.setSubjectId(subjectId);
        e.setRequesterId(requesterId);
        e.setDataCategory(category);
        e.setConsentId(consentId);
        e.setGrantedAt(Instant.now());
        discoveryGrants.save(e);
    }

    @Override
    @Transactional
    public void revokeDiscovery(UUID consentId) {
        discoveryGrants.deleteByConsentId(consentId);
    }

    private Pointer toPointer(PointerEntity p, CategoryPolicyEntity policy) {
        Sensitivity sensitivity = policy == null ? Sensitivity.RESTRICTED : Sensitivity.valueOf(policy.getSensitivity());
        DiscoveryPolicy discovery =
                policy == null ? DiscoveryPolicy.VISIBLE : DiscoveryPolicy.valueOf(policy.getDiscoveryPolicy());
        return new Pointer(
                p.getId(),
                new SubjectRef(p.getSubjectId()),
                p.getDepartmentCode(),
                DataCategory.of(p.getDataCategory()),
                sensitivity,
                discovery,
                p.getSourceRef(),
                p.getIssuedAt(),
                p.getValidUntil(),
                p.getAsOf(),
                FreshnessMode.valueOf(p.getFreshnessMode()),
                p.getStatus());
    }
}
