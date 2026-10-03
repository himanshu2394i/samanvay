package com.samanvay.connector.internal.web;

import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ConnectorRuntime;
import com.samanvay.shared.InvalidRequestException;
import com.samanvay.shared.SamanvayException;
import com.samanvay.shared.security.Callers;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADMIN: run a trial fetch of a drafted (or published) connector for the department's published FAKE sample person, and see what
 * came back. The answer is always the trial's result (HTTP 200) so a department refusal or a missing credential is shown as the
 * finding it is; only a bad request (no person to ask for) or an unknown connector is an HTTP error.
 */
@RestController
@RequestMapping("/api/connector")
class ConnectorTrialController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ConnectorRuntime runtime;
    private final ConnectorCatalog catalog;

    ConnectorTrialController(ConnectorRuntime runtime, ConnectorCatalog catalog) {
        this.runtime = runtime;
        this.catalog = catalog;
    }

    record TrialBody(String personId) {}

    /** {@code outcome}: SUCCESS, NOT_FOUND, UNAVAILABLE, INVALID or ERROR (the call itself failed, e.g. refused or misconfigured). */
    record TrialResult(boolean ok, String outcome, String personId, JsonNode fields, String detail) {}

    @PostMapping("/trial/{ref}")
    TrialResult trial(@PathVariable String ref, @RequestBody(required = false) TrialBody body) {
        ConnectorDefinition connector = catalog.byRef(ref); // 404 when unknown
        String person = body != null && body.personId() != null && !body.personId().isBlank() ? body.personId().trim() : sample(connector);
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
