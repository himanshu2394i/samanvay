package com.samanvay.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.ConnectorDraft;
import com.samanvay.catalog.api.ConnectorTestReport;
import com.samanvay.catalog.api.DataSourceDraft;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.catalog.api.DepartmentIdentity;
import com.samanvay.catalog.api.FieldMapping;
import com.samanvay.catalog.api.JourneyDraft;
import com.samanvay.catalog.api.JourneyWrite;
import com.samanvay.catalog.api.MappingDraft;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code GET /api/ops/overview} over a real database: only what manifest onboarding created or adopted (the {@code onboarded}
 * flag) is shown, with each document's mapping onto the central schema; hand-made (seeded-like) rows are not.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpsOverviewIT extends PostgresIntegrationTest {

    static final String S = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    static final String DEPT = "OVW" + S;
    static final String SEEDED_DEPT = "OVS" + S;
    static final String SOURCE = "ovw-rest-" + S.toLowerCase();
    static final String READY_JOURNEY = "OVW_READY_" + S;
    static final String BLOCKED_JOURNEY = "OVW_BLOCKED_" + S;
    static final String SEEDED_JOURNEY = "OVW_SEEDED_" + S;

    @LocalServerPort
    int port;

    @Autowired
    CatalogOnboarding wizard;

    @Autowired
    JourneyWrite journeyWrite;

    @Autowired
    JdbcClient jdbc;

    @BeforeAll
    void setUp() {
        // an onboarded department with a published bank connector and a DRAFT marks connector
        wizard.registerDepartment(new DepartmentDraft(DEPT, "Overview IT Dept", null, null, 1000,
                new DepartmentIdentity("OVW_ID", "https://ovw.example.gov/login", "https://ovw.example.gov/jwks", "dept:" + DEPT)));
        jdbc.sql("UPDATE catalog_department SET manifest_digest = 'digest', manifest_key_thumbprint = 'thumb-" + S + "' WHERE code = :c").param("c", DEPT).update();
        wizard.registerDataSource(new DataSourceDraft(SOURCE, DEPT, "REST", "ovw.example.gov", "NONE", "secret:none"));

        String bank = connector("ovw-bank-" + S.toLowerCase(), "BANK_ACCOUNT", "Credential/BankAccount@1", true,
                List.of(new FieldMapping("accountRef", "accountRef", List.of()), new FieldMapping("holderName", "holderName", List.of())));
        connector("ovw-marks-" + S.toLowerCase(), "MARKS", "Credential/Marks@1", false, List.of(new FieldMapping("board", "board", List.of())));
        // a hand-made connector of the same department in another category: not onboarded, so not shown
        wizard.createDraft(new ConnectorDraft("ovw-income-" + S.toLowerCase(), SOURCE, DataCategory.of("INCOME_CERTIFICATE"),
                "{\"FETCH\":{\"endpoint\":\"/x\"}}", "[]", 1000));
        jdbc.sql("UPDATE catalog_data_source SET onboarded = TRUE WHERE code = :c").param("c", SOURCE).update();
        jdbc.sql("UPDATE catalog_connector SET onboarded = TRUE WHERE ref IN (:a, :b)").param("a", bank)
                .param("b", "ovw-marks-" + S.toLowerCase() + "@1").update();

        journey(READY_JOURNEY, List.of("BANK_ACCOUNT"), true);
        journey(BLOCKED_JOURNEY, List.of("BANK_ACCOUNT", "MARKS"), true);
        journey(SEEDED_JOURNEY, List.of("BANK_ACCOUNT"), false);

        // a department with a data source but no manifest: never onboarded
        wizard.registerDepartment(new DepartmentDraft(SEEDED_DEPT, "Seeded-like Dept", null, null, 1000));
        wizard.registerDataSource(new DataSourceDraft("ovs-rest-" + S.toLowerCase(), SEEDED_DEPT, "REST", "ovs.example.gov", "NONE", "secret:none"));
        jdbc.sql("UPDATE catalog_data_source SET onboarded = TRUE WHERE code = :c").param("c", "ovs-rest-" + S.toLowerCase()).update();
    }

    private String connector(String id, String category, String schema, boolean publish, List<FieldMapping> rules) {
        String ref = id + "@1";
        String caps = "{\"FETCH\":{\"endpoint\":\"/x\",\"mapping_ref\":\"map-" + ref + "\",\"output_schema\":\"" + schema + "\"}}";
        wizard.createDraft(new ConnectorDraft(id, SOURCE, DataCategory.of(category), caps, "[]", 1000));
        wizard.saveMapping(new MappingDraft("map-" + ref, ref, rules));
        if (publish) {
            wizard.publish(ref, new ConnectorTestReport(true, List.of()));
        }
        return ref;
    }

    private void journey(String code, List<String> categories, boolean onboarded) {
        journeyWrite.createJourney(new JourneyDraft(code, code, "OV", 48, "SCHOLARSHIP_ELIGIBILITY", DEPT, categories,
                categories.stream().collect(java.util.stream.Collectors.toMap(c -> c, c -> DEPT)), null));
        if (onboarded) {
            jdbc.sql("UPDATE catalog_journey SET onboarded = TRUE WHERE code = :c").param("c", code).update();
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> overview() {
        return TestHttp.as(TestTokens.officer("ops-overview-officer")).get().uri("http://localhost:" + port + "/api/ops/overview")
                .retrieve().body(Map.class);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> department(Map<String, Object> body, String code) {
        return ((List<Map<String, Object>>) body.get("departments")).stream().filter(d -> code.equals(d.get("code"))).findFirst().orElse(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyOnboardedDepartmentsAreListed() {
        Map<String, Object> body = overview();

        assertThat(body.get("generatedAt")).isNotNull();
        assertThat(department(body, DEPT)).isNotNull();
        assertThat(department(body, SEEDED_DEPT)).isNull(); // no manifest digest
        List<String> codes = ((List<Map<String, Object>>) body.get("departments")).stream().map(d -> (String) d.get("code")).toList();
        assertThat(codes).isSorted();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDepartmentShowsItsKeyLoginSourcesAndOnlyItsOnboardedDocuments() {
        Map<String, Object> d = department(overview(), DEPT);

        assertThat(d).containsEntry("name", "Overview IT Dept").containsEntry("pinnedKeyThumbprint", "thumb-" + S)
                .containsEntry("loginUrl", "https://ovw.example.gov/login");
        List<Map<String, Object>> sources = (List<Map<String, Object>>) d.get("dataSources");
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0)).containsEntry("code", SOURCE).containsEntry("protocol", "REST").containsEntry("host", "ovw.example.gov")
                .containsEntry("health", "UNKNOWN");
        List<Map<String, Object>> documents = (List<Map<String, Object>>) d.get("documents");
        assertThat(documents).extracting(m -> m.get("category")).containsExactlyInAnyOrder("BANK_ACCOUNT", "MARKS"); // not INCOME_CERTIFICATE
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPublishedDocumentListsItsMappingWithRequiredFlagsAndADraftIsNotWorking() {
        List<Map<String, Object>> documents = (List<Map<String, Object>>) department(overview(), DEPT).get("documents");
        Map<String, Object> bank = documents.stream().filter(m -> "BANK_ACCOUNT".equals(m.get("category"))).findFirst().orElseThrow();
        Map<String, Object> marks = documents.stream().filter(m -> "MARKS".equals(m.get("category"))).findFirst().orElseThrow();

        assertThat(bank).containsEntry("title", "Bank account").containsEntry("connectorRef", "ovw-bank-" + S.toLowerCase() + "@1")
                .containsEntry("connectorStatus", "PUBLISHED").containsEntry("dataSourceCode", SOURCE).containsEntry("sourceHealth", "UNKNOWN")
                .containsEntry("working", true).containsEntry("centralSchemaRef", "Credential/BankAccount@1");
        assertThat(bank.get("lastTrial")).isNull();
        assertThat((List<Map<String, Object>>) bank.get("mappings")).containsExactly(
                Map.of("source", "accountRef", "target", "accountRef", "required", true),
                Map.of("source", "holderName", "target", "holderName", "required", false));
        assertThat((List<?>) bank.get("unmappedRequired")).isEmpty();

        assertThat(marks).containsEntry("connectorStatus", "DRAFT").containsEntry("working", false);
        assertThat((List<String>) marks.get("unmappedRequired")).containsExactly("percentage");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onboardedJourneysAreListedUnderTheirRequesterWithReadyAndNeeds() {
        List<Map<String, Object>> journeys = (List<Map<String, Object>>) department(overview(), DEPT).get("journeys");

        assertThat(journeys).extracting(j -> j.get("code")).containsExactlyInAnyOrder(READY_JOURNEY, BLOCKED_JOURNEY); // not the hand-made one
        Map<String, Object> ready = journeys.stream().filter(j -> READY_JOURNEY.equals(j.get("code"))).findFirst().orElseThrow();
        assertThat(ready).containsEntry("status", "DRAFT").containsEntry("ready", true);
        assertThat((List<Map<String, Object>>) ready.get("needs")).containsExactly(Map.of("category", "BANK_ACCOUNT", "department", DEPT, "working", true));
        assertThat((Map<String, Object>) ready.get("counts")).containsEntry("running", 0).containsEntry("completed", 0)
                .containsEntry("failed", 0).containsEntry("last7Days", 0);

        Map<String, Object> blocked = journeys.stream().filter(j -> BLOCKED_JOURNEY.equals(j.get("code"))).findFirst().orElseThrow();
        assertThat(blocked).containsEntry("ready", false);
        assertThat((List<Map<String, Object>>) blocked.get("needs")).containsExactly(
                Map.of("category", "BANK_ACCOUNT", "department", DEPT, "working", true),
                Map.of("category", "MARKS", "department", DEPT, "working", false));
    }

    @Test
    void theOverviewCarriesNoSecretOrCitizenValue() {
        assertThat(overview().toString()).doesNotContain("secret:").doesNotContain("authSpec").doesNotContain("authConfigRef");
    }
}
