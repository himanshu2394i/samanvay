package com.samanvay.ops.internal.web;

import com.samanvay.ops.internal.service.JourneyStatusService;
import com.samanvay.ops.internal.service.JourneyStatusView;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One journey's connected-and-working view and middle-layer log. Staff only (OFFICER, ADMIN; see SecurityConfig). */
@RestController
@RequestMapping("/api/ops")
class OpsJourneyController {

    private final JourneyStatusService status;

    OpsJourneyController(JourneyStatusService status) {
        this.status = status;
    }

    @GetMapping("/journeys/{code}")
    ResponseEntity<JourneyStatusView> journey(@PathVariable String code) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status.status(code));
    }
}
