package com.samanvay.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Frequency values map to the enum; anything else fails loudly instead of disabling a rule. */
class PurposeFrequencyTest {

    @Test
    void knownValuesMapAndOnlyTheOneCheckValuesAreEnforced() {
        assertThat(Purpose.Frequency.fromCode("ONCE").oneCheckPerApplication()).isTrue();
        assertThat(Purpose.Frequency.fromCode("ONCE_PER_DOCUMENT_PER_APPLICATION").oneCheckPerApplication()).isTrue();
        assertThat(Purpose.Frequency.fromCode("ONCE_PER_PAYMENT").oneCheckPerApplication()).as("scoped per payment (keyed HMAC), not per application").isFalse();
        assertThat(Purpose.Frequency.fromCode("ONCE_PER_YEAR").oneCheckPerApplication()).as("enforced per year, not per application").isFalse();
        assertThat(Purpose.Frequency.fromCode(null)).isNull();
    }

    @Test
    void unknownValueFailsLoudly() {
        assertThatThrownBy(() -> Purpose.Frequency.fromCode("ONCE_PER_DOCUMENT_PER_APPLICTION"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown consent frequency")
                .hasMessageContaining("ONCE_PER_DOCUMENT_PER_APPLICTION");
        assertThatThrownBy(() -> Purpose.Frequency.fromCode("once")).isInstanceOf(IllegalStateException.class);
    }
}
