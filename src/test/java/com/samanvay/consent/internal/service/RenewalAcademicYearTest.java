package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.AuditRef;
import com.samanvay.audit.api.AuditService;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.JourneyDefinition;
import com.samanvay.catalog.api.JourneyNotFoundException;
import com.samanvay.catalog.api.JourneyPolicy;
import com.samanvay.catalog.api.Purpose;
import com.samanvay.catalog.api.PurposeCatalog;
import com.samanvay.consent.api.ApprovedAwards;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.NoPriorAwardException;
import com.samanvay.consent.internal.repository.AccessGrantRepository;
import com.samanvay.consent.internal.repository.ConsentArtifactRepository;
import com.samanvay.consent.internal.repository.ConsentEventRepository;
import com.samanvay.consent.internal.repository.ConsentRequestRepository;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.registry.api.DiscoveryRegistry;
import com.samanvay.shared.CanonicalJson;
import com.samanvay.shared.EnvSecretStore;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * "Prior award" = an approved award in the academic year immediately before the current one,
 * Asia/Kolkata, with the academic year's start month read from the award's scheme (journey)
 * configuration. Times below are IST (+05:30) unless they carry Z.
 */
class RenewalAcademicYearTest {

    static final String PURPOSE = "RENEWAL_TEST";
    static final String DEPT = "DEPT_A";

    @ParameterizedTest(name = "[{index}] now={0} award={1} scheme={2} -> {3}")
    @CsvSource({
        // January renewal, award in the previous December (June scheme)
        "2027-01-15T10:00+05:30, 2026-12-10T10:00+05:30, JUNE,  REJECT", // same academic year 2026-27
        "2027-01-15T10:00+05:30, 2025-12-10T10:00+05:30, JUNE,  ACCEPT", // prior academic year 2025-26
        // 31 May / 1 June boundaries (June scheme)
        "2027-06-01T00:30+05:30, 2027-05-31T23:30+05:30, JUNE,  ACCEPT", // now AY 2027-28, award AY 2026-27
        "2027-06-01T00:30+05:30, 2026-06-01T00:00+05:30, JUNE,  ACCEPT", // first minute of AY 2026-27
        "2027-06-01T00:30+05:30, 2026-05-31T23:59+05:30, JUNE,  REJECT", // AY 2025-26: two years back
        "2027-05-31T23:30+05:30, 2026-05-31T23:59+05:30, JUNE,  ACCEPT", // now still AY 2026-27
        "2027-05-31T23:30+05:30, 2026-06-01T00:00+05:30, JUNE,  REJECT", // same AY 2026-27
        // the year boundary is in Asia/Kolkata, not UTC: 2026-05-31T19:00Z is 1 June 00:30 IST
        "2027-05-31T12:00+05:30, 2026-05-31T19:00Z,      JUNE,  REJECT",
        "2027-05-31T12:00+05:30, 2026-05-31T18:00Z,      JUNE,  ACCEPT", // 31 May 23:30 IST
        // a scheme whose academic year starts in April
        "2027-01-15T10:00+05:30, 2026-04-15T10:00+05:30, APRIL, REJECT", // same AY Apr 2026 - Mar 2027
        "2027-01-15T10:00+05:30, 2026-04-15T10:00+05:30, JUNE,  ACCEPT", // same dates, June scheme: prior AY
        "2027-01-15T10:00+05:30, 2026-03-31T23:59+05:30, APRIL, ACCEPT", // AY Apr 2025 - Mar 2026
        "2027-04-01T00:00+05:30, 2026-03-31T23:59+05:30, APRIL, REJECT", // now AY 2027-28: two back
        // a scheme with no academic year never yields a prior award
        "2027-01-15T10:00+05:30, 2025-12-10T10:00+05:30, NONE,  REJECT"
    })
    void priorAcademicYearDecides(String now, String award, String scheme, String expected) {
        ConsentServices consents = consents(Instant.from(parse(now)), List.of(new ApprovedAwards.Award(
                "REF-1", scheme, Instant.from(parse(award)))));
        ConsentRequestDraft draft = new ConsentRequestDraft(UUID.randomUUID(), DEPT, PURPOSE);
        if (expected.equals("ACCEPT")) {
            assertThat(consents.request(draft).purposeCode()).isEqualTo(PURPOSE);
        } else {
            assertThatThrownBy(() -> consents.request(draft)).isInstanceOf(NoPriorAwardException.class);
        }
    }

    private static OffsetDateTime parse(String t) {
        return OffsetDateTime.parse(t);
    }

    private static ConsentServices consents(Instant now, List<ApprovedAwards.Award> awards) {
        AuditService audit = mock(AuditService.class);
        when(audit.record(any())).thenReturn(new AuditRef(1, new byte[] {1}));
        ConsentRequestRepository requests = mock(ConsentRequestRepository.class);
        when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
        JourneyCatalog journeys = mock(JourneyCatalog.class);
        Map<String, Integer> startMonths = Map.of("JUNE", 6, "APRIL", 4);
        when(journeys.byCode(any())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            if (!code.equals("NONE") && !startMonths.containsKey(code)) {
                throw new JourneyNotFoundException(code);
            }
            return new JourneyDefinition(code, code, "bpmn", List.of("MARKS"),
                    new JourneyPolicy(false, 72, DEPT, "P", "APP", Map.of()), "PUBLISHED", startMonths.get(code));
        });
        PurposeCatalog purposes = code -> PURPOSE.equals(code)
                ? Optional.of(new Purpose(PURPOSE, "renewal", null, "JOURNEY", true, DEPT, List.of("MARKS"),
                        List.of("NEW_MARKSHEET_VERIFICATION_RESULT"), Purpose.RequesterRule.PRIOR_AWARD_DEPARTMENT,
                        365, null, null, "Renewal", null, Purpose.LabelStatus.APPROVED, Purpose.LabelStatus.MISSING,
                        true))
                : Optional.empty();
        return new ConsentServices(
                requests,
                mock(ConsentArtifactRepository.class),
                mock(ConsentEventRepository.class),
                mock(AccessGrantRepository.class),
                mock(IdentityLinking.class),
                mock(DiscoveryRegistry.class),
                journeys,
                purposes,
                citizen -> awards,
                new RefusalAuditor(audit, mock(PlatformTransactionManager.class)),
                mock(ConsentUsageService.class),
                new GrantSigner(new EnvSecretStore(), new CanonicalJson()),
                audit,
                e -> {},
                Clock.fixed(now, ZoneOffset.UTC));
    }
}
