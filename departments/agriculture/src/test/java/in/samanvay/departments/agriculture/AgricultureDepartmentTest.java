package in.samanvay.departments.agriculture;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agriculture: its farmer record is read from its own database through a read-only VIEW over JDBC, and its
 * crop-sowing report is a batch CSV over SFTP. This service publishes the manifest for both.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgricultureDepartmentTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    JsonNode manifest() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/.well-known/samanvay/manifest")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    JsonNode document(String category) throws Exception {
        for (JsonNode d : manifest().get("documents")) {
            if (category.equals(d.get("category").asString())) {
                return d;
            }
        }
        throw new AssertionError("no document " + category);
    }

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    @Test
    void manifest_identifies_the_department_and_publishes_two_documents() throws Exception {
        JsonNode m = manifest();
        assertThat(m.get("department").get("code").asString()).isEqualTo("AGRICULTURE");
        assertThat(m.get("identity").get("personIdType").asString()).isEqualTo("AGRI_FARMER_ID");
        assertThat(m.get("documents")).hasSize(2);
    }

    @Test
    void manifest_publishes_the_farmer_subsidy_journey_needing_documents_from_three_departments() throws Exception {
        JsonNode j = manifest().get("journeys");
        assertThat(j).hasSize(1);
        assertThat(j.get(0).get("code").asString()).isEqualTo("FARMER_SUBSIDY");
        assertThat(j.get(0).get("referencePrefix").asString()).isEqualTo("FAR");
        assertThat(j.get(0).get("consentPurpose").asString()).isEqualTo("FARMER_SUBSIDY");
        assertThat(j.get(0).get("requester").asString()).isEqualTo("AGRICULTURE");
        java.util.Set<String> needs = new java.util.TreeSet<>();
        j.get(0).get("requiredCategories").forEach(c -> needs.add(c.get("department").asString() + ":" + c.get("category").asString()));
        assertThat(needs).containsExactly("AGRICULTURE:CROP_RECORD", "DBT:BANK_ACCOUNT", "REVENUE:LAND_PARCEL");
    }

    @Test
    void manifest_publishes_a_sample_person_that_really_is_in_the_departments_data() throws Exception {
        String sample = manifest().get("sample").get("personId").asString();
        assertThat(sample).isEqualTo("AG-1001");
        assertThat(Files.readString(Path.of("db/init.sql"))).contains("'" + sample + "'");
        assertThat(Files.readString(Path.of("sftp/crop.csv"))).contains(sample + ",");
    }

    @Test
    void farmer_record_is_a_jdbc_view_read_with_a_db_account_over_tls() throws Exception {
        JsonNode d = document("FARMER_RECORD");
        assertThat(d.get("protocol").asString()).isEqualTo("JDBC");
        assertThat(d.has("lookup")).isFalse();
        JsonNode jdbc = d.get("access").get("jdbc");
        assertThat(jdbc.get("database").asString()).isEqualTo("agridb");
        assertThat(jdbc.get("readOnlyView").asString()).isEqualTo("v_farmer_record");
        assertThat(jdbc.get("keyColumn").asString()).isEqualTo("agri_person_id");
        assertThat(jdbc.get("tlsRequired").asBoolean()).isTrue();
        // the manifest names a view, never SQL
        assertThat(d.toString().toLowerCase()).doesNotContain("select ");
        assertThat(d.get("auth").get("scheme").asString()).isEqualTo("DB_USER");
    }

    @Test
    void crop_sowing_is_an_sftp_csv_with_password_login() throws Exception {
        JsonNode d = document("CROP_RECORD");
        assertThat(d.get("protocol").asString()).isEqualTo("SFTP_CSV");
        JsonNode sftp = d.get("access").get("sftp");
        assertThat(sftp.get("directory").asString()).isEqualTo("/outbound");
        assertThat(sftp.get("fileNamePattern").asString()).isEqualTo("crop.csv");
        assertThat(sftp.get("keyColumn").asString()).isEqualTo("agriPersonId");
        assertThat(sftp.has("hostKeyFingerprint")).isFalse();
        assertThat(d.get("auth").get("scheme").asString()).isEqualTo("PASSWORD");
        // no secret values anywhere in the manifest
        assertThat(manifest().toString()).doesNotContain("agri_ro_demo").doesNotContain("agri-sftp-dev");
    }

    @Test
    void the_crop_csv_header_matches_its_declared_columns() throws Exception {
        List<String> declared = strings(document("CROP_RECORD").get("access").get("sftp").get("columns"));
        List<String> lines = Files.readAllLines(Path.of("sftp/crop.csv"));
        assertThat(Arrays.asList(lines.get(0).split(","))).isEqualTo(declared);
        assertThat(lines).anyMatch(l -> l.startsWith("AG-1001,"));
    }

    @Test
    void the_db_script_exposes_only_the_view_to_the_read_only_account() throws Exception {
        String sql = Files.readString(Path.of("db/init.sql"));
        assertThat(sql).contains("CREATE VIEW v_farmer_record");
        assertThat(sql).contains("GRANT SELECT ON v_farmer_record TO agri_ro");
        // the account must not be able to read the base table or write anything
        assertThat(sql).doesNotContain("ON farmer TO agri_ro").doesNotContain("GRANT INSERT").doesNotContain("GRANT UPDATE")
                .doesNotContain("GRANT ALL");
    }

    @Test
    void the_view_columns_match_the_declared_fields_and_key() throws Exception {
        String sql = Files.readString(Path.of("db/init.sql"));
        Matcher m = Pattern.compile("CREATE VIEW v_farmer_record AS\\s+SELECT(.*?)FROM", Pattern.DOTALL).matcher(sql);
        assertThat(m.find()).isTrue();
        List<String> viewColumns = Arrays.stream(m.group(1).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        JsonNode d = document("FARMER_RECORD");
        List<String> declared = new ArrayList<>();
        d.get("fields").forEach(f -> declared.add(f.get("name").asString()));
        assertThat(viewColumns).contains(d.get("access").get("jdbc").get("keyColumn").asString());
        assertThat(viewColumns).containsAll(declared);
    }
}
