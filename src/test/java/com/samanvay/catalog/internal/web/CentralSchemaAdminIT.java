package com.samanvay.catalog.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * The central schema is the shared vocabulary every department's fields are mapped onto. Admins can see it and add to it from
 * the staff console; a schema is never edited in place (published connectors map onto it), a change is a new version.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CentralSchemaAdminIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private RestClient admin() {
        return TestHttp.as(TestTokens.admin("schema-admin"));
    }

    private int post(RestClient who, Map<String, Object> body) {
        return who.post().uri(url("/api/catalog/schemas")).contentType(MediaType.APPLICATION_JSON).body(body).exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private static Map<String, Object> draft(String ref, String category, List<Map<String, Object>> fields) {
        return Map.of("ref", ref, "category", category, "fields", fields);
    }

    private static Map<String, Object> field(String name, String type, boolean required) {
        return Map.of("name", name, "type", type, "required", required);
    }

    @Test
    void the_seeded_schemas_are_listed_with_category_fields_types_and_required() {
        List<?> all = admin().get().uri(url("/api/catalog/schema-details")).retrieve().body(List.class);
        Map<?, ?> income = all.stream().map(o -> (Map<?, ?>) o).filter(m -> "Credential/IncomeCertificate@1".equals(m.get("ref"))).findFirst().orElseThrow();
        assertThat(income.get("category")).isEqualTo("INCOME_CERTIFICATE");
        assertThat(income.get("version")).isEqualTo(1);
        List<?> fields = (List<?>) income.get("fields");
        Map<?, ?> annual = fields.stream().map(o -> (Map<?, ?>) o).filter(f -> "annualIncome".equals(f.get("name"))).findFirst().orElseThrow();
        assertThat(annual.get("type")).isEqualTo("integer");
        assertThat(annual.get("required")).isEqualTo(true);
    }

    @Test
    void an_admin_adds_a_new_schema_and_it_shows_up_in_both_lists() {
        String ref = "Credential/Test" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@1";
        int status = post(admin(), draft(ref, "TEST_CATEGORY_A", List.of(field("holderName", "string", true), field("amount", "integer", false))));
        assertThat(status).isEqualTo(200);

        assertThat(admin().get().uri(url("/api/catalog/schemas")).retrieve().body(List.class)).contains(ref);
        Map<?, ?> created = ((List<?>) admin().get().uri(url("/api/catalog/schema-details")).retrieve().body(List.class)).stream()
                .map(o -> (Map<?, ?>) o).filter(m -> ref.equals(m.get("ref"))).findFirst().orElseThrow();
        assertThat(created.get("category")).isEqualTo("TEST_CATEGORY_A");
        assertThat(((List<?>) created.get("fields"))).hasSize(2);
    }

    @Test
    void a_schema_is_never_edited_in_place_the_same_ref_is_refused() {
        String ref = "Credential/Once" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@1";
        assertThat(post(admin(), draft(ref, "TEST_CATEGORY_B", List.of(field("a", "string", true))))).isEqualTo(200);
        assertThat(post(admin(), draft(ref, "TEST_CATEGORY_B", List.of(field("a", "string", true), field("b", "string", false))))).isEqualTo(400);
    }

    @Test
    void a_bad_draft_is_refused_with_nothing_saved() {
        RestClient admin = admin();
        List<Map<String, Object>> ok = List.of(field("a", "string", true));
        assertThat(post(admin, draft("no-version", "CAT_X", ok))).as("ref without @version").isEqualTo(400);
        assertThat(post(admin, draft("Credential/Bad ref@1", "CAT_X", ok))).as("ref with a space").isEqualTo(400);
        assertThat(post(admin, draft("Credential/Okref@1", "lower case", ok))).as("category not UPPER_SNAKE").isEqualTo(400);
        assertThat(post(admin, draft("Credential/Okref@1", "CAT_X", List.of()))).as("no fields").isEqualTo(400);
        assertThat(post(admin, draft("Credential/Okref@1", "CAT_X", List.of(field("Bad Name", "string", true))))).as("field name").isEqualTo(400);
        assertThat(post(admin, draft("Credential/Okref@1", "CAT_X", List.of(field("a", "date", true))))).as("field type").isEqualTo(400);
        assertThat(post(admin, draft("Credential/Okref@1", "CAT_X", List.of(field("a", "string", true), field("a", "string", false))))).as("duplicate field").isEqualTo(400);
        assertThat(admin.get().uri(url("/api/catalog/schemas")).retrieve().body(List.class)).doesNotContain("Credential/Okref@1");
    }

    @Test
    void officers_can_read_the_schemas_but_only_admins_can_add_one() {
        RestClient officer = TestHttp.as(TestTokens.officer("schema-officer"));
        int read = officer.get().uri(url("/api/catalog/schema-details")).exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(read).isEqualTo(200);
        assertThat(post(officer, draft("Credential/Nope@1", "CAT_Y", List.of(field("a", "string", true))))).isEqualTo(403);
        RestClient citizen = TestHttp.as(TestTokens.citizen("schema-citizen"));
        int citizenRead = citizen.get().uri(url("/api/catalog/schema-details")).exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(citizenRead).isEqualTo(403);
    }
}
