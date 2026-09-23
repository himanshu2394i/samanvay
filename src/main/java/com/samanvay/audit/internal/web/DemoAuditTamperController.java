package com.samanvay.audit.internal.web;

import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.VerificationResult;
import com.samanvay.audit.internal.service.DemoAuditTamper;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("demo")
@RestController
@RequestMapping("/api/audit")
class DemoAuditTamperController {

    private final AuditService audit;
    private final DemoAuditTamper tamper;

    DemoAuditTamperController(AuditService audit, DemoAuditTamper tamper) {
        this.audit = audit;
        this.tamper = tamper;
    }

    @PostMapping("/demo/tamper/{seq}")
    VerificationResult tamper(@PathVariable long seq) {
        tamper.rewriteReason(seq, "tampered-demo");
        return audit.verify(seq, seq);
    }
}
