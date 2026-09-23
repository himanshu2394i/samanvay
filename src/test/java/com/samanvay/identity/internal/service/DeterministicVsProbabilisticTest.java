package com.samanvay.identity.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.AuditService;
import com.samanvay.identity.internal.domain.LinkEntity;
import com.samanvay.identity.internal.repository.CandidateMatchRepository;
import com.samanvay.identity.internal.repository.CitizenRepository;
import com.samanvay.identity.internal.repository.LinkRepository;
import com.samanvay.identity.internal.repository.ProfileRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeterministicVsProbabilisticTest {

    @Test
    void exactLocalIdDoesNotScore() {
        LinkRepository links = mock(LinkRepository.class);
        LinkEntity existing = new LinkEntity();
        existing.setCitizenId(UUID.randomUUID());
        when(links.findByDepartmentCodeAndLocalIdTokenAndStatus("REVENUE", "RC-1", "ACTIVE"))
                .thenReturn(Optional.of(existing));
        IdentityServices svc = new IdentityServices(
                mock(CitizenRepository.class),
                mock(ProfileRepository.class),
                links,
                mock(CandidateMatchRepository.class),
                new CandidateScorer(),
                mock(ReviewerAuth.class),
                e -> {},
                mock(AuditService.class),
                List.of(),
                mock(com.samanvay.catalog.api.JourneyCatalog.class),
                mock(com.samanvay.catalog.api.DepartmentCatalog.class));
        var rec = JsonMapper.builder().build().readTree("{\"localId\":\"RC-1\",\"name\":\"X\"}");
        assertThat(svc.submitCandidate("REVENUE", rec).kind()).isEqualTo("ALREADY_LINKED");
    }
}
