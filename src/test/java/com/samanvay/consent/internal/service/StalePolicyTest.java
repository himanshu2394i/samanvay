package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.AuditRef;
import com.samanvay.audit.api.AuditService;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.JourneyPolicy;
import com.samanvay.consent.api.AccessDecision;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.DenialReason;
import com.samanvay.consent.internal.domain.ConsentArtifactEntity;
import com.samanvay.consent.internal.repository.AccessGrantRepository;
import com.samanvay.consent.internal.repository.ConsentArtifactRepository;
import com.samanvay.consent.internal.repository.ConsentEventRepository;
import com.samanvay.consent.internal.repository.ConsentRequestRepository;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.Link;
import com.samanvay.registry.api.DiscoveryPolicy;
import com.samanvay.registry.api.DiscoveryRegistry;
import com.samanvay.registry.api.FreshnessMode;
import com.samanvay.registry.api.Pointer;
import com.samanvay.registry.api.Sensitivity;
import com.samanvay.shared.CanonicalJson;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.EnvSecretStore;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StalePolicyTest {

    @Test
    void stalePointerDeniedWhenJourneyRejectsStale() {
        AccessDecision denied = authorize(false);
        assertThat(denied).isInstanceOf(AccessDecision.Denied.class);
        assertThat(((AccessDecision.Denied) denied).reason()).isEqualTo(DenialReason.STALE_NOT_ACCEPTED);
    }

    @Test
    void stalePointerAllowedWhenJourneyAcceptsStale() {
        AccessDecision decision = authorize(true);
        assertThat(decision).isInstanceOf(AccessDecision.Granted.class);
    }

    private static AccessDecision authorize(boolean acceptStale) {
        UUID citizen = UUID.randomUUID();
        IdentityLinking linking = mock(IdentityLinking.class);
        when(linking.activeLink(any(), any()))
                .thenReturn(Optional.of(new Link(UUID.randomUUID(), citizen, "MUNICIPAL", "P", "x", "CITIZEN_ASSERTED", "ACTIVE")));
        ConsentArtifactRepository artifacts = mock(ConsentArtifactRepository.class);
        ConsentArtifactEntity artifact = new ConsentArtifactEntity();
        artifact.setId(UUID.randomUUID());
        artifact.setSubjectCitizenId(citizen);
        artifact.setRequesterId("INDUSTRY");
        artifact.setPurposeCode("BUSINESS_NOC");
        artifact.setDataCategories(new String[] {"PROPERTY"});
        artifact.setStatus("ACTIVE");
        artifact.setVersion(1);
        artifact.setValidUntil(Instant.parse("2027-01-01T00:00:00Z"));
        artifact.setFrequencyLimit(99);
        when(artifacts.findMatching(any(), any(), any(), any())).thenReturn(Optional.of(artifact));
        DiscoveryRegistry registry = mock(DiscoveryRegistry.class);
        when(registry.hasClearance(any(), any())).thenReturn(true);
        when(registry.locate(any(), any(), any(), any())).thenReturn(Optional.of(new Pointer(
                UUID.randomUUID(),
                new SubjectRef(citizen),
                "MUNICIPAL",
                DataCategory.of("PROPERTY"),
                Sensitivity.RESTRICTED,
                DiscoveryPolicy.VISIBLE,
                "{}",
                LocalDate.now(),
                LocalDate.now().plusYears(1),
                Instant.parse("2026-09-01T00:00:00Z"),
                FreshnessMode.BATCH,
                "AVAILABLE")));
        JourneyCatalog journeys = mock(JourneyCatalog.class);
        when(journeys.policy(any()))
                .thenReturn(new JourneyPolicy(acceptStale, 120, "INDUSTRY", "BUSINESS_NOC", "NOC", Map.of("PROPERTY", "MUNICIPAL")));
        AuditService audit = mock(AuditService.class);
        when(audit.record(any())).thenReturn(new AuditRef(1, new byte[] {1}));
        AccessGrantRepository grants = mock(AccessGrantRepository.class);
        when(grants.countByConsentIdAndIssuedAtAfter(any(), any())).thenReturn(0L);
        ConsentServices authority = new ConsentServices(
                mock(ConsentRequestRepository.class),
                artifacts,
                mock(ConsentEventRepository.class),
                grants,
                linking,
                registry,
                journeys,
                new GrantSigner(new EnvSecretStore(), new CanonicalJson()),
                audit,
                e -> {},
                Clock.fixed(Instant.parse("2026-09-13T12:00:00Z"), ZoneOffset.UTC));
        return authority.authorize(new AccessRequest(
                new SubjectRef(citizen),
                new RequesterRef("INDUSTRY"),
                DataCategory.of("PROPERTY"),
                "MUNICIPAL",
                "muni-property@1",
                PurposeCode.of("BUSINESS_NOC"),
                "BUSINESS_NOC"));
    }
}
