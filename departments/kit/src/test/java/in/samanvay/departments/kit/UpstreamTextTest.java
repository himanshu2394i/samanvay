package in.samanvay.departments.kit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UpstreamTextTest {

    @Test
    void aConflictSaysWhatIsActuallyWrongInsteadOfTheGenericLine() {
        assertThat(UpstreamText.forCitizen(409, "Journey INCOME_CERT_RENEWAL is not published", "JOURNEY_NOT_PUBLISHED"))
                .contains("not open for applications yet");
        assertThat(UpstreamText.forCitizen(409, "The citizen already has an open application for INCOME_CERT_RENEWAL", "JOURNEY_ALREADY_OPEN"))
                .contains("already have an application in progress");
        assertThat(UpstreamText.forCitizen(409, "x", "CONSENT_REQUEST_NOT_PENDING")).contains("already been used");
        assertThat(UpstreamText.forCitizen(409, "x", "CONSENT_TERMS_CHANGED")).contains("changed");
    }

    @Test
    void anUnknownReasonStillGetsTheGenericLineAndNeverLeaksInternals() {
        assertThat(UpstreamText.forCitizen(409, "Journey X_Y is not published", "SOMETHING_NEW")).isEqualTo("This conflicts with the current state. Refresh and try again.");
        assertThat(UpstreamText.forCitizen(409, null, null)).isEqualTo("This conflicts with the current state. Refresh and try again.");
        assertThat(UpstreamText.forCitizen(404, "Connect your Revenue account first.", null)).isEqualTo("Connect your Revenue account first.");
    }
}
