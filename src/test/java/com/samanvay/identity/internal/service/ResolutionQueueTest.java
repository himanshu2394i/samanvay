package com.samanvay.identity.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityResolution;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.identity.api.ReviewFilter;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = SamanvayApplication.class)
class ResolutionQueueTest extends PostgresIntegrationTest {

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityResolution resolution;

    @Test
    void probabilisticMatchIsQueuedNotLinked() {
        var citizen = profiles.register(new ProfileDraft(
                "Ramesh Kumar",
                "रमेश",
                "Ramesh",
                "Kumar",
                "Suresh",
                LocalDate.of(2004, 1, 15),
                "DAY",
                "M",
                "99****21"));
        var rec = JsonMapper.builder()
                .build()
                .readTree("{\"localId\":\"NEW-1\",\"name\":\"Ramesh Kumar\",\"dob\":\"2004-01-15\",\"fatherName\":\"Suresh\"}");
        assertThat(resolution.submitCandidate("FIRE", rec).kind()).isEqualTo("QUEUED");
        var page = resolution.reviewQueue(ReviewFilter.pending(), Pageable.ofSize(20));
        assertThat(page.getContent().stream().anyMatch(c -> c.citizenId().equals(citizen))).isTrue();
        assertThat(page.getContent().stream().noneMatch(c -> "ACTIVE".equals(c.status()))).isTrue();
    }
}
