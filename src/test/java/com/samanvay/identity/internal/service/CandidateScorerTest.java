package com.samanvay.identity.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.identity.api.Profile;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CandidateScorerTest {

    @Test
    void yearPrecisionAndDevanagariMatch() {
        CandidateScorer scorer = new CandidateScorer();
        Profile year = new Profile(UUID.randomUUID(), "Ramesh", "रमेश", "Kumar", "Suresh", LocalDate.of(2004, 6, 1), "YEAR");
        var rec = JsonMapper.builder().build().readTree("{\"name\":\"Ramesh\",\"dob\":\"2004-01-15\",\"fatherName\":\"Suresh\"}");
        assertThat(scorer.score(year, rec)).isGreaterThan(0.7);
        Profile latin = new Profile(UUID.randomUUID(), "Ramesh", "रमेश", "Kumar", "Suresh", LocalDate.of(2004, 1, 15), "DAY");
        var hindi = JsonMapper.builder().build().readTree("{\"name\":\"ramesh\",\"dob\":\"2004-01-15\",\"fatherName\":\"Suresh\"}");
        assertThat(scorer.score(latin, hindi)).isGreaterThan(0.85);
    }
}
