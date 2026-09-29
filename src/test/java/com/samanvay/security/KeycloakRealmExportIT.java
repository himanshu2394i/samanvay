package com.samanvay.security;

import static com.samanvay.shared.test.KeycloakTestSupport.BROKER_ALIAS;
import static com.samanvay.shared.test.KeycloakTestSupport.CITIZEN;
import static com.samanvay.shared.test.KeycloakTestSupport.DEPARTMENT;
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
    void staffSignInIsPasskeyOrPasswordPlusTotpAndNothingElse() throws Exception {
        JsonNode realm = admin("/admin/realms/" + STAFF);
        // the whole browser flow, exactly: any extra alternative would be a new way in
        assertThat(shape(STAFF, realm.get("browserFlow").asString())).containsExactly(
                "0 auth-cookie ALTERNATIVE",
                "0 [staff browser forms] ALTERNATIVE",
                "1 auth-username-password-form REQUIRED",
                "1 [staff second factor] CONDITIONAL",
                "2 conditional-credential REQUIRED {credentials=webauthn-passwordless, included=false}",
                "2 auth-otp-form REQUIRED");
        assertThat(shape(STAFF, realm.get("directGrantFlow").asString())).containsExactly(
                "0 direct-grant-validate-username REQUIRED",
                "0 direct-grant-validate-password REQUIRED",
                "0 direct-grant-validate-otp REQUIRED");
        // reset: TOTP before the new password, and the TOTP itself is never reset
        assertThat(realm.get("resetPasswordAllowed").asBoolean()).isFalse();
        assertThat(shape(STAFF, realm.get("resetCredentialsFlow").asString())).containsExactly(
                "0 reset-credentials-choose-user REQUIRED",
                "0 reset-credential-email REQUIRED",
                "0 auth-otp-form REQUIRED",
                "0 reset-password REQUIRED");

        JsonNode totp = admin("/admin/realms/" + STAFF + "/authentication/required-actions/CONFIGURE_TOTP");
        assertThat(totp.get("enabled").asBoolean()).isTrue();
        assertThat(totp.get("defaultAction").asBoolean()).isTrue();
    }

    @Test
    void passkeysRequireUserVerificationWithDefaultAttestationAndAuthenticators() throws Exception {
        for (String realmName : List.of(STAFF, CITIZEN)) {
            JsonNode realm = admin("/admin/realms/" + realmName);
            assertThat(realm.get("webAuthnPolicyPasswordlessPasskeysEnabled").asBoolean()).as(realmName).isTrue();
            assertThat(realm.get("webAuthnPolicyPasswordlessUserVerificationRequirement").asString())
                    .as(realmName + " user verification")
                    .isEqualTo("required");
            assertThat(realm.get("webAuthnPolicyPasswordlessAttestationConveyancePreference").asString())
                    .isEqualTo("not specified");
            assertThat(realm.get("webAuthnPolicyPasswordlessAuthenticatorAttachment").asString())
                    .isEqualTo("not specified");
            assertThat(realm.get("webAuthnPolicyPasswordlessAcceptableAaguids")).isEmpty();
            assertThat(realm.get("webAuthnPolicyPasswordlessRpEntityName").asString()).isNotBlank();
            JsonNode action = admin(
                    "/admin/realms/" + realmName + "/authentication/required-actions/webauthn-register-passwordless");
            assertThat(action.get("enabled").asBoolean()).as(realmName + " passkey registration").isTrue();
        }
    }

    @Test
    void citizenSignInIsEmailCodeOrPasskeyWithoutPasswordsOrTotp() throws Exception {
        JsonNode realm = admin("/admin/realms/" + CITIZEN);
        assertThat(shape(CITIZEN, realm.get("browserFlow").asString())).containsExactly(
                "0 auth-cookie ALTERNATIVE",
                // acts only on kc_idp_hint (no default provider): the way in to the department IdP
                "0 identity-provider-redirector ALTERNATIVE",
                "0 [citizen browser forms] ALTERNATIVE",
                "1 auth-username-form REQUIRED",
                "1 [citizen email code] CONDITIONAL",
                "2 conditional-credential REQUIRED {credentials=webauthn-passwordless, included=false}",
                "2 samanvay-email-otp REQUIRED {length=6, maxAttempts=5, ttlSeconds=300}");
        // no password or TOTP enrolment, ever
        for (String action : List.of("CONFIGURE_TOTP", "UPDATE_PASSWORD")) {
            JsonNode a = admin("/admin/realms/" + CITIZEN + "/authentication/required-actions/" + action);
            assertThat(a.get("enabled").asBoolean()).as(action).isFalse();
            assertThat(a.get("defaultAction").asBoolean()).as(action).isFalse();
        }
        assertThat(realm.get("resetPasswordAllowed").asBoolean()).isFalse();
        // registration asks for no password; the email is verified by link before the first token
        assertThat(realm.get("verifyEmail").asBoolean()).isTrue();
        assertThat(shape(CITIZEN, realm.get("registrationFlow").asString())).containsExactly(
                "0 [citizen registration form] REQUIRED",
                "1 registration-user-creation REQUIRED",
                "1 registration-recaptcha-action DISABLED",
                "1 registration-terms-and-conditions DISABLED");
        // mail goes to the dev/test catcher
        assertThat(realm.get("smtpServer").get("host").asString()).isEqualTo("mailpit");
    }

    @Test
    void citizenDirectGrantsAreDeniedByFlowAndOnEveryClient() throws Exception {
        JsonNode realm = admin("/admin/realms/" + CITIZEN);
        assertThat(shape(CITIZEN, realm.get("directGrantFlow").asString()))
                .containsExactly("0 deny-access-authenticator REQUIRED");
        for (JsonNode client : admin("/admin/realms/" + CITIZEN + "/clients")) {
            assertThat(client.path("directAccessGrantsEnabled").asBoolean(false))
                    .as(client.get("clientId").asString())
                    .isFalse();
        }
    }

    @Test
    void citizenRealmBrokersToTheMockDepartmentRealm() throws Exception {
        String base = "/admin/realms/" + CITIZEN + "/identity-provider/instances";
        assertThat(names(admin(base), "alias")).containsExactly(BROKER_ALIAS);
        JsonNode idp = admin(base + "/" + BROKER_ALIAS);
        assertThat(idp.get("providerId").asString()).isEqualTo("oidc");
        assertThat(idp.get("enabled").asBoolean()).isTrue();
        assertThat(idp.get("storeToken").asBoolean()).isFalse();
        assertThat(idp.get("linkOnly").asBoolean()).isFalse();
        JsonNode config = idp.get("config");
        assertThat(config.get("clientId").asString()).isEqualTo("samanvay-citizen-broker");
        assertThat(config.get("clientAuthMethod").asString()).isEqualTo("client_secret_post");
        assertThat(config.get("validateSignature").asString()).isEqualTo("true");
        assertThat(config.get("useJwksUrl").asString()).isEqualTo("true");
        assertThat(config.get("pkceEnabled").asString()).isEqualTo("true");
        assertThat(config.get("pkceMethod").asString()).isEqualTo("S256");
        assertThat(config.get("authorizationUrl").asString()).contains("/realms/" + DEPARTMENT + "/");
        // a brokered sign-in creates a new citizen; it never attaches to an existing one
        assertThat(idp.get("firstBrokerLoginFlowAlias").asString()).isEqualTo("citizen first broker login");
        assertThat(shape(CITIZEN, "citizen first broker login")).containsExactly("0 idp-create-user-if-unique REQUIRED");

        // broker mappers: department claims -> session notes (never user attributes); unique username
        JsonNode mappers = admin(base + "/" + BROKER_ALIAS + "/mappers");
        List<String> lines = new ArrayList<>();
        for (JsonNode m : mappers) {
            java.util.TreeMap<String, String> mapperConfig = new java.util.TreeMap<>();
            m.get("config").properties().forEach(e -> {
                if (List.of("claim", "attribute", "attribute.value", "template", "target").contains(e.getKey())) {
                    mapperConfig.put(e.getKey(), e.getValue().asString());
                }
            });
            lines.add(m.get("identityProviderMapper").asString() + " " + mapperConfig.entrySet());
        }
        assertThat(lines).containsExactlyInAnyOrder(
                "oidc-username-idp-mapper [target=LOCAL, template=${ALIAS}.${CLAIM.sub}]",
                "hardcoded-user-session-attribute-idp-mapper [attribute=dept_idp_alias, attribute.value=dept-idp]",
                "oidc-user-session-note-idp-mapper [attribute=dept_idp_department, claim=department]",
                "oidc-user-session-note-idp-mapper [attribute=dept_idp_local_id_type, claim=local_id_type]",
                "oidc-user-session-note-idp-mapper [attribute=dept_idp_local_id, claim=local_id]");

        // ...and the citizen UI client releases exactly those notes as access-token claims
        JsonNode ui = admin("/admin/realms/" + CITIZEN + "/clients?clientId=samanvay-citizen-ui").get(0);
        assertThat(ui.get("defaultClientScopes").toString()).contains("department-idp");
        String scopeId = null;
        for (JsonNode s : admin("/admin/realms/" + CITIZEN + "/client-scopes")) {
            if ("department-idp".equals(s.get("name").asString())) {
                scopeId = s.get("id").asString();
            }
        }
        assertThat(scopeId).isNotNull();
        List<String> claims = new ArrayList<>();
        for (JsonNode m : admin("/admin/realms/" + CITIZEN + "/client-scopes/" + scopeId + "/protocol-mappers/models")) {
            assertThat(m.get("protocolMapper").asString()).isEqualTo("oidc-usersessionmodel-note-mapper");
            assertThat(m.get("config").get("access.token.claim").asString()).isEqualTo("true");
            assertThat(m.get("config").get("id.token.claim").asString()).isEqualTo("false");
            claims.add(m.get("config").get("user.session.note").asString() + "->" + m.get("config").get("claim.name").asString());
        }
        assertThat(claims).containsExactlyInAnyOrder(
                "dept_idp_alias->dept_idp",
                "dept_idp_department->dept_code",
                "dept_idp_local_id_type->dept_local_id_type",
                "dept_idp_local_id->dept_local_id");
    }

    @Test
    void mockDepartmentRealmIsPasswordPlusTotpWithAnAdminManagedLocalId() throws Exception {
        JsonNode realm = admin("/admin/realms/" + DEPARTMENT);
        assertThat(shape(DEPARTMENT, realm.get("browserFlow").asString())).containsExactly(
                "0 auth-cookie ALTERNATIVE",
                "0 [department browser forms] ALTERNATIVE",
                "1 auth-username-password-form REQUIRED",
                "1 auth-otp-form REQUIRED");
        assertThat(shape(DEPARTMENT, realm.get("directGrantFlow").asString()))
                .containsExactly("0 deny-access-authenticator REQUIRED");
        assertThat(realm.get("registrationAllowed").asBoolean()).isFalse();
        assertThat(realm.get("resetPasswordAllowed").asBoolean()).isFalse();
        JsonNode totp = admin("/admin/realms/" + DEPARTMENT + "/authentication/required-actions/CONFIGURE_TOTP");
        assertThat(totp.get("enabled").asBoolean()).isTrue();
        assertThat(totp.get("defaultAction").asBoolean()).isTrue();

        for (JsonNode client : admin("/admin/realms/" + DEPARTMENT + "/clients")) {
            assertThat(client.path("directAccessGrantsEnabled").asBoolean(false)).as(client.get("clientId").asString()).isFalse();
        }
        JsonNode broker = admin("/admin/realms/" + DEPARTMENT + "/clients?clientId=samanvay-citizen-broker").get(0);
        assertThat(broker.get("publicClient").asBoolean()).isFalse();
        assertThat(broker.get("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(broker.get("serviceAccountsEnabled").asBoolean()).isFalse();
        assertThat(broker.get("attributes").get("pkce.code.challenge.method").asString()).isEqualTo("S256");
        assertThat(broker.get("redirectUris")).hasSize(1);
        assertThat(broker.get("redirectUris").get(0).asString())
                .endsWith("/realms/" + CITIZEN + "/broker/" + BROKER_ALIAS + "/endpoint");

        for (String attribute : List.of("local_id", "local_id_type")) {
            JsonNode declared = null;
            for (JsonNode a : admin("/admin/realms/" + DEPARTMENT + "/users/profile").get("attributes")) {
                if (attribute.equals(a.get("name").asString())) {
                    declared = a;
                }
            }
            assertThat(declared).as(attribute + " declared").isNotNull();
            assertThat(declared.get("permissions").get("edit").toString()).as(attribute).isEqualTo("[\"admin\"]");
        }
        JsonNode dev = admin("/admin/realms/" + DEPARTMENT + "/users?username=dev-dept-citizen&exact=true").get(0);
        assertThat(dev.get("attributes").get("local_id").get(0).asString()).isNotBlank();
        assertThat(dev.get("attributes").get("local_id_type").get(0).asString()).isEqualTo("RATION");
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
        // nothing from the mock department realm is ever a bearer for the API
        for (String realm : List.of(STAFF, CITIZEN, DEPARTMENT)) {
            for (JsonNode client : admin("/admin/realms/" + realm + "/clients")) {
                String id = client.get("clientId").asString();
                if (!realm.equals(DEPARTMENT) && (id.startsWith("samanvay-") || id.startsWith("dept-"))) {
                    continue;
                }
                client.path("protocolMappers").forEach(m -> assertThat(m.path("config").path("included.custom.audience").asString(""))
                        .as(realm + "/" + id + " must not get the API audience")
                        .isNotEqualTo("samanvay-api"));
            }
        }
    }

    @Test
    void staffDepartmentIsAnAdminManagedAttributeReleasedAsAClaim() throws Exception {
        JsonNode profile = admin("/admin/realms/" + STAFF + "/users/profile");
        JsonNode department = null;
        for (JsonNode a : profile.get("attributes")) {
            if ("department".equals(a.get("name").asString())) {
                department = a;
            }
        }
        assertThat(department).as("department attribute declared").isNotNull();
        assertThat(department.get("permissions").get("edit").toString()).isEqualTo("[\"admin\"]");
        JsonNode ui = admin("/admin/realms/" + STAFF + "/clients?clientId=samanvay-staff-ui").get(0);
        assertThat(ui.get("defaultClientScopes").toString()).contains("\"department\"");
        JsonNode dept = admin("/admin/realms/" + STAFF + "/clients?clientId=dept-scholarship-dev").get(0);
        assertThat(dept.get("protocolMappers").toString())
                .contains("oidc-hardcoded-claim-mapper")
                .contains("\"claim.value\":\"SCHOLARSHIP\"");
    }

    /**
     * A flow as "level provider-or-[subflow] REQUIREMENT {config}" lines, in
     * execution order (nested sub-flows included).
     */
    private static List<String> shape(String realm, String flowAlias) throws Exception {
        JsonNode executions = admin("/admin/realms/" + realm + "/authentication/flows/"
                + URLEncoder.encode(flowAlias, StandardCharsets.UTF_8).replace("+", "%20") + "/executions");
        List<String> out = new ArrayList<>();
        for (JsonNode e : executions) {
            String what = e.path("authenticationFlow").asBoolean(false)
                    ? "[" + e.get("displayName").asString() + "]"
                    : e.get("providerId").asString();
            String line = e.get("level").asInt() + " " + what + " " + e.get("requirement").asString();
            if (e.hasNonNull("authenticationConfig")) {
                JsonNode config = admin("/admin/realms/" + realm + "/authentication/config/"
                        + e.get("authenticationConfig").asString()).get("config");
                line += " " + new java.util.TreeMap<>(JSON_MAPPER.convertValue(config, Map.class));
            }
            out.add(line);
        }
        return out;
    }

    private static final tools.jackson.databind.ObjectMapper JSON_MAPPER = KeycloakTestSupport.JSON;

    private static List<String> names(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(field == null ? n.asString() : n.get(field).asString()));
        return out;
    }
}
