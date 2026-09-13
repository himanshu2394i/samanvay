package com.samanvay.orchestration.internal.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.consent.api.AccessAuthority;
import com.samanvay.consent.api.AccessDecision;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.ConsentRequest;
import com.samanvay.consent.api.DenialReason;
import com.samanvay.connector.api.Capability;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ConnectorRuntime;
import com.samanvay.connector.api.ExecutionInputs;
import com.samanvay.connector.api.FailureKind;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FetchDataDelegateTest {

    @Test
    void mapsResultsAndDenials() {
        AccessAuthority authority = mock(AccessAuthority.class);
        ConnectorRuntime runtime = mock(ConnectorRuntime.class);
        FetchDataDelegate delegate = new FetchDataDelegate(authority, runtime);
        AccessRequest req = new AccessRequest(
                new SubjectRef(UUID.randomUUID()),
                new RequesterRef("SCHOLARSHIP"),
                DataCategory.INCOME_CERTIFICATE,
                "REVENUE",
                "rev-income@1",
                PurposeCode.SCHOLARSHIP_ELIGIBILITY,
                "POST_MATRIC_SCHOLARSHIP");
        ExecutionInputs inputs = new ExecutionInputs(DataCategory.INCOME_CERTIFICATE, "wf", Map.of(), Map.of(), Map.of());

        when(authority.authorize(req))
                .thenReturn(new AccessDecision.Denied(DenialReason.NO_CONSENT, Optional.of(new ConsentRequest(
                        UUID.randomUUID(), req.subject().citizenId(), "SCHOLARSHIP", "P", "t", List.of(), "PENDING"))));
        assertThat(delegate.execute(req, inputs)).isEqualTo("NO_CONSENT");

        AccessGrant grant = new AccessGrant(
                UUID.randomUUID(),
                new byte[] {1},
                UUID.randomUUID(),
                1,
                req.subject(),
                req.requester(),
                req.category(),
                req.departmentCode(),
                req.connectorRef(),
                req.purpose(),
                Instant.now(),
                Instant.now().plusSeconds(60),
                new byte[] {2});
        when(authority.authorize(req)).thenReturn(new AccessDecision.Granted(grant));
        when(runtime.execute(grant, Capability.FETCH, inputs))
                .thenReturn(new ConnectorResult.Success(JsonMapper.builder().build().createObjectNode(), null));
        assertThat(delegate.execute(req, inputs)).isEqualTo("COMPLETED");
        when(runtime.execute(any(), any(), any()))
                .thenReturn(new ConnectorResult.Unavailable(FailureKind.TIMEOUT, true));
        assertThat(delegate.execute(req, inputs)).isEqualTo("PENDING_SOURCE");
    }
}
