package com.samanvay.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A client written before the identity acknowledgement existed sends no such field; that must still read as "not acknowledged". */
class OnboardRequestJsonTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void a_request_without_the_acknowledgement_is_not_acknowledged() {
        OnboardRequest r = JSON.readValue("{\"baseUrl\":\"https://d.gov\",\"manifestDigest\":\"x\",\"categories\":[\"MARKS\"],\"acceptSuggestedMappings\":true,\"mappings\":{}}",
                OnboardRequest.class);
        assertThat(r.acknowledgeIdentityChange()).isFalse();
        assertThat(r.approvedManifestKey()).isNull();
    }

    @Test
    void the_acknowledgement_is_read_when_sent() {
        OnboardRequest r = JSON.readValue("{\"baseUrl\":\"https://d.gov\",\"manifestDigest\":\"x\",\"categories\":[],\"acceptSuggestedMappings\":false,\"acknowledgeIdentityChange\":true}",
                OnboardRequest.class);
        assertThat(r.acknowledgeIdentityChange()).isTrue();
    }
}
