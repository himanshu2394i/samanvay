package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.Capability;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorStatus;
import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.FieldMapping;
import com.samanvay.catalog.api.ManifestOnboarding;
import com.samanvay.catalog.api.OnboardRequest;
import com.samanvay.catalog.api.OnboardingPlan;
import com.samanvay.catalog.api.OnboardingResult;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.InvalidRequestException;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Onboarding a department in one go from its published manifest, against the real database and the REAL manifests the four
 * departments publish (served from an in-process HTTP server). Each test renames the department to a unique code so tests
 * cannot pollute each other (the database is shared across test classes).
 */
@SpringBootTest(classes = SamanvayApplication.class,
        properties = {"samanvay.catalog.allowed-private-hosts=127.0.0.1,localhost", "samanvay.catalog.allow-unsigned-manifests=true"})
class ManifestOnboardingIT extends PostgresIntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    ManifestOnboarding onboarding;

    @Autowired
    CatalogOnboarding wizard;

    @Autowired
    com.samanvay.catalog.api.JourneyWrite journeyWrite;

    @Autowired
    ConnectorCatalog catalog;

    @Autowired
    DepartmentCatalog departments;

    @Autowired
    JdbcClient jdbc;

    HttpServer server;
    volatile String served;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Serves a department's real manifest renamed to a unique department; returns its base URL. */
    String serve(String fixture, String code) throws IOException {
        served = unique(fixture, code);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/samanvay/manifest", ex -> {
            byte[] out = served.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static String unique(String fixture, String code) throws IOException {
        try (var in = ManifestOnboardingIT.class.getResourceAsStream("/manifests/" + fixture + ".json")) {
            ObjectNode m = (ObjectNode) JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            String old = m.get("department").get("code").asString();
            ((ObjectNode) m.get("department")).put("code", code);
            // The test server answers on 127.0.0.1; a department's login and keys must be on the same host as its manifest.
            if (m.get("identity") instanceof ObjectNode id) {
                for (String k : new String[] {"loginUrl", "jwksUrl"}) {
                    id.put(k, id.get(k).asString().replace("localhost", "127.0.0.1"));
                }
            }
            for (JsonNode j : m.get("journeys")) {
                ObjectNode jo = (ObjectNode) j;
                jo.put("code", jo.get("code").asString() + "_" + code);
                if (old.equals(jo.get("requester").asString())) {
                    jo.put("requester", code);
                }
                for (JsonNode rc : jo.get("requiredCategories")) {
                    if (old.equals(rc.get("department").asString())) {
                        ((ObjectNode) rc).put("department", code);
                    }
                }
            }
            return m.toString();
        }
    }

    static String code(String prefix) {
        return prefix + System.nanoTime();
    }

    OnboardRequest all(OnboardingPlan plan, String baseUrl) {
        return new OnboardRequest(baseUrl, plan.manifestDigest(), plan.documents().stream().map(OnboardingPlan.DocumentPlan::category).toList(), true, Map.of());
    }

    int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    // --- plan ------------------------------------------------------------------------------------------------

    @Test
    void a_plan_describes_the_department_and_changes_nothing() throws IOException {
        String dept = code("DBTP");
        String url = serve("dbt", dept);
        int[] before = {count("catalog_department"), count("catalog_data_source"), count("catalog_connector"), count("catalog_journey"), count("catalog_mapping")};

        OnboardingPlan plan = onboarding.plan(url);

        assertThat(plan.departmentCode()).isEqualTo(dept);
        assertThat(plan.departmentExists()).isFalse();
        assertThat(plan.manifestDigest()).hasSize(64);
        assertThat(plan.documents()).hasSize(1);
        assertThat(plan.documents().get(0).ready()).isTrue();
        assertThat(plan.documents().get(0).centralSchemaRef()).isEqualTo("Credential/BankAccount@1");
        assertThat(plan.journeys()).hasSize(1);
        assertThat(plan.pendingSteps()).anyMatch(s -> s.kind().equals("PROVISION_SECRET"));
        assertThat(new int[] {count("catalog_department"), count("catalog_data_source"), count("catalog_connector"), count("catalog_journey"), count("catalog_mapping")})
                .containsExactly(before);
    }

    // --- onboard ---------------------------------------------------------------------------------------------

    @Test
    void onboarding_creates_the_department_source_connector_mapping_and_journey_as_drafts() throws IOException {
        String dept = code("DBTO");
        String url = serve("dbt", dept);
        OnboardingPlan plan = onboarding.plan(url);

        OnboardingResult r = onboarding.onboard(all(plan, url));

        assertThat(r.departmentCode()).isEqualTo(dept);
        String sourceCode = dept.toLowerCase() + "-rest";
        assertThat(r.dataSources()).containsExactly(sourceCode);
        assertThat(r.connectorRefs()).containsExactly(dept.toLowerCase() + "-bank-account@1");
        assertThat(r.mappingRefs()).containsExactly("map-" + dept.toLowerCase() + "-bank-account@1");
        assertThat(r.journeysCreated()).hasSize(1);

        // the department keeps its login description and the digest of what was reviewed
        assertThat(departments.identity(dept)).hasValueSatisfying(id -> {
            assertThat(id.personIdType()).isEqualTo("DBT_ID");
            assertThat(id.assertionIssuer()).isEqualTo("dept:DBT");
        });
        assertThat(jdbc.sql("SELECT manifest_digest FROM catalog_department WHERE code = :c").param("c", dept).query(String.class).single())
                .isEqualTo(plan.manifestDigest());

        // the data source carries the manifest's auth, not any secret
        var connector = catalog.byRef(r.connectorRefs().get(0));
        var source = catalog.dataSourceFor(connector);
        assertThat(source.code()).isEqualTo(sourceCode);
        assertThat(source.protocol()).isEqualTo("REST");
        assertThat(source.authType()).isEqualTo("OAUTH2_CLIENT");
        assertThat(source.authConfigRef()).isEqualTo("secret:" + sourceCode);
        assertThat(JSON.readTree(source.authSpecJson()).get("tokenUrl").asString()).isEqualTo("/oauth/token");
        assertThat(source.authSpecJson()).doesNotContain("dbt-dev-secret");

        // the connector is a DRAFT with the endpoint, schema, mapping reference and a person-ID input
        assertThat(connector.status()).isEqualTo(ConnectorStatus.DRAFT);
        JsonNode fetch = JSON.readTree(connector.capabilitiesJson()).get("FETCH");
        assertThat(fetch.get("endpoint").asString()).isEqualTo("/v1/bank");
        assertThat(fetch.get("output_schema").asString()).isEqualTo("Credential/BankAccount@1");
        assertThat(fetch.get("mapping_ref").asString()).isEqualTo(r.mappingRefs().get(0));
        assertThat(JSON.readTree(connector.inputsJson()).get(0).get("from").asString()).isEqualTo("link.personId");
        assertThat(catalog.mapping(r.mappingRefs().get(0)).rules()).isNotEmpty();

        // the journey is a DRAFT; nothing is live yet
        assertThat(jdbc.sql("SELECT status FROM catalog_journey WHERE code = :c").param("c", "DBT_ACCOUNT_SEEDING_" + dept).query(String.class).single())
                .isEqualTo("DRAFT");
        assertThat(r.pendingSteps()).anyMatch(s -> s.kind().equals("PROVISION_SECRET") && s.subject().equals(sourceCode));
    }

    @Test
    void a_drafted_connector_passes_the_config_test_and_can_be_published_and_then_resolves() throws IOException {
        String dept = code("DBTL");
        String url = serve("dbt", dept);
        OnboardingResult r = onboarding.onboard(all(onboarding.plan(url), url));
        String ref = r.connectorRefs().get(0);

        assertThat(catalog.resolve(dept, DataCategory.of("BANK_ACCOUNT"), Capability.FETCH)).isEmpty(); // not live yet
        var report = wizard.test(ref);
        assertThat(report.passed()).isTrue();
        assertThat(wizard.publish(ref, report).status()).isEqualTo(ConnectorStatus.PUBLISHED);
        assertThat(catalog.resolve(dept, DataCategory.of("BANK_ACCOUNT"), Capability.FETCH)).hasValueSatisfying(c -> assertThat(c.ref()).isEqualTo(ref));
    }

    @Test
    void re_onboarding_makes_a_new_connector_version_and_the_live_one_stays_until_the_new_one_is_published() throws IOException {
        String dept = code("DBTV");
        String url = serve("dbt", dept);
        OnboardingResult first = onboarding.onboard(all(onboarding.plan(url), url));
        wizard.publish(first.connectorRefs().get(0), wizard.test(first.connectorRefs().get(0)));

        OnboardingResult second = onboarding.onboard(all(onboarding.plan(url), url));

        assertThat(second.connectorRefs()).containsExactly(dept.toLowerCase() + "-bank-account@2");
        assertThat(second.dataSources()).isEmpty();
        assertThat(second.journeysCreated()).isEmpty();
        assertThat(second.skipped()).anyMatch(s -> s.contains("data source") && s.contains("already exists")).anyMatch(s -> s.contains("journey"));
        assertThat(catalog.resolve(dept, DataCategory.of("BANK_ACCOUNT"), Capability.FETCH)).hasValueSatisfying(c -> assertThat(c.version()).isEqualTo(1));
        wizard.publish(second.connectorRefs().get(0), wizard.test(second.connectorRefs().get(0)));
        assertThat(catalog.resolve(dept, DataCategory.of("BANK_ACCOUNT"), Capability.FETCH)).hasValueSatisfying(c -> assertThat(c.version()).isEqualTo(2));
    }

    @Test
    void ticking_a_subset_creates_only_those_connectors_and_their_sources() throws IOException {
        String dept = code("REVS");
        String url = serve("revenue", dept);
        OnboardingPlan plan = onboarding.plan(url);
        assertThat(plan.documents()).hasSize(4);

        OnboardingResult r = onboarding.onboard(new OnboardRequest(url, plan.manifestDigest(), List.of("LAND_PARCEL"), true, Map.of()));

        assertThat(r.connectorRefs()).hasSize(1);
        assertThat(r.dataSources()).containsExactly(dept.toLowerCase() + "-sftp");
        var fetch = JSON.readTree(catalog.byRef(r.connectorRefs().get(0)).capabilitiesJson()).get("FETCH");
        assertThat(fetch.get("key_column").asString()).isEqualTo("personId");
        assertThat(r.pendingSteps()).anyMatch(s -> s.kind().equals("CONFIGURE_SFTP"));
    }

    @Test
    void a_rest_document_with_a_resolve_step_keeps_it_in_the_stored_connector() throws IOException {
        String dept = code("REVR");
        String url = serve("revenue", dept);
        OnboardingPlan plan = onboarding.plan(url);
        OnboardingResult r = onboarding.onboard(new OnboardRequest(url, plan.manifestDigest(), List.of("INCOME_CERTIFICATE"), true, Map.of()));
        var fetch = JSON.readTree(catalog.byRef(r.connectorRefs().get(0)).capabilitiesJson()).get("FETCH");
        assertThat(fetch.get("resolve").get("path").asString()).isEqualTo("/v1/persons/{personId}/documents?type=INCOME_CERTIFICATE");
        assertThat(fetch.get("resolve").get("into").asString()).isEqualTo("key");
    }

    @Test
    void an_admin_can_supply_their_own_mapping_instead_of_accepting_the_suggestions() throws IOException {
        String dept = code("DBTM");
        String url = serve("dbt", dept);
        OnboardingPlan plan = onboarding.plan(url);
        var own = List.of(new FieldMapping("accountRef", "accountRef", List.of()), new FieldMapping("holderName", "holderName", List.of()));
        OnboardingResult r = onboarding.onboard(new OnboardRequest(url, plan.manifestDigest(), List.of("BANK_ACCOUNT"), false, Map.of("BANK_ACCOUNT", own)));
        assertThat(catalog.mapping(r.mappingRefs().get(0)).rules()).hasSize(2);
    }

    // --- the onboarded flag (what the staff console shows) ---------------------------------------------------------

    boolean onboarded(String table, String keyColumn, String key) {
        return jdbc.sql("SELECT onboarded FROM " + table + " WHERE " + keyColumn + " = :k").param("k", key).query(Boolean.class).single();
    }

    @Test
    void onboarding_marks_what_it_creates_as_onboarded_and_leaves_hand_made_rows_alone() throws IOException {
        String dept = code("DBTF");
        // a hand-made source, connector and journey of another department, like the seeded demo rows
        String other = code("HAND");
        wizard.registerDepartment(new com.samanvay.catalog.api.DepartmentDraft(other, "Hand made", null, null, 1000));
        wizard.registerDataSource(new com.samanvay.catalog.api.DataSourceDraft(other.toLowerCase() + "-rest", other, "REST", "hand.example.gov", "NONE", "secret:none"));
        String handRef = wizard.createDraft(new com.samanvay.catalog.api.ConnectorDraft(other.toLowerCase() + "-bank", other.toLowerCase() + "-rest",
                DataCategory.of("BANK_ACCOUNT"), "{\"FETCH\":{\"endpoint\":\"/x\"}}", "[]", 1000)).ref();
        String url = serve("dbt", dept);

        OnboardingResult r = onboarding.onboard(all(onboarding.plan(url), url));

        assertThat(onboarded("catalog_data_source", "code", r.dataSources().get(0))).isTrue();
        assertThat(onboarded("catalog_connector", "ref", r.connectorRefs().get(0))).isTrue();
        assertThat(onboarded("catalog_journey", "code", r.journeysCreated().get(0))).isTrue();
        assertThat(onboarded("catalog_data_source", "code", other.toLowerCase() + "-rest")).isFalse();
        assertThat(onboarded("catalog_connector", "ref", handRef)).isFalse();
    }

    @Test
    void a_journey_that_already_existed_with_a_declared_code_is_adopted_not_duplicated() throws IOException {
        String dept = code("DBTJ");
        String journeyCode = "DBT_ACCOUNT_SEEDING_" + dept;
        journeyWrite.createJourney(new com.samanvay.catalog.api.JourneyDraft(journeyCode, "Hand made", "HM", 48, "SCHOLARSHIP_ELIGIBILITY", dept,
                List.of("BANK_ACCOUNT"), Map.of("BANK_ACCOUNT", dept), null));
        assertThat(onboarded("catalog_journey", "code", journeyCode)).isFalse();
        String url = serve("dbt", dept);

        OnboardingResult r = onboarding.onboard(all(onboarding.plan(url), url));

        assertThat(r.journeysCreated()).isEmpty();
        assertThat(r.skipped()).anyMatch(s -> s.contains(journeyCode));
        assertThat(onboarded("catalog_journey", "code", journeyCode)).isTrue();
    }

    // --- refusals leave nothing behind --------------------------------------------------------------------------

    @Test
    void a_manifest_that_changed_since_it_was_reviewed_is_refused_and_nothing_is_written() throws IOException {
        String dept = code("DBTD");
        String url = serve("dbt", dept);
        OnboardingPlan reviewed = onboarding.plan(url);
        served = served.replace("accountRef", "accountNo"); // the department changes its published structure
        int sourcesBefore = count("catalog_data_source");

        assertThatThrownBy(() -> onboarding.onboard(all(reviewed, url))).isInstanceOf(InvalidRequestException.class).hasMessageContaining("changed");
        assertThat(count("catalog_data_source")).isEqualTo(sourcesBefore);
        assertThat(departments.byCode(dept)).isEmpty();
    }

    @Test
    void a_manifest_naming_keys_on_another_host_is_refused_at_plan_and_at_onboard_and_nothing_is_written() throws IOException {
        String dept = code("DBTK");
        String url = serve("dbt", dept);
        OnboardingPlan reviewed = onboarding.plan(url);
        served = served.replaceAll("http://127\\.0\\.0\\.1:\\d+/\\.well-known/jwks\\.json", "https://evil.example.net/jwks.json");

        assertThatThrownBy(() -> onboarding.plan(url)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("evil.example.net");
        assertThatThrownBy(() -> onboarding.onboard(all(reviewed, url))).isInstanceOf(InvalidRequestException.class).hasMessageContaining("evil.example.net");
        assertThat(departments.byCode(dept)).isEmpty();
    }

    @Test
    void no_ticked_documents_an_unknown_category_or_an_unapproved_mapping_are_refused() throws IOException {
        String dept = code("DBTR");
        String url = serve("dbt", dept);
        OnboardingPlan plan = onboarding.plan(url);
        assertThatThrownBy(() -> onboarding.onboard(new OnboardRequest(url, plan.manifestDigest(), List.of(), true, Map.of()))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> onboarding.onboard(new OnboardRequest(url, plan.manifestDigest(), List.of("NOT_A_DOCUMENT"), true, Map.of())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("NOT_A_DOCUMENT");
        // mappings are propose-only: neither accepted nor supplied means refused
        assertThatThrownBy(() -> onboarding.onboard(new OnboardRequest(url, plan.manifestDigest(), List.of("BANK_ACCOUNT"), false, Map.of())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("mapping");
        assertThat(departments.byCode(dept)).isEmpty();
    }

    @Test
    void a_document_with_no_seeded_central_schema_is_refused_with_a_seed_first_message() throws IOException {
        String dept = code("DBTS");
        String url = serve("dbt", dept);
        served = served.replace("\"BANK_ACCOUNT\"", "\"UNSEEDED_THING\"");
        OnboardingPlan plan = onboarding.plan(url);
        assertThat(plan.documents().get(0).ready()).isFalse();
        assertThatThrownBy(() -> onboarding.onboard(all(plan, url))).isInstanceOf(InvalidRequestException.class).hasMessageContaining("seed");
        assertThat(departments.byCode(dept)).isEmpty();
    }

    @Test
    void a_failure_part_way_rolls_everything_back() throws IOException {
        String dept = code("REVF");
        String url = serve("revenue", dept);
        OnboardingPlan plan = onboarding.plan(url);
        // the admin supplies a mapping for only one of two ticked documents: validated before any write, so nothing exists afterwards
        var own = List.of(new FieldMapping("annualIncome", "annualIncome", List.of()));
        assertThatThrownBy(() -> onboarding.onboard(new OnboardRequest(url, plan.manifestDigest(), List.of("INCOME_CERTIFICATE", "LAND_PARCEL"), false,
                Map.of("INCOME_CERTIFICATE", own)))).isInstanceOf(InvalidRequestException.class);
        assertThat(departments.byCode(dept)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM catalog_data_source WHERE department_code = :c").param("c", dept).query(Integer.class).single()).isZero();
    }

    // --- drift ----------------------------------------------------------------------------------------------------

    @Test
    void after_onboarding_the_next_plan_flags_a_changed_manifest() throws IOException {
        String dept = code("DBTC");
        String url = serve("dbt", dept);
        onboarding.onboard(all(onboarding.plan(url), url));
        OnboardingPlan unchanged = onboarding.plan(url);
        assertThat(unchanged.departmentExists()).isTrue();
        assertThat(unchanged.changedSinceOnboarding()).isFalse();
        assertThat(unchanged.documents().get(0).newVersionOfExisting()).isTrue();

        served = served.replace("holderName", "accountHolder");
        assertThat(onboarding.plan(url).changedSinceOnboarding()).isTrue();
    }

    // --- all four real departments ---------------------------------------------------------------------------------

    @Test
    void all_four_real_department_manifests_onboard_end_to_end() throws IOException {
        for (String fixture : List.of("revenue", "dbt", "education", "agriculture")) {
            String dept = code(fixture.substring(0, 3).toUpperCase() + "A");
            String url = serve(fixture, dept);
            OnboardingPlan plan = onboarding.plan(url);
            assertThat(plan.documents()).as(fixture).allSatisfy(d -> assertThat(d.ready()).as(d.category()).isTrue());
            OnboardingResult r = onboarding.onboard(all(plan, url));
            assertThat(r.connectorRefs()).as(fixture).hasSize(plan.documents().size());
            for (String ref : r.connectorRefs()) {
                var report = wizard.test(ref);
                assertThat(report.passed()).as(fixture + " " + ref).isTrue();
            }
            server.stop(0);
            server = null;
        }
    }
}
