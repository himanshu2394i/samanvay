package com.samanvay.identity.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.identity.api.ReviewerRequiredException;
import com.samanvay.identity.internal.domain.CandidateMatchEntity;
import com.samanvay.identity.internal.repository.CandidateMatchRepository;
import com.samanvay.identity.internal.repository.CitizenRepository;
import com.samanvay.identity.internal.repository.LinkRepository;
import com.samanvay.identity.internal.repository.ProfileRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * No-auto-link layer 2 of 3 (service). Layer 1 (edge route rule) and layer 3
 * (DB CHECK) are pinned in {@code NoAutoLinkConstraintIT}.
 *
 * <p>The reviewer check reads only the security context (populated solely from
 * a validated JWT); the old {@code X-Roles} header and body {@code reviewerId}
 * no longer exist as inputs, so the only way to pass is a real reviewer token.
 */
class ConfirmRequiresReviewerRoleTest {

    private final CandidateMatchRepository candidates = mock(CandidateMatchRepository.class);
    private final CitizenRepository citizens = mock(CitizenRepository.class);
    private final LinkRepository links = mock(LinkRepository.class);
    private final AuditService audit = mock(AuditService.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void confirmWithoutAuthenticationFails() {
        assertThatThrownBy(() -> service().confirm(UUID.randomUUID(), "ok"))
                .isInstanceOf(ReviewerRequiredException.class);
        verify(links, never()).save(any());
    }

    @Test
    void confirmAndRejectAsOfficerFail() {
        authenticate("officer-1", "ROLE_OFFICER");
        assertThatThrownBy(() -> service().confirm(UUID.randomUUID(), "ok"))
                .isInstanceOf(ReviewerRequiredException.class);
        assertThatThrownBy(() -> service().reject(UUID.randomUUID(), "no"))
                .isInstanceOf(ReviewerRequiredException.class);
        verify(links, never()).save(any());
    }

    @Test
    void confirmAsReviewerRecordsTokenSubjectAsReviewer() {
        authenticate("reviewer-sub-42", "ROLE_REVIEWER");
        CandidateMatchEntity c = new CandidateMatchEntity();
        UUID id = UUID.randomUUID();
        c.setId(id);
        c.setCitizenId(UUID.randomUUID());
        c.setDepartmentCode("REVENUE");
        c.setScore(BigDecimal.valueOf(0.91));
        c.setStatus("PENDING");
        com.samanvay.identity.internal.domain.CitizenEntity citizen = new com.samanvay.identity.internal.domain.CitizenEntity();
        citizen.setId(c.getCitizenId());
        citizen.setStatus("ACTIVE");
        when(citizens.findById(c.getCitizenId())).thenReturn(Optional.of(citizen));
        when(candidates.lockById(id)).thenReturn(Optional.of(c));

        service().confirm(id, "looks right");

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().actorId()).isEqualTo("reviewer-sub-42");
        assertThat(entry.getValue().action()).isEqualTo("CANDIDATE_CONFIRMED");
        assertThat(entry.getValue().actorType()).isEqualTo(ActorType.REVIEWER);
    }

    private static void authenticate(String subject, String authority) {
        var auth = new TestingAuthenticationToken(subject, null, authority);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private IdentityServices service() {
        return new IdentityServices(
                citizens,
                mock(ProfileRepository.class),
                links,
                candidates,
                new CandidateScorer(),
                new ReviewerAuth(),
                e -> {},
                audit,
                List.of(),
                mock(com.samanvay.catalog.api.JourneyCatalog.class),
                mock(com.samanvay.catalog.api.DepartmentCatalog.class));
    }
}
