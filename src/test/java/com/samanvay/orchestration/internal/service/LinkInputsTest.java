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
}
