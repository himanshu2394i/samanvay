package com.samanvay.security;

import static com.samanvay.shared.test.KeycloakTestSupport.STAFF;
import static com.samanvay.shared.test.KeycloakTestSupport.CITIZEN;
import static com.samanvay.shared.test.KeycloakTestSupport.admin;
import static com.samanvay.shared.test.KeycloakTestSupport.claims;
import static com.samanvay.shared.test.KeycloakTestSupport.importUser;
import static com.samanvay.shared.test.KeycloakTestSupport.token;
import static com.samanvay.shared.test.KeycloakTestSupport.totp;
import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.KeycloakTestSupport;
import com.samanvay.shared.test.KeycloakTestSupport.BrowserLogin;
import com.samanvay.shared.test.PostgresContainerSupport;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.ProfileDraft;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The application validating tokens minted by the real, pinned Keycloak with
 * the committed realm exports - not TestTokens look-alikes. Keys come from the
 * realm's JWKS endpoint, exactly as in a deployment.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev") // the container's issuer is plain http, which only dev/demo accept
class KeycloakTokensIT extends PostgresContainerSupport {

    @LocalServerPort
    int port;

    @Autowired
    CitizenProfiles profiles;

    @DynamicPropertySource
    static void keycloakRealms(DynamicPropertyRegistry registry) {
        for (String[] r : new String[][] {{"staff", STAFF}, {"citizen", CITIZEN}}) {
            registry.add("samanvay.security." + r[0] + ".issuer-uri", () -> KeycloakTestSupport.issuer(r[1]));
            registry.add("samanvay.security." + r[0] + ".jwk-set-uri",
                    () -> KeycloakTestSupport.issuer(r[1]) + "/protocol/openid-connect/certs");
        }
    }

    @Test
    void departmentClientCredentialsTokenIsAccepted() throws Exception {
        JsonNode client = admin("/admin/realms/" + STAFF + "/clients?clientId=dept-scholarship-dev").get(0);
        String secret = admin("/admin/realms/" + STAFF + "/clients/" + client.get("id").asString() + "/client-secret")
                .get("value").asString();
        String accessToken = token(STAFF, Map.of(
                        "grant_type", "client_credentials",
                        "client_id", "dept-scholarship-dev",
                        "client_secret", secret))
                .get("access_token").asString();
        assertThat(claims(accessToken).get("aud").toString()).contains("samanvay-api");
        assertThat(claims(accessToken).get("department").asString()).isEqualTo("SCHOLARSHIP");

        assertThat(get(accessToken, "/api/catalog/departments")).isEqualTo(200);
        assertThat(get(accessToken, "/api/journeys/exceptions")).as("department is not an officer").isEqualTo(403);
    }

    @Test
    void staffUiTokenFromARealBrowserLoginIsAccepted() throws Exception {
        String secret = "kc-officer-totp-secret-01";
        importUser(STAFF, "kc-officer", "Kc-officer-pw-1", secret, "\"officer\"", Map.of("department", "SCHOLARSHIP"));

        BrowserLogin login = new BrowserLogin(STAFF, "samanvay-staff-ui")
                .submit(Map.of("username", "kc-officer", "password", "Kc-officer-pw-1"));
        assertThat(login.finished()).as("password alone does not finish the staff login").isFalse();
        login.submit(Map.of("otp", totp(secret)));
        String accessToken = login.accessToken();

        JsonNode claims = claims(accessToken);
        assertThat(claims.get("azp").asString()).isEqualTo("samanvay-staff-ui");
        assertThat(claims.get("typ").asString()).isEqualTo("Bearer");
        assertThat(claims.get("aud").toString()).contains("samanvay-api");
        assertThat(claims.get("department").asString()).isEqualTo("SCHOLARSHIP");
        assertThat(get(accessToken, "/api/journeys/exceptions")).isEqualTo(200);
        // the department claim binds the officer's consent requests
        UUID citizen = profiles.register(draft());
        assertThat(requestConsent(accessToken, citizen, "SCHOLARSHIP_ELIGIBILITY")).isEqualTo(200);
        assertThat(requestConsent(accessToken, citizen, "FARMER_SUBSIDY")).isEqualTo(403);
    }

    @Test
    void citizenTokenFromAnEmailCodeLoginIsAccepted() throws Exception {
        importUser(CITIZEN, "kc-citizen", null, null, "\"default-roles-samanvay-citizen\"", Map.of());
        java.time.Instant sent = java.time.Instant.now();
        BrowserLogin login = new BrowserLogin(CITIZEN, "samanvay-citizen-ui").submit(Map.of("username", "kc-citizen"));
        login.submit(Map.of("emailCode", KeycloakTestSupport.mailedCode("kc-citizen@test.samanvay.invalid", sent)));
        String accessToken = login.accessToken();

        JsonNode claims = claims(accessToken);
        assertThat(claims.get("aud").toString()).contains("samanvay-api");
        assertThat(claims.get("realm_access").toString()).contains("citizen");
        assertThat(get(accessToken, "/api/identity/proof-providers")).isEqualTo(200);
        assertThat(get(accessToken, "/api/journeys/exceptions")).as("citizen is not staff").isEqualTo(403);
    }

    @Test
    void adminCliPasswordGrantTokenIsRefused() throws Exception {
        // Keycloak will issue it (admin-cli allows direct grants and the staff
        // direct-grant flow is satisfied by password + TOTP) - the API must not accept it.
        String secret = "kc-cli-totp-secret-0001";
        importUser(STAFF, "kc-cli-officer", "Kc-cli-pw-1", secret, "\"officer\"", Map.of());
        String accessToken = token(STAFF, Map.of(
                        "grant_type", "password",
                        "client_id", "admin-cli",
                        "username", "kc-cli-officer",
                        "password", "Kc-cli-pw-1",
                        "totp", totp(secret)))
                .get("access_token").asString();
        assertThat(claims(accessToken).get("azp").asString()).isEqualTo("admin-cli");

        for (String route : List.of("/api/journeys/exceptions", "/api/catalog/departments", "/api/audit/head")) {
            assertThat(get(accessToken, route)).as(route).isEqualTo(401);
        }
    }

    private int requestConsent(String accessToken, UUID citizen, String purpose) {
        return TestHttp.as(accessToken).post().uri("http://localhost:" + port + "/api/consent/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizen, "purposeCode", purpose))
                .exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private static ProfileDraft draft() {
        return new ProfileDraft("Kc Citizen", "केसी", "Kc", "Citizen", "Father", LocalDate.of(1999, 9, 9), "DAY", "M", "90****09");
    }

    private int get(String accessToken, String path) {
        return TestHttp.as(accessToken).get().uri("http://localhost:" + port + path)
                .exchange((rq, rs) -> rs.getStatusCode().value());
    }
}
