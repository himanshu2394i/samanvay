package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.samanvay.payments.api.Disbursement;
import com.samanvay.payments.api.DisbursementService;
import com.samanvay.payments.api.Instalment;
import com.samanvay.shared.security.CitizenAccess;
import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.ApplicationView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DisbursementWebTest {

    private final ApplicationTracking tracking = mock(ApplicationTracking.class);
    private final DisbursementService disbursements = mock(DisbursementService.class);
    private final CitizenAccess citizenAccess = mock(CitizenAccess.class);
    private final DisbursementWeb web = new DisbursementWeb(tracking, disbursements, citizenAccess);

    private static final UUID CITIZEN = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();

    private ApplicationView view(String status) {
        return new ApplicationView("SCH-1", CITIZEN, "POST_MATRIC_SCHOLARSHIP", status, Instant.now(), Instant.now(), INSTANCE);
    }

    @Test
    void returnsTheDisbursementAndAuthorisesTheCaller() {
        when(tracking.byReference("SCH-1")).thenReturn(view("APPROVED"));
        var disbursement = new Disbursement(
                UUID.randomUUID(),
                INSTANCE,
                CITIZEN,
                "POST_MATRIC_SCHOLARSHIP",
                "ISSUED",
                Instant.now(),
                List.of(new Instalment(UUID.randomUUID(), 1, "SCHEDULED"), new Instalment(UUID.randomUUID(), 2, "SCHEDULED")));
        when(disbursements.forApplication(INSTANCE)).thenReturn(Optional.of(disbursement));

        var response = web.disbursement("SCH-1");

        verify(citizenAccess).requireMayActOn(CITIZEN); // authorised before reading
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo("ISSUED");
        assertThat(body.instalmentCount()).isEqualTo(2);
        assertThat(body.instalments()).extracting(DisbursementWeb.InstalmentView::sequence).containsExactly(1, 2);
    }

    @Test
    void returns204WhenNotYetDisbursed() {
        when(tracking.byReference("SCH-1")).thenReturn(view("VERIFIED"));
        when(disbursements.forApplication(INSTANCE)).thenReturn(Optional.empty());

        var response = web.disbursement("SCH-1");

        verify(citizenAccess).requireMayActOn(CITIZEN);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }
}
