package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.AuditRef;
import com.samanvay.audit.api.AuditService;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.consent.api.AccessDecision;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.DenialReason;
import com.samanvay.consent.internal.repository.AccessGrantRepository;
import com.samanvay.consent.internal.repository.ConsentArtifactRepository;
import com.samanvay.consent.internal.repository.ConsentEventRepository;
import com.samanvay.consent.internal.repository.ConsentRequestRepository;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.registry.api.DiscoveryRegistry;
import com.samanvay.shared.CanonicalJson;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.EnvSecretStore;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultAccessAuthorityTest {

    @Test
    void missingLinkShortCircuits() {
        IdentityLinking linking = mock(IdentityLinking.class);
        when(linking.activeLink(any(), any())).thenReturn(Optional.empty());
        ConsentServices authority = authority(linking, mock(ConsentArtifactRepository.class), mock(DiscoveryRegistry.class));
        AccessDecision decision = authority.authorize(req());
        assertThat(decision).isInstanceOf(AccessDecision.Denied.class);
        assertThat(((AccessDecision.Denied) decision).reason()).isEqualTo(DenialReason.NO_ACTIVE_LINK);
    }

    private static ConsentServices authority(
            IdentityLinking linking, ConsentArtifactRepository artifacts, DiscoveryRegistry registry) {
        AuditService audit = mock(AuditService.class);
        when(audit.record(any())).thenReturn(new AuditRef(1, new byte[] {1}));
        ConsentRequestRepository requests = mock(ConsentRequestRepository.class);
        when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ConsentServices(
                requests,
                artifacts,
                mock(ConsentEventRepository.class),
                mock(AccessGrantRepository.class),
                linking,
                registry,
                mock(JourneyCatalog.class),
                new GrantSigner(new EnvSecretStore(), new CanonicalJson()),
                audit,
                e -> {},
                Clock.fixed(Instant.parse("2026-09-13T12:00:00Z"), ZoneOffset.UTC));
    }

    private static AccessRequest req() {
        return new AccessRequest(
                new SubjectRef(UUID.randomUUID()),
                new RequesterRef("SCHOLARSHIP"),
                DataCategory.INCOME_CERTIFICATE,
                "REVENUE",
                "rev-income@1",
                PurposeCode.SCHOLARSHIP_ELIGIBILITY,
                "POST_MATRIC_SCHOLARSHIP");
    }
}
