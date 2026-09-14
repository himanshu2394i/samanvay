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
class FarmerPortalIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void farmerPagesAreASeparateProductSkin() {
        RestClient http = RestClient.create();
        String landing = http.get().uri(url("/farmer")).retrieve().body(String.class);
        String root = http.get().uri(url("/")).retrieve().body(String.class);
        assertThat(landing).contains("Government of Maharashtra");
        assertThat(landing).contains("Apply for subsidy");
        assertThat(landing).doesNotContain("Control plane");
        assertThat(landing).doesNotContain("Apply for scholarship");
        assertThat(root).contains("/farmer/");
        assertThat(root).contains("/licence/");
        assertThat(root).contains("/scholarship/");
        assertThat(root).contains("Citizen services");
        assertThat(root).doesNotContain("Control plane");
    }

    @Test
    void portalHttpFlowStartsFarmerSubsidyJourney() throws InterruptedException {
        RestClient http = RestClient.create();
        UUID citizenId = http.post()
                .uri(url("/api/identity/citizens"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        """
                        {
                          "nameLatin": "Suresh Patil",
                          "nameDevanagari": "सुरेश",
                          "givenName": "Suresh",
                          "familyName": "Patil",
                          "fatherName": "Kumar",
                          "dob": "1975-08-12",
                          "dobPrecision": "DAY",
                          "gender": "M",
                          "contactMasked": "77****09"
                        }
                        """)
                .retrieve()
                .body(UUID.class);
        assertThat(citizenId).isNotNull();

        String suffix = citizenId.toString().substring(0, 8);
        for (String department : new String[] {"REVENUE", "AGRICULTURE", "DBT"}) {
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
                            "FARMER-" + department + "-" + suffix,
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
                        "AGRICULTURE",
                        "purposeCode",
                        "FARMER_SUBSIDY",
                        "purposeText",
                        "Farmer subsidy",
                        "categories",
                        new String[] {"LAND_PARCEL", "CROP_RECORD", "BANK_ACCOUNT"}))
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
                .uri(url("/api/journeys/FARMER_SUBSIDY/start"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizenId, "submission", Map.of()))
                .retrieve()
                .body(Map.class);
        assertThat(started.get("journeyCode")).isEqualTo("FARMER_SUBSIDY");

        ApplicationSummary app = awaitApplication(http, citizenId);
        assertThat(app.referenceNo()).contains("-FAR-");
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
        throw new AssertionError("tracking did not project a farmer subsidy application");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
