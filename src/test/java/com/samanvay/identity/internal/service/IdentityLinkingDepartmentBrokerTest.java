package com.samanvay.identity.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.AuditService;
import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.Link;
import com.samanvay.identity.api.LinkAsserted;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProvider;
import com.samanvay.identity.api.VerifiedLocalId;
import com.samanvay.identity.internal.domain.LinkEntity;
import com.samanvay.identity.internal.proof.DepartmentBrokerLinkProofProvider;
import com.samanvay.identity.internal.repository.CandidateMatchRepository;
import com.samanvay.identity.internal.repository.CitizenRepository;
import com.samanvay.identity.internal.repository.LinkRepository;
import com.samanvay.identity.internal.repository.ProfileRepository;
import com.samanvay.shared.security.CitizenTokenVerifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Docker-free: a department-brokered proof, verified by the real
 * {@link DepartmentBrokerLinkProofProvider}, drives the real {@code assertLink} to a verified link.
 * Only Keycloak (token signing) and the database are faked.
 */
class IdentityLinkingDepartmentBrokerTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final String SUBJECT = "kc-brokered-sub";
    private static final String TOKEN = "brokered-access-token";

    private final UUID citizen = UUID.randomUUID();
    private final LinkRepository links = mock(LinkRepository.class);
    private final List<Object> events = new ArrayList<>();
    private Map<String, Object> claims = Map.of(
            "dept_idp", "dept-idp",
            "dept_code", "REVENUE",
            "dept_local_id_type", "RATION",
            "dept_local_id", "RC-BROKERED-1",
            "auth_time", NOW.minusSeconds(20).getEpochSecond());

    @Test
    void brokeredLoginCreatesAVerifiedLinkForThatCitizenDepartmentAndLocalId() {
        when(links.findByCitizenIdAndDepartmentCodeAndStatus(any(), any(), any())).thenReturn(Optional.empty());
        when(links.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        Link link = service().assertLink(citizen, "REVENUE", "RATION", "RC-BROKERED-1", AuthProof.departmentIdp(TOKEN));

        assertThat(link.citizenId()).isEqualTo(citizen);
        assertThat(link.departmentCode()).isEqualTo("REVENUE");
        assertThat(link.localIdType()).isEqualTo("RATION");
        assertThat(link.localIdToken()).isEqualTo("RC-BROKERED-1");
        assertThat(link.provenance()).isEqualTo("CITIZEN_ASSERTED");
        assertThat(link.status()).isEqualTo("ACTIVE");
        assertThat(events).containsExactly(new LinkAsserted(citizen, "REVENUE"));
    }

    @Test
    void aBrokeredIdentityThatIsNotTheRequestedOneNeverInserts() {
        assertThatThrownBy(() -> service().assertLink(
                        citizen, "REVENUE", "RATION", "RC-SOMEONE-ELSE", AuthProof.departmentIdp(TOKEN)))
                .isInstanceOf(LinkProofInvalidException.class);
        assertThatThrownBy(() -> service().assertLink(
                        citizen, "EDUCATION", "RATION", "RC-BROKERED-1", AuthProof.departmentIdp(TOKEN)))
                .isInstanceOf(LinkProofInvalidException.class);
        verify(links, never()).saveAndFlush(any());
    }

    @Test
    void anAlreadyLinkedDepartmentIsSkippedLikeForEveryOtherProof() {
        LinkEntity existing = new LinkEntity();
        existing.setId(UUID.randomUUID());
        existing.setCitizenId(citizen);
        existing.setDepartmentCode("REVENUE");
        existing.setLocalIdType("RATION");
        existing.setLocalIdToken("RC-EARLIER");
        existing.setProvenance("CITIZEN_ASSERTED");
        existing.setStatus("ACTIVE");
        when(links.findByCitizenIdAndDepartmentCodeAndStatus(citizen, "REVENUE", "ACTIVE"))
                .thenReturn(Optional.of(existing));

        Link skipped = service().assertLink(citizen, "REVENUE", "RATION", "RC-BROKERED-1", AuthProof.departmentIdp(TOKEN));

        assertThat(skipped.id()).isEqualTo(existing.getId());
        verify(links, never()).saveAndFlush(any());
    }

    @Test
    void whenConfiguredTheProviderIsListedAlongsideTheExistingOneWhichStillWorks() {
        IdentityServices svc = service();
        assertThat(svc.availableProofProviders())
                .extracting(info -> info.kind() + ":" + info.label())
                .containsExactlyInAnyOrder(
                        "LOCAL_ID_OTP:Local ID + OTP (demo)",
                        "DEPT_IDP:Department sign-in (mock department IdP)");

        when(links.findByCitizenIdAndDepartmentCodeAndStatus(any(), any(), any())).thenReturn(Optional.empty());
        when(links.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(svc.assertLink(UUID.randomUUID(), "EDUCATION", "STUDENT", "STU-1", AuthProof.localIdOtpDemo())
                        .localIdToken())
                .isEqualTo("STU-1");
    }

    @Test
    void withoutTheProviderTheProofKindIsRefusedAndNotListed() {
        IdentityServices svc = serviceWith(List.of(otp()));
        assertThat(svc.availableProofProviders())
                .extracting(info -> info.kind())
                .containsExactly(LinkProofKind.LOCAL_ID_OTP);
        assertThatThrownBy(() -> svc.assertLink(citizen, "REVENUE", "RATION", "RC-BROKERED-1", AuthProof.departmentIdp(TOKEN)))
                .isInstanceOf(LinkProofInvalidException.class);
    }

    private IdentityServices service() {
        CitizenRepository citizens = mock(CitizenRepository.class);
        when(citizens.existsByIdAndAuthSubject(citizen, SUBJECT)).thenReturn(true);
        CitizenTokenVerifier verifier =
                raw -> TOKEN.equals(raw) ? Optional.of(new CitizenTokenVerifier.VerifiedToken(SUBJECT, claims)) : Optional.empty();
        var broker = new DepartmentBrokerLinkProofProvider(
                verifier, citizens, Clock.fixed(NOW, ZoneOffset.UTC), "dept-idp", Duration.ofMinutes(10));
        return serviceWith(List.of(otp(), broker));
    }

    private IdentityServices serviceWith(List<LinkProofProvider> providers) {
        return new IdentityServices(
                mock(CitizenRepository.class),
                mock(ProfileRepository.class),
                links,
                mock(CandidateMatchRepository.class),
                new CandidateScorer(),
                mock(ReviewerAuth.class),
                events::add,
                mock(AuditService.class),
                providers,
                mock(com.samanvay.catalog.api.JourneyCatalog.class),
                mock(com.samanvay.catalog.api.DepartmentCatalog.class));
    }

    private static LinkProofProvider otp() {
        return new LinkProofProvider() {
            @Override
            public LinkProofKind kind() {
                return LinkProofKind.LOCAL_ID_OTP;
            }

            @Override
            public String label() {
                return "Local ID + OTP (demo)";
            }

            @Override
            public VerifiedLocalId verify(AuthProof proof, com.samanvay.identity.api.LinkProofContext context) {
                return new VerifiedLocalId(context.localIdType(), context.localId());
            }
        };
    }
}
