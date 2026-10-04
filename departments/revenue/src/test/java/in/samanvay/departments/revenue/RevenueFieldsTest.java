package in.samanvay.departments.revenue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.samanvay.departments.revenue.RevenueRecords.Doc;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A certificate answers with the fields its manifest entry declares and nothing else, whatever else the database row holds. */
class RevenueFieldsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static Doc doc(String type, Map<String, Object> fields) {
        return new Doc(type, "K-1", "RV-1001", LocalDate.parse("2026-04-12"), fields);
    }

    MockMvc mvc(Doc doc) {
        RevenueRecords records = new RevenueRecords() {
            public boolean personExists(String personId) { return true; }
            public List<Doc> forPerson(String personId, String type) { return List.of(doc); }
            public Optional<Doc> byKey(String type, String key) { return Optional.of(doc); }
        };
        return MockMvcBuilders.standaloneSetup(new RevenueController(records)).build();
    }

    @Test
    void an_income_certificate_carries_only_its_declared_fields() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("annualIncome", "185000");
        row.put("annualIncomeDisplay", "Rs 185000");
        row.put("holderName", "Asha Patil");
        row.put("district", "Nashik");
        row.put("issuerOffice", "Tahsildar, Nashik");
        row.put("financialYear", "2025-26");
        row.put("internalNotes", "audit pending");
        row.put("aadhaarLast4", "1234");
        String body = mvc(doc("INCOME_CERTIFICATE", row)).perform(get("/v1/income/K-1")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode json = JSON.readTree(body);
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("key", "issuedOn", "annualIncome", "annualIncomeDisplay", "holderName", "district",
                "issuerOffice", "financialYear");
    }

    @Test
    void a_declared_field_the_row_does_not_have_is_simply_absent() throws Exception {
        String body = mvc(doc("CASTE_CERTIFICATE", Map.of("holderName", "Asha Patil", "caste", "Maratha", "secret", "x")))
                .perform(get("/v1/caste/K-1")).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(body).propertyNames()).containsExactlyInAnyOrder("key", "issuedOn", "holderName", "caste");
    }

    @Test
    void every_declared_field_of_the_manifest_is_what_the_controller_allows() {
        assertThat(RevenueManifestController.DECLARED.keySet()).containsExactlyInAnyOrder("INCOME_CERTIFICATE", "CASTE_CERTIFICATE", "DOMICILE_CERTIFICATE");
        assertThat(RevenueManifestController.DECLARED.get("DOMICILE_CERTIFICATE")).extracting(RevenueManifestController.Field::name)
                .containsExactly("holderName", "state", "district", "issuerOffice");
    }
}
