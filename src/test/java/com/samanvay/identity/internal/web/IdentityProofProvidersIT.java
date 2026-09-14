package com.samanvay.identity.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.Link;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProviderInfo;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityProofProvidersIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityLinking linking;

    @Test
    void listExposesLabeledSandboxProvidersNotKeycloak() {
        LinkProofProviderInfo[] listed = http().get()
                .uri(url("/api/identity/proof-providers"))
                .retrieve()
                .body(LinkProofProviderInfo[].class);
        assertThat(listed).isNotEmpty();
        assertThat(Arrays.stream(listed).map(LinkProofProviderInfo::kind))
                .contains(LinkProofKind.DIGILOCKER, LinkProofKind.LOCAL_ID_OTP)
                .doesNotContain(LinkProofKind.DEPT_IDP);
        assertThat(Arrays.stream(listed).map(LinkProofProviderInfo::label))
                .contains("DigiLocker sandbox (mock)", "Local ID + OTP (demo)")
                .noneMatch(label -> label.toLowerCase().contains("keycloak"));
    }

    @Test
    void digiLockerMockAssertSucceedsAndInvalidProofFails() {
        UUID citizen = register();
        Link link = http().post()
                .uri(url("/api/identity/links"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "citizenId",
                        citizen,
                        "departmentCode",
                        "REVENUE",
                        "localIdType",
                        "RATION",
                        "localId",
                        "RC-http-" + citizen,
                        "provider",
                        "DIGILOCKER",
                        "proof",
                        "sandbox"))
                .retrieve()
                .body(Link.class);
        assertThat(link.status()).isEqualTo("ACTIVE");
        assertThat(link.provenance()).isEqualTo("CITIZEN_ASSERTED");

        HttpStatusCode invalid = http().post()
                .uri(url("/api/identity/links"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "citizenId",
                        register(),
                        "departmentCode",
                        "EDUCATION",
                        "localIdType",
                        "STUDENT",
                        "localId",
                        "STU-bad",
                        "provider",
                        "DIGILOCKER",
                        "proof",
                        "not-a-sandbox-token"))
                .exchange((req, res) -> res.getStatusCode());
        assertThat(invalid.value()).isEqualTo(401);

        HttpStatusCode missingProvider = http().post()
                .uri(url("/api/identity/links"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "citizenId",
                        register(),
                        "departmentCode",
                        "EDUCATION",
                        "localIdType",
                        "STUDENT",
                        "localId",
                        "STU-jti",
                        "proof",
                        "stub"))
                .exchange((req, res) -> res.getStatusCode());
        assertThat(missingProvider.value()).isEqualTo(401);
    }

    @Test
    void alreadyLinkedDepartmentIsSkippedAndDuplicateLocalIdRejected() {
        UUID first = register();
        String localId = "RC-dup-" + first;
        Link firstLink = linking.assertLink(first, "REVENUE", "RATION", localId, com.samanvay.identity.api.AuthProof.digiLockerSandbox());
        Link skipped = linking.assertLink(first, "REVENUE", "RATION", "RC-other", com.samanvay.identity.api.AuthProof.digiLockerSandbox());
        assertThat(skipped.id()).isEqualTo(firstLink.id());

        UUID second = register();
        HttpStatusCode conflict = http().post()
                .uri(url("/api/identity/links"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "citizenId",
                        second,
                        "departmentCode",
                        "REVENUE",
                        "localIdType",
                        "RATION",
                        "localId",
                        localId,
                        "provider",
                        "DIGILOCKER",
                        "proof",
                        "sandbox"))
                .exchange((req, res) -> res.getStatusCode());
        assertThat(conflict.value()).isEqualTo(409);
    }

    private UUID register() {
        return profiles.register(new ProfileDraft(
                "Ramesh Kumar",
                "रमेश",
                "Ramesh",
                "Kumar",
                "Suresh",
                LocalDate.of(2004, 1, 15),
                "DAY",
                "M",
                "99****21"));
    }

    private RestClient http() {
        return RestClient.create();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
