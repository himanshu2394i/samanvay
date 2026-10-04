package com.samanvay.orchestration.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.identity.api.Link;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a connector can bind from the citizen's link: the department's person ID under explicit names, plus the old key. */
class LinkInputsTest {

    @Test
    void the_person_id_and_its_type_are_available_under_explicit_names_and_the_old_key_still_works() {
        Link link = new Link(UUID.randomUUID(), UUID.randomUUID(), "REVENUE", "REVENUE_PERSON_ID", "RV-1001", "CITIZEN_ASSERTED", "ACTIVE");
        assertThat(DefaultJourneyService.linkInputs(link))
                .containsEntry("personId", "RV-1001")
                .containsEntry("localIdType", "REVENUE_PERSON_ID")
                .containsEntry("localIdToken", "RV-1001");
    }

    @Test
    void no_link_means_no_link_inputs() {
        assertThat(DefaultJourneyService.linkInputs(null)).isEmpty();
    }

    @Test
    void the_discovery_candidate_is_built_as_json_so_odd_characters_cannot_break_it() {
        assertThat(DefaultJourneyService.candidate("RC-\"1\"\\x").get("localId").asString()).isEqualTo("RC-\"1\"\\x");
    }

    @Test
    void a_zero_or_negative_sla_means_no_due_time_never_a_born_breached_one() {
        assertThat(DefaultJourneyService.slaDueAt(0)).isNull();
        assertThat(DefaultJourneyService.slaDueAt(-5)).isNull();
        assertThat(DefaultJourneyService.slaDueAt(72)).isAfter(java.time.Instant.now().plusSeconds(71 * 3600L));
    }
}
