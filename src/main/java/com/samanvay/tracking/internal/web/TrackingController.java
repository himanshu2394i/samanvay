package com.samanvay.tracking.internal.web;

import com.samanvay.tracking.api.ApplicationSummary;
import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.ApplicationView;
import com.samanvay.tracking.api.StepView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/applications")
class TrackingController {

    private final ApplicationTracking tracking;

    TrackingController(ApplicationTracking tracking) {
        this.tracking = tracking;
    }

    @GetMapping
    List<ApplicationSummary> list(
            @RequestParam(required = false) UUID citizenId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var p = PageRequest.of(page, size);
        return citizenId == null ? tracking.recent(p).getContent() : tracking.forCitizen(citizenId, p).getContent();
    }

    @GetMapping("/{referenceNo}")
    ApplicationView byReference(@PathVariable String referenceNo) {
        return tracking.byReference(referenceNo);
    }

    @GetMapping("/{referenceNo}/steps")
    List<StepView> steps(@PathVariable String referenceNo) {
        return tracking.steps(referenceNo);
    }
}
