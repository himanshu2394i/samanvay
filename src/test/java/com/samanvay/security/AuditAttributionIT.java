package com.samanvay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.catalog.api.PurposeCatalog;
import com.samanvay.connector.api.Capability;
import com.samanvay.connector.api.ConnectorRuntime;
import com.samanvay.connector.api.ExecutionInputs;
import com.samanvay.consent.api.AccessAuthority;
import com.samanvay.consent.api.AccessDecision;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.consent.api.InvalidGrantException;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PrincipalRef;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DATA_ACCESSED / GRANT_REJECTED name who triggered the access (from the
 * token) and a catalog purpose code - never SYSTEM/"connector" - and the hash
 * chain still verifies across pre-existing SYSTEM entries and new ones.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditAttributionIT extends PostgresIntegrationTest {

    private static final List<String> SCHOLARSHIP_CATEGORIES =
            List.of("INCOME_CERTIFICATE", "CASTE_CERTIFICATE", "MARKS", "BANK_ACCOUNT");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityLinking linking;

    @Autowired
    ConsentService consents;

    @Autowired
    AccessAuthority access;

    @Autowired
    ConnectorRuntime connectors;

    @Autowired
    AuditService audit;

    @Autowired
    PurposeCatalog purposes;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void officerStartedJourneyRecordsOfficerSubjectAndCatalogPurpose() {
        // A pre-existing, pre-attribution style row: must stay as it is and keep verifying.
        audit.record(new AuditEntry(
                ActorType.SYSTEM, "connector", "DATA_ACCESSED", "legacy-subject", "rev-income@1", "REVENUE",
                null, null, Outcome.ALLOWED, null, Map.of()));

        UUID citizen = seededScholarshipCitizen();
        startAs(TestTokens.officer("officer-attr-7"), citizen);

        List<Map<String, Object>> rows = dataAccessed(citizen);
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("actor_type")).isEqualTo("OFFICER");
            assertThat(r.get("actor_id")).isEqualTo("officer-attr-7");
            String purpose = purpose(r);
            assertThat(purpose).isEqualTo("SCHOLARSHIP_ELIGIBILITY");
            assertThat(purposes.isActive(purpose)).as("purpose comes from the catalog").isTrue();
        });

        assertChainVerifies();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit.audit_entry WHERE subject_id = 'legacy-subject' AND actor_type = 'SYSTEM'",
                        Integer.class))
                .as("old SYSTEM entry untouched")
                .isEqualTo(1);
    }

    @Test
    void departmentClientTokenRecordsClientId() {
        UUID citizen = seededScholarshipCitizen();
        startAs(
                TestTokens.department("dept-scholarship-it", "revenue-rest-mock", "education-soap-mock", "dbt-rest-mock"),
                citizen);

        List<Map<String, Object>> rows = dataAccessed(citizen);
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("actor_type")).isEqualTo("DEPARTMENT");
            assertThat(r.get("actor_id")).isEqualTo("dept-scholarship-it");
            assertThat(purposes.isActive(purpose(r))).isTrue();
        });
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM consent_access_grant WHERE subject_citizen_id = ? AND principal_id = 'dept-scholarship-it' AND principal_type = 'DEPARTMENT'",
                        Integer.class,
                        citizen))
                .isGreaterThanOrEqualTo(rows.size());
        assertChainVerifies();
    }

    @Test
    void rejectedGrantIsAttributedToItsPrincipalNotToConnector() {
        UUID citizen = seededScholarshipCitizen();
        PrincipalRef dept = new PrincipalRef(PrincipalRef.Kind.DEPARTMENT, "dept-rejected-it");
        AccessRequest request = new AccessRequest(
                new SubjectRef(citizen),
                new RequesterRef("SCHOLARSHIP"),
                DataCategory.INCOME_CERTIFICATE,
                "REVENUE",
                "rev-income@1",
                PurposeCode.SCHOLARSHIP_ELIGIBILITY,
                "POST_MATRIC_SCHOLARSHIP",
                dept);
        AccessDecision decision = access.authorize(request);
        assertThat(decision).isInstanceOf(AccessDecision.Granted.class);
        AccessGrant tampered = ((AccessDecision.Granted) decision).grant().withCategory(DataCategory.MARKS);

        assertThatThrownBy(() -> connectors.execute(
                        tampered,
                        Capability.FETCH,
                        new ExecutionInputs(DataCategory.MARKS, "wf-it", Map.of(), Map.of(), Map.of())))
                .isInstanceOf(InvalidGrantException.class);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT actor_type, actor_id, meta::text AS meta FROM audit.audit_entry "
                        + "WHERE action = 'GRANT_REJECTED' AND subject_id = ? ORDER BY seq DESC LIMIT 1",
                citizen.toString());
        assertThat(row.get("actor_type")).isEqualTo("DEPARTMENT");
        assertThat(row.get("actor_id")).isEqualTo("dept-rejected-it");
        assertThat(purpose(row)).isEqualTo("SCHOLARSHIP_ELIGIBILITY");
        assertChainVerifies();
    }

    @Test
    void unknownPurposeIsRefusedBeforeAnyGrant() {
        UUID citizen = seededScholarshipCitizen();
        AccessDecision decision = access.authorize(new AccessRequest(
                new SubjectRef(citizen),
                new RequesterRef("SCHOLARSHIP"),
                DataCategory.INCOME_CERTIFICATE,
                "REVENUE",
                "rev-income@1",
                PurposeCode.of("BROWSING_FOR_FUN"),
                "POST_MATRIC_SCHOLARSHIP",
                new PrincipalRef(PrincipalRef.Kind.OFFICER, "officer-x")));
        assertThat(decision).isInstanceOfSatisfying(AccessDecision.Denied.class, d -> assertThat(d.reason().name())
                .isEqualTo("UNKNOWN_PURPOSE"));
    }

    private void startAs(String token, UUID citizen) {
        TestHttp.as(token)
                .post()
                .uri("http://localhost:" + port + "/api/journeys/POST_MATRIC_SCHOLARSHIP/start")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizen, "submission", Map.of()))
                .retrieve()
                .toBodilessEntity();
    }

    private List<Map<String, Object>> dataAccessed(UUID citizen) {
        return jdbc.queryForList(
                "SELECT actor_type, actor_id, meta::text AS meta FROM audit.audit_entry "
                        + "WHERE action = 'DATA_ACCESSED' AND subject_id = ? ORDER BY seq",
                citizen.toString());
    }

    private static String purpose(Map<String, Object> row) {
        JsonNode meta = JSON.readTree(row.get("meta").toString());
        return meta.get("purpose").asString();
    }

    private void assertChainVerifies() {
        long head = audit.headSeq();
        assertThat(audit.verify(1, head).valid()).as("hash chain 1..%d", head).isTrue();
    }

    private UUID seededScholarshipCitizen() {
        UUID citizen = profiles.register(new ProfileDraft(
                "Attr Citizen", "अट्र", "Attr", "Citizen", "Father", LocalDate.of(2003, 3, 3), "DAY", "F", "98****00"));
        String suffix = citizen.toString().substring(0, 8);
        linking.assertLink(citizen, "REVENUE", "RATION", "ATTR-R-" + suffix, com.samanvay.identity.api.AuthProof.digiLockerSandbox());
        linking.assertLink(citizen, "EDUCATION", "STUDENT", "ATTR-E-" + suffix, com.samanvay.identity.api.AuthProof.digiLockerSandbox());
        linking.assertLink(citizen, "DBT", "DBT", "ATTR-D-" + suffix, com.samanvay.identity.api.AuthProof.digiLockerSandbox());
        var request = consents.request(new ConsentRequestDraft(
                citizen, "SCHOLARSHIP", "SCHOLARSHIP_ELIGIBILITY", "Post-matric scholarship", SCHOLARSHIP_CATEGORIES));
        consents.grant(request.id(), citizen, new AuthProof("attr-session"));
        // Pointers are projected after commit from LinkAsserted; wait for the real end state.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(
                        () -> jdbc.queryForObject(
                                "SELECT count(DISTINCT data_category) FROM registry_pointer WHERE subject_id = ? AND data_category IN ('INCOME_CERTIFICATE','CASTE_CERTIFICATE','MARKS','BANK_ACCOUNT')",
                                Integer.class,
                                citizen),
                        n -> n == SCHOLARSHIP_CATEGORIES.size());
        return citizen;
    }
}
