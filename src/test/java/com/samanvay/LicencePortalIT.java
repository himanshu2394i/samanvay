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
    void portalHttpFlowStartsBusinessNocJourney() {
        // Signed-in citizen: self-registration binds the record to this token subject.
        RestClient http = TestHttp.as(TestTokens.citizen("cit-" + UUID.randomUUID()));
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
        // The real POLLUTION service (JDBC) keys by premise id; register this citizen's premise as held.
        com.samanvay.shared.test.RealDepartments.seedPremise("LICENCE-POLLUTION-" + suffix);

        Map<?, ?> request = http.post()
                .uri(url("/api/consent/requests"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "citizenId",
                        citizenId,
                        "purposeCode",
                        "BUSINESS_NOC"))
                .retrieve()
                .body(Map.class);

        http.post()
                .uri(url("/api/consent/requests/" + request.get("id") + "/grant"))
                .contentType(MediaType.APPLICATION_JSON)
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
