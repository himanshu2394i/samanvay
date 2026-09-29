package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.consent.api.DenialReason;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** The consent copy tables (citizen and officer) are complete and hold the final, agreed copy. */
class ConsentCopyTableTest {

    static final List<String> REQUIRED = List.of(
            "denied.CONSENT_REVOKED", "denied.CONSENT_EXPIRED", "status.ACTIVE", "status.EXPIRED", "status.REVOKED");
    static final List<String> OFFICER_REQUIRED = List.of("denied.CHECK_ALREADY_USED");

    @Test
    void everyRequiredEntryIsPresentAndNonBlank() {
        Properties table = ConsentCopy.table();
        assertThat(REQUIRED).allSatisfy(key -> assertThat(table.getProperty(key)).as(key).isNotBlank());
        assertThat(table.stringPropertyNames()).as("no stray keys").containsExactlyInAnyOrderElementsOf(REQUIRED);
        Properties officer = ConsentCopy.officerTable();
        assertThat(OFFICER_REQUIRED).allSatisfy(key -> assertThat(officer.getProperty(key)).as(key).isNotBlank());
        assertThat(officer.stringPropertyNames()).as("no stray officer keys")
                .containsExactlyInAnyOrderElementsOf(OFFICER_REQUIRED);
    }

    @Test
    void finalOfficerCopy() {
        assertThat(ConsentCopy.officerDenied(DenialReason.CHECK_ALREADY_USED))
                .isEqualTo("This document has already been checked for this application. "
                        + "The citizen's permission allows one check.");
        assertThat(ConsentCopy.officerDenied(DenialReason.NO_POINTER)).isEqualTo("NO_POINTER");
    }

    @Test
    void finalCopy() {
        assertThat(ConsentCopy.denied(DenialReason.CONSENT_REVOKED, null))
                .isEqualTo("You withdrew this permission, so this department can no longer check this document.");
        assertThat(ConsentCopy.denied(DenialReason.CONSENT_EXPIRED, Instant.parse("2026-09-27T20:00:00Z")))
                .as("date in Asia/Kolkata: 28 Sep 01:30 IST")
                .isEqualTo("This permission ended on 28 September 2026, so this department can no longer check this "
                        + "document. If your application still needs it, you can give permission again.");
        assertThat(ConsentCopy.statusLabel("ACTIVE")).isEqualTo("Active");
        assertThat(ConsentCopy.statusLabel("EXPIRED")).isEqualTo("Ended");
        assertThat(ConsentCopy.statusLabel("REVOKED")).isEqualTo("Withdrawn by you");
        assertThat(ConsentCopy.denied(DenialReason.NO_POINTER, null)).isEqualTo("NO_POINTER");
    }
}
