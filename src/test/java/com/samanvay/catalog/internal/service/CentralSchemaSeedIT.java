package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.DepartmentManifest;
import com.samanvay.catalog.api.MappingSuggestion;
import com.samanvay.catalog.api.SchemaCatalog;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The central schema is seeded (decision: seed first, then onboard). It is derived from what the four departments
 * publish: every document category has exactly one central schema that names its category, and each central schema's
 * required fields can be mapped from the department's own fields.
 */
@SpringBootTest(classes = SamanvayApplication.class)
class CentralSchemaSeedIT extends PostgresIntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> DEPARTMENTS = List.of("revenue", "dbt", "education", "agriculture");

    @Autowired
    SchemaCatalog schemas;

    @Autowired
    JdbcClient jdbc;

    static DepartmentManifest manifest(String dept) throws IOException {
        try (var in = CentralSchemaSeedIT.class.getResourceAsStream("/manifests/" + dept + ".json")) {
            return CatalogServices.parseManifest(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** category -> central schema ref, by the schema's own "x-category". */
    List<String> schemasFor(String category) {
        List<String> out = new ArrayList<>();
        for (String ref : schemas.refs()) {
            JsonNode def = JSON.readTree(schemas.definition(ref));
            if (def.get("x-category") != null && category.equals(def.get("x-category").asString())) {
                out.add(ref);
            }
        }
        return out;
    }

    @Test
    void every_document_category_the_departments_publish_has_exactly_one_central_schema() throws Exception {
        for (String dept : DEPARTMENTS) {
            for (var doc : manifest(dept).documents()) {
                assertThat(schemasFor(doc.category())).as(dept + " / " + doc.category()).hasSize(1);
            }
        }
    }

    @Test
    void each_central_schema_names_its_fields_and_what_is_required() throws Exception {
        for (String dept : DEPARTMENTS) {
            for (var doc : manifest(dept).documents()) {
                JsonNode def = JSON.readTree(schemas.definition(schemasFor(doc.category()).get(0)));
                assertThat(def.get("properties")).as(doc.category()).isNotNull();
                assertThat(def.get("properties").size()).as(doc.category()).isGreaterThanOrEqualTo(3);
                // a schema with no `required` list (CropRecord, never had one) has nothing to check here
                if (def.get("required") != null) {
                    def.get("required").forEach(r -> assertThat(def.get("properties").has(r.asString())).as(doc.category() + " required " + r).isTrue());
                }
            }
        }
    }

    @Test
    void every_required_central_field_can_be_mapped_from_the_departments_published_fields() throws Exception {
        for (String dept : DEPARTMENTS) {
            for (var doc : manifest(dept).documents()) {
                JsonNode def = JSON.readTree(schemas.definition(schemasFor(doc.category()).get(0)));
                List<String> sources = doc.fields().stream().map(DepartmentManifest.Field::name).toList();
                List<String> targets = new ArrayList<>();
                def.get("properties").properties().forEach(e -> targets.add(e.getKey()));
                Set<String> mapped = new java.util.HashSet<>();
                for (MappingSuggestion s : new MappingSuggestor().suggest(sources, targets)) {
                    mapped.add(s.target());
                }
                if (def.get("required") != null) {
                    def.get("required").forEach(r -> assertThat(mapped).as(dept + " / " + doc.category() + " required " + r.asString()).contains(r.asString()));
                }
            }
        }
    }

    @Test
    void the_validation_contract_of_the_existing_schemas_is_unchanged() {
        assertThat(JSON.readTree(schemas.definition("Credential/IncomeCertificate@1")).get("required").toString()).isEqualTo("[\"annualIncome\"]");
        assertThat(JSON.readTree(schemas.definition("Credential/BankAccount@1")).get("required").toString()).isEqualTo("[\"accountRef\"]");
        assertThat(JSON.readTree(schemas.definition("LandParcel@1")).get("required").toString()).isEqualTo("[\"surveyNo\"]");
    }

    @Test
    void the_journeys_the_departments_publish_have_registered_consent_purposes() throws Exception {
        for (String dept : DEPARTMENTS) {
            for (var j : manifest(dept).journeys()) {
                Integer n = jdbc.sql("SELECT count(*) FROM catalog_purpose WHERE code = :c AND status = 'ACTIVE'")
                        .param("c", j.consentPurpose()).query(Integer.class).single();
                assertThat(n).as(dept + " journey " + j.code() + " purpose " + j.consentPurpose()).isEqualTo(1);
            }
        }
    }
}
