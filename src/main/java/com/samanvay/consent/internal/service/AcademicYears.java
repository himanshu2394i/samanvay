package com.samanvay.consent.internal.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Academic years of a scheme whose year starts in a given month, in Asia/Kolkata. */
final class AcademicYears {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private AcademicYears() {}

    /** The calendar year in which the academic year containing {@code t} started. */
    static int startYearOf(Instant t, int startMonth) {
        if (startMonth < 1 || startMonth > 12) {
            throw new IllegalArgumentException("academic year start month must be 1-12: " + startMonth);
        }
        ZonedDateTime local = t.atZone(ZONE);
        return local.getMonthValue() >= startMonth ? local.getYear() : local.getYear() - 1;
    }

    /** True when {@code award} falls in the academic year immediately before the one containing {@code now}. */
    static boolean isPriorYear(Instant award, Instant now, int startMonth) {
        return startYearOf(award, startMonth) == startYearOf(now, startMonth) - 1;
    }
}
