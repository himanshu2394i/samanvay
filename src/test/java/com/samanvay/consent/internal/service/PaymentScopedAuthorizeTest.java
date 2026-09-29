package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.AuditEntry;
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
import com.samanvay.consent.internal.repository.ConsentUsageRepository.Claim;
import com.samanvay.consent.internal.repository.ConsentUsageRepository.ClaimResult;
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
import com.samanvay.shared.test.TestPrincipals;
import com.samanvay.shared.test.TestPurposes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** ONCE_PER_PAYMENT in {@code ConsentServices.authorize}, Docker-free: usage and registry are mocked. */
class PaymentScopedAuthorizeTest {

    private static final String PAYMENT = "5b9c1e0a-1111-4222-8333-444455556666";

    private final UUID citizen = UUID.randomUUID();
    private final UUID consentId = UUID.randomUUID();
    private ConsentUsageService usage;
    private PaymentScopeKeys paymentKeys;
    private final List<AuditEntry> audited = new ArrayList<>();
    private ConsentServices authority;

    @BeforeEach
    void setUp() {
        IdentityLinking linking = mock(IdentityLinking.class);
        when(linking.activeLink(any(), any()))
                .thenReturn(Optional.of(new Link(UUID.randomUUID(), citizen, "MUNICIPAL", "P", "x", "CITIZEN_ASSERTED", "ACTIVE")));
        ConsentArtifactRepository artifacts = mock(ConsentArtifactRepository.class);
        ConsentArtifactEntity artifact = new ConsentArtifactEntity();
        artifact.setId(consentId);
        artifact.setSubjectCitizenId(citizen);
        artifact.setRequesterId("INDUSTRY");
        artifact.setPurposeCode("BUSINESS_NOC");
        artifact.setDataCategories(new String[] {"PROPERTY"});
        artifact.setStatus("ACTIVE");
        artifact.setVersion(1);
        artifact.setValidUntil(Instant.parse("2027-01-01T00:00:00Z"));
        artifact.setFrequencyLimit(99);
        artifact.setFrequency("ONCE_PER_PAYMENT");
        when(artifacts.findMatching(any(), any(), any(), any())).thenReturn(Optional.of(artifact));
        DiscoveryRegistry registry = mock(DiscoveryRegistry.class);
        when(registry.hasClearance(any(), any())).thenReturn(true);
        when(registry.locate(any(), any(), any(), any())).thenReturn(Optional.of(new Pointer(
                UUID.randomUUID(), new SubjectRef(citizen), "MUNICIPAL", DataCategory.of("PROPERTY"),
                Sensitivity.RESTRICTED, DiscoveryPolicy.VISIBLE, "{}", LocalDate.now(), LocalDate.now().plusYears(1),
                Instant.parse("2026-09-01T00:00:00Z"), FreshnessMode.BATCH, "AVAILABLE")));
        JourneyCatalog journeys = mock(JourneyCatalog.class);
        when(journeys.policy(any()))
                .thenReturn(new JourneyPolicy(true, 120, "INDUSTRY", "BUSINESS_NOC", "NOC", Map.of("PROPERTY", "MUNICIPAL")));
        AuditService audit = mock(AuditService.class);
        when(audit.record(any())).thenAnswer(inv -> {
            audited.add(inv.getArgument(0));
            return new AuditRef(1, new byte[] {1});
        });
        AccessGrantRepository grants = mock(AccessGrantRepository.class);
        when(grants.countByConsentIdAndIssuedAtAfter(any(), any())).thenReturn(0L);
        usage = mock(ConsentUsageService.class);
        when(usage.claim(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new ClaimResult(Claim.CLAIMED, UUID.randomUUID(), UUID.randomUUID()));
        var store = new PaymentScopeKeysTest.MapSecretStore()
                .with("consent-payment-scope-key", "old-key")
                .with("consent-payment-scope-key-v2", "new-key");
        paymentKeys = new PaymentScopeKeys(store, "v2", List.of("v1"));
        authority = new ConsentServices(
                mock(ConsentRequestRepository.class),
                artifacts,
                mock(ConsentEventRepository.class),
                grants,
                linking,
                registry,
                journeys,
                TestPurposes.catalog(),
                anyCitizen -> List.of(),
                new RefusalAuditor(audit, mock(org.springframework.transaction.PlatformTransactionManager.class)),
                usage,
                paymentKeys,
                new GrantSigner(new EnvSecretStore(), new CanonicalJson()),
                audit,
                e -> {},
                Clock.fixed(Instant.parse("2026-09-13T12:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());
    }

    private AccessRequest request(String paymentId) {
        return new AccessRequest(
                new SubjectRef(citizen), new RequesterRef("INDUSTRY"), DataCategory.of("PROPERTY"), "MUNICIPAL",
                "muni-property@1", PurposeCode.of("BUSINESS_NOC"), "BUSINESS_NOC", TestPrincipals.OFFICER,
                "app-1", paymentId);
    }

    @Test
    void aPaymentCheckClaimsTheKeyedHashOfThePaymentIdWithTheKeyVersion() {
        AccessDecision decision = authority.authorize(request(PAYMENT));
        assertThat(decision).isInstanceOf(AccessDecision.Granted.class);
        ArgumentCaptor<String> scopeKey = ArgumentCaptor.forClass(String.class);
        verify(usage).claim(eq(consentId), eq("PROPERTY"), scopeKey.capture(), eq("v2"), any());
        assertThat(scopeKey.getValue())
                .isEqualTo(paymentKeys.active(PAYMENT).scopeKey())
                .startsWith("PAYMENT:")
                .doesNotContain(PAYMENT)
                .doesNotContain("app-1");
        assertThat(audited).filteredOn(e -> "GRANT_ISSUED".equals(e.action())).singleElement()
                .satisfies(e -> {
                    assertThat(e.meta()).containsEntry("scopeKeyVersion", "v2");
                    assertThat(e.meta().values().toString()).as("raw payment id not in the audit meta").doesNotContain(PAYMENT);
                });
    }

    @Test
    void aSecondCheckForTheSamePaymentIsRefusedWhenTheClaimComesBackRefused() {
        when(usage.claim(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new ClaimResult(Claim.REFUSED, null, null));
        AccessDecision decision = authority.authorize(request(PAYMENT));
        assertThat(decision).isInstanceOf(AccessDecision.Denied.class);
        assertThat(((AccessDecision.Denied) decision).reason()).isEqualTo(DenialReason.CHECK_ALREADY_USED);
        assertThat(audited).extracting(AuditEntry::action).containsExactly("CONSENT_FREQUENCY_REFUSED");
    }

    @Test
    void aDifferentPaymentIdClaimsADifferentScopeKey() {
        authority.authorize(request(PAYMENT));
        authority.authorize(request(PAYMENT + "-2"));
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(usage, org.mockito.Mockito.times(2)).claim(any(), anyString(), keys.capture(), any(), any());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void noPaymentIdIsRefusedAndNeverClaims() {
        for (String missing : new String[] {null, "", "   "}) {
            AccessDecision decision = authority.authorize(request(missing));
            assertThat(decision).isInstanceOf(AccessDecision.Denied.class);
            assertThat(((AccessDecision.Denied) decision).reason()).isEqualTo(DenialReason.PAYMENT_REQUIRED);
        }
        verify(usage, never()).claim(any(), anyString(), anyString(), any(), any());
        assertThat(audited).extracting(AuditEntry::action).containsOnly("GRANT_DENIED");
    }

    @Test
    void aPaymentAlreadyCheckedUnderARetiredKeyIsStillRefusedAfterRotation() {
        String retiredScope = paymentKeys.retired(PAYMENT).get(0).scopeKey();
        when(usage.isHeld(consentId, "PROPERTY", retiredScope)).thenReturn(true);
        AccessDecision decision = authority.authorize(request(PAYMENT));
        assertThat(decision).isInstanceOf(AccessDecision.Denied.class);
        assertThat(((AccessDecision.Denied) decision).reason()).isEqualTo(DenialReason.CHECK_ALREADY_USED);
        verify(usage, never()).claim(any(), anyString(), anyString(), any(), any());
        assertThat(audited).extracting(AuditEntry::action).containsExactly("CONSENT_FREQUENCY_REFUSED");
    }

    @Test
    void anotherPaymentIsNotBlockedByARetiredKeyCheckOfADifferentPayment() {
        when(usage.isHeld(consentId, "PROPERTY", paymentKeys.retired(PAYMENT).get(0).scopeKey())).thenReturn(true);
        assertThat(authority.authorize(request(PAYMENT + "-other"))).isInstanceOf(AccessDecision.Granted.class);
    }
}
