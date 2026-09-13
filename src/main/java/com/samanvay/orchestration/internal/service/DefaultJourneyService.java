package com.samanvay.orchestration.internal.service;

import com.samanvay.catalog.api.Capability;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.JourneyDefinition;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.ConsentRevoked;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ExecutionInputs;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.Link;
import com.samanvay.orchestration.api.ApplicationStateChanged;
import com.samanvay.orchestration.api.InstanceNotFoundException;
import com.samanvay.orchestration.api.JourneyInstance;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.orchestration.api.JourneyStarted;
import com.samanvay.orchestration.api.JourneyState;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
class DefaultJourneyService implements JourneyService {

    private final JourneyCatalog journeys;
    private final ConnectorCatalog connectors;
    private final WorkflowEngine engine;
    private final FetchDataDelegate fetch;
    private final IdentityLinking linking;
    private final InstanceRepository instances;
    private final StepStateRepository steps;
    private final ApplicationEventPublisher events;

    DefaultJourneyService(
            JourneyCatalog journeys,
            ConnectorCatalog connectors,
            WorkflowEngine engine,
            FetchDataDelegate fetch,
            IdentityLinking linking,
            InstanceRepository instances,
            StepStateRepository steps,
            ApplicationEventPublisher events) {
        this.journeys = journeys;
        this.connectors = connectors;
        this.engine = engine;
        this.fetch = fetch;
        this.linking = linking;
        this.instances = instances;
        this.steps = steps;
        this.events = events;
    }

    @Override
    @Transactional
    public JourneyInstance start(String journeyCode, UUID citizenId, JsonNode submission) {
        JourneyDefinition journey = journeys.byCode(journeyCode);
        Map<String, Object> vars = new HashMap<>();
        vars.put("citizenId", citizenId.toString());
        vars.put("journeyCode", journeyCode);
        String processId = engine.start(journey.bpmnRef(), vars);
        UUID id = UUID.randomUUID();
        InstanceEntity e = new InstanceEntity();
        e.setId(id);
        e.setJourneyCode(journeyCode);
        e.setCitizenId(citizenId);
        e.setProcessInstanceId(processId);
        e.setPinnedConnectorVersions(pinVersions(journey));
        e.setCreatedAt(Instant.now());
        instances.save(e);
        events.publishEvent(new JourneyStarted(id, journeyCode, citizenId, processId, Instant.now().plusSeconds(journey.policy().slaHours() * 3600L)));
        boolean pending = false;
        boolean failed = false;
        for (String category : journey.requiredCategories()) {
            String dept = departmentFor(category);
            var connector = connectors.resolve(dept, DataCategory.of(category), Capability.FETCH).orElseThrow();
            Link link = linking.activeLink(citizenId, dept).orElse(null);
            var request = new AccessRequest(
                    new SubjectRef(citizenId),
                    new RequesterRef("SCHOLARSHIP"),
                    DataCategory.of(category),
                    dept,
                    connector.ref(),
                    PurposeCode.SCHOLARSHIP_ELIGIBILITY,
                    journeyCode);
            var inputs = new ExecutionInputs(
                    DataCategory.of(category),
                    processId,
                    link == null ? Map.of() : Map.of("localIdToken", link.localIdToken()),
                    Map.of(),
                    Map.of());
            ConnectorResult result = fetch.executeForResult(request, inputs);
            String outcome = switch (result) {
                case ConnectorResult.Success s -> "COMPLETED";
                case ConnectorResult.Unavailable u when u.retryable() -> "PENDING_SOURCE";
                case ConnectorResult.NotFound nf -> "NOT_FOUND";
                case ConnectorResult.Invalid inv -> "INVALID";
                default -> "FAILED";
            };
            saveStep(id, category, outcome);
            if ("COMPLETED".equals(outcome)) {
                events.publishEvent(new StepCompleted(id, category, outcome, null));
            } else if ("PENDING_SOURCE".equals(outcome)) {
                pending = true;
                events.publishEvent(new StepPendingSource(id, category, 1, Instant.now().plusSeconds(30)));
            } else {
                failed = true;
                events.publishEvent(new StepFailed(id, category, outcome));
            }
        }
        String status = pending ? "PARTIALLY_VERIFIED" : failed ? "REJECTED" : "VERIFIED";
        events.publishEvent(new ApplicationStateChanged(id, status));
        return new JourneyInstance(id, processId, journeyCode, citizenId);
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

    @ApplicationModuleListener
    void onConsentRevoked(ConsentRevoked event) {
        // in-flight steps would be marked AUTHORIZATION_WITHDRAWN; Journey 1 happy-path has no long-lived grant
    }

    private void saveStep(UUID instanceId, String stepCode, String outcome) {
        StepStateEntity s = new StepStateEntity();
        s.setId(UUID.randomUUID());
        s.setInstanceId(instanceId);
        s.setStepCode(stepCode);
        s.setStatus("COMPLETED".equals(outcome) ? "COMPLETED" : "PENDING_SOURCE".equals(outcome) ? "PENDING_SOURCE" : "FAILED");
        s.setAttemptCount(1);
        s.setLastFailureReason("COMPLETED".equals(outcome) ? null : outcome);
        steps.save(s);
    }

    private String pinVersions(JourneyDefinition journey) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String cat : journey.requiredCategories()) {
            var c = connectors.resolve(departmentFor(cat), DataCategory.of(cat), Capability.FETCH);
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

    static String departmentFor(String category) {
        return switch (category) {
            case "MARKS" -> "EDUCATION";
            case "BANK_ACCOUNT" -> "DBT";
            default -> "REVENUE";
        };
    }
}
