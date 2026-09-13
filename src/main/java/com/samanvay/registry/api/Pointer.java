package com.samanvay.registry.api;

import com.samanvay.shared.DataCategory;
import com.samanvay.shared.SubjectRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Pointer(
        UUID id,
        SubjectRef subject,
        String departmentCode,
        DataCategory category,
        Sensitivity sensitivity,
        DiscoveryPolicy discoveryPolicy,
        String sourceRef,
        LocalDate issuedAt,
        LocalDate validUntil,
        Instant asOf,
        FreshnessMode freshnessMode,
        String status) {

    public Freshness freshness() {
        return new Freshness(freshnessMode, asOf);
    }
}
