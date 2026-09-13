package com.samanvay.audit.internal.web;

import com.samanvay.audit.api.AuditQuery;
import com.samanvay.audit.api.AuditRecord;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Checkpoint;
import com.samanvay.audit.api.VerificationResult;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
class AuditController {

    private final AuditService audit;

    AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/head")
    Head head() {
        return new Head(audit.headSeq());
    }

    @GetMapping("/verify")
    VerificationResult verify(
            @RequestParam(required = false) Long from, @RequestParam(required = false) Long to) {
        long head = audit.headSeq();
        long start = from == null || from < 1 ? 1 : from;
        long end = to == null || to < 1 ? Math.max(head, 1) : to;
        if (head == 0) {
            return VerificationResult.ok(0, 0);
        }
        return audit.verify(start, Math.min(end, head));
    }

    @GetMapping("/entries")
    List<AuditRecord> entries(
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return audit.browse(new AuditQuery(null, action, null, null), PageRequest.of(page, size))
                .getContent();
    }

    @GetMapping("/checkpoint")
    Checkpoint checkpoint() {
        return audit.latestCheckpoint().orElse(null);
    }

    record Head(long seq) {}
}
