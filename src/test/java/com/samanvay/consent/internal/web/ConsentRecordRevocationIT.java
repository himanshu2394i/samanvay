package com.samanvay.consent.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.consent.api.AccessAuthority;
import com.samanvay.consent.api.AccessDecision;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.DenialReason;
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
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Phase 2 consent record (DEPA-style, one catalog purpose per consent) and its
 * revocation, over the real HTTP surface and the real AccessAuthority.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsentRecordRevocationIT extends PostgresIntegrationTest {

    static final String WITHDRAWN =
            "You withdrew this permission, so this department can no longer check this document.";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityLinking linking;

    @Autowired
    AccessAuthority access;

    // ---- grant -> revoke -> refused fetch (audited) ----------------------------------------

    @Test
    void grantThenRevokeThenFetchIsRefusedWithPlainMessageAndAudited() throws InterruptedException {
        Citizen c = linkedCitizen();
        UUID consentId = requestAndGrant(c, "SCH_ELIGIBILITY_CHECK");
        AccessRequest fetch = incomeFetch(c.id(), "SCH_ELIGIBILITY_CHECK");
        assertThat(awaitGranted(fetch)).as("fetch works while the consent is active")
                .isInstanceOf(AccessDecision.Granted.class);

        int status = citizenHttp(c).post().uri(url("/api/consent/me/" + consentId + "/revoke"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "changed my mind"))
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(status).isEqualTo(200);

        Map<String, Object> row = consentRow(consentId);
        assertThat(row.get("status")).isEqualTo("REVOKED");
        assertThat(row.get("revoked_at")).isNotNull();
        assertThat(row.get("revoked_by")).isEqualTo(c.subject());
        Map<String, Object> revokedAudit = audit("CONSENT_REVOKED", consentId);
        assertThat(revokedAudit.get("actor_type")).isEqualTo("CITIZEN");
        assertThat(revokedAudit.get("actor_id")).isEqualTo(c.subject());
        assertThat(revokedAudit.get("meta").toString()).contains("SCH_ELIGIBILITY_CHECK").contains(c.subject());

        AccessDecision after = access.authorize(fetch);
        assertThat(after).isInstanceOf(AccessDecision.Denied.class);
        AccessDecision.Denied denied = (AccessDecision.Denied) after;
        assertThat(denied.reason()).isEqualTo(DenialReason.CONSENT_REVOKED);
        assertThat(denied.message()).isEqualTo(WITHDRAWN);

        Map<String, Object> refusal = jdbc.queryForMap(
                "SELECT outcome, reason, meta::text AS meta FROM audit.audit_entry "
                        + "WHERE action = 'GRANT_DENIED' AND consent_id = ? ORDER BY seq DESC LIMIT 1",
                consentId);
        assertThat(refusal.get("outcome")).isEqualTo("DENIED");
        assertThat(refusal.get("reason")).isEqualTo("CONSENT_REVOKED");
        assertThat(refusal.get("meta").toString())
                .contains("SCH_ELIGIBILITY_CHECK")
                .contains("officer-p2-fetch")
                .contains(WITHDRAWN);

        // Revoking again changes nothing (original revoked_at/by kept, no second audit entry).
        citizenHttp(c).post().uri(url("/api/consent/me/" + consentId + "/revoke")).retrieve().toBodilessEntity();
        assertThat(consentRow(consentId).get("revoked_at")).isEqualTo(row.get("revoked_at"));
        assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'CONSENT_REVOKED' AND consent_id = ?",
                        consentId))
                .isEqualTo(1);
    }

    @Test
    void grantRecordsCatalogDataTypesCapsExpiryAndIsAudited() {
        Citizen c = linkedCitizen();
        UUID eligibility = requestAndGrant(c, "SCH_ELIGIBILITY_CHECK");
        UUID identity = requestAndGrant(c, "SCH_IDENTITY_REVIEW");

        Map<String, Object> row = consentRow(eligibility);
        assertThat(row.get("requester_id")).isEqualTo("SCHOLARSHIP");
        assertThat(row.get("purpose_code")).isEqualTo("SCH_ELIGIBILITY_CHECK");
        assertThat(strings(row.get("data_types"))).containsExactlyElementsOf(catalogDataTypes("SCH_ELIGIBILITY_CHECK"));
        assertThat(lifetime(row)).isEqualTo(Duration.ofDays(180));
        assertThat(lifetime(consentRow(identity))).isEqualTo(Duration.ofDays(30));

        Map<String, Object> granted = audit("CONSENT_GRANTED", eligibility);
        assertThat(granted.get("actor_type")).isEqualTo("CITIZEN");
        assertThat(granted.get("actor_id")).isEqualTo(c.subject());
        assertThat(granted.get("meta").toString()).contains("SCH_ELIGIBILITY_CHECK");
    }

    // ---- expiry ---------------------------------------------------------------------------

    @Test
    void expiredConsentIsRefusedAndAudited() throws InterruptedException {
        Citizen c = linkedCitizen();
        UUID consentId = requestAndGrant(c, "SCH_ELIGIBILITY_CHECK");
        AccessRequest fetch = incomeFetch(c.id(), "SCH_ELIGIBILITY_CHECK");
        assertThat(awaitGranted(fetch)).isInstanceOf(AccessDecision.Granted.class);

        jdbc.update("UPDATE consent_artifact SET valid_until = now() - interval '1 minute' WHERE id = ?", consentId);

        AccessDecision after = access.authorize(fetch);
        assertThat(after).isInstanceOf(AccessDecision.Denied.class);
        assertThat(((AccessDecision.Denied) after).reason()).isEqualTo(DenialReason.CONSENT_EXPIRED);
        assertThat(((AccessDecision.Denied) after).message()).doesNotContain("withdrew");
        assertThat(count("SELECT count(*) FROM audit.audit_entry "
                        + "WHERE action = 'GRANT_DENIED' AND reason = 'CONSENT_EXPIRED' AND consent_id = ?", consentId))
                .isEqualTo(1);
    }

    // ---- purpose codes --------------------------------------------------------------------

    @Test
    void unknownOrRetiredPurposeCodeIsRejected() {
        jdbc.update("INSERT INTO catalog_purpose (code, text, category_type, status, requester_department, data_categories) "
                + "VALUES ('SCH_RETIRED_TEST', 'retired', 'JOURNEY', 'RETIRED', 'SCHOLARSHIP', ARRAY['MARKS']) "
                + "ON CONFLICT DO NOTHING");
        Citizen c = linkedCitizen();
        assertThat(requestStatus(c, "SCH_NOT_A_CODE")).isEqualTo(400);
        assertThat(requestStatus(c, "SCH_RETIRED_TEST")).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM consent_request WHERE subject_citizen_id = ?", c.id())).isZero();
    }

    @Test
    void purposeRetiredBetweenRequestAndGrantCannotBeGranted() {
        String code = "SCH_RETIRE_LATER_" + Long.toHexString(System.nanoTime()).toUpperCase();
        jdbc.update("INSERT INTO catalog_purpose (code, text, category_type, status, requester_department, data_categories) "
                + "VALUES (?, 'soon retired', 'JOURNEY', 'ACTIVE', 'SCHOLARSHIP', ARRAY['MARKS'])", code);
        Citizen c = linkedCitizen();
        UUID requestId = request(c, code);
        jdbc.update("UPDATE catalog_purpose SET status = 'RETIRED' WHERE code = ?", code);
        int status = citizenHttp(c).post().uri(url("/api/consent/requests/" + requestId + "/grant"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("citizenId", c.id()))
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(status).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM consent_artifact WHERE subject_citizen_id = ?", c.id())).isZero();
    }

    @Test
    void bodyCannotSetRequesterDataTypesOrText() {
        Citizen c = linkedCitizen();
        Map<?, ?> created = citizenHttp(c).post().uri(url("/api/consent/requests"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "citizenId", c.id(),
                        "purposeCode", "SCH_BANK_VERIFY",
                        "requesterId", "MALLORY-DEPT",
                        "requester", "MALLORY-DEPT",
                        "purposeText", "harmless survey",
                        "labelEn", "harmless survey",
                        "dataTypes", List.of("AADHAAR_FULL"),
                        "categories", List.of("HEALTH_RECORD")))
                .retrieve().body(Map.class);
        Map<?, ?> artifact = citizenHttp(c).post().uri(url("/api/consent/requests/" + created.get("id") + "/grant"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "citizenId", c.id(),
                        "requesterId", "MALLORY-DEPT",
                        "dataTypes", List.of("AADHAAR_FULL"),
                        "validUntil", "2099-01-01T00:00:00Z"))
                .retrieve().body(Map.class);

        Map<String, Object> row = consentRow(UUID.fromString(artifact.get("id").toString()));
        assertThat(row.get("requester_id")).isEqualTo("SCHOLARSHIP");
        assertThat(row.get("purpose_text")).isEqualTo("Confirm the bank account your scholarship is paid into");
        assertThat(strings(row.get("data_types"))).containsExactly("BANK_IFSC", "BANK_ACCOUNT_CHECK_RESULT");
        assertThat(strings(row.get("data_categories"))).containsExactly("BANK_ACCOUNT");
        assertThat(lifetime(row)).isEqualTo(Duration.ofDays(365));
    }

    // ---- SCH_RENEWAL_CHECK: needs a prior-year award by the requesting department ----------

    @Test
    void renewalWithoutPriorAwardIsRejectedAndAudited() {
        Citizen c = linkedCitizen();
        // An award from THIS year is not a prior-year award.
        award(c.id(), "POST_MATRIC_SCHOLARSHIP", "now()");
        String officer = "off-renew-none-" + UUID.randomUUID();
        Map<String, Object> problem = TestHttp.as(TestTokens.officerOf(officer, "SCHOLARSHIP")).post()
                .uri(url("/api/consent/requests")).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", c.id(), "purposeCode", "SCH_RENEWAL_CHECK"))
                .exchange((rq, rs) -> {
                    assertThat(rs.getStatusCode().value()).isEqualTo(409);
                    return rs.bodyTo(Map.class);
                });
        assertThat(problem.get("reason")).isEqualTo("NO_PRIOR_AWARD");
        assertThat(requestStatus(c, "SCH_RENEWAL_CHECK")).as("citizen asking gets the same answer").isEqualTo(409);
        assertThat(count("SELECT count(*) FROM consent_request WHERE subject_citizen_id = ?", c.id())).isZero();

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_type, outcome, reason, meta::text AS meta FROM audit.audit_entry "
                        + "WHERE action = 'CONSENT_REQUEST_REFUSED' AND actor_id = ?",
                officer);
        assertThat(audit.get("actor_type")).isEqualTo("OFFICER");
        assertThat(audit.get("outcome")).isEqualTo("DENIED");
        assertThat(audit.get("reason")).isEqualTo("NO_PRIOR_AWARD");
        assertThat(audit.get("meta").toString()).contains("SCH_RENEWAL_CHECK");
    }

    @Test
    void renewalRequestedByDepartmentOtherThanTheAwardingOneIsRejectedAndAudited() {
        Citizen c = linkedCitizen();
        award(c.id(), "POST_MATRIC_SCHOLARSHIP", "now() - interval '1 year'"); // decided by SCHOLARSHIP
        String officer = "off-renew-rev-" + UUID.randomUUID();
        Map<String, Object> problem = TestHttp.as(TestTokens.officerOf(officer, "REVENUE")).post()
                .uri(url("/api/consent/requests")).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", c.id(), "purposeCode", "SCH_RENEWAL_CHECK", "requesterId", "SCHOLARSHIP"))
                .exchange((rq, rs) -> {
                    assertThat(rs.getStatusCode().value()).isEqualTo(403);
                    return rs.bodyTo(Map.class);
                });
        assertThat(problem.get("reason")).isEqualTo("NOT_AWARDING_DEPARTMENT");
        assertThat(count("SELECT count(*) FROM consent_request WHERE subject_citizen_id = ?", c.id())).isZero();

        Map<String, Object> refused = jdbc.queryForMap(
                "SELECT actor_type, department_id, reason, meta::text AS meta FROM audit.audit_entry "
                        + "WHERE action = 'CONSENT_REQUEST_REFUSED' AND actor_id = ?",
                officer);
        assertThat(refused.get("actor_type")).isEqualTo("OFFICER");
        assertThat(refused.get("department_id")).isEqualTo("REVENUE");
        assertThat(refused.get("reason")).isEqualTo("NOT_AWARDING_DEPARTMENT");
        assertThat(refused.get("meta").toString()).contains("SCH_RENEWAL_CHECK");
        assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'API_FORBIDDEN' AND actor_id = ?",
                        officer))
                .as("the 403 is also on the refused-call trail")
                .isEqualTo(1);
    }

    @Test
    void renewalWithPriorYearAwardFromTheRequesterIsAllowedAsItsOwnConsent() {
        Citizen c = linkedCitizen();
        award(c.id(), "POST_MATRIC_SCHOLARSHIP", "now() - interval '1 year'");
        UUID consentId = requestAndGrant(c, "SCH_RENEWAL_CHECK");
        Map<String, Object> row = consentRow(consentId);
        assertThat(row.get("purpose_code")).isEqualTo("SCH_RENEWAL_CHECK");
        assertThat(row.get("requester_id")).isEqualTo("SCHOLARSHIP");
        assertThat(lifetime(row)).isEqualTo(Duration.ofDays(365));
        assertThat(count("SELECT count(*) FROM consent_artifact WHERE subject_citizen_id = ?", c.id()))
                .as("never bundled: one purpose, one consent")
                .isEqualTo(1);
    }

    // ---- ownership ------------------------------------------------------------------------

    @Test
    void anotherCitizenCannotRevoke() {
        Citizen owner = linkedCitizen();
        Citizen other = linkedCitizen();
        UUID consentId = requestAndGrant(owner, "SCH_ELIGIBILITY_CHECK");

        int otherCitizen = citizenHttp(other).post().uri(url("/api/consent/me/" + consentId + "/revoke"))
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(otherCitizen)
                .as("token-bound route: someone else's consent is not found")
                .isEqualTo(404);
        assertThat(revokeViaBody(other, consentId, other.id())).as("own citizenId, foreign consent").isEqualTo(404);
        assertThat(revokeViaBody(other, consentId, owner.id())).as("foreign citizenId").isEqualTo(403);
        int staff = TestHttp.as(TestTokens.officer("off-p2-revoke")).post()
                .uri(url("/api/consent/me/" + consentId + "/revoke"))
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(staff)
                .as("staff cannot use the citizen route")
                .isEqualTo(403);

        assertThat(consentRow(consentId).get("status")).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'CONSENT_REVOKED' AND consent_id = ?",
                        consentId))
                .isZero();
    }

    // ---- helpers --------------------------------------------------------------------------

    record Citizen(UUID id, String subject) {}

    private Citizen linkedCitizen() {
        String subject = "cit-p2-" + UUID.randomUUID();
        UUID id = profiles.registerSelf(new ProfileDraft(
                "Sunita Pawar", "सुनीता", "Sunita", "Pawar", "Ramesh",
                LocalDate.of(2004, 6, 1), "DAY", "F", "98****11"), subject);
        linking.assertLink(id, "REVENUE", "RATION", "RC-p2-" + Long.toHexString(System.nanoTime()),
                com.samanvay.identity.api.AuthProof.digiLockerSandbox());
        return new Citizen(id, subject);
    }

    private RestClient citizenHttp(Citizen c) {
        return TestHttp.as(TestTokens.citizen(c.subject()));
    }

    private UUID request(Citizen c, String purpose) {
        Map<?, ?> created = citizenHttp(c).post().uri(url("/api/consent/requests"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", c.id(), "purposeCode", purpose))
                .retrieve().body(Map.class);
        return UUID.fromString(created.get("id").toString());
    }

    private UUID requestAndGrant(Citizen c, String purpose) {
        UUID requestId = request(c, purpose);
        Map<?, ?> artifact = citizenHttp(c).post().uri(url("/api/consent/requests/" + requestId + "/grant"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("citizenId", c.id()))
                .retrieve().body(Map.class);
        return UUID.fromString(artifact.get("id").toString());
    }

    private int requestStatus(Citizen c, String purpose) {
        return citizenHttp(c).post().uri(url("/api/consent/requests")).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", c.id(), "purposeCode", purpose))
                .exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private int revokeViaBody(Citizen caller, UUID consentId, UUID citizenId) {
        return citizenHttp(caller).post().uri(url("/api/consent/" + consentId + "/revoke"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("citizenId", citizenId, "reason", "x"))
                .exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private void award(UUID citizen, String journey, String decidedAtSql) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tracking_application (id, reference_no, citizen_id, journey_code, process_instance_id, "
                        + "status, submitted_at, closed_at) VALUES (?, ?, ?, ?, ?, 'APPROVED', " + decidedAtSql + ", "
                        + decidedAtSql + ")",
                id, "P2AWARD-" + id.toString().substring(0, 8), citizen, journey, "pi-" + id);
    }

    private AccessRequest incomeFetch(UUID citizen, String purpose) {
        return new AccessRequest(
                new SubjectRef(citizen),
                new RequesterRef("SCHOLARSHIP"),
                DataCategory.INCOME_CERTIFICATE,
                "REVENUE",
                "rev-income@1",
                PurposeCode.of(purpose),
                null,
                new PrincipalRef(PrincipalRef.Kind.OFFICER, "officer-p2-fetch"));
    }

    /** Registry pointers arrive asynchronously after linking. */
    private AccessDecision awaitGranted(AccessRequest request) throws InterruptedException {
        AccessDecision last = null;
        for (int i = 0; i < 100; i++) {
            last = access.authorize(request);
            if (last instanceof AccessDecision.Granted) {
                return last;
            }
            Thread.sleep(50);
        }
        return last;
    }

    private Map<String, Object> consentRow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM consent_artifact WHERE id = ?", id);
    }

    private Map<String, Object> audit(String action, UUID consentId) {
        return jdbc.queryForMap(
                "SELECT actor_type, actor_id, outcome, meta::text AS meta FROM audit.audit_entry "
                        + "WHERE action = ? AND consent_id = ?",
                action, consentId);
    }

    private List<String> catalogDataTypes(String code) {
        return jdbc.queryForObject("SELECT data_types FROM catalog_purpose WHERE code = ?",
                (rs, i) -> strings(rs.getArray(1)), code);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static Duration lifetime(Map<String, Object> row) {
        return Duration.between(
                ((Timestamp) row.get("valid_from")).toInstant(), ((Timestamp) row.get("valid_until")).toInstant());
    }

    private static List<String> strings(Object sqlArray) {
        try {
            return List.of((String[]) ((Array) sqlArray).getArray());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
