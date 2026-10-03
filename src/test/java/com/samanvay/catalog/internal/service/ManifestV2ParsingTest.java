package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.catalog.api.DepartmentManifest;
import com.samanvay.catalog.api.DepartmentManifest.Document;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The middle layer reads each department's v2 manifest. The fixtures under src/test/resources/manifests are the
 * real output of the four departments/ services (regenerate with scripts/capture-department-manifests.sh).
 */
class ManifestV2ParsingTest {

    static DepartmentManifest load(String dept) throws IOException {
        try (var in = ManifestV2ParsingTest.class.getResourceAsStream("/manifests/" + dept + ".json")) {
            return CatalogServices.parseManifest(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static Document doc(DepartmentManifest m, String category) {
        return m.documents().stream().filter(d -> category.equals(d.category())).findFirst().orElseThrow();
    }

    @Test
    void revenue_publishes_resolve_api_key_auth_sftp_access_identity_and_a_journey() throws IOException {
        DepartmentManifest m = load("revenue");
        assertThat(m.manifestVersion()).isEqualTo(2);
        assertThat(m.department().code()).isEqualTo("REVENUE");
        assertThat(m.identity().personIdType()).isEqualTo("REVENUE_PERSON_ID");
        assertThat(m.identity().loginUrl()).endsWith("/login");
        assertThat(m.identity().jwksUrl()).endsWith("/.well-known/jwks.json");
        assertThat(m.identity().assertionIssuer()).isEqualTo("dept:REVENUE");
        assertThat(m.documents()).hasSize(4);

        Document income = doc(m, "INCOME_CERTIFICATE");
        assertThat(income.protocol()).isEqualTo("REST");
        assertThat(income.lookup().resolve().path()).isEqualTo("/v1/persons/{personId}/documents");
        assertThat(income.lookup().resolve().method()).isEqualTo("GET");
        assertThat(income.lookup().resolve().query()).containsEntry("type", "INCOME_CERTIFICATE");
        assertThat(income.lookup().resolve().listField()).isEqualTo("documents");
        assertThat(income.lookup().resolve().keyField()).isEqualTo("key");
        assertThat(income.lookup().resolve().latestField()).isEqualTo("latest");
        assertThat(income.auth().scheme()).isEqualTo("API_KEY");
        assertThat(income.auth().parameters().get(0).name()).isEqualTo("X-Api-Key");
        assertThat(income.auth().parameters().get(0).in()).isEqualTo("header");
        assertThat(income.auth().parameters().get(0).secret()).isTrue();

        Document land = doc(m, "LAND_PARCEL");
        assertThat(land.protocol()).isEqualTo("SFTP_CSV");
        assertThat(land.lookup()).isNull();
        assertThat(land.access().sftp().directory()).isEqualTo("/outbound");
        assertThat(land.access().sftp().fileNamePattern()).isEqualTo("712.csv");
        assertThat(land.access().sftp().keyColumn()).isEqualTo("personId");
        assertThat(land.access().sftp().columns()).hasSize(7).startsWith("personId");
        assertThat(land.access().sftp().hostKeyFingerprint()).isNull();
        assertThat(land.auth().scheme()).isEqualTo("PASSWORD");

        assertThat(m.journeys()).hasSize(1);
        assertThat(m.journeys().get(0).code()).isEqualTo("INCOME_CERT_RENEWAL");
        assertThat(m.journeys().get(0).requiredCategories().get(0).category()).isEqualTo("INCOME_CERTIFICATE");
    }

    @Test
    void dbt_publishes_oauth2_client_auth_and_no_resolve() throws IOException {
        DepartmentManifest m = load("dbt");
        Document bank = doc(m, "BANK_ACCOUNT");
        assertThat(bank.lookup()).isNull();
        assertThat(bank.auth().scheme()).isEqualTo("OAUTH2_CLIENT");
        assertThat(bank.auth().tokenUrl()).isEqualTo("/oauth/token");
        assertThat(bank.auth().scopes()).containsExactly("bank.read");
        assertThat(m.identity().personIdType()).isEqualTo("DBT_ID");
        assertThat(bank.method()).isEqualTo("POST");
        assertThat(bank.inputs().get(0).in()).isEqualTo("body");
        assertThat(m.sample().personId()).isEqualTo("DBT-1001");
    }

    @Test
    void education_publishes_soap_access_and_ws_security_auth_and_a_cross_department_journey() throws IOException {
        DepartmentManifest m = load("education");
        Document marks = doc(m, "MARKS");
        assertThat(marks.access().soap().soapAction()).isEqualTo("GetMarks");
        assertThat(m.sample().personId()).isEqualTo("EDU-1001");
        assertThat(marks.access().soap().endpoint()).isEqualTo("/marks/service");
        assertThat(marks.access().soap().requestTemplate()).contains("{{studentId}}");
        assertThat(marks.auth().scheme()).isEqualTo("WS_SECURITY_USERNAME");
        assertThat(marks.auth().passwordType()).isEqualTo("PasswordText");
        assertThat(m.journeys().get(0).requiredCategories()).hasSize(4);
        assertThat(m.journeys().get(0).requiredCategories()).anyMatch(c -> "DBT".equals(c.department()) && "BANK_ACCOUNT".equals(c.category()));
    }

    @Test
    void agriculture_publishes_a_jdbc_view_and_an_sftp_csv() throws IOException {
        DepartmentManifest m = load("agriculture");
        Document farmer = doc(m, "FARMER_RECORD");
        assertThat(farmer.protocol()).isEqualTo("JDBC");
        assertThat(farmer.access().jdbc().readOnlyView()).isEqualTo("v_farmer_record");
        assertThat(farmer.access().jdbc().keyColumn()).isEqualTo("agri_person_id");
        assertThat(farmer.access().jdbc().tlsRequired()).isTrue();
        assertThat(farmer.auth().scheme()).isEqualTo("DB_USER");
        assertThat(doc(m, "CROP_RECORD").access().sftp().columns()).containsExactly("agriPersonId", "season", "crop", "areaHectares");
    }

    @Test
    void a_v1_manifest_without_the_new_blocks_still_parses() {
        String v1 = "{\"manifestVersion\":1,\"department\":{\"code\":\"SANDBOX\",\"name\":\"Sandbox\",\"description\":\"d\"},"
                + "\"documents\":[{\"category\":\"BANK_ACCOUNT\",\"title\":\"Bank\",\"protocol\":\"REST\",\"method\":\"GET\",\"path\":\"/bank\","
                + "\"inputs\":[{\"name\":\"dbtId\",\"in\":\"query\",\"required\":true,\"description\":\"x\"}],"
                + "\"fields\":[{\"name\":\"accountRef\",\"type\":\"string\",\"sensitive\":true}]}],\"journeys\":[]}";
        DepartmentManifest m = CatalogServices.parseManifest(v1);
        assertThat(m.identity()).isNull();
        Document d = m.documents().get(0);
        assertThat(d.auth()).isNull();
        assertThat(d.lookup()).isNull();
        assertThat(d.access()).isNull();
        assertThat(d.path()).isEqualTo("/bank");
    }

    @Test
    void unknown_future_fields_are_ignored() {
        String future = "{\"manifestVersion\":3,\"someNewBlock\":{\"x\":1},\"department\":{\"code\":\"X\",\"name\":\"X\",\"description\":\"d\",\"extra\":1},"
                + "\"documents\":[],\"journeys\":[]}";
        assertThat(CatalogServices.parseManifest(future).department().code()).isEqualTo("X");
    }
}
