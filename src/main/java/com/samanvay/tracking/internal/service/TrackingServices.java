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
import org.springframework.transaction.annotation.Transactional;

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
        String ref = nextReference(event.journeyCode());
        ApplicationEntity e = new ApplicationEntity();
        e.setId(event.instanceId());
        e.setReferenceNo(ref);
        e.setCitizenId(event.citizenId());
        e.setJourneyCode(event.journeyCode());
        e.setProcessInstanceId(event.processInstanceId());
        e.setStatus("SUBMITTED");
        e.setSubmittedAt(Instant.now());
        e.setSlaDueAt(event.slaDueAt());
        applications.save(e);
        events.publishEvent(new ApplicationReferenceIssued(ref, event.citizenId()));
    }

    @ApplicationModuleListener
    void on(StepCompleted event) {
        upsertStep(event.instanceId(), event.stepCode(), "COMPLETED", event.outcome(), Instant.now(), event.auditRef());
    }

    @ApplicationModuleListener
    void on(StepFailed event) {
        upsertStep(event.instanceId(), event.stepCode(), "FAILED", event.outcome(), Instant.now(), null);
    }

    @ApplicationModuleListener
    void on(StepPendingSource event) {
        upsertStep(event.instanceId(), event.stepCode(), "PENDING_SOURCE", null, null, null);
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

    private void upsertStep(UUID applicationId, String stepCode, String status, String outcome, Instant completed, Long auditRef) {
        StepEntity s = steps.findByApplicationIdAndStepCode(applicationId, stepCode).orElseGet(StepEntity::new);
        if (s.getId() == null) {
            s.setId(UUID.randomUUID());
            s.setApplicationId(applicationId);
            s.setStepCode(stepCode);
            s.setStartedAt(Instant.now());
        }
        s.setDepartmentCode(departmentFor(stepCode));
        s.setStatus(status);
        s.setOutcome(outcome);
        s.setSource("API");
        s.setCompletedAt(completed);
        s.setAuditRef(auditRef);
        steps.save(s);
    }

    String nextReference(String journeyCode) {
        Long n = jdbc.queryForObject("SELECT nextval('tracking_reference_seq')", Long.class);
        String prefix = "POST_MATRIC_SCHOLARSHIP".equals(journeyCode) ? "SCH" : "APP";
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
                .map(e -> new ApplicationSummary(e.getReferenceNo(), e.getCitizenId(), e.getJourneyCode(), e.getStatus()));
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

    private static String departmentFor(String step) {
        return switch (step) {
            case "MARKS" -> "EDUCATION";
            case "BANK_ACCOUNT" -> "DBT";
            default -> "REVENUE";
        };
    }
}
