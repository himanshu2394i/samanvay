package com.samanvay.orchestration.internal.web;

import com.samanvay.orchestration.api.JourneyExceptionView;
import com.samanvay.orchestration.api.JourneyInstance;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.orchestration.api.JourneyState;
import com.samanvay.shared.security.Caller;
import com.samanvay.shared.security.Callers;
import com.samanvay.shared.security.CitizenAccess;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/journeys")
class JourneyController {

    private final JourneyService journeys;
    private final CitizenAccess citizenAccess;

    JourneyController(JourneyService journeys, CitizenAccess citizenAccess) {
        this.journeys = journeys;
        this.citizenAccess = citizenAccess;
    }

    @PostMapping("/{code}/start")
    JourneyInstance start(@PathVariable String code, @RequestBody StartBody body) {
        Caller caller = Callers.require();
        citizenAccess.requireMayActOn(body.citizenId());
        if (caller.isDepartmentClient()) {
            // One client scope per data source: a department client may only
            // start a journey whose every fetch it is scoped for.
            Set<String> missing = new TreeSet<>(journeys.dataSources(code));
            missing.removeAll(caller.dataSourceScopes());
            if (!missing.isEmpty()) {
                throw new AccessDeniedException("client lacks data-source scope(s): " + String.join(", ", missing));
            }
        }
        return journeys.start(code, body.citizenId(), body.submission(), caller.principal());
    }

    @GetMapping("/instances/{id}")
    JourneyState state(@PathVariable UUID id) {
        return journeys.state(id);
    }

    @PostMapping("/instances/{id}/retry")
    void retry(@PathVariable UUID id) {
        journeys.retryPending(id, Callers.require().principal());
    }

    @GetMapping("/exceptions")
    List<JourneyExceptionView> exceptions() {
        return journeys.openExceptions();
    }

    record StartBody(UUID citizenId, JsonNode submission) {}
}
