package in.samanvay.departments.revenue;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Revenue department over real HTTP: published manifest, API-key security, resolve, document fetch. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RevenueDepartmentTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String KEY = "revenue-dev-key-change-me";

    @LocalServerPort
    int port;

    HttpResponse<String> get(String path, String apiKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (apiKey != null) {
            b.header("X-Api-Key", apiKey);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void manifest_is_public_and_declares_documents_resolve_auth_and_identity() throws Exception {
        HttpResponse<String> r = get("/.well-known/samanvay/manifest", null);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode m = JSON.readTree(r.body());
        assertThat(m.get("department").get("code").asString()).isEqualTo("REVENUE");
        assertThat(m.get("documents")).hasSize(4);

        JsonNode income = null;
        for (JsonNode d : m.get("documents")) {
            if ("INCOME_CERTIFICATE".equals(d.get("category").asString())) {
                income = d;
            }
        }
        assertThat(income).isNotNull();
        assertThat(income.get("protocol").asString()).isEqualTo("REST");
        assertThat(income.get("path").asString()).isEqualTo("/v1/income/{key}");
        // a person can hold several income certificates, so Revenue publishes a resolve step
        assertThat(income.get("lookup").get("resolve").get("path").asString()).isEqualTo("/v1/persons/{personId}/documents");
        assertThat(income.get("auth").get("scheme").asString()).isEqualTo("API_KEY");
        assertThat(income.get("auth").get("parameters").get(0).get("name").asString()).isEqualTo("X-Api-Key");
        assertThat(m.get("identity").get("personIdType").asString()).isEqualTo("REVENUE_PERSON_ID");
    }

    @Test
    void manifest_publishes_the_7_12_land_record_as_an_sftp_csv_document() throws Exception {
        JsonNode m = JSON.readTree(get("/.well-known/samanvay/manifest", null).body());
        assertThat(m.get("documents")).hasSize(4);
        JsonNode land = null;
        for (JsonNode d : m.get("documents")) {
            if ("LAND_PARCEL".equals(d.get("category").asString())) {
                land = d;
            }
        }
        assertThat(land).isNotNull();
        assertThat(land.get("protocol").asString()).isEqualTo("SFTP_CSV");
        JsonNode sftp = land.get("access").get("sftp");
        assertThat(sftp.get("directory").asString()).isEqualTo("/outbound");
        assertThat(sftp.get("fileNamePattern").asString()).isEqualTo("712.csv");
        assertThat(sftp.get("format").asString()).isEqualTo("CSV");
        assertThat(sftp.get("keyColumn").asString()).isEqualTo("personId");
        assertThat(sftp.get("columns").get(0).asString()).isEqualTo("personId");
        // no raw secrets, and the host key is only published when configured (none in the test profile)
        assertThat(sftp.has("hostKeyFingerprint")).isFalse();
        assertThat(land.get("auth").get("scheme").asString()).isEqualTo("PASSWORD");
    }

    @Test
    void the_7_12_csv_matches_its_declared_columns_and_has_rows_for_known_people() throws Exception {
        java.util.List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of("sftp/712.csv"));
        JsonNode m = JSON.readTree(get("/.well-known/samanvay/manifest", null).body());
        JsonNode cols = null;
        for (JsonNode d : m.get("documents")) {
            if ("LAND_PARCEL".equals(d.get("category").asString())) {
                cols = d.get("access").get("sftp").get("columns");
            }
        }
        java.util.List<String> declared = new java.util.ArrayList<>();
        cols.forEach(c -> declared.add(c.asString()));
        assertThat(java.util.Arrays.asList(lines.get(0).split(","))).isEqualTo(declared);
        assertThat(lines).anyMatch(l -> l.startsWith("RV-1001,"));
        assertThat(lines).anyMatch(l -> l.startsWith("RV-1002,"));
    }

    @Test
    void manifest_publishes_its_journey_with_the_documents_it_needs() throws Exception {
        JsonNode j = JSON.readTree(get("/.well-known/samanvay/manifest", null).body()).get("journeys");
        assertThat(j).hasSize(1);
        assertThat(j.get(0).get("code").asString()).isEqualTo("INCOME_CERT_RENEWAL");
        assertThat(j.get(0).get("referencePrefix").asString()).isEqualTo("ICR");
        assertThat(j.get(0).get("slaHours").asInt()).isPositive();
        assertThat(j.get(0).get("consentPurpose").asString()).isEqualTo("INCOME_CERT_RENEWAL");
        assertThat(j.get(0).get("requester").asString()).isEqualTo("REVENUE");
        assertThat(j.get(0).get("requiredCategories")).hasSize(1);
        assertThat(j.get(0).get("requiredCategories").get(0).get("category").asString()).isEqualTo("INCOME_CERTIFICATE");
        assertThat(j.get(0).get("requiredCategories").get(0).get("department").asString()).isEqualTo("REVENUE");
    }

    @Test
    void every_rest_documents_resolve_describes_its_query_and_the_shape_of_the_answer() throws Exception {
        JsonNode m = JSON.readTree(get("/.well-known/samanvay/manifest", null).body());
        int restDocs = 0;
        for (JsonNode d : m.get("documents")) {
            if (!"REST".equals(d.get("protocol").asString())) {
                continue;
            }
            restDocs++;
            JsonNode r = d.get("lookup").get("resolve");
            assertThat(r.get("query").get("type").asString()).isEqualTo(d.get("category").asString());
            assertThat(r.get("listField").asString()).isEqualTo("documents");
            assertThat(r.get("keyField").asString()).isEqualTo("key");
            assertThat(r.get("latestField").asString()).isEqualTo("latest");
        }
        assertThat(restDocs).isEqualTo(3);
    }

    @Test
    void manifest_publishes_a_sample_person_that_really_has_documents() throws Exception {
        JsonNode m = JSON.readTree(get("/.well-known/samanvay/manifest", null).body());
        String sample = m.get("sample").get("personId").asString();
        assertThat(sample).isEqualTo("RV-1001");
        assertThat(JSON.readTree(get("/v1/persons/" + sample + "/documents?type=INCOME_CERTIFICATE", KEY).body()).get("documents")).isNotEmpty();
    }

    @Test
    void api_calls_without_or_with_a_wrong_key_are_refused() throws Exception {
        assertThat(get("/v1/persons/RV-1001/documents?type=INCOME_CERTIFICATE", null).statusCode()).isEqualTo(401);
        assertThat(get("/v1/persons/RV-1001/documents?type=INCOME_CERTIFICATE", "nope").statusCode()).isEqualTo(401);
        assertThat(get("/v1/income/INC-2026-0007", null).statusCode()).isEqualTo(401);
    }

    @Test
    void resolve_lists_every_certificate_a_person_holds_newest_flagged() throws Exception {
        HttpResponse<String> r = get("/v1/persons/RV-1001/documents?type=INCOME_CERTIFICATE", KEY);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("personId").asString()).isEqualTo("RV-1001");
        assertThat(body.get("documents")).hasSize(2);
        int latest = 0;
        for (JsonNode d : body.get("documents")) {
            if (d.get("latest").asBoolean()) {
                latest++;
                assertThat(d.get("key").asString()).isEqualTo("INC-2026-0007");
            }
        }
        assertThat(latest).isEqualTo(1);
    }

    @Test
    void resolve_for_an_unknown_person_is_404_and_a_type_they_lack_is_empty() throws Exception {
        assertThat(get("/v1/persons/RV-9999/documents?type=INCOME_CERTIFICATE", KEY).statusCode()).isEqualTo(404);
        HttpResponse<String> r = get("/v1/persons/RV-1002/documents?type=CASTE_CERTIFICATE", KEY);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("documents")).isEmpty();
    }

    @Test
    void a_document_is_fetched_by_its_key() throws Exception {
        HttpResponse<String> r = get("/v1/income/INC-2026-0007", KEY);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode d = JSON.readTree(r.body());
        assertThat(d.get("holderName").asString()).isEqualTo("Asha Patil");
        assertThat(d.get("annualIncome").asString()).isEqualTo("185000");
        assertThat(d.get("financialYear").asString()).isEqualTo("2025-26");
        assertThat(get("/v1/income/INC-0000-0000", KEY).statusCode()).isEqualTo(404);
        // a caste key is not an income document
        assertThat(get("/v1/income/CST-0001", KEY).statusCode()).isEqualTo(404);
    }
}
