package com.samanvay.tracking.internal.web;

import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.ApplicationView;
import com.samanvay.tracking.api.StepView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/applications")
class TrackingController {

    private final ApplicationTracking tracking;

    TrackingController(ApplicationTracking tracking) {
        this.tracking = tracking;
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
