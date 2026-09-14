package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.tracking.api.ApplicationSummary;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScholarshipPortalIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void portalPagesAreASeparateProductSkin() {
        RestClient http = RestClient.create();
        String landing = http.get().uri(url("/scholarship")).retrieve().body(String.class);
        String indexed = http.get().uri(url("/scholarship/index.html")).retrieve().body(String.class);
        String root = http.get().uri(url("/")).retrieve().body(String.class);
        assertThat(landing).contains("Government of Maharashtra");
        assertThat(landing).contains("Apply for scholarship");
        assertThat(landing).contains("Skip to main content");
        assertThat(landing).doesNotContain("Control plane");
        assertThat(indexed).contains("Scholarship Services");
        assertThat(indexed).doesNotContain("nav-tools");
        assertThat(root).contains("/scholarship/");
        assertThat(root).contains("Scholarship Portal");
        assertThat(root).contains("Apply for scholarship");
        assertThat(root).doesNotContain("Control plane");
    }

    @Test
    void portalHttpFlowStartsPostMatricScholarshipJourney() throws InterruptedException {
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
        assertThat(citizenId).isNotNull();

        String suffix = citizenId.toString().substring(0, 8);
        for (String department : new String[] {"REVENUE", "EDUCATION", "DBT"}) {
            Map<?, ?> link = http.post()
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
                    .body(Map.class);
            assertThat(link.get("departmentCode")).isEqualTo(department);
            assertThat(link.get("status")).isEqualTo("ACTIVE");
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
        assertThat(request.get("id")).isNotNull();

        http.post()
                .uri(url("/api/consent/requests/" + request.get("id") + "/grant"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Auth-Jti", "ui-session")
                .body(Map.of("citizenId", citizenId))
                .retrieve()
                .toBodilessEntity();

        Map<?, ?> started = http.post()
                .uri(url("/api/journeys/POST_MATRIC_SCHOLARSHIP/start"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizenId, "submission", Map.of()))
                .retrieve()
                .body(Map.class);
        assertThat(started.get("citizenId").toString()).isEqualTo(citizenId.toString());
        assertThat(started.get("journeyCode")).isEqualTo("POST_MATRIC_SCHOLARSHIP");

        ApplicationSummary app = awaitApplication(http, citizenId);
        assertThat(app.referenceNo()).startsWith("MH-SCH-");
        assertThat(app.status()).isIn("SUBMITTED", "VERIFIED", "PARTIALLY_VERIFIED");
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
