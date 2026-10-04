package com.samanvay.connector.internal.service;

import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.TrialGate;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ConnectorRuntime;
import com.samanvay.shared.InvalidRequestException;
import com.samanvay.shared.SamanvayException;
import com.samanvay.shared.security.Callers;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a trial fetch of a connector and records it durably ({@link TrialLog}): one place for the admin trial endpoint and for the
 * catalog's "test" and "publish" steps, so what publish checks is exactly what a trial recorded.
 */
@Service
public class ConnectorTrialService implements TrialGate {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** {@code outcome}: SUCCESS, NOT_FOUND, UNAVAILABLE, INVALID or ERROR (the call itself failed, e.g. refused or misconfigured). */
    public record TrialResult(boolean ok, String outcome, String personId, JsonNode fields, String detail) {}

    private final ConnectorRuntime runtime;
    private final ConnectorCatalog catalog;
    private final TrialLog trials;
    private final Clock clock;

    ConnectorTrialService(ConnectorRuntime runtime, ConnectorCatalog catalog, TrialLog trials, Clock clock) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.trials = trials;
        this.clock = clock;
    }

    /** Runs and records a trial for {@code requestedPerson}, else the connector's sample person. */
    public TrialResult run(String ref, String requestedPerson) {
        TrialResult result = fetch(ref, requestedPerson);
        trials.record(ref, result.outcome());
        return result;
    }

    @Override
    public Optional<TrialSummary> runSample(String connectorRef) {
        if (sample(catalog.byRef(connectorRef)) == null) {
            return Optional.empty();
        }
        TrialResult r = run(connectorRef, null);
        return Optional.of(new TrialSummary(r.outcome(), r.detail()));
    }

    @Override
    public boolean succeededWithin(String connectorRef, Duration window) {
        return trials.last(connectorRef)
                .filter(t -> "SUCCESS".equals(t.outcome()) && t.at().isAfter(clock.instant().minus(window)))
                .isPresent();
    }

    private TrialResult fetch(String ref, String requestedPerson) {
        ConnectorDefinition connector = catalog.byRef(ref); // 404 when unknown
        String person = requestedPerson != null && !requestedPerson.isBlank() ? requestedPerson.trim() : sample(connector);
        if (person == null) {
            throw new InvalidRequestException("This connector has no sample person: name one in personId");
        }
        try {
            ConnectorResult r = runtime.trial(ref, person, Callers.require().principal());
            return switch (r) {
                case ConnectorResult.Success s -> new TrialResult(true, "SUCCESS", person, s.canonical(), null);
                case ConnectorResult.NotFound nf -> new TrialResult(false, "NOT_FOUND", person, null, nf.detail());
                case ConnectorResult.Unavailable u -> new TrialResult(false, "UNAVAILABLE", person, null, String.valueOf(u.kind()));
                case ConnectorResult.Invalid i -> new TrialResult(false, "INVALID", person, null, String.join("; ", i.violations()));
            };
        } catch (SamanvayException e) {
            throw e;
        } catch (RuntimeException e) {
            // Adapter messages name the source and parameter, never a secret value.
            return new TrialResult(false, "ERROR", person, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String sample(ConnectorDefinition connector) {
        JsonNode fetch = JSON.readTree(connector.capabilitiesJson()).get("FETCH");
        JsonNode s = fetch == null ? null : fetch.get("sample_person_id");
        return s == null || s.asString().isBlank() ? null : s.asString();
    }
}
