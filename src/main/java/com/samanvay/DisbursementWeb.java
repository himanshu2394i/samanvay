package com.samanvay;

import com.samanvay.payments.api.Disbursement;
import com.samanvay.payments.api.DisbursementService;
import com.samanvay.payments.api.Instalment;
import com.samanvay.shared.security.CitizenAccess;
import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.ApplicationView;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads the disbursement issued for an approved application, so the citizen (their own) and an
 * officer can see the outcome of the journey. A cross-module bridge (like {@code IssuedRecordsWeb}):
 * it resolves the application through {@code tracking}, authorises the caller with {@code CitizenAccess}
 * (the same {@code /api/applications/**} rule already gates the route to CITIZEN/OFFICER), and reads
 * the mock-DBT record from {@code payments}. No amount is exposed — the mock DBT holds none; only the
 * status and per-instalment schedule. 204 when the application exists but is not yet disbursed.
 */
@RestController
@RequestMapping("/api/applications")
class DisbursementWeb {

    private final ApplicationTracking tracking;
    private final DisbursementService disbursements;
    private final CitizenAccess citizenAccess;

    DisbursementWeb(ApplicationTracking tracking, DisbursementService disbursements, CitizenAccess citizenAccess) {
        this.tracking = tracking;
        this.disbursements = disbursements;
        this.citizenAccess = citizenAccess;
    }

    @GetMapping("/{referenceNo}/disbursement")
    ResponseEntity<DisbursementView> disbursement(@PathVariable String referenceNo) {
        ApplicationView app = tracking.byReference(referenceNo);
        citizenAccess.requireMayActOn(app.citizenId());
        return disbursements
                .forApplication(app.instanceId())
                .map(d -> ResponseEntity.ok(DisbursementView.of(d)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** The citizen/officer-facing view of a disbursement: no citizen id, no internal payment ids. */
    record DisbursementView(String status, Instant createdAt, int instalmentCount, List<InstalmentView> instalments) {
        static DisbursementView of(Disbursement d) {
            List<InstalmentView> items = d.instalments().stream().map(InstalmentView::of).toList();
            return new DisbursementView(d.status(), d.createdAt(), items.size(), items);
        }
    }

    record InstalmentView(int sequence, String status) {
        static InstalmentView of(Instalment i) {
            return new InstalmentView(i.sequence(), i.status());
        }
    }
}
