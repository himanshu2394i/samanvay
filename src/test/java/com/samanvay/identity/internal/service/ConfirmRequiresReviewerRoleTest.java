package com.samanvay.identity.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.AuditService;
import com.samanvay.identity.api.ReviewerRequiredException;
import com.samanvay.identity.internal.repository.CandidateMatchRepository;
import com.samanvay.identity.internal.repository.CitizenRepository;
import com.samanvay.identity.internal.repository.LinkRepository;
import com.samanvay.identity.internal.repository.ProfileRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfirmRequiresReviewerRoleTest {

    @Test
    void confirmWithoutRoleFails() {
        ReviewerAuth auth = mock(ReviewerAuth.class);
        when(auth.isReviewer()).thenReturn(false);
        IdentityServices svc = new IdentityServices(
                mock(CitizenRepository.class),
                mock(ProfileRepository.class),
                mock(LinkRepository.class),
                mock(CandidateMatchRepository.class),
                new CandidateScorer(),
                auth,
                e -> {},
                mock(AuditService.class));
        assertThatThrownBy(() -> svc.confirm(UUID.randomUUID(), "officer", "ok"))
                .isInstanceOf(ReviewerRequiredException.class);
    }
}
