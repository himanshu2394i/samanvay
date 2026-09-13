package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MappingSuggestorTest {

    private final MappingSuggestor suggestor = new MappingSuggestor();

    @Test
    void lexicalMatchPrefersNormalizedSynonyms() {
        var suggestions = suggestor.suggest(
                java.util.List.of("annual_income", "holder_name", "unrelated_xyz"),
                java.util.List.of("annualIncome", "holderName"));
        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.get(0).source()).isEqualTo("annual_income");
        assertThat(suggestions.get(0).target()).isEqualTo("annualIncome");
        assertThat(suggestions.get(0).confidence()).isGreaterThan(0.6);
        assertThat(suggestions.get(1).source()).isEqualTo("holder_name");
        assertThat(suggestions.get(1).target()).isEqualTo("holderName");
    }

    @Test
    void suggestionsAreAdvisoryAndDoNotMutateCatalog() {
        assertThat(suggestor.suggest(java.util.List.of("dob"), java.util.List.of("dateOfBirth")))
                .allMatch(s -> s.rationale().contains("lexical"));
    }
}
