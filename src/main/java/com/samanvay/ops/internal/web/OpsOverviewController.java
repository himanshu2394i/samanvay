package com.samanvay.ops.internal.web;

import com.samanvay.ops.internal.service.OpsOverviewService;
import com.samanvay.ops.internal.service.OpsOverviewView;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The onboarded departments, their documents and journeys. Staff only (OFFICER, ADMIN; see SecurityConfig). */
@RestController
@RequestMapping("/api/ops")
class OpsOverviewController {

    private final OpsOverviewService overview;

    OpsOverviewController(OpsOverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/overview")
    ResponseEntity<OpsOverviewView> overview() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(overview.overview());
    }
}
