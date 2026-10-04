package com.samanvay.orchestration.internal.service;

import com.samanvay.catalog.api.Capability;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.JourneyDefinition;
import com.samanvay.catalog.api.JourneyPolicy;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.ConsentRevoked;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ExecutionInputs;
import com.samanvay.connector.api.FailureKind;
import com.samanvay.orchestration.api.JourneyConflictException;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.IdentityResolution;
import com.samanvay.identity.api.Link;
import com.samanvay.orchestration.api.ApplicationStateChanged;
import com.samanvay.orchestration.api.InstanceNotFoundException;
import com.samanvay.orchestration.api.JourneyInstance;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.orchestration.api.JourneyExceptionView;
import com.samanvay.orchestration.api.JourneyStarted;
import com.samanvay.orchestration.api.JourneyState;
import com.samanvay.orchestration.api.MissingDepartmentLinksException;
import com.samanvay.orchestration.api.ManualUploadRequested;
import com.samanvay.orchestration.api.StepCompleted;
import com.samanvay.orchestration.api.StepFailed;
import com.samanvay.orchestration.api.StepPendingSource;
import com.samanvay.orchestration.api.WorkflowEngine;
import com.samanvay.orchestration.internal.domain.InstanceEntity;
import com.samanvay.orchestration.internal.domain.StepStateEntity;
import com.samanvay.orchestration.internal.repository.InstanceRepository;
import com.samanvay.orchestration.internal.repository.StepStateRepository;
import com.samanvay.orchestration.internal.workflow.FetchDataDelegate;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PrincipalRef;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class DefaultJourneyService implements JourneyService {

    private static final Logger log = LoggerFactory.getLogger(DefaultJourneyService.class);
    private static final Set<String> TERMINAL = Set.of("APPROVED", "REJECTED", "CLOSED");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ExecutorService FANOUT = Executors.newVirtualThreadPerTaskExecutor();

    private final JourneyCatalog journeys;
    private final ConnectorCatalog connectors;
    private final WorkflowEngine engine;
    private final FetchDataDelegate fetch;
    private final IdentityLinking linking;
    private final IdentityResolution resolution;
    private final InstanceRepository instances;
    private final StepStateRepository steps;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    DefaultJourneyService(
            JourneyCatalog journeys,
            ConnectorCatalog connectors,
            WorkflowEngine engine,
            FetchDataDelegate fetch,
            IdentityLinking linking,
            IdentityResolution resolution,
            InstanceRepository instances,
            StepStateRepository steps,
            JdbcTemplate jdbc,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactions) {
        this.journeys = journeys;
        this.connectors = connectors;
        this.engine = engine;
        this.fetch = fetch;
        this.linking = linking;
        this.resolution = resolution;
        this.instances = instances;
        this.steps = steps;
        this.jdbc = jdbc;
        this.events = events;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public JourneyInstance start(String journeyCode, UUID citizenId, JsonNode submission, PrincipalRef initiatedBy) {
        java.util.Objects.requireNonNull(initiatedBy, "initiatedBy");
        JourneyDefinition journey = journeys.byCode(journeyCode);
        if (!"PUBLISHED".equals(journey.status())) {
            throw new JourneyConflictException("JOURNEY_NOT_PUBLISHED", "Journey " + journey.code() + " is not published");
        }
        requireDepartmentLinks(journey, citizenId);
        JourneyInstance started = tx.execute(status -> persistStart(journey, citizenId));
        List<CategoryFetch> fetches =
                fetchAll(journey, started.id(), started.processInstanceId(), citizenId, journey.requiredCategories(), initiatedBy);
        tx.executeWithoutResult(status -> applyFetches(started.id(), journey.requiredCategories(), fetches));
        return started;
    }

    private List<CategoryFetch> fetchAll(
            JourneyDefinition journey,
            UUID applicationId,
            String processId,
            UUID citizenId,
            List<String> categories,
            PrincipalRef initiatedBy) {
        return categories.stream()
                .map(category -> CompletableFuture.supplyAsync(
                        () -> fetchCategory(journey, applicationId, processId, citizenId, category, initiatedBy), FANOUT))
                .map(CompletableFuture::join)
                .toList();
    }

    private void requireDepartmentLinks(JourneyDefinition journey, UUID citizenId) {
        var sources = journey.policy().sources();
        if (sources == null || sources.isEmpty()) {
            return;
        }
        LinkedHashSet<String> required = new LinkedHashSet<>();
        for (String category : journey.requiredCategories()) {
            required.add(journey.policy().sourceDepartment(category));
        }
        List<String> missing = new ArrayList<>();
        for (String dept : required) {
            if (linking.activeLink(citizenId, dept).isEmpty()) {
                missing.add(dept);
            }
        }
        if (!missing.isEmpty()) {
            throw new MissingDepartmentLinksException(journey.code(), missing);
        }
    }

    JourneyInstance persistStart(JourneyDefinition journey, UUID citizenId) {
        // One open application per citizen and journey. The advisory lock (held to the end of this transaction) makes
        // two simultaneous starts take turns, so the second one sees the first's row.
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, "journey-start:" + citizenId + ":" + journey.code());
        Integer open = jdbc.queryForObject(
                "SELECT count(*) FROM orchestration_instance WHERE citizen_id = ? AND journey_code = ?"
                        + " AND status NOT IN ('APPROVED','REJECTED','CLOSED')",
                Integer.class,
                citizenId,
                journey.code());
        if (open != null && open > 0) {
            throw new JourneyConflictException(
                    "JOURNEY_ALREADY_OPEN", "The citizen already has an open application for " + journey.code());
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("citizenId", citizenId.toString());
        vars.put("journeyCode", journey.code());
        String processId = engine.start(journey.bpmnRef(), vars);
        UUID id = UUID.randomUUID();
        InstanceEntity e = new InstanceEntity();
        e.setId(id);
        e.setJourneyCode(journey.code());
        e.setCitizenId(citizenId);
        e.setProcessInstanceId(processId);
        e.setPinnedConnectorVersions(pinVersions(journey));
        e.setCreatedAt(Instant.now());
        instances.save(e);
        // A row per required category from the start, so a category that is never fetched still holds VERIFIED back.
        for (String category : journey.requiredCategories()) {
            StepStateEntity step = new StepStateEntity();
            step.setId(UUID.randomUUID());
            step.setInstanceId(id);
            step.setStepCode(category);
            step.setStatus("PENDING");
            steps.save(step);
        }
        events.publishEvent(new JourneyStarted(
                id, journey.code(), citizenId, processId, slaDueAt(journey.policy().slaHours()), journey.policy().referencePrefix()));
        return new JourneyInstance(id, processId, journey.code(), citizenId);
    }

    private CategoryFetch fetchCategory(
            JourneyDefinition journey,
            UUID applicationId,
            String processId,
            UUID citizenId,
            String category,
            PrincipalRef initiatedBy) {
        JourneyPolicy policy = journey.policy();
        String dept = null;
        String source = "API";
        try {
            dept = policy.sourceDepartment(category);
            var connector = connectors.resolve(dept, DataCategory.of(category), Capability.FETCH).orElseThrow();
            String protocol = connectors.dataSourceFor(connector).protocol();
            source = "SFTP_CSV".equals(protocol) || "JDBC".equals(protocol) ? "BATCH" : "API";
            Link link = linking.activeLink(citizenId, dept).orElse(null);
            var request = new AccessRequest(
                    new SubjectRef(citizenId),
                    new RequesterRef(policy.requester()),
                    DataCategory.of(category),
                    dept,
                    connector.ref(),
                    PurposeCode.of(policy.purpose()),
                    journey.code(),
                    initiatedBy,
                    applicationId.toString());
            var inputs = new ExecutionInputs(DataCategory.of(category), processId, linkInputs(link), Map.of(), Map.of());
            ConnectorResult result = fetch.executeForResult(request, inputs);
            if (result instanceof ConnectorResult.Success && link != null) {
                try {
                    resolution.submitCandidate(dept, candidate(link.localIdToken()));
                } catch (RuntimeException e) {
                    // Discovery is a side effect: the document was fetched, so the step stands.
                    log.warn("discovery candidate for {} was not recorded: {}", dept, e.getClass().getSimpleName());
                }
            }
            return new CategoryFetch(category, dept, source, result);
        } catch (RuntimeException e) {
            // A timeout, a 4xx/5xx, a body that is not JSON or a mapping failure must not abort the fan-out and leave an
            // application nobody can finish: the step waits as PENDING_SOURCE with an open exception, and a retry tries again.
            log.warn("fetch of {} for application {} failed: {}", category, applicationId, e.getClass().getSimpleName());
            return new CategoryFetch(category, dept, source, new ConnectorResult.Unavailable(FailureKind.REMOTE_FAULT, true));
        }
    }

    static JsonNode candidate(String localId) {
        return JSON.createObjectNode().put("localId", localId);
    }

    /** When an application is due, or {@code null} for a journey with no SLA (hours of zero or less), never one born breached. */
    static Instant slaDueAt(int slaHours) {
        return slaHours <= 0 ? null : Instant.now().plusSeconds(slaHours * 3600L);
    }

    /**
     * What a connector can bind with {@code from: link.<name>}: the department's person ID as {@code personId} (what a
     * department manifest's input is named after), its type as {@code localIdType}, and the original {@code localIdToken}
     * so connectors written before manifests keep working.
     */
    static Map<String, String> linkInputs(Link link) {
        return link == null
                ? Map.of()
                : Map.of("personId", link.localIdToken(), "localIdType", link.localIdType(), "localIdToken", link.localIdToken());
    }

    void applyFetches(UUID instanceId, List<String> requiredCategories, List<CategoryFetch> fetches) {
        // Lock the row: a concurrent approve/reject/cancel waits for this transaction, and a late start or retry that
        // finds the application already final changes nothing and publishes nothing.
        String current = jdbc.queryForObject(
                "SELECT status FROM orchestration_instance WHERE id = ? FOR UPDATE", String.class, instanceId);
        if (TERMINAL.contains(current)) {
            return;
        }
        for (CategoryFetch f : fetches) {
            String outcome = switch (f.result()) {
                case ConnectorResult.Success s -> "COMPLETED";
                case ConnectorResult.Unavailable u when u.retryable() -> "PENDING_SOURCE";
                case ConnectorResult.NotFound nf -> "NOT_FOUND";
                case ConnectorResult.Invalid inv -> "INVALID";
                default -> "FAILED";
            };
            saveStep(instanceId, f.category(), outcome);
            if ("COMPLETED".equals(outcome)) {
                events.publishEvent(new StepCompleted(instanceId, f.category(), outcome, null, f.department(), f.source()));
                jdbc.update(
                        "UPDATE orchestration_exception SET status = 'RESOLVED' WHERE instance_id = ? AND step_code = ? AND status = 'OPEN'",
                        instanceId,
                        f.category());
            } else if ("PENDING_SOURCE".equals(outcome)) {
                events.publishEvent(new StepPendingSource(instanceId, f.category(), 1, Instant.now().plusSeconds(30), f.department()));
                events.publishEvent(new ManualUploadRequested(instanceId, f.category(), f.department()));
                openException(instanceId, f.category(), outcome);
            } else {
                events.publishEvent(new StepFailed(instanceId, f.category(), outcome, f.department()));
                openException(instanceId, f.category(), outcome);
            }
        }
        // VERIFIED only when every required category is COMPLETED, judged from the stored rows (not just this batch), so a
        // category that was never fetched, or whose row is missing, holds the application back.
        Map<String, String> rows = stepStatuses(instanceId);
        boolean allCompleted = requiredCategories.stream().allMatch(c -> "COMPLETED".equals(rows.get(c)));
        boolean anyWaiting = requiredCategories.stream().anyMatch(c -> {
            String s = rows.get(c);
            return s == null || "PENDING".equals(s) || "PENDING_SOURCE".equals(s);
        });
        String status = allCompleted ? "VERIFIED" : anyWaiting ? "PARTIALLY_VERIFIED" : "REJECTED";
        // Keep orchestration's own status current (the officer approval step reads it).
        jdbc.update("UPDATE orchestration_instance SET status = ? WHERE id = ?", status, instanceId);
        if ("REJECTED".equals(status)) {
            closeExceptions(instanceId);
        }
        events.publishEvent(new ApplicationStateChanged(instanceId, status));
    }

    private Map<String, String> stepStatuses(UUID instanceId) {
        Map<String, String> rows = new HashMap<>();
        steps.findByInstanceId(instanceId).forEach(s -> rows.put(s.getStepCode(), s.getStatus()));
        return rows;
    }

    @Override
    public void signal(UUID instanceId, String signalName, JsonNode payload) {
        var e = instances.findById(instanceId).orElseThrow(InstanceNotFoundException::new);
        engine.signal(e.getProcessInstanceId(), signalName, Map.of());
    }

    @Override
    public void cancel(UUID instanceId, String reason) {
        instances.findById(instanceId).orElseThrow(InstanceNotFoundException::new);
        tx.executeWithoutResult(status -> {
            int closed = jdbc.update(
                    "UPDATE orchestration_instance SET status = 'CLOSED' WHERE id = ? AND status NOT IN ('APPROVED','REJECTED','CLOSED')",
                    instanceId);
            if (closed == 0) {
                String current = jdbc.queryForObject("SELECT status FROM orchestration_instance WHERE id = ?", String.class, instanceId);
                if ("APPROVED".equals(current)) {
                    throw new JourneyConflictException(
                            "APPLICATION_APPROVED", "Application " + instanceId + " is approved and cannot be cancelled");
                }
                return; // already rejected or closed: nothing to do
            }
            closeExceptions(instanceId);
            events.publishEvent(new ApplicationStateChanged(instanceId, "CLOSED"));
        });
    }

    @Override
    public JourneyState state(UUID instanceId) {
        instances.findById(instanceId).orElseThrow(InstanceNotFoundException::new);
        String status = jdbc.queryForObject("SELECT status FROM orchestration_instance WHERE id = ?", String.class, instanceId);
        return new JourneyState(instanceId, status, new java.util.TreeMap<>(stepStatuses(instanceId)));
    }

    @Override
    public void retryPending(UUID instanceId, PrincipalRef initiatedBy) {
        java.util.Objects.requireNonNull(initiatedBy, "initiatedBy");
        InstanceEntity e = instances.findById(instanceId).orElseThrow(InstanceNotFoundException::new);
        String current = jdbc.queryForObject("SELECT status FROM orchestration_instance WHERE id = ?", String.class, instanceId);
        if (TERMINAL.contains(current)) {
            return; // approved, rejected or closed is final: fetching again could only waste a consent check
        }
        JourneyDefinition journey = journeys.byCode(e.getJourneyCode());
        Map<String, String> rows = stepStatuses(instanceId);
        List<String> todo = journey.requiredCategories().stream().filter(c -> !"COMPLETED".equals(rows.get(c))).toList();
        List<CategoryFetch> fetches = fetchAll(journey, e.getId(), e.getProcessInstanceId(), e.getCitizenId(), todo, initiatedBy);
        tx.executeWithoutResult(status -> applyFetches(instanceId, journey.requiredCategories(), fetches));
    }

    @Override
    public java.util.Set<String> dataSources(String journeyCode) {
        JourneyDefinition journey = journeys.byCode(journeyCode);
        java.util.Set<String> codes = new java.util.TreeSet<>();
        for (String cat : journey.requiredCategories()) {
            connectors.resolve(journey.policy().sourceDepartment(cat), DataCategory.of(cat), Capability.FETCH)
                    .ifPresent(c -> codes.add(connectors.dataSourceFor(c).code()));
        }
        return codes;
    }

    @Override
    public List<JourneyExceptionView> openExceptions() {
        return jdbc.query(
                """
                SELECT id, instance_id, step_code, reason, created_at
                FROM orchestration_exception WHERE status = 'OPEN' ORDER BY created_at DESC
                """,
                (rs, n) -> new JourneyExceptionView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("instance_id", UUID.class),
                        rs.getString("step_code"),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    @ApplicationModuleListener
    void onConsentRevoked(ConsentRevoked event) {
        // in-flight steps would be marked AUTHORIZATION_WITHDRAWN
    }

    private void saveStep(UUID instanceId, String stepCode, String outcome) {
        StepStateEntity s = steps.findByInstanceId(instanceId).stream()
                .filter(existing -> stepCode.equals(existing.getStepCode()))
                .findFirst()
                .orElseGet(StepStateEntity::new);
        if (s.getId() == null) {
            s.setId(UUID.randomUUID());
            s.setInstanceId(instanceId);
            s.setStepCode(stepCode);
        }
        s.setStatus("COMPLETED".equals(outcome) ? "COMPLETED" : "PENDING_SOURCE".equals(outcome) ? "PENDING_SOURCE" : "FAILED");
        s.setAttemptCount(s.getAttemptCount() + 1);
        s.setLastFailureReason("COMPLETED".equals(outcome) ? null : outcome);
        steps.save(s);
    }

    /** One open exception per step: a retry that fails again refreshes the reason instead of queueing a duplicate. */
    private void openException(UUID instanceId, String stepCode, String reason) {
        int refreshed = jdbc.update(
                "UPDATE orchestration_exception SET reason = ? WHERE instance_id = ? AND step_code = ? AND status = 'OPEN'",
                reason,
                instanceId,
                stepCode);
        if (refreshed > 0) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO orchestration_exception (id, instance_id, step_code, reason, status)
                VALUES (?, ?, ?, ?, 'OPEN')
                """,
                UUID.randomUUID(),
                instanceId,
                stepCode,
                reason);
    }

    private void closeExceptions(UUID instanceId) {
        jdbc.update("UPDATE orchestration_exception SET status = 'RESOLVED' WHERE instance_id = ? AND status = 'OPEN'", instanceId);
    }

    private String pinVersions(JourneyDefinition journey) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String cat : journey.requiredCategories()) {
            var c = connectors.resolve(journey.policy().sourceDepartment(cat), DataCategory.of(cat), Capability.FETCH);
            if (c.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(c.get().connectorId()).append("\":").append(c.get().version());
        }
        return sb.append('}').toString();
    }

    private record CategoryFetch(String category, String department, String source, ConnectorResult result) {}
}
