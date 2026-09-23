package com.samanvay.identity.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.AuditService;
import com.samanvay.identity.api.ReviewFilter;
import com.samanvay.identity.internal.domain.CandidateMatchEntity;
import com.samanvay.identity.internal.domain.ProfileEntity;
import com.samanvay.identity.internal.repository.CandidateMatchRepository;
import com.samanvay.identity.internal.repository.CitizenRepository;
import com.samanvay.identity.internal.repository.LinkRepository;
import com.samanvay.identity.internal.repository.ProfileRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.json.JsonMapper;

class ResolutionQueueTest {

    @Test
    void probabilisticMatchIsQueuedNotLinked() {
        UUID citizen = UUID.randomUUID();
        ProfileEntity profile = new ProfileEntity();
        profile.setCitizenId(citizen);
        profile.setNameLatin("Ramesh Kumar");
        profile.setNameDevanagari("रमेश");
        profile.setFatherName("Suresh");
        profile.setDob(LocalDate.of(2004, 1, 15));
        profile.setDobPrecision("DAY");
        ProfileRepository profiles = mock(ProfileRepository.class);
        when(profiles.findAll()).thenReturn(List.of(profile));
        LinkRepository links = mock(LinkRepository.class);
        when(links.findByDepartmentCodeAndLocalIdTokenAndStatus(any(), any(), any())).thenReturn(Optional.empty());
        CandidateMatchRepository candidates = mock(CandidateMatchRepository.class);
        when(candidates.findByCitizenIdAndDepartmentCode(any(), any())).thenReturn(Optional.empty());
        when(candidates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CandidateMatchEntity pending = new CandidateMatchEntity();
        pending.setId(UUID.randomUUID());
        pending.setCitizenId(citizen);
        pending.setDepartmentCode("FIRE");
        pending.setScore(BigDecimal.valueOf(0.85));
        pending.setStatus("PENDING");
        when(candidates.findByStatus(any(), any())).thenReturn(new PageImpl<>(List.of(pending)));
        IdentityServices svc = new IdentityServices(
                mock(CitizenRepository.class),
                profiles,
                links,
                candidates,
                new CandidateScorer(),
                mock(ReviewerAuth.class),
                e -> {},
                mock(AuditService.class),
                List.of(),
                mock(com.samanvay.catalog.api.JourneyCatalog.class),
                mock(com.samanvay.catalog.api.DepartmentCatalog.class));
        var rec = JsonMapper.builder()
                .build()
                .readTree(
                        "{\"localId\":\"NEW-1\",\"name\":\"Ramesh Kumar\",\"dob\":\"2004-01-15\",\"fatherName\":\"Suresh\"}");
        assertThat(svc.submitCandidate("FIRE", rec).kind()).isEqualTo("QUEUED");
        var page = svc.reviewQueue(ReviewFilter.pending(), Pageable.ofSize(20));
        assertThat(page.getContent().stream().anyMatch(c -> c.citizenId().equals(citizen))).isTrue();
        assertThat(page.getContent().stream().noneMatch(c -> "ACTIVE".equals(c.status()))).isTrue();
    }
}
