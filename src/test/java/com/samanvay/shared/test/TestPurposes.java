package com.samanvay.shared.test;

import com.samanvay.catalog.api.Purpose;
import com.samanvay.catalog.api.PurposeCatalog;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** In-memory {@link PurposeCatalog} for unit tests (mirrors the journey purposes V181 registers). */
public final class TestPurposes {

    public static final Set<String> KNOWN = Set.of("SCHOLARSHIP_ELIGIBILITY", "BUSINESS_NOC", "FARMER_SUBSIDY");

    private static final Map<String, String> REQUESTER = Map.of(
            "SCHOLARSHIP_ELIGIBILITY", "SCHOLARSHIP", "BUSINESS_NOC", "INDUSTRY", "FARMER_SUBSIDY", "AGRICULTURE");

    private TestPurposes() {}

    public static PurposeCatalog catalog() {
        return code -> KNOWN.contains(code)
                ? Optional.of(new Purpose(
                        code, "test purpose " + code, null, "JOURNEY", true, REQUESTER.get(code), List.of("INCOME_CERTIFICATE")))
                : Optional.empty();
    }
}
