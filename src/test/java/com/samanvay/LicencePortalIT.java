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
class LicencePortalIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void licencePagesAreASeparateProductSkin() {
        RestClient http = RestClient.create();
        String landing = http.get().uri(url("/licence")).retrieve().body(String.class);
        String root = http.get().uri(url("/")).retrieve().body(String.class);
        assertThat(landing).contains("Government of Maharashtra");
        assertThat(landing).contains("Apply for licence");
        assertThat(landing).doesNotContain("Control plane");
        assertThat(landing).doesNotContain("Apply for scholarship");
        assertThat(root).contains("/licence/");
        assertThat(root).contains("/farmer/");
        assertThat(root).contains("/scholarship/");
        assertThat(root).contains("Citizen services");
        assertThat(root).doesNotContain("Control plane");
    }

    @Test
    void portalHttpFlowStartsBusinessNocJourney() throws InterruptedException {
        RestClient http = RestClient.create();
        UUID citizenId = http.post()
                .uri(url("/api/identity/citizens"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        """
                        {
                          "nameLatin": "Anita Desai",
                          "nameDevanagari": "अनिता",
                          "givenName": "Anita",
                          "familyName": "Desai",
                          "fatherName": "Ravi",
                          "dob": "1988-03-04",
                          "dobPrecision": "DAY",
                          "gender": "F",
                          "contactMasked": "98****11"
                        }
                        """)
                .retrieve()
                .body(UUID.class);
        assertThat(citizenId).isNotNull();

        String suffix = citizenId.toString().substring(0, 8);
        for (String department : new String[] {"MUNICIPAL", "FIRE", "POLLUTION", "REVENUE"}) {
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
                            "LICENCE-" + department + "-" + suffix,
                            "provider",
                            "DIGILOCKER",
                            "proof",
                            "sandbox"))
                    .retrieve()
                    .body(Map.class);
            assertThat(link.get("status")).isEqualTo("ACTIVE");
        }

        Map<?, ?> request = http.post()
                .uri(url("/api/consent/requests"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "citizenId",
                        citizenId,
                        "requesterId",
                        "INDUSTRY",
                        "purposeCode",
                        "BUSINESS_NOC",
                        "purposeText",
                        "Business licence / NOC",
                        "categories",
                        new String[] {"PROPERTY", "FIRE_NOC", "POLLUTION_CLEARANCE", "LAND_RECORD"}))
                .retrieve()
                .body(Map.class);

        http.post()
                .uri(url("/api/consent/requests/" + request.get("id") + "/grant"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Auth-Jti", "ui-session")
                .body(Map.of("citizenId", citizenId))
                .retrieve()
                .toBodilessEntity();

        Map<?, ?> started = http.post()
                .uri(url("/api/journeys/BUSINESS_NOC/start"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizenId, "submission", Map.of()))
                .retrieve()
                .body(Map.class);
        assertThat(started.get("journeyCode")).isEqualTo("BUSINESS_NOC");

        ApplicationSummary app = awaitApplication(http, citizenId);
        assertThat(app.referenceNo()).contains("-NOC-");
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
        throw new AssertionError("tracking did not project a licence application");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
