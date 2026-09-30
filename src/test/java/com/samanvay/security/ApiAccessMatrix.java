package com.samanvay.security;

import static com.samanvay.security.ApiAccessMatrix.Who.ADMIN;
import static com.samanvay.security.ApiAccessMatrix.Who.CITIZEN;
import static com.samanvay.security.ApiAccessMatrix.Who.DEPARTMENT;
import static com.samanvay.security.ApiAccessMatrix.Who.OFFICER;
import static com.samanvay.security.ApiAccessMatrix.Who.REVIEWER;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The role-to-route contract, written down once. {@code ApiAccessMatrixIT}
 * checks it against the <em>live</em> route list: a controller method that is
 * not listed here fails the build, as does an entry here that no longer
 * exists (demo-only routes excepted).
 *
 * <p>Anonymous callers are never allowed on {@code /api/**}; they are not
 * listed. "Allowed" means the request passes authentication and
 * authorization (the response is neither 401 nor 403); for CITIZEN it means
 * "on their own record".
 */
final class ApiAccessMatrix {

    enum Who {
        ANONYMOUS,
        CITIZEN,
        OFFICER,
        REVIEWER,
        ADMIN,
        DEPARTMENT
    }

    static final Set<String> DEMO_ONLY = Set.of(
            "POST /api/audit/demo/tamper/{seq}",
            "GET /api/connector/chaos/{dataSourceCode}",
            "POST /api/connector/chaos/{dataSourceCode}/kill",
            "POST /api/connector/chaos/{dataSourceCode}/revive");

    static final Map<String, Set<Who>> ALLOWED = new LinkedHashMap<>();

    static {
        // catalog
        allow("GET /api/catalog/departments", CITIZEN, OFFICER, REVIEWER, ADMIN, DEPARTMENT);
        allow("GET /api/catalog/journeys", CITIZEN, OFFICER, REVIEWER, ADMIN, DEPARTMENT);
        allow("GET /api/catalog/journeys/{code}", CITIZEN, OFFICER, REVIEWER, ADMIN, DEPARTMENT);
        allow("GET /api/catalog/connectors", OFFICER, ADMIN);
        allow("GET /api/catalog/schemas", OFFICER, ADMIN);
        allow("POST /api/catalog/departments", ADMIN);
        allow("POST /api/catalog/data-sources", ADMIN);
        allow("POST /api/catalog/connectors", ADMIN);
        allow("POST /api/catalog/mappings", ADMIN);
        allow("POST /api/catalog/connectors/{ref}/test", ADMIN);
        allow("POST /api/catalog/connectors/{ref}/publish", ADMIN);
        allow("POST /api/catalog/import/openapi", ADMIN);
        // identity
        allow("POST /api/identity/citizens", CITIZEN, OFFICER);
        allow("GET /api/identity/citizens/{id}", CITIZEN, OFFICER, REVIEWER);
        allow("GET /api/identity/citizens/{id}/links", CITIZEN, OFFICER, REVIEWER);
        allow("GET /api/identity/citizens/{id}/connect-accounts", CITIZEN, OFFICER, REVIEWER);
        allow("GET /api/identity/proof-providers", CITIZEN, OFFICER);
        allow("POST /api/identity/links", CITIZEN);
        allow("GET /api/identity/review-queue", REVIEWER);
        allow("POST /api/identity/candidates/{id}/confirm", REVIEWER);
        allow("POST /api/identity/candidates/{id}/reject", REVIEWER);
        // consent
        allow("POST /api/consent/requests", CITIZEN, OFFICER, DEPARTMENT);
        allow("POST /api/consent/requests/{id}/grant", CITIZEN);
        allow("POST /api/consent/{id}/revoke", CITIZEN);
        allow("POST /api/consent/me/{id}/revoke", CITIZEN);
        allow("GET /api/consent/citizens/{citizenId}", CITIZEN, OFFICER);
        // orchestration
        allow("POST /api/journeys/{code}/start", CITIZEN, OFFICER, DEPARTMENT);
        allow("GET /api/journeys/instances/{id}", OFFICER);
        allow("POST /api/journeys/instances/{id}/retry", OFFICER);
        allow("POST /api/journeys/instances/{id}/approve", OFFICER);
        allow("POST /api/journeys/instances/{id}/reject", OFFICER);
        allow("GET /api/journeys/exceptions", OFFICER);
        // officer bank-account review
        allow("GET /api/officer/bank-reviews", OFFICER);
        allow("POST /api/officer/bank-reviews/{id}/passbook", OFFICER);
        allow("POST /api/officer/bank-reviews/{id}/request-document", OFFICER);
        allow("POST /api/officer/bank-reviews/{id}/approve", OFFICER);
        allow("POST /api/officer/bank-reviews/{id}/reject", OFFICER);
        // tracking
        allow("GET /api/applications", CITIZEN, OFFICER);
        allow("GET /api/applications/{referenceNo}", CITIZEN, OFFICER);
        allow("GET /api/applications/{referenceNo}/steps", CITIZEN, OFFICER);
        allow("GET /api/applications/{referenceNo}/issued-records", CITIZEN, OFFICER);
        // connector
        allow("GET /api/connector/issued-documents", CITIZEN, OFFICER);
        allow("GET /api/connector/chaos/{dataSourceCode}", OFFICER, ADMIN);
        allow("POST /api/connector/chaos/{dataSourceCode}/kill", OFFICER, ADMIN);
        allow("POST /api/connector/chaos/{dataSourceCode}/revive", OFFICER, ADMIN);
        // ops dashboards
        allow("GET /api/ops/metrics", OFFICER, ADMIN);
        // audit
        allow("GET /api/audit/head", OFFICER, ADMIN);
        allow("GET /api/audit/verify", OFFICER, ADMIN);
        allow("GET /api/audit/entries", OFFICER, ADMIN);
        allow("GET /api/audit/checkpoint", OFFICER, ADMIN);
        allow("POST /api/audit/demo/tamper/{seq}", ADMIN);
    }

    private ApiAccessMatrix() {}

    private static void allow(String route, Who... who) {
        Set<Who> set = EnumSet.noneOf(Who.class);
        set.addAll(java.util.List.of(who));
        if (ALLOWED.put(route, set) != null) {
            throw new IllegalStateException("duplicate matrix entry " + route);
        }
    }
}
