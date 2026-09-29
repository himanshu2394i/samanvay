package com.samanvay.payments.internal.service;

import com.samanvay.orchestration.api.ApplicationStateChanged;
import com.samanvay.payments.api.DisbursementService;
import com.samanvay.tracking.api.ApplicationNotFoundException;
import com.samanvay.tracking.api.ApplicationTracking;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Issues the disbursement when an application reaches {@code APPROVED}. The trigger is the
 * {@link ApplicationStateChanged} event, like tracking and notifications: payments does not call
 * into orchestration and orchestration does not know payments exists. Events may be delivered
 * more than once; {@link DisbursementService#disburse} is idempotent, so that is harmless.
 */
@Component
class DisbursementOnApproval {

    static final String APPROVED = "APPROVED";

    private final DisbursementService disbursements;
    private final ApplicationTracking tracking;

    DisbursementOnApproval(DisbursementService disbursements, ApplicationTracking tracking) {
        this.disbursements = disbursements;
        this.tracking = tracking;
    }

    @ApplicationModuleListener
    void on(ApplicationStateChanged event) {
        if (!APPROVED.equals(event.newStatus())) {
            return;
        }
        // Fails (and the event stays incomplete, to be resubmitted) if tracking does not know the application yet.
        var application = tracking.byInstanceId(event.instanceId())
                .orElseThrow(() -> new ApplicationNotFoundException(event.instanceId().toString()));
        disbursements.disburse(event.instanceId(), application.citizenId(), application.journeyCode());
    }
}
