package com.samanvay.tracking.internal.service;

import com.samanvay.consent.api.ConsentRevoked;
import com.samanvay.orchestration.api.ApplicationStateChanged;
import com.samanvay.orchestration.api.JourneyStarted;
import com.samanvay.orchestration.api.SlaBreached;
import com.samanvay.orchestration.api.StepCompleted;
import com.samanvay.orchestration.api.StepFailed;
import com.samanvay.orchestration.api.StepPendingSource;
import com.samanvay.tracking.api.ApplicationNotFoundException;
import com.samanvay.tracking.api.ApplicationReferenceIssued;
import com.samanvay.tracking.api.ApplicationSummary;
import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.ApplicationView;
import com.samanvay.tracking.api.StepView;
import com.samanvay.tracking.internal.domain.ApplicationEntity;
import com.samanvay.tracking.internal.domain.StepEntity;
import com.samanvay.tracking.internal.repository.ApplicationRepository;
import com.samanvay.tracking.internal.repository.StepRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

@Service
class TrackingServices implements ApplicationTracking {

    private final ApplicationRepository applications;
    private final StepRepository steps;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    TrackingServices(
            ApplicationRepository applications,
            StepRepository steps,
            JdbcTemplate jdbc,
            ApplicationEventPublisher events) {
        this.applications = applications;
        this.steps = steps;
        this.jdbc = jdbc;
        this.events = events;
    }

    @ApplicationModuleListener
    void on(JourneyStarted event) {
        Timestamp sla = event.slaDueAt() == null ? null : Timestamp.from(event.slaDueAt());
        jdbc.update(
                """
                INSERT INTO tracking_application
                  (id, reference_no, citizen_id, journey_code, process_instance_id, status, submitted_at, sla_due_at)
                VALUES (?, ?, ?, ?, ?, 'SUBMITTED', now(), ?)
                ON CONFLICT (id) DO UPDATE SET
                  citizen_id = EXCLUDED.citizen_id,
                  journey_code = EXCLUDED.journey_code,
                  process_instance_id = EXCLUDED.process_instance_id,
                  sla_due_at = EXCLUDED.sla_due_at
                """,
                event.instanceId(),
                nextReference(event.referencePrefix()),
                event.citizenId(),
                event.journeyCode(),
                event.processInstanceId(),
                sla);
        String ref = jdbc.queryForObject(
                "SELECT reference_no FROM tracking_application WHERE id = ?", String.class, event.instanceId());
        events.publishEvent(new ApplicationReferenceIssued(ref, event.citizenId()));
    }

    @ApplicationModuleListener
    void on(StepCompleted event) {
        upsertStep(
                event.instanceId(),
                event.stepCode(),
                "COMPLETED",
                event.outcome(),
                Instant.now(),
                event.auditRef(),
                event.departmentCode(),
                event.source());
    }

    @ApplicationModuleListener
    void on(StepFailed event) {
        upsertStep(event.instanceId(), event.stepCode(), "FAILED", event.outcome(), Instant.now(), null, event.departmentCode(), "API");
    }

    @ApplicationModuleListener
    void on(StepPendingSource event) {
        upsertStep(event.instanceId(), event.stepCode(), "PENDING_SOURCE", null, null, null, event.departmentCode(), "API");
        applications.findById(event.instanceId()).ifPresent(a -> {
            a.setStatus("PARTIALLY_VERIFIED");
            applications.save(a);
        });
    }

    @ApplicationModuleListener
    void on(ApplicationStateChanged event) {
        applications.findById(event.instanceId()).ifPresent(a -> {
            a.setStatus(event.newStatus());
            applications.save(a);
        });
    }

    @ApplicationModuleListener
    void on(SlaBreached event) {
        applications.findById(event.instanceId()).ifPresent(a -> applications.save(a));
    }

    @ApplicationModuleListener
    void on(ConsentRevoked event) {
        // Journey 1: no long-lived in-flight grant to mark
    }

    private void upsertStep(
            UUID applicationId,
            String stepCode,
            String status,
            String outcome,
            Instant completed,
            Long auditRef,
            String departmentCode,
            String source) {
        ensureApplication(applicationId);
        StepEntity s = steps.findByApplicationIdAndStepCode(applicationId, stepCode).orElseGet(StepEntity::new);
        if (s.getId() == null) {
            s.setId(UUID.randomUUID());
            s.setApplicationId(applicationId);
            s.setStepCode(stepCode);
            s.setStartedAt(Instant.now());
        }
        s.setDepartmentCode(departmentCode);
        s.setStatus(status);
        s.setOutcome(outcome);
        s.setSource(source == null || source.isBlank() ? "API" : source);
        s.setCompletedAt(completed);
        s.setAuditRef(auditRef);
        steps.save(s);
    }

    /**
     * After-commit module listeners are not ordered. {@code ON CONFLICT}
     * avoids a Postgres-aborted TX if {@link JourneyStarted} already inserted
     * the same id (catching unique-violation still poisons the step write).
     */
    private void ensureApplication(UUID id) {
        jdbc.update(
                """
                INSERT INTO tracking_application
                  (id, reference_no, citizen_id, journey_code, process_instance_id, status, submitted_at)
                VALUES (?, ?, ?, 'UNKNOWN', 'pending', 'SUBMITTED', now())
                ON CONFLICT (id) DO NOTHING
                """,
                id,
                nextReference("UNKNOWN"),
                new UUID(0L, 0L));
    }

    String nextReference(String referencePrefix) {
        Long n = jdbc.queryForObject("SELECT nextval('tracking_reference_seq')", Long.class);
        String prefix = referencePrefix == null || referencePrefix.isBlank() ? "APP" : referencePrefix;
        return "MH-" + prefix + "-" + Year.now() + "-" + String.format("%06d", n);
    }

    @Override
    public ApplicationView byReference(String referenceNo) {
        ApplicationEntity e = applications.findByReferenceNo(referenceNo).orElseThrow(() -> new ApplicationNotFoundException(referenceNo));
        return new ApplicationView(e.getReferenceNo(), e.getCitizenId(), e.getJourneyCode(), e.getStatus(), e.getSubmittedAt(), e.getSlaDueAt());
    }

    @Override
    public Page<ApplicationSummary> forCitizen(UUID citizenId, Pageable p) {
        return applications
                .findByCitizenId(citizenId, p)
                .map(e -> new ApplicationSummary(
                        e.getReferenceNo(), e.getCitizenId(), e.getJourneyCode(), e.getStatus(), e.getSlaDueAt()));
    }

    @Override
    public Page<ApplicationSummary> recent(Pageable p) {
        return applications
                .findAll(p)
                .map(e -> new ApplicationSummary(
                        e.getReferenceNo(), e.getCitizenId(), e.getJourneyCode(), e.getStatus(), e.getSlaDueAt()));
    }

    @Override
    public List<StepView> steps(String referenceNo) {
        ApplicationEntity e = applications.findByReferenceNo(referenceNo).orElseThrow(() -> new ApplicationNotFoundException(referenceNo));
        return steps.findByApplicationId(e.getId()).stream()
                .map(s -> new StepView(
                        s.getStepCode(),
                        s.getDepartmentCode(),
                        s.getStatus(),
                        s.getStartedAt(),
                        s.getCompletedAt(),
                        s.getSlaDueAt(),
                        s.getSource(),
                        Optional.ofNullable(s.getOutcome()),
                        Optional.ofNullable(s.getDataAsOf())))
                .toList();
    }

}
