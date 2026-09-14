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
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    public JourneyInstance start(String journeyCode, UUID citizenId, JsonNode submission) {
        JourneyDefinition journey = journeys.byCode(journeyCode);
        requireDepartmentLinks(journey, citizenId);
        JourneyInstance started = tx.execute(status -> persistStart(journey, citizenId));
        List<CategoryFetch> fetches = journey.requiredCategories().stream()
                .map(category -> CompletableFuture.supplyAsync(
                        () -> fetchCategory(journey, started.processInstanceId(), citizenId, category), FANOUT))
                .map(CompletableFuture::join)
                .toList();
        tx.executeWithoutResult(status -> applyFetches(started.id(), fetches));
        return started;
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
        events.publishEvent(new JourneyStarted(
                id,
                journey.code(),
                citizenId,
                processId,
                Instant.now().plusSeconds(journey.policy().slaHours() * 3600L),
                journey.policy().referencePrefix()));
        return new JourneyInstance(id, processId, journey.code(), citizenId);
    }

    private CategoryFetch fetchCategory(JourneyDefinition journey, String processId, UUID citizenId, String category) {
        JourneyPolicy policy = journey.policy();
        String dept = policy.sourceDepartment(category);
        var connector = connectors.resolve(dept, DataCategory.of(category), Capability.FETCH).orElseThrow();
        Link link = linking.activeLink(citizenId, dept).orElse(null);
        var request = new AccessRequest(
                new SubjectRef(citizenId),
                new RequesterRef(policy.requester()),
                DataCategory.of(category),
                dept,
                connector.ref(),
                PurposeCode.of(policy.purpose()),
                journey.code());
        var inputs = new ExecutionInputs(
                DataCategory.of(category),
                processId,
                link == null ? Map.of() : Map.of("localIdToken", link.localIdToken()),
                Map.of(),
                Map.of());
        ConnectorResult result = fetch.executeForResult(request, inputs);
        if (result instanceof ConnectorResult.Success && link != null) {
            resolution.submitCandidate(dept, JSON.readTree("{\"localId\":\"" + link.localIdToken() + "\"}"));
        }
        String protocol = connectors.dataSourceFor(connector).protocol();
        String source = "SFTP_CSV".equals(protocol) || "JDBC".equals(protocol) ? "BATCH" : "API";
        return new CategoryFetch(category, dept, source, result);
    }

    void applyFetches(UUID instanceId, List<CategoryFetch> fetches) {
        boolean pending = false;
        boolean failed = false;
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
                pending = true;
                events.publishEvent(new StepPendingSource(instanceId, f.category(), 1, Instant.now().plusSeconds(30), f.department()));
                events.publishEvent(new ManualUploadRequested(instanceId, f.category(), f.department()));
                openException(instanceId, f.category(), outcome);
            } else {
                failed = true;
                events.publishEvent(new StepFailed(instanceId, f.category(), outcome, f.department()));
                openException(instanceId, f.category(), outcome);
            }
        }
        String status = pending ? "PARTIALLY_VERIFIED" : failed ? "REJECTED" : "VERIFIED";
        events.publishEvent(new ApplicationStateChanged(instanceId, status));
    }

    @Override
    public void signal(UUID instanceId, String signalName, JsonNode payload) {
        var e = instances.findById(instanceId).orElseThrow(InstanceNotFoundException::new);
        engine.signal(e.getProcessInstanceId(), signalName, Map.of());
    }

    @Override
    public void cancel(UUID instanceId, String reason) {
        instances.findById(instanceId).orElseThrow(InstanceNotFoundException::new);
        events.publishEvent(new ApplicationStateChanged(instanceId, "CLOSED"));
    }

    @Override
    public JourneyState state(UUID instanceId) {
        instances.findById(instanceId).orElseThrow(InstanceNotFoundException::new);
        Map<String, String> outcomes = new HashMap<>();
        steps.findByInstanceId(instanceId).forEach(s -> outcomes.put(s.getStatus(), s.getStatus()));
        return new JourneyState(instanceId, "RUNNING", outcomes);
    }

    @Override
    public void retryPending(UUID instanceId) {
        InstanceEntity e = instances.findById(instanceId).orElseThrow(InstanceNotFoundException::new);
        JourneyDefinition journey = journeys.byCode(e.getJourneyCode());
        List<CategoryFetch> fetches = steps.findByInstanceId(instanceId).stream()
                .filter(s -> !"COMPLETED".equals(s.getStatus()))
                .map(s -> fetchCategory(journey, e.getProcessInstanceId(), e.getCitizenId(), s.getStepCode()))
                .toList();
        tx.executeWithoutResult(status -> applyFetches(instanceId, fetches));
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
        s.setAttemptCount(1);
        s.setLastFailureReason("COMPLETED".equals(outcome) ? null : outcome);
        steps.save(s);
    }

    private void openException(UUID instanceId, String stepCode, String reason) {
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
