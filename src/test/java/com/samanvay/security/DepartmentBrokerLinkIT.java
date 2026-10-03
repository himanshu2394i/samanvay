package com.samanvay.security;

import static com.samanvay.shared.test.KeycloakTestSupport.CITIZEN;
import static com.samanvay.shared.test.KeycloakTestSupport.STAFF;
import static com.samanvay.shared.test.KeycloakTestSupport.brokeredCitizenAccessToken;
import static com.samanvay.shared.test.KeycloakTestSupport.claims;
import static com.samanvay.shared.test.KeycloakTestSupport.importUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.Link;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProviderInfo;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.shared.test.KeycloakTestSupport;
import com.samanvay.shared.test.KeycloakTestSupport.BrowserLogin;
import com.samanvay.shared.test.PostgresContainerSupport;
import com.samanvay.shared.test.TestHttp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The federated-identity story end to end, against the real pinned Keycloak and the committed realms: a
 * citizen brokered in through the mock department IdP obtains a token, and that token as the proof drives
 * {@code assertLink} to a verified link. Needs Docker (Keycloak + Postgres); runs in CI.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev") // the container's issuer is plain http, which only dev/demo accept
class DepartmentBrokerLinkIT extends PostgresContainerSupport {

    @LocalServerPort
    int port;

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityLinking linking;

    @DynamicPropertySource
    static void keycloakRealms(DynamicPropertyRegistry registry) {
        for (String[] r : new String[][] {{"staff", STAFF}, {"citizen", CITIZEN}}) {
            registry.add("samanvay.security." + r[0] + ".issuer-uri", () -> KeycloakTestSupport.issuer(r[1]));
            registry.add("samanvay.security." + r[0] + ".jwk-set-uri",
                    () -> KeycloakTestSupport.issuer(r[1]) + "/protocol/openid-connect/certs");
        }
        registry.add("samanvay.identity.department-idp.enabled", () -> "true");
    }

    @Test
    void brokeredLoginDrivesAssertLinkToAVerifiedLink() throws Exception {
        String localId = "RC-LINK-" + UUID.randomUUID();
        String token = brokeredCitizenAccessToken("link-it-user-1", "RATION", localId);
        UUID citizen = profiles.registerSelf(draft(), claims(token).get("sub").asString());

        assertThat(linking.availableProofProviders()).extracting(LinkProofProviderInfo::kind).contains(LinkProofKind.DEPT_IDP);
        assertThat(get(token, "/api/identity/proof-providers")).isEqualTo(200);

        // over HTTP, as the citizen: the brokered token is both the caller's bearer and the proof
        Link link = TestHttp.as(token).post().uri("http://localhost:" + port + "/api/identity/links")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "citizenId", citizen,
                        "departmentCode", "REVENUE",
                        "localIdType", "RATION",
                        "localId", localId,
                        "provider", "DEPT_IDP",
                        "proof", token))
                .retrieve()
                .body(Link.class);
        assertThat(link.status()).isEqualTo("ACTIVE");
        assertThat(link.provenance()).isEqualTo("CITIZEN_ASSERTED");
        assertThat(link.citizenId()).isEqualTo(citizen);
        assertThat(link.departmentCode()).isEqualTo("REVENUE");
        assertThat(link.localIdType()).isEqualTo("RATION");
        assertThat(link.localIdToken()).isEqualTo(localId);

        assertThat(linking.activeLink(citizen, "REVENUE")).map(Link::id).contains(link.id());
        // asserting again is idempotent, like every proof kind
        assertThat(linking.assertLink(citizen, "REVENUE", "RATION", localId, AuthProof.departmentIdp(token)).id())
                .isEqualTo(link.id());
    }

    @Test
    void theBrokeredIdentityMustBeExactlyTheOneBeingLinked() throws Exception {
        String localId = "RC-MISMATCH-" + UUID.randomUUID();
        String token = brokeredCitizenAccessToken("link-it-user-2", "RATION", localId);
        UUID citizen = profiles.registerSelf(draft(), claims(token).get("sub").asString());

        assertThatThrownBy(() -> linking.assertLink(citizen, "REVENUE", "RATION", "RC-NOT-MINE", AuthProof.departmentIdp(token)))
                .isInstanceOf(LinkProofInvalidException.class);
        assertThatThrownBy(() -> linking.assertLink(citizen, "REVENUE", "PROPERTY", localId, AuthProof.departmentIdp(token)))
                .isInstanceOf(LinkProofInvalidException.class);
        assertThatThrownBy(() -> linking.assertLink(citizen, "EDUCATION", "RATION", localId, AuthProof.departmentIdp(token)))
                .isInstanceOf(LinkProofInvalidException.class);
        assertThat(linking.activeLinks(citizen)).isEmpty();
    }

    @Test
    void aBrokeredTokenIsNotAProofForSomeoneElsesRecord() throws Exception {
        String localId = "RC-OTHER-" + UUID.randomUUID();
        String token = brokeredCitizenAccessToken("link-it-user-3", "RATION", localId);
        UUID someoneElse = profiles.registerSelf(draft(), "kc-sub-of-another-citizen-" + UUID.randomUUID());
        UUID unbound = profiles.register(draft());

        for (UUID record : List.of(someoneElse, unbound)) {
            assertThatThrownBy(() -> linking.assertLink(record, "REVENUE", "RATION", localId, AuthProof.departmentIdp(token)))
                    .isInstanceOf(LinkProofInvalidException.class);
        }
    }

    @Test
    void aTamperedOrNonBrokeredTokenIsNotAProof() throws Exception {
        String localId = "RC-TAMPER-" + UUID.randomUUID();
        String token = brokeredCitizenAccessToken("link-it-user-4", "RATION", localId);
        UUID citizen = profiles.registerSelf(draft(), claims(token).get("sub").asString());
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThatThrownBy(() -> linking.assertLink(citizen, "REVENUE", "RATION", localId, AuthProof.departmentIdp(tampered)))
                .isInstanceOf(LinkProofInvalidException.class);

        // a genuine citizen token from a plain password sign-in has no department claims
        importUser(CITIZEN, "link-it-password", "Link-it-cit-pw-1", null, "\"default-roles-samanvay-citizen\"", Map.of());
        BrowserLogin login = new BrowserLogin(CITIZEN, "samanvay-citizen-ui")
                .submit(Map.of("username", "link-it-password", "password", "Link-it-cit-pw-1"));
        String emailCodeToken = login.accessToken();
        UUID emailCodeCitizen = profiles.registerSelf(draft(), claims(emailCodeToken).get("sub").asString());
        assertThatThrownBy(() -> linking.assertLink(
                        emailCodeCitizen, "REVENUE", "RATION", localId, AuthProof.departmentIdp(emailCodeToken)))
                .isInstanceOf(LinkProofInvalidException.class);
        assertThat(linking.activeLinks(citizen)).isEmpty();
        assertThat(linking.activeLinks(emailCodeCitizen)).isEmpty();
    }

    private int get(String accessToken, String path) {
        return TestHttp.as(accessToken).get().uri("http://localhost:" + port + path)
                .exchange((rq, rs) -> rs.getStatusCode().value());
    }

    private static ProfileDraft draft() {
        return new ProfileDraft("Dept Citizen", "डीसी", "Dept", "Citizen", "Father", LocalDate.of(1998, 8, 8), "DAY", "M", "90****08");
    }
}
