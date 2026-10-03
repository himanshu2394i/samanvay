package com.samanvay.identity.internal.proof;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Docker-free guard over the COMMITTED realm exports (the files docker compose and the Keycloak ITs
 * import): the citizen realm's broker, its mappers and the claims the link-proof provider reads are
 * one consistent chain, and the mock department realm is wired to match. The Keycloak ITs then prove
 * that chain against a live Keycloak.
 */
class DepartmentBrokerRealmExportTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DEPT_REALM = "samanvay-department";

    private final JsonNode citizen = realm("samanvay-citizen");
    private final JsonNode department = realm(DEPT_REALM);

    @Test
    void theMockDepartmentRealmIsImportedAlongsideTheOthers() {
        assertThat(department.get("realm").asString()).isEqualTo(DEPT_REALM);
        assertThat(department.get("enabled").asBoolean()).isTrue();
        assertThat(realm("samanvay-staff").get("realm").asString()).isEqualTo("samanvay-staff");
    }

    @Test
    void theCitizenBrokerPointsAtTheMockDepartmentRealmAndItsClient() {
        JsonNode idp = citizen.get("identityProviders").get(0);
        assertThat(citizen.get("identityProviders")).hasSize(1);
        assertThat(idp.get("alias").asString()).isEqualTo("dept-idp");
        assertThat(idp.get("providerId").asString()).isEqualTo("oidc");
        assertThat(idp.get("enabled").asBoolean()).isTrue();
        assertThat(idp.get("storeToken").asBoolean()).as("brokered tokens are not stored").isFalse();
        assertThat(idp.get("linkOnly").asBoolean()).isFalse();
        JsonNode config = idp.get("config");
        for (String url : List.of("authorizationUrl", "tokenUrl", "jwksUrl", "logoutUrl")) {
            assertThat(config.get(url).asString()).as(url).contains("/realms/" + DEPT_REALM + "/protocol/openid-connect/");
        }
        assertThat(config.get("issuer").asString()).endsWith("/realms/" + DEPT_REALM);
        assertThat(config.get("validateSignature").asString()).isEqualTo("true");
        assertThat(config.get("useJwksUrl").asString()).isEqualTo("true");
        assertThat(config.get("pkceEnabled").asString()).isEqualTo("true");
        assertThat(config.get("pkceMethod").asString()).isEqualTo("S256");

        JsonNode client = client(department, config.get("clientId").asString());
        assertThat(client.get("publicClient").asBoolean()).isFalse();
        assertThat(client.get("secret").asString()).as("broker and client share the placeholder secret")
                .isEqualTo(config.get("clientSecret").asString());
        assertThat(client.get("directAccessGrantsEnabled").asBoolean()).isFalse();
        assertThat(client.get("serviceAccountsEnabled").asBoolean()).isFalse();
        assertThat(client.get("attributes").get("pkce.code.challenge.method").asString()).isEqualTo("S256");
        // exactly the citizen realm's broker endpoint may receive the code
        assertThat(strings(client.get("redirectUris")))
                .containsExactly(config.get("authorizationUrl").asString()
                        .replaceAll("/realms/.*", "/realms/samanvay-citizen/broker/dept-idp/endpoint"));
    }

    @Test
    void aBrokeredSignInCreatesAFreshCitizenAndNeverAttachesToAnExistingOne() {
        assertThat(citizen.get("identityProviders").get(0).get("firstBrokerLoginFlowAlias").asString())
                .isEqualTo("citizen first broker login");
        JsonNode flow = flow(citizen, "citizen first broker login");
        assertThat(executions(flow)).containsExactly("idp-create-user-if-unique REQUIRED");
        assertThat(mapperTypes()).contains("oidc-username-idp-mapper");
        assertThat(mapper("unique brokered username").get("config").get("template").asString())
                .isEqualTo("${ALIAS}.${CLAIM.sub}");
    }

    @Test
    void brokerMappersCarryDepartmentAndLocalIdIntoSessionNotesTheProviderReadsAsClaims() {
        // department token claim -> session note (broker mapper); Keycloak names the note after the claim
        for (JsonNode m : citizen.get("identityProviderMappers")) {
            assertThat(m.get("identityProviderAlias").asString()).isEqualTo("dept-idp");
        }
        JsonNode noteMapper = mapper("department claims to session notes");
        assertThat(noteMapper.get("identityProviderMapper").asString()).isEqualTo("oidc-user-session-note-idp-mapper");
        assertThat(noteMapper.get("config").get("are.claim.values.regex").asString()).isEqualTo("true");
        List<String> noteNames = new ArrayList<>();
        for (JsonNode pair : JSON.readTree(noteMapper.get("config").get("claims").asString())) {
            assertThat(pair.get("value").asString()).as("any value").isEqualTo(".*");
            noteNames.add(pair.get("key").asString());
        }
        assertThat(noteNames).containsExactly("department", "local_id_type", "local_id");
        JsonNode alias = mapper("department alias");
        assertThat(alias.get("identityProviderMapper").asString()).isEqualTo("hardcoded-user-session-attribute-idp-mapper");
        assertThat(alias.get("config").get("attribute.value").asString()).isEqualTo("dept-idp");

        // session note -> citizen access-token claim (client scope of the citizen UI client)
        JsonNode scope = null;
        for (JsonNode s : citizen.get("clientScopes")) {
            if ("department-idp".equals(s.get("name").asString())) {
                scope = s;
            }
        }
        assertThat(scope).isNotNull();
        Map<String, String> noteToClaim = new TreeMap<>();
        for (JsonNode m : scope.get("protocolMappers")) {
            assertThat(m.get("protocolMapper").asString()).isEqualTo("oidc-usersessionmodel-note-mapper");
            assertThat(m.get("config").get("access.token.claim").asString()).isEqualTo("true");
            assertThat(m.get("config").get("id.token.claim").asString()).isEqualTo("false");
            noteToClaim.put(m.get("config").get("user.session.note").asString(), m.get("config").get("claim.name").asString());
        }
        assertThat(client(citizen, "samanvay-citizen-ui").get("defaultClientScopes").toString()).contains("department-idp");
        assertThat(noteToClaim)
                .containsEntry(alias.get("config").get("attribute").asString(), DepartmentBrokerLinkProofProvider.CLAIM_IDP)
                .containsEntry("department", DepartmentBrokerLinkProofProvider.CLAIM_DEPARTMENT)
                .containsEntry("local_id_type", DepartmentBrokerLinkProofProvider.CLAIM_LOCAL_ID_TYPE)
                .containsEntry("local_id", DepartmentBrokerLinkProofProvider.CLAIM_LOCAL_ID)
                .hasSize(4);
        // and it is a note (set server-side per session), never a user attribute the citizen could edit
        assertThat(mapperTypes()).doesNotContain("oidc-user-attribute-idp-mapper", "hardcoded-attribute-idp-mapper");
    }

    @Test
    void theDepartmentClientReleasesTheIdentityTheBrokerMaps() {
        JsonNode client = client(department, "samanvay-citizen-broker");
        Map<String, JsonNode> byClaim = new TreeMap<>();
        for (JsonNode m : client.get("protocolMappers")) {
            byClaim.put(m.get("config").get("claim.name").asString(), m);
        }
        assertThat(byClaim.keySet()).contains("department", "local_id_type", "local_id", "email", "given_name", "family_name");
        for (String claim : List.of("department", "local_id_type", "local_id")) {
            assertThat(byClaim.get(claim).get("config").get("id.token.claim").asString()).as(claim).isEqualTo("true");
        }
        assertThat(byClaim.get("department").get("config").get("claim.value").asString()).isEqualTo("REVENUE");
        assertThat(byClaim.get("local_id").get("config").get("user.attribute").asString()).isEqualTo("local_id");
        assertThat(byClaim.get("local_id_type").get("config").get("user.attribute").asString()).isEqualTo("local_id_type");
        // no API audience: nothing from this realm is ever a bearer for the API
        assertThat(client.toString()).doesNotContain("samanvay-api");
    }

    @Test
    void theMockDepartmentIdentityIsAdminManagedAndSignInIsPasswordPlusTotp() {
        JsonNode profile = JSON.readTree(department.get("components").get("org.keycloak.userprofile.UserProfileProvider")
                .get(0).get("config").get("kc.user.profile.config").get(0).asString());
        for (JsonNode a : profile.get("attributes")) {
            String name = a.get("name").asString();
            if (name.equals("local_id") || name.equals("local_id_type")) {
                assertThat(a.get("permissions").get("edit").toString()).as(name).isEqualTo("[\"admin\"]");
            }
        }
        assertThat(department.get("browserFlow").asString()).isEqualTo("department browser");
        assertThat(executions(flow(department, "department browser forms")))
                .containsExactly("auth-username-password-form REQUIRED", "auth-otp-form REQUIRED");
        assertThat(executions(flow(department, department.get("directGrantFlow").asString())))
                .containsExactly("deny-access-authenticator REQUIRED");
        assertThat(department.get("registrationAllowed").asBoolean()).isFalse();
        assertThat(department.get("resetPasswordAllowed").asBoolean()).isFalse();
        for (JsonNode c : department.get("clients")) {
            assertThat(c.path("directAccessGrantsEnabled").asBoolean(false)).as(c.get("clientId").asString()).isFalse();
        }
        JsonNode user = department.get("users").get(0);
        assertThat(user.get("username").asString()).isEqualTo("dev-dept-citizen");
        assertThat(user.get("attributes").get("local_id").get(0).asString()).isNotBlank();
        assertThat(user.get("credentials").get(0).get("temporary").asBoolean()).as("dev password must be changed").isTrue();
        assertThat(user.get("requiredActions").toString()).contains("CONFIGURE_TOTP", "UPDATE_PASSWORD");
    }

    @Test
    void theCitizenBrowserFlowOffersTheRedirectorOnlyForAnIdpHintAndKeepsTheExistingPaths() {
        JsonNode top = flow(citizen, citizen.get("browserFlow").asString());
        assertThat(executions(top)).containsExactly(
                "auth-cookie ALTERNATIVE",
                "identity-provider-redirector ALTERNATIVE",
                "[citizen browser forms] ALTERNATIVE");
        for (JsonNode e : top.get("authenticationExecutions")) {
            if ("identity-provider-redirector".equals(e.path("authenticator").asString())) {
                assertThat(e.has("authenticatorConfig")).as("no default provider: acts only on kc_idp_hint").isFalse();
            }
        }
        assertThat(executions(flow(citizen, "citizen browser forms"))).containsExactly("auth-username-password-form REQUIRED");
        assertThat(executions(flow(citizen, citizen.get("directGrantFlow").asString())))
                .containsExactly("deny-access-authenticator REQUIRED");
    }

    private JsonNode mapper(String name) {
        for (JsonNode m : citizen.get("identityProviderMappers")) {
            if (name.equals(m.get("name").asString())) {
                return m;
            }
        }
        throw new AssertionError("no broker mapper " + name);
    }

    private List<String> mapperTypes() {
        List<String> out = new ArrayList<>();
        citizen.get("identityProviderMappers").forEach(m -> out.add(m.get("identityProviderMapper").asString()));
        return out;
    }

    private static JsonNode client(JsonNode realm, String clientId) {
        for (JsonNode c : realm.get("clients")) {
            if (clientId.equals(c.get("clientId").asString())) {
                return c;
            }
        }
        throw new AssertionError("no client " + clientId);
    }

    private static JsonNode flow(JsonNode realm, String alias) {
        for (JsonNode f : realm.get("authenticationFlows")) {
            if (alias.equals(f.get("alias").asString())) {
                return f;
            }
        }
        throw new AssertionError("no flow " + alias);
    }

    private static List<String> executions(JsonNode flow) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : flow.get("authenticationExecutions")) {
            String what = e.has("flowAlias") ? "[" + e.get("flowAlias").asString() + "]" : e.get("authenticator").asString();
            out.add(what + " " + e.get("requirement").asString());
        }
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    private static JsonNode realm(String name) {
        try (InputStream in = DepartmentBrokerRealmExportTest.class.getResourceAsStream("/keycloak-realms/" + name + "-realm.json")) {
            assertThat(in).as("committed realm export " + name).isNotNull();
            return JSON.readTree(in);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
