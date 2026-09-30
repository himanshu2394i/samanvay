package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.catalog.api.MappingSuggestion;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MappingSuggestorTest {

    private final MappingSuggestor suggestor = new MappingSuggestor();

    private Optional<MappingSuggestion> forTarget(List<MappingSuggestion> all, String target) {
        return all.stream().filter(s -> s.target().equals(target)).findFirst();
    }

    @Test
    void lexicalMatchPrefersNormalizedSynonyms() {
        var suggestions = suggestor.suggest(
                List.of("annual_income", "holder_name", "unrelated_xyz"), List.of("annualIncome", "holderName"));
        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.get(0).source()).isEqualTo("annual_income");
        assertThat(suggestions.get(0).target()).isEqualTo("annualIncome");
        assertThat(suggestions.get(0).confidence()).isGreaterThan(0.6);
        assertThat(suggestions.get(1).source()).isEqualTo("holder_name");
        assertThat(suggestions.get(1).target()).isEqualTo("holderName");
    }

    @Test
    void semanticPassMatchesDomainSynonymsLexicalScoringMisses() {
        var suggestions = suggestor.suggest(
                List.of("dob", "acct_no", "mobile", "favourite_colour"),
                List.of("dateOfBirth", "accountNumber", "phone"));

        assertThat(suggestions).hasSize(3);
        for (var target : List.of("dateOfBirth", "accountNumber", "phone")) {
            var s = forTarget(suggestions, target).orElseThrow();
            assertThat(s.rationale()).isEqualTo("semantic");
            assertThat(s.approved()).isFalse();
            assertThat(s.confidence()).isGreaterThanOrEqualTo(0.9);
        }
        assertThat(forTarget(suggestions, "dateOfBirth").orElseThrow().source()).isEqualTo("dob");
        assertThat(forTarget(suggestions, "accountNumber").orElseThrow().source()).isEqualTo("acct_no");
        assertThat(forTarget(suggestions, "phone").orElseThrow().source()).isEqualTo("mobile");
        // an unrelated source is never proposed for any target
        assertThat(suggestions).noneMatch(s -> s.source().equals("favourite_colour"));
    }

    @Test
    void lexicalWinsTieAndKeepsItsRationale() {
        var suggestions = suggestor.suggest(List.of("annualIncome"), List.of("annualIncome"));
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).rationale()).isEqualTo("lexical");
        assertThat(suggestions.get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void unrelatedFieldIsNotProposed() {
        assertThat(suggestor.suggest(List.of("favourite_colour"), List.of("dateOfBirth")))
                .isEmpty();
    }

    @Test
    void suggestionsAreAdvisoryAndDoNotAutoApprove() {
        assertThat(suggestor.suggest(List.of("dob"), List.of("dateOfBirth")))
                .isNotEmpty()
                .allMatch(s -> !s.approved())
                .allMatch(s -> !s.rationale().isBlank());
    }

    @Test
    void semanticPassKnowsMaharashtraRevenueVocabulary() {
        var suggestions = suggestor.suggest(
                List.of("zilla", "tehsil", "gatNumber", "gramName"),
                List.of("district", "taluka", "surveyNumber", "village"));

        assertThat(suggestions).hasSize(4).allMatch(s -> s.rationale().equals("semantic") && !s.approved());
        assertThat(forTarget(suggestions, "district").orElseThrow().source()).isEqualTo("zilla");
        assertThat(forTarget(suggestions, "taluka").orElseThrow().source()).isEqualTo("tehsil");
        assertThat(forTarget(suggestions, "surveyNumber").orElseThrow().source()).isEqualTo("gatNumber");
        assertThat(forTarget(suggestions, "village").orElseThrow().source()).isEqualTo("gramName");
    }
}
