package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.tracking.api.ApplicationSummary;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IssuedRecordsIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void lockerListsIssuedDocumentsForARealDepartmentSystem() {
        RestClient http = RestClient.create();
        List<?> revenue = http.get()
                .uri(url("/api/connector/issued-documents?departmentCode=REVENUE"))
                .retrieve()
                .body(List.class);
        assertThat(revenue).isNotEmpty();
        String blob = revenue.toString();
        assertThat(blob).contains("Income");
        assertThat(blob).contains("Aaple Sarkar");
        assertThat(blob).contains("Mahabhulekh");
        assertThat(blob).contains("not live");
    }

    @Test
    void applicationIssuedRecordsAreFetchedLiveAndNotWrittenToTracking() throws InterruptedException {
        RestClient http = RestClient.create();
        UUID citizenId = http.post()
                .uri(url("/api/identity/citizens"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        """
                        {
                          "nameLatin": "Anita Deshmukh",
                          "nameDevanagari": "अनिता",
                          "givenName": "Anita",
                          "familyName": "Deshmukh",
                          "fatherName": "Prakash",
                          "dob": "2003-06-12",
                          "dobPrecision": "DAY",
                          "gender": "F",
                          "contactMasked": "98****11"
                        }
                        """)
                .retrieve()
                .body(UUID.class);

        String suffix = citizenId.toString().substring(0, 8);
        for (String department : new String[] {"REVENUE", "EDUCATION", "DBT"}) {
            http.post()
                    .uri(url("/api/identity/links"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "citizenId",
                            citizenId,
                            "departmentCode",
                            department,
                            "localIdType",
                            department,
                            "localId",
                            "PORTAL-" + department + "-" + suffix,
                            "provider",
                            "DIGILOCKER",
                            "proof",
                            "sandbox"))
                    .retrieve()
                    .toBodilessEntity();
        }

        Map<?, ?> request = http.post()
                .uri(url("/api/consent/requests"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "citizenId",
                        citizenId,
                        "requesterId",
                        "SCHOLARSHIP",
                        "purposeCode",
                        "SCHOLARSHIP_ELIGIBILITY",
                        "purposeText",
                        "Post-matric scholarship eligibility",
                        "categories",
                        new String[] {"INCOME_CERTIFICATE", "CASTE_CERTIFICATE", "MARKS", "BANK_ACCOUNT"}))
                .retrieve()
                .body(Map.class);

        http.post()
                .uri(url("/api/consent/requests/" + request.get("id") + "/grant"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Auth-Jti", "ui-session")
                .body(Map.of("citizenId", citizenId))
                .retrieve()
                .toBodilessEntity();

        http.post()
                .uri(url("/api/journeys/POST_MATRIC_SCHOLARSHIP/start"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizenId, "submission", Map.of()))
                .retrieve()
                .toBodilessEntity();

        ApplicationSummary app = awaitApplication(http, citizenId);
        List<Map> records = http.get()
                .uri(url("/api/applications/" + app.referenceNo() + "/issued-records"))
                .retrieve()
                .body(List.class);
        assertThat(records).isNotEmpty();
        String blob = records.toString();
        assertThat(blob).contains("Income");
        assertThat(blob).contains("false");
        assertThat(blob.toLowerCase()).contains("income");
        assertThat(blob).containsAnyOf("1,85,000", "185000", "998877", "annualIncome");
        assertThat(jdbc.queryForObject(
                        "SELECT string_agg(t::text, ' ') FROM ("
                                + "SELECT tracking_application.*::text AS t FROM tracking_application "
                                + "UNION ALL SELECT tracking_step.*::text FROM tracking_step) s",
                        String.class))
                .doesNotContain("INCOME-AMT-998877")
                .doesNotContain("1,85,000");
    }

    private ApplicationSummary awaitApplication(RestClient http, UUID citizenId) throws InterruptedException {
        for (int i = 0; i < 80; i++) {
            ApplicationSummary[] apps = http.get()
                    .uri(url("/api/applications?citizenId=" + citizenId + "&size=5"))
                    .retrieve()
                    .body(ApplicationSummary[].class);
            if (apps != null && apps.length > 0) {
                return apps[0];
            }
            Thread.sleep(50);
        }
        throw new AssertionError("tracking did not project a scholarship application");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
