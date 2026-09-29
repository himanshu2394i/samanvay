package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.consent.internal.domain.ConsentArtifactEntity;
import com.samanvay.consent.internal.domain.ConsentEventEntity;
import com.samanvay.consent.internal.repository.AccessGrantRepository;
import com.samanvay.consent.internal.repository.ConsentArtifactRepository;
import com.samanvay.consent.internal.repository.ConsentEventRepository;
import com.samanvay.consent.internal.repository.ConsentRequestRepository;
import com.samanvay.registry.api.DiscoveryRegistry;
import com.samanvay.shared.EnvSecretStore;
import com.samanvay.shared.CanonicalJson;
import com.samanvay.shared.test.TestPurposes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** The expiry marker and the retention purge, against mocked repositories (SQL is covered by ConsentLifecycleJobsIT). */
class ConsentLifecycleJobsTest {

    static final Instant NOW = Instant.parse("2026-09-29T03:15:00Z");

    final ConsentArtifactRepository artifacts = mock(ConsentArtifactRepository.class);
    final ConsentEventRepository eventsLog = mock(ConsentEventRepository.class);
    final DiscoveryRegistry registry = mock(DiscoveryRegistry.class);
    final AuditService audit = mock(AuditService.class);
    final ConsentServices service = new ConsentServices(
            mock(ConsentRequestRepository.class),
            artifacts,
            eventsLog,
            mock(AccessGrantRepository.class),
            null,
            registry,
            null,
            TestPurposes.catalog(),
            anyCitizen -> List.of(),
            null,
            mock(ConsentUsageService.class),
            new GrantSigner(new EnvSecretStore(), new CanonicalJson()),
            audit,
            e -> {},
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void activeConsentPastValidUntilIsMarkedExpiredWithEventAndAudit() {
        ConsentArtifactEntity due = artifact("ACTIVE", NOW.minusSeconds(60));
        when(artifacts.findActiveEndedBefore(NOW, ConsentServices.EXPIRY_BATCH)).thenReturn(List.of(due));

        assertThat(service.markExpired()).isEqualTo(1);

        assertThat(due.getStatus()).isEqualTo("EXPIRED");
        assertThat(due.getVersion()).isEqualTo(2);
        assertThat(due.getUpdatedAt()).isEqualTo(NOW);
        verify(artifacts).save(due);
        ArgumentCaptor<ConsentEventEntity> event = ArgumentCaptor.forClass(ConsentEventEntity.class);
        verify(eventsLog).save(event.capture());
        assertThat(event.getValue().getConsentId()).isEqualTo(due.getId());
        assertThat(event.getValue().getEventType()).isEqualTo("EXPIRED");
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        AuditEntry e = entry.getValue();
        assertThat(e.action()).isEqualTo("CONSENT_EXPIRED");
        assertThat(e.actorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(e.actorId()).isEqualTo("consent-expiry-job");
        assertThat(e.consentId()).isEqualTo(due.getId());
        assertThat(e.subjectId()).isEqualTo(due.getSubjectCitizenId().toString());
        assertThat(e.outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(e.meta()).containsEntry("consentVersion", "2").containsEntry("principalType", "SYSTEM");
        verify(registry).revokeDiscovery(due.getId());
    }

    @Test
    void nothingIsWrittenWhenNoActiveConsentHasEnded() {
        // The query only returns ACTIVE rows past valid_until, so a not-yet-expired ACTIVE row,
        // a REVOKED row and an already EXPIRED row never reach the service.
        when(artifacts.findActiveEndedBefore(any(), anyInt())).thenReturn(List.of());

        assertThat(service.markExpired()).isZero();

        verify(artifacts, never()).save(any());
        verifyNoInteractions(eventsLog, audit, registry);
    }

    @Test
    void purgeUsesTheRetentionCutoffAndDeletesChildrenBeforeTheConsent() {
        Instant cutoff = NOW.minus(Duration.ofDays(2555));
        when(artifacts.deleteEndedBefore(cutoff)).thenReturn(3);

        assertThat(service.purgeEndedRecords(Duration.ofDays(2555))).isEqualTo(3);

        InOrder order = inOrder(artifacts);
        order.verify(artifacts).deleteUsageOfEndedBefore(cutoff);
        order.verify(artifacts).deleteGrantsOfEndedBefore(cutoff);
        order.verify(artifacts).deleteEventsOfEndedBefore(cutoff);
        order.verify(artifacts).deleteEndedBefore(cutoff);
    }

    @Test
    void purgeNeverTouchesAuditOrWritesAnything() {
        service.purgeEndedRecords(Duration.ofDays(30));

        verifyNoInteractions(audit, eventsLog, registry);
        verify(artifacts, never()).save(any());
        verify(artifacts, never()).delete(any());
        verify(artifacts, never()).deleteAll();
        verify(artifacts, never()).deleteById(any());
        verify(artifacts).deleteEndedBefore(eq(NOW.minus(Duration.ofDays(30))));
    }

    @Test
    void retentionDefaultsToSevenYears() {
        assertThat(new ConsentRetentionProperties(null).retention()).isEqualTo(Duration.ofDays(2555));
    }

    private static ConsentArtifactEntity artifact(String status, Instant validUntil) {
        ConsentArtifactEntity a = new ConsentArtifactEntity();
        a.setId(UUID.randomUUID());
        a.setSubjectCitizenId(UUID.randomUUID());
        a.setRequesterId("SCHOLARSHIP");
        a.setPurposeCode("SCHOLARSHIP_ELIGIBILITY");
        a.setDataCategories(new String[] {"INCOME_CERTIFICATE"});
        a.setStatus(status);
        a.setVersion(1);
        a.setValidUntil(validUntil);
        a.setUpdatedAt(validUntil.minus(Duration.ofDays(30)));
        return a;
    }
}
