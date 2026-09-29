package com.samanvay.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Rules under test come from the Principal Architect's ruling on the #31 name
 * match, with two decisions this matcher takes where the ruling left the edge
 * cases open (both erring towards a human review, never a silent MATCH):
 *
 * <ul>
 *   <li>A match that relies on any initial is PARTIAL at most, never MATCH
 *       (QA's sibling-account concern: "R. Patil" must not equal "Rohan Patil").
 *   <li>Name-part order is normalised away, so "surname + one other" is read as
 *       "at least two aligned name parts" for PARTIAL.
 * </ul>
 */
class NameMatcherTest {

    private final NameMatcher matcher = NameMatcher.withDefaultHonorifics();

    @ParameterizedTest(name = "[{index}] \"{0}\" vs \"{1}\" -> {2}")
    @CsvSource(delimiter = '|', value = {
        // --- MATCH: same parts, whatever the surface form ---
        " Rahul Kumar Patil | Rahul Kumar Patil | MATCH ",   // identical
        " Rahul Kumar Patil | Patil Rahul Kumar | MATCH ",   // order normalised
        " RAHUL KUMAR PATIL | rahul kumar patil | MATCH ",   // case
        " Rahul  Kumar   Patil | Rahul Kumar Patil | MATCH ",// extra spaces
        " Dr. Rahul Kumar Patil | Rahul Kumar Patil | MATCH ",// honorific dropped
        " Shri Rahul Patil | Rahul Patil | MATCH ",          // honorific (shri)
        " Rahul-Kumar Patil | Rahul Kumar Patil | MATCH ",   // punctuation
        " राहुल पाटील | पाटील राहुल | MATCH ",                 // Devanagari, reordered

        // --- PARTIAL: two+ parts align, or an initial carried the match ---
        " Rahul Patil | Rahul Kumar Patil | PARTIAL ",       // missing middle
        " Sneha Kulkarni | Sneha Rajesh Kulkarni | PARTIAL ",// father-name middle
        " R. K. Patil | Rahul Kumar Patil | PARTIAL ",       // initials, capped
        " R. Patil | Rohan Patil | PARTIAL ",                // sibling initial: NOT match
        " R Patil | Rahul Patil | PARTIAL ",                 // initial expands

        // --- NO_MATCH: at most one part aligns ---
        " Priya Deshmukh | Pooja Deshmukh | NO_MATCH ",      // surname only
        " Amit Sharma | Rahul Patil | NO_MATCH ",            // nothing aligns
        " Patil | Rahul Patil | NO_MATCH ",                  // lone surname

        // --- NOT_CHECKED: cannot be compared ---
        " राहुल पाटील | Rahul Patil | NOT_CHECKED ",          // different scripts
        " Rahul Patil | राहुल पाटील | NOT_CHECKED ",          // different scripts (swapped)
    })
    void tableCases(String recorded, String provider, NameMatchResult expected) {
        assertThat(matcher.match(recorded, provider))
                .as("\"%s\" vs \"%s\"", recorded, provider)
                .isEqualTo(expected);
    }

    @Test
    void emptyOrBlankOrNullIsNotChecked() {
        assertThat(matcher.match(null, "Rahul Patil")).isEqualTo(NameMatchResult.NOT_CHECKED);
        assertThat(matcher.match("Rahul Patil", null)).isEqualTo(NameMatchResult.NOT_CHECKED);
        assertThat(matcher.match("", "Rahul Patil")).isEqualTo(NameMatchResult.NOT_CHECKED);
        assertThat(matcher.match("   ", "Rahul Patil")).isEqualTo(NameMatchResult.NOT_CHECKED);
        // a name that is only a honorific normalises to nothing
        assertThat(matcher.match("Dr.", "Rahul Patil")).isEqualTo(NameMatchResult.NOT_CHECKED);
    }

    /** Reordering parts, re-casing, and adding punctuation never change the verdict. */
    @Test
    void resultIsStableUnderReorderingCasingAndPunctuation() {
        String canonical = "Rahul Kumar Patil";
        Random rng = new Random(42);
        for (int i = 0; i < 50; i++) {
            List<String> parts = new ArrayList<>(List.of("Rahul", "Kumar", "Patil"));
            Collections.shuffle(parts, rng);
            String noisy = parts.stream()
                    .map(p -> rng.nextBoolean() ? p.toUpperCase() : p.toLowerCase())
                    .reduce((a, b) -> a + (rng.nextBoolean() ? "  " : " . ") + b)
                    .orElseThrow();
            assertThat(matcher.match(canonical, noisy))
                    .as("noisy form: \"%s\"", noisy)
                    .isEqualTo(NameMatchResult.MATCH);
        }
    }

    @Test
    void versionIsRecordedForReproducibility() {
        assertThat(matcher.version()).isEqualTo(NameMatcher.VERSION);
        assertThat(NameMatcher.VERSION).isNotBlank();
    }
}
