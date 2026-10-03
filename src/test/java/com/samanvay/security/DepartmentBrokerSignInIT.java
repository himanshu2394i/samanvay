package com.samanvay.security;

import static com.samanvay.shared.test.KeycloakTestSupport.BROKER_ALIAS;
import static com.samanvay.shared.test.KeycloakTestSupport.CITIZEN;
import static com.samanvay.shared.test.KeycloakTestSupport.DEPARTMENT;
import static com.samanvay.shared.test.KeycloakTestSupport.admin;
import static com.samanvay.shared.test.KeycloakTestSupport.brokeredCitizenAccessToken;
import static com.samanvay.shared.test.KeycloakTestSupport.claims;
import static com.samanvay.shared.test.KeycloakTestSupport.importUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.shared.test.KeycloakTestSupport.BrowserLogin;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A citizen signs in through the MOCK department identity provider, brokered by Keycloak (scripted
 * through the real login pages of both realms, see BrowserLogin). Needs Docker (pinned Keycloak);
 * runs in CI with the other Keycloak ITs.
 */
class DepartmentBrokerSignInIT {

    @Test
    void citizenBrokeredInThroughTheDepartmentIdpGetsATokenCarryingTheDepartmentIdentity() throws Exception {
        String localId = "RC-IT-" + UUID.randomUUID();
        Instant before = Instant.now().minusSeconds(5);

        String accessToken = brokeredCitizenAccessToken("broker-it-user", "RATION", localId);

        JsonNode claims = claims(accessToken);
        // a normal citizen-realm access token: the API accepts exactly these
        assertThat(claims.get("iss").asString()).endsWith("/realms/" + CITIZEN);
        assertThat(claims.get("azp").asString()).isEqualTo("samanvay-citizen-ui");
        assertThat(claims.get("typ").asString()).isEqualTo("Bearer");
        assertThat(claims.get("aud").toString()).contains("samanvay-api");
        assertThat(claims.get("realm_access").toString()).contains("citizen");
        // ...plus what the broker mappers carried over from the department's ID token
        assertThat(claims.get("dept_idp").asString()).isEqualTo(BROKER_ALIAS);
        assertThat(claims.get("dept_code").asString()).isEqualTo("REVENUE");
        assertThat(claims.get("dept_local_id_type").asString()).isEqualTo("RATION");
        assertThat(claims.get("dept_local_id").asString()).isEqualTo(localId);
        assertThat(Instant.ofEpochSecond(claims.get("auth_time").asLong())).isAfter(before);
        // a fresh citizen user; the realm uses the email as the user name (a clash with an existing citizen fails closed,
        // see aBrokeredSignInNeverAttachesToAnExistingCitizenWithTheSameEmail)
        assertThat(claims.get("preferred_username").asString()).isEqualTo("broker-it-user@test.samanvay.invalid");

        // Keycloak recorded the federated identity for that user
        JsonNode identities = admin("/admin/realms/" + CITIZEN + "/users/" + claims.get("sub").asString() + "/federated-identity");
        assertThat(identities).hasSize(1);
        assertThat(identities.get(0).get("identityProvider").asString()).isEqualTo(BROKER_ALIAS);
    }

    @Test
    void theDepartmentsOwnPasswordAloneDoesNotFinishTheBrokeredSignIn() throws Exception {
        String user = "broker-it-pw-only";
        String secret = "broker-it-pw-only-totp";
        importUser(DEPARTMENT, user, "Broker-it-pw-1", secret, "\"default-roles-samanvay-department\"",
                Map.of("local_id_type", "RATION", "local_id", "RC-PW-ONLY"));

        BrowserLogin login = BrowserLogin.brokered(CITIZEN, "samanvay-citizen-ui", BROKER_ALIAS);
        assertThat(login.page().body()).as("the department's login page, not the citizen realm's").contains("name=\"password\"");
        login.submit(Map.of("username", user, "password", "Broker-it-pw-1"));
        assertThat(login.finished()).isFalse();
        assertThat(login.page().body()).contains("id=\"kc-otp-login-form\"");
        login.submit(Map.of("otp", "000000"));
        assertThat(login.finished()).as("wrong TOTP").isFalse();
    }

    @Test
    void aPasswordSignInCarriesNoDepartmentClaims() throws Exception {
        importUser(CITIZEN, "broker-it-password", "Broker-it-cit-pw-1", null, "\"default-roles-samanvay-citizen\"", Map.of());
        BrowserLogin login = new BrowserLogin(CITIZEN, "samanvay-citizen-ui")
                .submit(Map.of("username", "broker-it-password", "password", "Broker-it-cit-pw-1"));

        JsonNode claims = claims(login.accessToken());
        for (String claim : new String[] {"dept_idp", "dept_code", "dept_local_id_type", "dept_local_id"}) {
            assertThat(claims.has(claim)).as(claim).isFalse();
        }
    }

    @Test
    void aBrokeredSignInNeverAttachesToAnExistingCitizenWithTheSameEmail() throws Exception {
        // Same username in both realms => same email. The department must not be able to claim the
        // existing citizen account by asserting its email.
        String name = "broker-it-clash";
        importUser(CITIZEN, name, null, null, "\"default-roles-samanvay-citizen\"", Map.of());
        String existingId = admin("/admin/realms/" + CITIZEN + "/users?username=" + name + "&exact=true").get(0).get("id").asString();
        String secret = "broker-it-clash-totp";
        importUser(DEPARTMENT, name, "Broker-it-pw-2", secret, "\"default-roles-samanvay-department\"",
                Map.of("local_id_type", "RATION", "local_id", "RC-CLASH"));

        BrowserLogin login = BrowserLogin.brokered(CITIZEN, "samanvay-citizen-ui", BROKER_ALIAS);
        login.submit(Map.of("username", name, "password", "Broker-it-pw-2"));
        login.submit(Map.of("otp", com.samanvay.shared.test.KeycloakTestSupport.totp(secret)));
        login.follow();

        assertThat(login.finished()).as("no token for a clash; last page: " + login.page().body()).isFalse();
        assertThat(admin("/admin/realms/" + CITIZEN + "/users/" + existingId + "/federated-identity"))
                .as("nothing was linked to the existing citizen")
                .isEmpty();
    }
}
