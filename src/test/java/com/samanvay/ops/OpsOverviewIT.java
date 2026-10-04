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
    static final String SEEDED_SOURCE_JOURNEY = "OVW_SEEDSRC_" + S;
    static final String SFTP_JOURNEY = "OVW_SFTP_" + S;
    static final String DEPT2 = "OV2" + S;
    static final String SFTP_SOURCE = "ovw-sftp-" + S.toLowerCase();

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
        jdbc.sql("UPDATE catalog_data_source SET health_status = 'GREEN' WHERE code = :c").param("c", SOURCE).update();

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

        // a published connector the manifest never created (seeded-like) in a department that has no onboarded document: it must not make a journey ready
        wizard.registerDepartment(new DepartmentDraft(SEEDED_DEPT, "Seeded-like Dept", null, null, 1000));
        wizard.registerDataSource(new DataSourceDraft("ovs-rest-" + S.toLowerCase(), SEEDED_DEPT, "REST", "ovs.example.gov", "NONE", "secret:none"));
        jdbc.sql("UPDATE catalog_data_source SET health_status = 'GREEN' WHERE code = :c").param("c", "ovs-rest-" + S.toLowerCase()).update();
        wizard.createDraft(new ConnectorDraft("ovs-bank-" + S.toLowerCase(), "ovs-rest-" + S.toLowerCase(), DataCategory.of("BANK_ACCOUNT"),
                "{\"FETCH\":{\"endpoint\":\"/x\"}}", "[]", 1000));
        wizard.publish("ovs-bank-" + S.toLowerCase() + "@1", new ConnectorTestReport(true, List.of()));
        journeyWrite.createJourney(new JourneyDraft(SEEDED_SOURCE_JOURNEY, SEEDED_SOURCE_JOURNEY, "OV", 48, "SCHOLARSHIP_ELIGIBILITY", DEPT, List.of("BANK_ACCOUNT"),
                Map.of("BANK_ACCOUNT", SEEDED_DEPT), null));
        jdbc.sql("UPDATE catalog_journey SET onboarded = TRUE WHERE code = :c").param("c", SEEDED_SOURCE_JOURNEY).update();

        // a second onboarded department: a published v1 that keeps serving while a newer v2 is still a DRAFT, and an SFTP source
        wizard.registerDepartment(new DepartmentDraft(DEPT2, "Second IT Dept", null, null, 1000));
        jdbc.sql("UPDATE catalog_department SET manifest_digest = 'digest2' WHERE code = :c").param("c", DEPT2).update();
        wizard.registerDataSource(new DataSourceDraft("ov2-rest-" + S.toLowerCase(), DEPT2, "REST", "ov2.example.gov", "NONE", "secret:none"));
        jdbc.sql("UPDATE catalog_data_source SET onboarded = TRUE, health_status = 'GREEN' WHERE code = :c").param("c", "ov2-rest-" + S.toLowerCase()).update();
        wizard.createDraft(new ConnectorDraft("ov2-bank-" + S.toLowerCase(), "ov2-rest-" + S.toLowerCase(), DataCategory.of("BANK_ACCOUNT"), "{\"FETCH\":{\"endpoint\":\"/x\"}}", "[]", 1000));
        wizard.publish("ov2-bank-" + S.toLowerCase() + "@1", new ConnectorTestReport(true, List.of()));
        wizard.createDraft(new ConnectorDraft("ov2-bank-" + S.toLowerCase(), "ov2-rest-" + S.toLowerCase(), DataCategory.of("BANK_ACCOUNT"), "{\"FETCH\":{\"endpoint\":\"/y\"}}", "[]", 1000));
        jdbc.sql("UPDATE catalog_connector SET onboarded = TRUE WHERE connector_id = :c").param("c", "ov2-bank-" + S.toLowerCase()).update();
        wizard.registerDataSource(new DataSourceDraft(SFTP_SOURCE, DEPT2, "SFTP_CSV", "ov2-sftp.example.gov:22", "PASSWORD", "secret:" + SFTP_SOURCE));
        jdbc.sql("UPDATE catalog_data_source SET onboarded = TRUE WHERE code = :c").param("c", SFTP_SOURCE).update();
        wizard.createDraft(new ConnectorDraft("ov2-parcel-" + S.toLowerCase(), SFTP_SOURCE, DataCategory.of("LAND_PARCEL"), "{\"FETCH\":{\"endpoint\":\"/o.csv\"}}", "[]", 1000));
        wizard.publish("ov2-parcel-" + S.toLowerCase() + "@1", new ConnectorTestReport(true, List.of()));
        jdbc.sql("UPDATE catalog_connector SET onboarded = TRUE WHERE ref = :c").param("c", "ov2-parcel-" + S.toLowerCase() + "@1").update();
        journeyWrite.createJourney(new JourneyDraft(SFTP_JOURNEY, SFTP_JOURNEY, "OV", 48, "SCHOLARSHIP_ELIGIBILITY", DEPT2, List.of("LAND_PARCEL"),
                Map.of("LAND_PARCEL", DEPT2), null));
        jdbc.sql("UPDATE catalog_journey SET onboarded = TRUE WHERE code = :c").param("c", SFTP_JOURNEY).update();

        // a department with a data source but no manifest: never onboarded
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
                .containsEntry("health", "GREEN");
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
                .containsEntry("connectorStatus", "PUBLISHED").containsEntry("dataSourceCode", SOURCE).containsEntry("sourceHealth", "GREEN")
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

        assertThat(journeys).extracting(j -> j.get("code")).containsExactlyInAnyOrder(READY_JOURNEY, BLOCKED_JOURNEY, SEEDED_SOURCE_JOURNEY); // not the hand-made one
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

    // --- working: what the overview claims about health -------------------------------------------------------

    private void setHealth(String source, String status) {
        jdbc.sql("UPDATE catalog_data_source SET health_status = :h WHERE code = :c").param("h", status).param("c", source).update();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> document(String dept, String category) {
        return ((List<Map<String, Object>>) department(overview(), dept).get("documents")).stream()
                .filter(m -> category.equals(m.get("category"))).findFirst().orElseThrow();
    }

    @Test
    void aRestSourceThatWasNeverProbedIsUnknownAndNotWorkingAndRedIsNotWorkingEither() {
        try {
            setHealth(SOURCE, "UNKNOWN");
            assertThat(document(DEPT, "BANK_ACCOUNT")).containsEntry("sourceHealth", "UNKNOWN").containsEntry("working", false);
            setHealth(SOURCE, "RED");
            assertThat(document(DEPT, "BANK_ACCOUNT")).containsEntry("sourceHealth", "RED").containsEntry("working", false);
            setHealth(SOURCE, "AMBER");
            assertThat(document(DEPT, "BANK_ACCOUNT")).containsEntry("working", true);
        } finally {
            setHealth(SOURCE, "GREEN");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void anSftpSourceIsAlwaysUnknownSoItIsWorkingOnlyWhenItsLastDurableTrialSucceeded() {
        String ref = "ov2-parcel-" + S.toLowerCase() + "@1";
        try {
            Map<String, Object> parcel = document(DEPT2, "LAND_PARCEL");
            assertThat(parcel).containsEntry("sourceHealth", "UNKNOWN").containsEntry("working", false); // never tried

            recordTrial(ref, "NOT_FOUND");
            assertThat(document(DEPT2, "LAND_PARCEL")).containsEntry("working", false);
            assertThat(needs(DEPT2, SFTP_JOURNEY)).containsExactly(Map.of("category", "LAND_PARCEL", "department", DEPT2, "working", false));

            recordTrial(ref, "SUCCESS");
            assertThat(document(DEPT2, "LAND_PARCEL")).containsEntry("sourceHealth", "UNKNOWN").containsEntry("working", true);
            assertThat(needs(DEPT2, SFTP_JOURNEY)).containsExactly(Map.of("category", "LAND_PARCEL", "department", DEPT2, "working", true));
        } finally {
            jdbc.sql("DELETE FROM connector_trial WHERE connector_ref = :r").param("r", ref).update();
        }
    }

    private void recordTrial(String ref, String outcome) {
        jdbc.sql("INSERT INTO connector_trial (connector_ref, tried_at, outcome) VALUES (:r, now(), :o)"
                + " ON CONFLICT (connector_ref) DO UPDATE SET tried_at = EXCLUDED.tried_at, outcome = EXCLUDED.outcome").param("r", ref).param("o", outcome).update();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> needs(String dept, String journey) {
        Map<String, Object> j = ((List<Map<String, Object>>) department(overview(), dept).get("journeys")).stream()
                .filter(x -> journey.equals(x.get("code"))).findFirst().orElseThrow();
        return (List<Map<String, Object>>) j.get("needs");
    }

    // --- versions: the published one serves while a newer draft is only pending ---------------------------------

    @Test
    void theHighestPublishedConnectorIsShownAndANewerDraftIsOnlyAPendingUpdate() {
        Map<String, Object> bank = document(DEPT2, "BANK_ACCOUNT");

        assertThat(bank).containsEntry("connectorRef", "ov2-bank-" + S.toLowerCase() + "@1").containsEntry("connectorStatus", "PUBLISHED")
                .containsEntry("working", true).containsEntry("pendingUpdateRef", "ov2-bank-" + S.toLowerCase() + "@2");
        // a document with no newer draft has no pending update
        assertThat(document(DEPT, "BANK_ACCOUNT").get("pendingUpdateRef")).isNull();
    }

    // --- a journey is ready only through connectors the manifest onboarded -----------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void aPublishedConnectorThatManifestOnboardingNeverCreatedDoesNotMakeAnOnboardedJourneyReady() {
        List<Map<String, Object>> journeys = (List<Map<String, Object>>) department(overview(), DEPT).get("journeys");
        Map<String, Object> j = journeys.stream().filter(x -> SEEDED_SOURCE_JOURNEY.equals(x.get("code"))).findFirst().orElseThrow();

        assertThat(j).containsEntry("ready", false);
        assertThat((List<Map<String, Object>>) j.get("needs")).containsExactly(Map.of("category", "BANK_ACCOUNT", "department", SEEDED_DEPT, "working", false));
    }
}
