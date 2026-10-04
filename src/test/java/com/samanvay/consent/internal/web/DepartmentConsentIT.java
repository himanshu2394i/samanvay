package com.samanvay.consent.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.samanvay.SamanvayApplication;
import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.CitizenActivity;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The department portal shows the citizen the exact consent wording, the citizen confirms with the one-time code on the portal, and the
 * department signs a statement of that with the key pinned from its signed manifest. Samanvay grants only a statement that is signed by
 * that key, bound to a request it issued (nonce, citizen, purpose, categories), recent and unused, and keeps it as evidence.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DepartmentConsentIT extends PostgresIntegrationTest {

    static final String JOURNEY = "POST_MATRIC_SCHOLARSHIP";
    static final String DEPT = "SCHOLARSHIP";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityLinking linking;

    @Autowired
    List<CitizenActivity> activities;

    ECKey key;
    String token;
    UUID citizen;

    @BeforeEach
    void setUp() throws JOSEException {
        key = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        jdbc.update("UPDATE catalog_department SET manifest_key_thumbprint = ? WHERE code = ?", key.computeThumbprint().toString(), DEPT);
        token = TestTokens.departmentOf("dept-scholarship-it", DEPT);
        citizen = profiles.register(new ProfileDraft("Meera Kulkarni", null, null, null, null, LocalDate.of(2004, 3, 9), "DAY", null, null));
        linking.assertLink(citizen, DEPT, "SCHOLARSHIP_ID", "SC-" + UUID.randomUUID().toString().substring(0, 8), AuthProof.localIdOtpDemo());
    }

    Map<?, ?> wording() {
        return TestHttp.as(token).post().uri("http://localhost:" + port + "/api/department/consents/requests")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("citizenId", citizen, "journeyCode", JOURNEY)).retrieve().body(Map.class);
    }

    JWTClaimsSet.Builder claims(Map<?, ?> w) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer("dept:" + DEPT).claim("dept_code", DEPT).jwtID(UUID.randomUUID().toString())
                .claim("citizen_id", citizen.toString()).claim("request_id", w.get("requestId")).claim("purpose", w.get("purposeCode"))
                .claim("nonce", w.get("nonce")).claim("categories", w.get("categories")).claim("method", "dept-otp")
                .claim("confirmed_at", now.getEpochSecond()).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(240)));
    }

    String sign(JWTClaimsSet.Builder c, ECKey signer) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("samanvay-consent"))
                .jwk(signer.toPublicJWK()).build(), c.build());
        jwt.sign(new ECDSASigner(signer));
        return jwt.serialize();
    }

    record Reply(int status, String body) {}

    Reply submit(String statement) {
        return TestHttp.as(token).post().uri("http://localhost:" + port + "/api/department/consents")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("statement", statement))
                .exchange((rq, rs) -> new Reply(rs.getStatusCode().value(), new String(rs.getBody().readAllBytes())));
    }

    static String evidence(String column) {
        return "SELECT e." + column + " FROM consent_evidence e JOIN consent_artifact a ON a.id = e.consent_id WHERE a.subject_citizen_id = ?";
    }

    int consentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM consent_artifact WHERE subject_citizen_id = ?", Integer.class, citizen);
    }

    @Test
    void asking_for_consent_is_audited_with_who_asked_and_for_which_purpose() {
        Map<?, ?> w = wording();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_entry WHERE action = 'CONSENT_REQUESTED' AND subject_id = ?"
                + " AND department_id = ? AND outcome = 'ALLOWED' AND actor_type = 'DEPARTMENT' AND meta->>'requestId' = ?"
                + " AND meta->>'purpose' = ?", Integer.class, citizen.toString(), DEPT, w.get("requestId"), w.get("purposeCode"))).isEqualTo(1);
    }

    @Test
    void the_request_returns_the_exact_wording_and_a_one_time_nonce() {
        Map<?, ?> w = wording();
        assertThat((String) w.get("purposeText")).isNotBlank();
        assertThat((List<?>) w.get("categories")).isNotEmpty();
        assertThat((List<?>) w.get("providers")).isNotEmpty();
        assertThat((String) w.get("nonce")).hasSizeGreaterThan(20);
        assertThat(w.get("validityDays")).isNotNull();
    }

    @Test
    void a_statement_signed_with_the_pinned_key_grants_the_consent_and_is_kept_as_evidence() throws Exception {
        Map<?, ?> w = wording();
        String statement = sign(claims(w), key);
        Reply r = submit(statement);
        assertThat(r.status()).isEqualTo(200);
        assertThat(consentCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(evidence("statement"), String.class, citizen)).isEqualTo(statement);
        assertThat(jdbc.queryForObject(evidence("key_thumbprint"), String.class, citizen)).isEqualTo(key.computeThumbprint().toString());
        assertThat(jdbc.queryForObject("SELECT status FROM consent_request WHERE id = ?::uuid", String.class, w.get("requestId"))).isEqualTo("GRANTED");
    }

    @Test
    void the_same_statement_cannot_be_used_twice() throws Exception {
        String statement = sign(claims(wording()), key);
        assertThat(submit(statement).status()).isEqualTo(200);
        assertThat(submit(statement).status()).isEqualTo(400);
        assertThat(consentCount()).isEqualTo(1);
    }

    @Test
    void every_refusal_looks_the_same_and_grants_nothing() throws Exception {
        Map<?, ?> w = wording();
        ECKey other = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        List<String> bodies = new ArrayList<>();
        for (String bad : new String[] {
            sign(claims(w), other), // not the pinned key
            sign(claims(w).claim("dept_code", "REVENUE"), key), // claims to be another department
            sign(claims(w).claim("nonce", "not-the-nonce"), key),
            sign(claims(w).claim("citizen_id", UUID.randomUUID().toString()), key),
            sign(claims(w).claim("request_id", UUID.randomUUID().toString()), key),
            sign(claims(w).claim("categories", List.of("MARKS")), key),
            sign(claims(w).claim("purpose", "SOMETHING_ELSE"), key),
            sign(claims(w).expirationTime(Date.from(Instant.now().minusSeconds(900))).issueTime(Date.from(Instant.now().minusSeconds(1200))), key),
            sign(claims(w).claim("confirmed_at", Instant.now().minusSeconds(3600).getEpochSecond()), key),
            sign(claims(w).claim("method", "something-else"), key),
            "not.a.jws"}) {
            Reply r = submit(bad);
            assertThat(r.status()).as("refused: %s", bad).isEqualTo(400);
            bodies.add(r.body().replaceAll("\"instance\":\"[^\"]*\"", ""));
        }
        assertThat(bodies).hasSize(11).allMatch(b -> b.equals(bodies.get(0)));
        assertThat(consentCount()).isZero();
        // the request is still open: the right statement still works
        assertThat(submit(sign(claims(w), key)).status()).isEqualTo(200);
    }

    @Test
    void only_the_department_that_runs_the_journey_may_ask_for_consent_for_it() {
        String education = TestTokens.departmentOf("dept-education-it", "EDUCATION");
        int status = TestHttp.as(education).post().uri("http://localhost:" + port + "/api/department/consents/requests")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("citizenId", citizen, "journeyCode", JOURNEY))
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(status).isEqualTo(403);
    }

    @Test
    void a_citizen_who_did_not_sign_in_with_the_department_is_not_found() {
        UUID stranger = profiles.register(new ProfileDraft("Other Person", null, null, null, null, LocalDate.of(1999, 1, 1), "DAY", null, null));
        int status = TestHttp.as(token).post().uri("http://localhost:" + port + "/api/department/consents/requests")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("citizenId", stranger, "journeyCode", JOURNEY))
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(status).isEqualTo(404);
    }

    // --- revoke from the department's portal -----------------------------------------------------------------------

    UUID grantedConsent() throws Exception {
        assertThat(submit(sign(claims(wording()), key)).status()).isEqualTo(200);
        return jdbc.queryForObject("SELECT id FROM consent_artifact WHERE subject_citizen_id = ?", UUID.class, citizen);
    }

    int revoke(String bearer, UUID consent) {
        return TestHttp.as(bearer).post().uri("http://localhost:" + port + "/api/department/consents/" + consent + "/revoke")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "citizen withdrew on the portal"))
                .exchange((rq, rs) -> rs.getStatusCode().value());
    }

    String consentStatus(UUID consent) {
        return jdbc.queryForObject("SELECT status FROM consent_artifact WHERE id = ?", String.class, consent);
    }

    @Test
    void the_department_that_collected_a_consent_can_revoke_it_and_it_is_audited() throws Exception {
        UUID consent = grantedConsent();
        assertThat(revoke(token, consent)).isEqualTo(200);
        assertThat(consentStatus(consent)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT revoked_by FROM consent_artifact WHERE id = ?", String.class, consent)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_entry WHERE action = 'CONSENT_REVOKED' AND actor_type = 'DEPARTMENT'"
                + " AND consent_id = ?", Integer.class, consent)).isEqualTo(1);
        assertThat(revoke(token, consent)).as("revoking again is harmless").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_entry WHERE action = 'CONSENT_REVOKED' AND consent_id = ?",
                Integer.class, consent)).isEqualTo(1);
    }

    @Test
    void another_department_cannot_revoke_it_and_learns_nothing() throws Exception {
        UUID consent = grantedConsent();
        String education = TestTokens.departmentOf("dept-education-it", "EDUCATION");
        assertThat(revoke(education, consent)).isEqualTo(404);
        assertThat(revoke(token, UUID.randomUUID())).as("no such consent looks the same").isEqualTo(404);
        assertThat(consentStatus(consent)).isEqualTo("ACTIVE");
    }

    @Test
    void the_requesting_department_cannot_revoke_once_the_citizen_is_no_longer_linked_to_it() throws Exception {
        UUID consent = grantedConsent();
        jdbc.update("UPDATE identity_link SET status = 'REVOKED' WHERE citizen_id = ? AND department_code = ?", citizen, DEPT);
        assertThat(revoke(token, consent)).isEqualTo(404);
        assertThat(consentStatus(consent)).isEqualTo("ACTIVE");
    }

    @Test
    void only_department_clients_may_use_the_department_revoke_route() throws Exception {
        UUID consent = grantedConsent();
        for (String other : new String[] {TestTokens.citizen("c"), TestTokens.officer("o"), TestTokens.admin("a")}) {
            assertThat(revoke(other, consent)).isEqualTo(403);
        }
        assertThat(consentStatus(consent)).isEqualTo("ACTIVE");
    }

    @Test
    void a_citizen_with_a_consent_request_counts_as_having_activity_for_merging() {
        assertThat(activities.stream().anyMatch(a -> a.hasActivity(citizen))).isFalse();
        wording();
        assertThat(activities.stream().anyMatch(a -> a.hasActivity(citizen))).isTrue();
    }
}
