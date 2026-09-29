package com.samanvay.ops.internal.web;

import com.samanvay.ops.internal.service.OpsMetricsService;
import com.samanvay.ops.internal.service.OpsMetricsView;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The four ops dashboards as one JSON document. Staff only (OFFICER, ADMIN; see SecurityConfig). This
 * is the only way the metrics leave the process: the app serves no actuator, Prometheus or other
 * metrics endpoint over HTTP.
 */
@RestController
@RequestMapping("/api/ops")
class OpsMetricsController {

    private final OpsMetricsService metrics;

    OpsMetricsController(OpsMetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/metrics")
    ResponseEntity<OpsMetricsView> metrics() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(metrics.metrics());
    }
}
