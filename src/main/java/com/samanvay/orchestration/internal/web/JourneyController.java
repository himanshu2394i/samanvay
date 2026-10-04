package com.samanvay.orchestration.internal.web;

import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.orchestration.api.JourneyExceptionView;
import com.samanvay.shared.NotFoundException;
import org.springframework.security.access.AccessDeniedException;
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
    private final IdentityLinking linking;

    JourneyController(
            JourneyService journeys, CitizenAccess citizenAccess, DepartmentScope departmentScope, IdentityLinking linking) {
        this.journeys = journeys;
        this.citizenAccess = citizenAccess;
        this.departmentScope = departmentScope;
        this.linking = linking;
    }

    @PostMapping("/{code}/start")
    JourneyInstance start(@PathVariable String code, @RequestBody StartBody body) {
        Caller caller = Callers.require();
        citizenAccess.requireMayActOn(body.citizenId());
        // A department client starts the journeys it runs, and only for its own citizens (404, like readiness, so it
        // learns nothing about anyone else). Consent and the citizen's links decide what is fetched.
        departmentScope.requireRuns(code);
        if (caller.isDepartmentClient()) {
            if (caller.department() == null || caller.department().isBlank()) {
                throw new AccessDeniedException("token carries no department");
            }
            if (body.citizenId() == null || linking.activeLink(body.citizenId(), caller.department()).isEmpty()) {
                throw new NotFoundException("citizen");
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
