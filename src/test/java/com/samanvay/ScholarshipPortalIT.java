package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import com.samanvay.tracking.api.ApplicationSummary;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
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
        assertThat(root).contains("Scholarship");
        assertThat(root).contains("/licence/");
        assertThat(root).contains("/farmer/");
        assertThat(root).contains("Citizen services");
        assertThat(root).doesNotContain("Control plane");

        String licence = http.get().uri(url("/licence")).retrieve().body(String.class);
        String farmer = http.get().uri(url("/farmer")).retrieve().body(String.class);
        assertThat(licence).contains("Apply for licence");
        assertThat(licence).contains("Government of Maharashtra");
        assertThat(farmer).contains("Farmer subsidy");
        assertThat(farmer).contains("Connect accounts");
        assertThat(farmer).contains("Agriculture");
        assertThat(farmer).doesNotContain("/onboard.html");
        assertThat(farmer).doesNotContain("Bind a catalog journey");
    }

    @Test
    void portalHttpFlowStartsPostMatricScholarshipJourney() {
        // Signed-in citizen: self-registration binds the record to this token subject.
        RestClient http = TestHttp.as(TestTokens.citizen("cit-" + UUID.randomUUID()));
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
                            "LOCAL_ID_OTP",
                            "proof",
                            "000000"))
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
                        "purposeCode",
                        "SCHOLARSHIP_ELIGIBILITY"))
                .retrieve()
                .body(Map.class);
        assertThat(request.get("id")).isNotNull();

        http.post()
                .uri(url("/api/consent/requests/" + request.get("id") + "/grant"))
                .contentType(MediaType.APPLICATION_JSON)
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

    private ApplicationSummary awaitApplication(RestClient http, UUID citizenId) {
        return Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(
                        () -> http.get()
                                .uri(url("/api/applications?citizenId=" + citizenId + "&size=5"))
                                .retrieve()
                                .body(ApplicationSummary[].class),
                        apps -> apps != null && apps.length > 0)[0];
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
