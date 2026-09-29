package com.samanvay.payments.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.samanvay.orchestration.api.ApplicationStateChanged;
import com.samanvay.payments.api.DisbursementService;
import com.samanvay.tracking.api.ApplicationNotFoundException;
import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.ApplicationView;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisbursementOnApprovalTest {

    private final DisbursementService disbursements = mock(DisbursementService.class);
    private final ApplicationTracking tracking = mock(ApplicationTracking.class);
    private final DisbursementOnApproval listener = new DisbursementOnApproval(disbursements, tracking);

    @Test
    void approvalIssuesTheDisbursementForTheApplicationsCitizenAndJourney() {
        UUID app = UUID.randomUUID();
        UUID citizen = UUID.randomUUID();
        when(tracking.byInstanceId(app)).thenReturn(Optional.of(
                new ApplicationView("MH-X-1", citizen, "SOME_JOURNEY", "VERIFIED", Instant.now(), null, app)));
        listener.on(new ApplicationStateChanged(app, "APPROVED"));
        verify(disbursements).disburse(app, citizen, "SOME_JOURNEY");
    }

    @Test
    void everyOtherStatusIsIgnored() {
        for (String status : new String[] {"SUBMITTED", "PARTIALLY_VERIFIED", "VERIFIED", "REJECTED", "CLOSED"}) {
            listener.on(new ApplicationStateChanged(UUID.randomUUID(), status));
        }
        verifyNoInteractions(disbursements, tracking);
    }

    @Test
    void anApplicationTrackingDoesNotKnowFailsSoTheEventCanBeResubmitted() {
        UUID app = UUID.randomUUID();
        when(tracking.byInstanceId(app)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> listener.on(new ApplicationStateChanged(app, "APPROVED")))
                .isInstanceOf(ApplicationNotFoundException.class);
        verify(disbursements, never()).disburse(any(), any(), any());
    }
}
