package com.samanvay.orchestration.internal.web;

import com.samanvay.orchestration.api.JourneyExceptionView;
import com.samanvay.orchestration.api.JourneyInstance;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.orchestration.api.JourneyState;
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

    JourneyController(JourneyService journeys) {
        this.journeys = journeys;
    }

    @PostMapping("/{code}/start")
    JourneyInstance start(@PathVariable String code, @RequestBody StartBody body) {
        return journeys.start(code, body.citizenId(), body.submission());
    }

    @GetMapping("/instances/{id}")
    JourneyState state(@PathVariable UUID id) {
        return journeys.state(id);
    }

    @PostMapping("/instances/{id}/retry")
    void retry(@PathVariable UUID id) {
        journeys.retryPending(id);
    }

    @GetMapping("/exceptions")
    List<JourneyExceptionView> exceptions() {
        return journeys.openExceptions();
    }

    record StartBody(UUID citizenId, JsonNode submission) {}
}
