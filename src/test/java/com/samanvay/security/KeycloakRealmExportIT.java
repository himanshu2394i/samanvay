package com.samanvay.security;

import static com.samanvay.shared.test.KeycloakTestSupport.CITIZEN;
import static com.samanvay.shared.test.KeycloakTestSupport.STAFF;
import static com.samanvay.shared.test.KeycloakTestSupport.admin;
import static com.samanvay.shared.test.KeycloakTestSupport.token;
import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.shared.test.KeycloakTestSupport;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Boots the pinned Keycloak with the committed realm exports (the same files
 * docker compose imports) and checks, through the admin REST API, the security
 * properties the application relies on. No admin-client dependency: plain JDK
 * HttpClient + Jackson (see {@link KeycloakTestSupport}).
 */
class KeycloakRealmExportIT {

    @Test
    void realmRolesExist() throws Exception {
        assertThat(names(admin("/admin/realms/" + STAFF + "/roles"), "name"))
                .contains("officer", "reviewer", "admin", "department")
                .doesNotContain("citizen");
        assertThat(names(admin("/admin/realms/" + CITIZEN + "/roles"), "name")).contains("citizen");
    }

    @Test
    void staffBrowserAndDirectGrantRequireTotp() throws Exception {
        JsonNode realm = admin("/admin/realms/" + STAFF);
        String browser = realm.get("browserFlow").asString();
        String direct = realm.get("directGrantFlow").asString();
        assertThat(browser).isNotEqualTo("browser");
        assertThat(requirementOf(STAFF, browser, "auth-otp-form")).isEqualTo("REQUIRED");
        assertThat(requirementOf(STAFF, browser, "auth-username-password-form")).isEqualTo("REQUIRED");
        assertThat(requirementOf(STAFF, direct, "direct-grant-validate-otp")).isEqualTo("REQUIRED");

        JsonNode totp = admin("/admin/realms/" + STAFF + "/authentication/required-actions/CONFIGURE_TOTP");
        assertThat(totp.get("enabled").asBoolean()).isTrue();
        assertThat(totp.get("defaultAction").asBoolean()).isTrue();
    }

    @Test
    void citizenBrowserFlowRequiresOtp() throws Exception {
        String browser = admin("/admin/realms/" + CITIZEN).get("browserFlow").asString();
        assertThat(requirementOf(CITIZEN, browser, "auth-otp-form")).isEqualTo("REQUIRED");
    }

    @Test
    void passkeysPolicyEnabledInBothRealms() throws Exception {
        for (String realmName : List.of(STAFF, CITIZEN)) {
            JsonNode realm = admin("/admin/realms/" + realmName);
            assertThat(realm.get("webAuthnPolicyPasswordlessPasskeysEnabled").asBoolean())
                    .as(realmName + " passkeys")
                    .isTrue();
            assertThat(realm.get("webAuthnPolicyPasswordlessRpEntityName").asString()).isNotBlank();
            JsonNode action = admin(
                    "/admin/realms/" + realmName + "/authentication/required-actions/webauthn-register-passwordless");
            assertThat(action.get("enabled").asBoolean()).as(realmName + " passkey registration").isTrue();
        }
    }

    @Test
    void departmentClientIsServiceAccountWithSourceScopes() throws Exception {
        JsonNode client = admin("/admin/realms/" + STAFF + "/clients?clientId=dept-scholarship-dev").get(0);
        assertThat(client.get("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(client.get("publicClient").asBoolean()).isFalse();
        List<String> scopes = new ArrayList<>();
        client.get("defaultClientScopes").forEach(n -> scopes.add(n.asString()));
        client.get("optionalClientScopes").forEach(n -> scopes.add(n.asString()));
        assertThat(scopes).contains("source:revenue-rest-mock", "source:education-soap-mock", "source:dbt-rest-mock");

        String id = client.get("id").asString();
        String secret = admin("/admin/realms/" + STAFF + "/clients/" + id + "/client-secret").get("value").asString();
        JsonNode tokenResponse = token(STAFF, Map.of(
                "grant_type", "client_credentials",
                "client_id", "dept-scholarship-dev",
                "client_secret", secret,
                "scope", "source:revenue-rest-mock source:education-soap-mock source:dbt-rest-mock"));
        JsonNode claims = KeycloakTestSupport.claims(tokenResponse.get("access_token").asString());
        assertThat(claims.get("client_id").asString()).isEqualTo("dept-scholarship-dev");
        assertThat(claims.get("azp").asString()).isEqualTo("dept-scholarship-dev");
        assertThat(claims.get("iss").asString()).endsWith("/realms/" + STAFF);
        assertThat(names(claims.get("realm_access").get("roles"), null)).contains("department");
        assertThat(claims.get("scope").asString())
                .contains("source:revenue-rest-mock", "source:education-soap-mock", "source:dbt-rest-mock");
    }

    @Test
    void uiClientsArePublicPkce() throws Exception {
        for (String[] rc : new String[][] {{STAFF, "samanvay-staff-ui"}, {CITIZEN, "samanvay-citizen-ui"}}) {
            JsonNode client = admin("/admin/realms/" + rc[0] + "/clients?clientId=" + rc[1]).get(0);
            assertThat(client.get("publicClient").asBoolean()).isTrue();
            assertThat(client.get("attributes").get("pkce.code.challenge.method").asString()).isEqualTo("S256");
            assertThat(client.get("directAccessGrantsEnabled").asBoolean()).isFalse();
        }
    }

    @Test
    void onlyTheApisOwnClientsCarryTheSamanvayApiAudience() throws Exception {
        for (String[] rc : new String[][] {
            {STAFF, "samanvay-staff-ui"}, {STAFF, "dept-scholarship-dev"}, {CITIZEN, "samanvay-citizen-ui"}
        }) {
            JsonNode client = admin("/admin/realms/" + rc[0] + "/clients?clientId=" + rc[1]).get(0);
            List<String> audiences = new ArrayList<>();
            client.path("protocolMappers").forEach(m -> {
                if ("oidc-audience-mapper".equals(m.get("protocolMapper").asString())
                        && "true".equals(m.get("config").path("access.token.claim").asString())) {
                    audiences.add(m.get("config").path("included.custom.audience").asString());
                }
            });
            assertThat(audiences).as(rc[1] + " audience mapper").containsExactly("samanvay-api");
        }
        for (String realm : List.of(STAFF, CITIZEN)) {
            for (JsonNode client : admin("/admin/realms/" + realm + "/clients")) {
                String id = client.get("clientId").asString();
                if (id.startsWith("samanvay-") || id.startsWith("dept-")) {
                    continue;
                }
                client.path("protocolMappers").forEach(m -> assertThat(m.path("config").path("included.custom.audience").asString(""))
                        .as(realm + "/" + id + " must not get the API audience")
                        .isNotEqualTo("samanvay-api"));
            }
        }
    }

    private static String requirementOf(String realm, String flowAlias, String providerId) throws Exception {
        JsonNode executions = admin("/admin/realms/" + realm + "/authentication/flows/"
                + URLEncoder.encode(flowAlias, StandardCharsets.UTF_8).replace("+", "%20") + "/executions");
        for (JsonNode e : executions) {
            if (e.has("providerId") && providerId.equals(e.get("providerId").asString())) {
                return e.get("requirement").asString();
            }
        }
        return "ABSENT";
    }

    private static List<String> names(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(field == null ? n.asString() : n.get(field).asString()));
        return out;
    }
}
