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
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProvider;
import com.samanvay.identity.api.VerifiedLocalId;
import com.samanvay.identity.internal.domain.LinkEntity;
import com.samanvay.identity.internal.repository.CandidateMatchRepository;
import com.samanvay.identity.internal.repository.CitizenRepository;
import com.samanvay.identity.internal.repository.LinkRepository;
import com.samanvay.identity.internal.repository.ProfileRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class IdentityLinkingProofTest {

    @Test
    void digiLockerSandboxAssertsAndSkipsAlreadyLinkedDept() {
        LinkRepository links = mock(LinkRepository.class);
        when(links.findByCitizenIdAndDepartmentCodeAndStatus(any(), any(), any())).thenReturn(Optional.empty());
        when(links.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        IdentityServices svc = service(links);
        UUID citizen = UUID.randomUUID();

        Link created = svc.assertLink(citizen, "REVENUE", "RATION", "RC-1", AuthProof.digiLockerSandbox());
        assertThat(created.localIdToken()).isEqualTo("RC-1");
        assertThat(created.provenance()).isEqualTo("CITIZEN_ASSERTED");

        LinkEntity existing = new LinkEntity();
        existing.setId(created.id());
        existing.setCitizenId(citizen);
        existing.setDepartmentCode("REVENUE");
        existing.setLocalIdType("RATION");
        existing.setLocalIdToken("RC-1");
        existing.setProvenance("CITIZEN_ASSERTED");
        existing.setStatus("ACTIVE");
        when(links.findByCitizenIdAndDepartmentCodeAndStatus(citizen, "REVENUE", "ACTIVE"))
                .thenReturn(Optional.of(existing));

        Link skipped = svc.assertLink(citizen, "REVENUE", "RATION", "RC-NEW", AuthProof.digiLockerSandbox());
        assertThat(skipped.id()).isEqualTo(created.id());
        verify(links).saveAndFlush(any());
    }

    @Test
    void invalidProofNeverInserts() {
        LinkRepository links = mock(LinkRepository.class);
        IdentityServices svc = service(links);
        assertThatThrownBy(() ->
                        svc.assertLink(UUID.randomUUID(), "REVENUE", "RATION", "RC-1", new AuthProof(LinkProofKind.DIGILOCKER, "nope")))
                .isInstanceOf(LinkProofInvalidException.class);
        verify(links, never()).saveAndFlush(any());
    }

    @Test
    void missingProofIsRejected() {
        IdentityServices svc = service(mock(LinkRepository.class));
        assertThatThrownBy(() -> svc.assertLink(UUID.randomUUID(), "REVENUE", "RATION", "RC-1", null))
                .isInstanceOf(LinkProofInvalidException.class);
        assertThatThrownBy(() ->
                        svc.assertLink(UUID.randomUUID(), "REVENUE", "RATION", "RC-1", new AuthProof(null, "sandbox")))
                .isInstanceOf(LinkProofInvalidException.class);
    }

    @Test
    void secondCitizenSameLocalIdRejected() {
        LinkRepository links = mock(LinkRepository.class);
        when(links.findByCitizenIdAndDepartmentCodeAndStatus(any(), any(), any())).thenReturn(Optional.empty());
        when(links.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
        IdentityServices svc = service(links);
        assertThatThrownBy(() ->
                        svc.assertLink(UUID.randomUUID(), "REVENUE", "RATION", "RC-1", AuthProof.digiLockerSandbox()))
                .isInstanceOf(com.samanvay.identity.api.DuplicateLocalIdException.class);
    }

    @Test
    void providerListUsesHonestSandboxLabels() {
        IdentityServices svc = service(mock(LinkRepository.class));
        assertThat(svc.availableProofProviders())
                .extracting(info -> info.kind() + ":" + info.label())
                .containsExactlyInAnyOrder(
                        "DIGILOCKER:DigiLocker sandbox (mock)", "LOCAL_ID_OTP:Local ID + OTP (demo)");
        assertThat(svc.availableProofProviders().stream().map(info -> info.label().toLowerCase()))
                .noneMatch(label -> label.contains("keycloak") || label.contains("live sso"));
    }

    private static IdentityServices service(LinkRepository links) {
        return new IdentityServices(
                mock(CitizenRepository.class),
                mock(ProfileRepository.class),
                links,
                mock(CandidateMatchRepository.class),
                new CandidateScorer(),
                mock(ReviewerAuth.class),
                e -> {},
                mock(AuditService.class),
                List.of(digiLocker(), otp()));
    }

    private static LinkProofProvider digiLocker() {
        return new LinkProofProvider() {
            @Override
            public LinkProofKind kind() {
                return LinkProofKind.DIGILOCKER;
            }

            @Override
            public String label() {
                return "DigiLocker sandbox (mock)";
            }

            @Override
            public VerifiedLocalId verify(AuthProof proof, com.samanvay.identity.api.LinkProofContext context) {
                if (proof == null || proof.provider() != LinkProofKind.DIGILOCKER || !"sandbox".equals(proof.payload())) {
                    throw new LinkProofInvalidException();
                }
                return new VerifiedLocalId(context.localIdType(), context.localId());
            }
        };
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
                if (!"000000".equals(proof.payload())) {
                    throw new LinkProofInvalidException();
                }
                return new VerifiedLocalId(context.localIdType(), context.localId());
            }
        };
    }
}
