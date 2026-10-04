package com.samanvay.identity.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.samanvay.SamanvayApplication;
import com.samanvay.identity.api.CitizenActivity;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.internal.proof.DepartmentAssertionVerifier;
import com.samanvay.identity.internal.proof.VerifiedAssertion;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

/**
 * A department portal signs a citizen in at home and asks Samanvay to find or create the citizen, then to link the other departments
 * the journey needs. The signature check itself is covered by DepartmentAssertionVerifier tests, so here the verifier is replaced by a
 * stand-in that reads "dept|person|name|dob|state|nonce|jti" from the token; everything else (database, routes, roles, replay store,
 * login states) is real.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DepartmentIdentityIT.Config.class)
class DepartmentIdentityIT extends PostgresIntegrationTest {

    static final Set<UUID> BUSY = new HashSet<>();
    /** One entry per verifier call: was a database transaction open on the calling thread while it ran (it may fetch keys over the network)? */
    static final java.util.List<Boolean> TX_OPEN_DURING_VERIFY = new java.util.concurrent.CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Config {
        @Bean
        CitizenActivity testActivity() {
            return BUSY::contains;
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    DepartmentAssertionVerifier verifier;

    final String edu = TestTokens.departmentOf("dept-education-it", "EDUCATION");
    final String agri = TestTokens.departmentOf("dept-agriculture-it", "AGRICULTURE");

    @BeforeEach
    void setUp() {
        BUSY.clear();
        TX_OPEN_DURING_VERIFY.clear();
        for (String[] d : new String[][] {{"EDUCATION", "EDU_STUDENT_ID", "http://edu.test"}, {"AGRICULTURE", "AGRI_FARMER_ID", "http://agri.test"},
                {"REVENUE", "REVENUE_PERSON_ID", "http://rev.test"}}) {
            jdbc.update("UPDATE catalog_department SET identity_spec = ?::jsonb WHERE code = ?",
                    "{\"personIdType\":\"" + d[1] + "\",\"loginUrl\":\"" + d[2] + "/login\",\"jwksUrl\":\"" + d[2] + "/jwks\",\"assertionIssuer\":\"dept:" + d[0] + "\"}", d[0]);
        }
        when(verifier.verify(anyString(), anyString())).thenAnswer(inv -> {
            TX_OPEN_DURING_VERIFY.add(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            String[] p = inv.getArgument(0, String.class).split("\\|", -1);
            if (p.length != 7 || !p[0].equals(inv.getArgument(1))) {
                throw new LinkProofInvalidException();
            }
            return new VerifiedAssertion(p[0], idType(p[0]), p[1], p[6], blank(p[4]), blank(p[5]),
                    blank(p[2]), p[3].isEmpty() ? null : LocalDate.parse(p[3]), Instant.now().plusSeconds(200));
        });
    }

    static String idType(String dept) {
        return switch (dept) {
            case "EDUCATION" -> "EDU_STUDENT_ID";
            case "AGRICULTURE" -> "AGRI_FARMER_ID";
            default -> "REVENUE_PERSON_ID";
        };
    }

    static String blank(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static String assertion(String dept, String person, String name, String dob, String state, String nonce) {
        return dept + "|" + person + "|" + name + "|" + dob + "|" + state + "|" + nonce + "|" + UUID.randomUUID();
    }

    RestClient as(String token) {
        return TestHttp.as(token);
    }

    Map<?, ?> post(String token, String path, Object body) {
        return as(token).post().uri("http://localhost:" + port + path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
    }

    int status(String token, String path, Object body) {
        return as(token).post().uri("http://localhost:" + port + path).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    UUID resolve(String token, String assertion) {
        return UUID.fromString((String) post(token, "/api/department/citizens/resolve", Map.of("assertion", assertion)).get("citizenId"));
    }

    String person() {
        return "P-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // --- resolve -------------------------------------------------------------------------------------------------

    @Test
    void the_first_sign_in_creates_the_citizen_and_the_next_one_finds_the_same_citizen() {
        String p = person();
        Map<?, ?> first = post(edu, "/api/department/citizens/resolve", Map.of("assertion", assertion("EDUCATION", p, "Meera Kulkarni", "2004-03-09", "", "")));
        Map<?, ?> second = post(edu, "/api/department/citizens/resolve", Map.of("assertion", assertion("EDUCATION", p, "Meera Kulkarni", "2004-03-09", "", "")));
        assertThat(first.get("created")).isEqualTo(true);
        assertThat(second.get("created")).isEqualTo(false);
        assertThat(second.get("citizenId")).isEqualTo(first.get("citizenId"));
        assertThat(jdbc.queryForObject("SELECT name_latin FROM identity_profile WHERE citizen_id = ?::uuid", String.class, first.get("citizenId")))
                .isEqualTo("Meera Kulkarni");
        assertThat(jdbc.queryForObject("SELECT dob::text FROM identity_profile WHERE citizen_id = ?::uuid", String.class, first.get("citizenId")))
                .isEqualTo("2004-03-09");
    }

    @Test
    void a_department_that_does_not_say_the_birth_date_still_gets_a_citizen() {
        UUID id = resolve(agri, assertion("AGRICULTURE", person(), "", "", "", ""));
        assertThat(jdbc.queryForObject("SELECT dob_precision FROM identity_profile WHERE citizen_id = ?", String.class, id)).isEqualTo("YEAR");
    }

    @Test
    void a_replayed_assertion_is_refused() {
        String a = assertion("EDUCATION", person(), "Asha Patil", "2003-01-01", "", "");
        resolve(edu, a);
        assertThat(status(edu, "/api/department/citizens/resolve", Map.of("assertion", a))).isEqualTo(401);
    }

    @Test
    void a_department_cannot_resolve_with_another_departments_assertion() {
        assertThat(status(edu, "/api/department/citizens/resolve", Map.of("assertion", assertion("REVENUE", person(), "X Y", "2000-01-01", "", "")))).isEqualTo(401);
    }

    @Test
    void only_department_clients_may_call() {
        Map<String, String> body = Map.of("assertion", assertion("EDUCATION", person(), "A B", "2000-01-01", "", ""));
        for (String token : new String[] {TestTokens.citizen("c"), TestTokens.officer("o"), TestTokens.admin("a")}) {
            assertThat(status(token, "/api/department/citizens/resolve", body)).isEqualTo(403);
        }
        int anon = TestHttp.anonymous().post().uri("http://localhost:" + port + "/api/department/citizens/resolve")
                .contentType(MediaType.APPLICATION_JSON).body(body).exchange((r, s) -> s.getStatusCode().value());
        assertThat(anon).isEqualTo(401);
    }

    // --- links ---------------------------------------------------------------------------------------------------

    @Test
    void the_return_address_must_be_on_the_requesting_departments_own_host() {
        UUID citizen = resolve(edu, assertion("EDUCATION", person(), "Ravi Joshi", "2002-05-05", "", ""));
        Map<?, ?> ok = post(edu, "/api/department/links/start",
                Map.of("citizenId", citizen, "departmentCode", "REVENUE", "returnTo", "http://edu.test/portal/callback"));
        String url = (String) ok.get("loginUrl");
        assertThat(URI.create(url).getHost()).isEqualTo("rev.test");
        assertThat(url).contains("return_to=").contains("state=").contains("nonce=");
        assertThat(status(edu, "/api/department/links/start",
                Map.of("citizenId", citizen, "departmentCode", "REVENUE", "returnTo", "http://evil.test/portal/callback"))).isEqualTo(400);
        assertThat(status(edu, "/api/department/links/start",
                Map.of("citizenId", citizen, "departmentCode", "REVENUE", "returnTo", "http://rev.test/portal/callback"))).isEqualTo(400);
    }

    @Test
    void a_department_can_only_act_for_a_citizen_who_signed_in_with_it() {
        UUID agriCitizen = resolve(agri, assertion("AGRICULTURE", person(), "Sunita More", "1990-01-01", "", ""));
        assertThat(status(edu, "/api/department/links/start",
                Map.of("citizenId", agriCitizen, "departmentCode", "REVENUE", "returnTo", "http://edu.test/portal/callback"))).isEqualTo(404);
        assertThat(status(edu, "/api/department/links",
                Map.of("citizenId", agriCitizen, "departmentCode", "REVENUE", "assertion", "x"))).isEqualTo(404);
    }

    @Test
    void linking_another_department_saves_the_link_once_the_login_state_matches() {
        UUID citizen = resolve(edu, assertion("EDUCATION", person(), "Kiran Desai", "2001-07-07", "", ""));
        String[] sn = startLogin(edu, citizen, "REVENUE");
        String rv = person();
        Map<?, ?> linked = post(edu, "/api/department/links",
                Map.of("citizenId", citizen, "departmentCode", "REVENUE", "assertion", assertion("REVENUE", rv, "", "", sn[0], sn[1])));
        assertThat(linked.get("citizenId")).isEqualTo(citizen.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_link WHERE citizen_id = ? AND department_code = 'REVENUE' AND status = 'ACTIVE'", Integer.class, citizen)).isEqualTo(1);
        // the state was used up: the same assertion cannot be replayed
        assertThat(status(edu, "/api/department/links",
                Map.of("citizenId", citizen, "departmentCode", "REVENUE", "assertion", assertion("REVENUE", rv, "", "", sn[0], sn[1])))).isEqualTo(401);
    }

    // --- one human, two home departments --------------------------------------------------------------------------

    @Test
    void the_same_person_signing_in_at_two_home_departments_ends_up_as_one_citizen() {
        String eduPerson = person();
        String agriPerson = person();
        UUID eduCitizen = resolve(edu, assertion("EDUCATION", eduPerson, "Meera Kulkarni", "2004-03-09", "", ""));
        UUID agriCitizen = resolve(agri, assertion("AGRICULTURE", agriPerson, "Meera Kulkarni", "2004-03-09", "", ""));
        assertThat(agriCitizen).isNotEqualTo(eduCitizen);

        String[] sn = startLogin(agri, agriCitizen, "EDUCATION");
        Map<?, ?> linked = post(agri, "/api/department/links",
                Map.of("citizenId", agriCitizen, "departmentCode", "EDUCATION", "assertion", assertion("EDUCATION", eduPerson, "", "", sn[0], sn[1])));

        assertThat(linked.get("citizenId")).isEqualTo(eduCitizen.toString());
        assertThat(jdbc.queryForObject("SELECT status FROM identity_citizen WHERE id = ?", String.class, agriCitizen)).isEqualTo("MERGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_link WHERE citizen_id = ? AND status = 'ACTIVE'", Integer.class, eduCitizen)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_entry WHERE action = 'CITIZEN_MERGED' AND resource = ?", Integer.class, agriCitizen.toString())).isEqualTo(1);
        // signing in at Agriculture again now finds the surviving citizen
        assertThat(resolve(agri, assertion("AGRICULTURE", agriPerson, "Meera Kulkarni", "2004-03-09", "", ""))).isEqualTo(eduCitizen);
    }

    @Test
    void a_citizen_who_already_has_activity_is_never_merged() {
        String eduPerson = person();
        UUID eduCitizen = resolve(edu, assertion("EDUCATION", eduPerson, "Neha Rao", "2000-02-02", "", ""));
        UUID agriCitizen = resolve(agri, assertion("AGRICULTURE", person(), "Neha Rao", "2000-02-02", "", ""));
        BUSY.add(agriCitizen);

        String[] sn = startLogin(agri, agriCitizen, "EDUCATION");
        int status = status(agri, "/api/department/links",
                Map.of("citizenId", agriCitizen, "departmentCode", "EDUCATION", "assertion", assertion("EDUCATION", eduPerson, "", "", sn[0], sn[1])));

        assertThat(status).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM identity_citizen WHERE id = ?", String.class, agriCitizen)).isEqualTo("ACTIVE");
        assertThat(eduCitizen).isNotNull();
    }

    // --- integrity ---------------------------------------------------------------------------------------------------

    UUID linked(String token, UUID citizen, String dept, String person) {
        String[] sn = startLogin(token, citizen, dept);
        return UUID.fromString((String) post(token, "/api/department/links",
                Map.of("citizenId", citizen, "departmentCode", dept, "assertion", assertion(dept, person, "", "", sn[0], sn[1]))).get("citizenId"));
    }

    int linkStatus(String token, UUID citizen, String dept, String person) {
        String[] sn = startLogin(token, citizen, dept);
        return status(token, "/api/department/links",
                Map.of("citizenId", citizen, "departmentCode", dept, "assertion", assertion(dept, person, "", "", sn[0], sn[1])));
    }

    String activeToken(UUID citizen, String dept) {
        return jdbc.queryForObject("SELECT local_id_token FROM identity_link WHERE citizen_id = ? AND department_code = ? AND status = 'ACTIVE'",
                String.class, citizen, dept);
    }

    @Test
    void linking_a_different_person_where_the_citizen_is_already_linked_is_a_conflict_not_a_success() {
        UUID citizen = resolve(edu, assertion("EDUCATION", person(), "Kiran Desai", "2001-07-07", "", ""));
        String first = person();
        linked(edu, citizen, "REVENUE", first);

        assertThat(linkStatus(edu, citizen, "REVENUE", person())).isEqualTo(409);
        assertThat(activeToken(citizen, "REVENUE")).isEqualTo(first);
        assertThat(linked(edu, citizen, "REVENUE", first)).as("the same person again is fine").isEqualTo(citizen);
    }

    @Test
    void a_revoked_link_does_not_stop_the_person_signing_in_again() {
        String p = person();
        UUID first = resolve(edu, assertion("EDUCATION", p, "Asha Patil", "2003-01-01", "", ""));
        jdbc.update("UPDATE identity_link SET status = 'REVOKED' WHERE citizen_id = ?", first);

        Map<?, ?> again = post(edu, "/api/department/citizens/resolve", Map.of("assertion", assertion("EDUCATION", p, "Asha Patil", "2003-01-01", "", "")));

        assertThat(again.get("created")).isEqualTo(true);
        assertThat(again.get("citizenId")).isNotEqualTo(first.toString());
        assertThat(activeToken(UUID.fromString((String) again.get("citizenId")), "EDUCATION")).isEqualTo(p);
    }

    @Test
    void a_suspended_citizen_is_refused_when_signing_in_and_when_acted_for() {
        String p = person();
        UUID citizen = resolve(edu, assertion("EDUCATION", p, "Neha Rao", "2000-02-02", "", ""));
        jdbc.update("UPDATE identity_citizen SET status = 'SUSPENDED' WHERE id = ?", citizen);

        assertThat(status(edu, "/api/department/citizens/resolve", Map.of("assertion", assertion("EDUCATION", p, "Neha Rao", "2000-02-02", "", "")))).isEqualTo(403);
        assertThat(status(edu, "/api/department/links/start",
                Map.of("citizenId", citizen, "departmentCode", "REVENUE", "returnTo", "http://edu.test/portal/callback"))).isEqualTo(404);
    }

    @Test
    void a_citizen_is_never_merged_into_a_survivor_that_is_not_active() {
        String eduPerson = person();
        UUID eduCitizen = resolve(edu, assertion("EDUCATION", eduPerson, "Meera Kulkarni", "2004-03-09", "", ""));
        UUID agriCitizen = resolve(agri, assertion("AGRICULTURE", person(), "Meera Kulkarni", "2004-03-09", "", ""));
        jdbc.update("UPDATE identity_citizen SET status = 'SUSPENDED' WHERE id = ?", eduCitizen);

        assertThat(linkStatus(agri, agriCitizen, "EDUCATION", eduPerson)).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM identity_citizen WHERE id = ?", String.class, agriCitizen)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_link WHERE citizen_id = ? AND status = 'ACTIVE'", Integer.class, agriCitizen)).isEqualTo(1);
    }

    @Test
    void after_a_merge_the_surviving_citizen_can_locate_the_department_that_moved() {
        String eduPerson = person();
        String revPerson = person();
        UUID eduCitizen = resolve(edu, assertion("EDUCATION", eduPerson, "Ravi Joshi", "2002-05-05", "", ""));
        String rev = TestTokens.departmentOf("dept-revenue-it", "REVENUE");
        UUID revCitizen = resolve(rev, assertion("REVENUE", revPerson, "Ravi Joshi", "2002-05-05", "", ""));
        assertThat(pointers(revCitizen, "EDUCATION")).as("the survivor has no Education pointer before the merge").isZero();

        String[] sn = startLogin(edu, eduCitizen, "REVENUE");
        Map<?, ?> merged = post(edu, "/api/department/links",
                Map.of("citizenId", eduCitizen, "departmentCode", "REVENUE", "assertion", assertion("REVENUE", revPerson, "", "", sn[0], sn[1])));

        assertThat(merged.get("citizenId")).isEqualTo(revCitizen.toString());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(pointers(revCitizen, "EDUCATION")).isPositive());
    }

    int pointers(UUID citizen, String dept) {
        return jdbc.queryForObject("SELECT count(*) FROM registry_pointer WHERE subject_id = ? AND department_code = ? AND status = 'AVAILABLE'",
                Integer.class, citizen, dept);
    }

    @Test
    void the_assertion_is_verified_before_any_database_transaction_opens() {
        UUID citizen = resolve(edu, assertion("EDUCATION", person(), "Kiran Desai", "2001-07-07", "", ""));
        linked(edu, citizen, "REVENUE", person());
        assertThat(TX_OPEN_DURING_VERIFY).hasSize(2).containsOnly(false);
    }

    @Test
    void simultaneous_first_sign_ins_of_one_person_make_one_citizen_and_never_a_server_error() throws Exception {
        String p = person();
        int callers = 4;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        try {
            java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return status(edu, "/api/department/citizens/resolve", Map.of("assertion", assertion("EDUCATION", p, "Asha Patil", "2003-01-01", "", "")));
                }));
            }
            go.countDown();
            java.util.List<Integer> statuses = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).contains(200).isSubsetOf(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_link WHERE department_code = 'EDUCATION' AND local_id_token = ? AND status = 'ACTIVE'",
                Integer.class, p)).isEqualTo(1);
    }

    // --- audit ---------------------------------------------------------------------------------------------------

    int audited(String action, String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM audit.audit_entry WHERE action = '" + action + "' AND " + where, Integer.class, args);
    }

    @Test
    void a_new_link_is_audited() {
        UUID citizen = resolve(edu, assertion("EDUCATION", person(), "Kiran Desai", "2001-07-07", "", ""));
        linked(edu, citizen, "REVENUE", person());
        assertThat(audited("CITIZEN_LINKED", "subject_id = ? AND department_id = 'REVENUE' AND outcome = 'ALLOWED'", citizen.toString())).isEqualTo(1);
    }

    @Test
    void an_assertion_that_does_not_verify_is_audited_as_a_denial_without_personal_data() {
        int before = audited("DEPARTMENT_ASSERTION_REFUSED", "department_id = 'EDUCATION' AND outcome = 'DENIED'");
        assertThat(status(edu, "/api/department/citizens/resolve", Map.of("assertion", "not-an-assertion"))).isEqualTo(401);
        assertThat(status(edu, "/api/department/citizens/resolve", Map.of("assertion", assertion("REVENUE", "RV-SECRET-ID", "X Y", "2000-01-01", "", "")))).isEqualTo(401);
        assertThat(audited("DEPARTMENT_ASSERTION_REFUSED", "department_id = 'EDUCATION' AND outcome = 'DENIED'")).isEqualTo(before + 2);
        assertThat(audited("DEPARTMENT_ASSERTION_REFUSED", "meta::text LIKE '%RV-SECRET-ID%' OR reason LIKE '%RV-SECRET-ID%'")).isZero();
    }

    String[] startLogin(String token, UUID citizen, String dept) {
        String portal = token.equals(edu) ? "http://edu.test" : "http://agri.test";
        String url = (String) post(token, "/api/department/links/start",
                Map.of("citizenId", citizen, "departmentCode", dept, "returnTo", portal + "/portal/callback")).get("loginUrl");
        Map<String, String> q = new java.util.HashMap<>();
        for (String kv : URI.create(url).getRawQuery().split("&")) {
            String[] p = kv.split("=", 2);
            q.put(p[0], java.net.URLDecoder.decode(p[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        return new String[] {q.get("state"), q.get("nonce")};
    }
}
