package com.samanvay.orchestration.internal.web;

import com.samanvay.orchestration.api.JourneyExceptionView;
import com.samanvay.orchestration.api.JourneyInstance;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.orchestration.api.JourneyState;
import com.samanvay.shared.security.Caller;
import com.samanvay.shared.security.Callers;
import com.samanvay.shared.security.CitizenAccess;
import com.samanvay.shared.security.DepartmentScope;
import java.util.List;
import java.util.UUID;
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
    private final DepartmentScope departmentScope;

    JourneyController(JourneyService journeys, CitizenAccess citizenAccess, DepartmentScope departmentScope) {
        this.journeys = journeys;
        this.citizenAccess = citizenAccess;
        this.departmentScope = departmentScope;
    }

    @PostMapping("/{code}/start")
    JourneyInstance start(@PathVariable String code, @RequestBody StartBody body) {
        Caller caller = Callers.require();
        citizenAccess.requireMayActOn(body.citizenId());
        // A department client starts the journeys it runs; consent and the citizen's links decide what is fetched.
        departmentScope.requireRuns(code);
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
