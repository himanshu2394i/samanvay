package com.samanvay.connector.internal.web;

import com.samanvay.connector.internal.service.ConnectorTrialService;
import com.samanvay.connector.internal.service.ConnectorTrialService.TrialResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN: run a trial fetch of a drafted (or published) connector for the department's published FAKE sample person, and see what
 * came back. The answer is always the trial's result (HTTP 200) so a department refusal or a missing credential is shown as the
 * finding it is; only a bad request (no person to ask for, or a person that does not look like the declared sample) or an unknown
 * connector is an HTTP error. Every trial is recorded durably; publishing a connector needs a recent successful one.
 */
@RestController
@RequestMapping("/api/connector")
class ConnectorTrialController {

    private final ConnectorTrialService trials;

    ConnectorTrialController(ConnectorTrialService trials) {
        this.trials = trials;
    }

    record TrialBody(String personId) {}

    @PostMapping("/trial/{ref}")
    TrialResult trial(@PathVariable String ref, @RequestBody(required = false) TrialBody body) {
        return trials.run(ref, body == null ? null : body.personId());
    }
}
