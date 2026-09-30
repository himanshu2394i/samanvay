package com.samanvay.orchestration.internal.web;

import com.samanvay.orchestration.internal.service.ApplicationApprovalService;
import com.samanvay.shared.security.Callers;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The officer approval step. OFFICER-only (SecurityConfig + ApiAccessMatrix). The id is the application
 * id (the journey instance id, {@code instanceId} on the tracking view); orchestration does not know
 * tracking's reference numbers. Repeating the call is safe.
 */
@RestController
@RequestMapping("/api/journeys/instances")
class OfficerApprovalController {

    private final ApplicationApprovalService approvals;

    OfficerApprovalController(ApplicationApprovalService approvals) {
        this.approvals = approvals;
    }

    @PostMapping("/{id}/approve")
    Approved approve(@PathVariable UUID id) {
        return new Approved(id, approvals.approve(id, Callers.require().subject()));
    }

    @PostMapping("/{id}/reject")
    Rejected reject(@PathVariable UUID id, @RequestBody(required = false) RejectRequest body) {
        String reason = body == null ? null : body.reason();
        return new Rejected(id, approvals.reject(id, Callers.require().subject(), reason));
    }

    record Approved(UUID instanceId, String status) {}

    record Rejected(UUID instanceId, String status) {}

    record RejectRequest(String reason) {}
}
