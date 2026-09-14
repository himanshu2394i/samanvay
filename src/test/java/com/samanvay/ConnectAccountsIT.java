package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.ConnectAccounts;
import com.samanvay.identity.api.DepartmentLinkNeed;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.Link;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProviderInfo;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.orchestration.api.MissingDepartmentLinksException;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = SamanvayApplication.class)
class ConnectAccountsIT extends PostgresIntegrationTest {

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityLinking linking;

    @Autowired
    JourneyService journeys;

    @Autowired
    ConsentService consents;

    @Test
    void scholarshipChecklistIsPerDepartmentAndSkipsLinked() {
        UUID citizen = register();
        ConnectAccounts before = linking.connectAccounts(citizen, "POST_MATRIC_SCHOLARSHIP");
        assertThat(before.departments()).hasSize(3);
        assertThat(before.departments())
                .extracting(DepartmentLinkNeed::departmentCode)
                .containsExactly("REVENUE", "EDUCATION", "DBT");
        DepartmentLinkNeed revenue = before.departments().getFirst();
        assertThat(revenue.categories()).containsExactly("INCOME_CERTIFICATE", "CASTE_CERTIFICATE");
        assertThat(before.departments()).allMatch(d -> !d.linked());

        List<LinkProofProviderInfo> providers = linking.availableProofProviders();
        assertThat(providers)
                .extracting(LinkProofProviderInfo::kind)
                .containsExactlyInAnyOrder(LinkProofKind.DIGILOCKER, LinkProofKind.LOCAL_ID_OTP);
        assertThat(providers).extracting(LinkProofProviderInfo::kind).doesNotContain(LinkProofKind.DEPT_IDP);
        assertThat(providers.stream()
                        .filter(p -> p.kind() == LinkProofKind.DIGILOCKER)
                        .findFirst()
                        .orElseThrow()
                        .label())
                .containsIgnoringCase("sandbox")
                .containsIgnoringCase("mock")
                .doesNotContain("Keycloak");
        assertThat(providers.stream()
                        .filter(p -> p.kind() == LinkProofKind.LOCAL_ID_OTP)
                        .findFirst()
                        .orElseThrow()
                        .label())
                .containsIgnoringCase("demo")
                .doesNotContain("Keycloak");

        assertThatThrownBy(() -> journeys.start(
                        "POST_MATRIC_SCHOLARSHIP", citizen, JsonMapper.builder().build().createObjectNode()))
                .isInstanceOf(MissingDepartmentLinksException.class)
                .hasMessageContaining("REVENUE")
                .hasMessageContaining("EDUCATION")
                .hasMessageContaining("DBT");

        Link first = linking.assertLink(
                citizen, "REVENUE", "RATION", "RC-connect-1", AuthProof.digiLockerSandbox());
        Link skipped = linking.assertLink(
                citizen, "REVENUE", "RATION", "OTHER", AuthProof.localIdOtpDemo());
        assertThat(skipped.id()).isEqualTo(first.id());
        assertThat(first.localIdType()).isEqualTo("RATION");
        assertThat(first.localIdToken()).isEqualTo("RC-connect-1");

        ConnectAccounts afterRevenue = linking.connectAccounts(citizen, "POST_MATRIC_SCHOLARSHIP");
        assertThat(afterRevenue.departments()).hasSize(3);
        assertThat(afterRevenue.departments().getFirst().linked()).isTrue();
        assertThat(afterRevenue.departments().stream().filter(d -> !d.linked()))
                .extracting(DepartmentLinkNeed::departmentCode)
                .containsExactly("EDUCATION", "DBT");

        linking.assertLink(citizen, "EDUCATION", "STUDENT", "STU-connect-1", AuthProof.digiLockerSandbox());
        linking.assertLink(citizen, "DBT", "DBT", "DBT-connect-1", AuthProof.digiLockerSandbox());
        ConnectAccounts done = linking.connectAccounts(citizen, "POST_MATRIC_SCHOLARSHIP");
        assertThat(done.departments()).allMatch(DepartmentLinkNeed::linked);

        var request = consents.request(new ConsentRequestDraft(
                citizen,
                "SCHOLARSHIP",
                "SCHOLARSHIP_ELIGIBILITY",
                "Post-matric scholarship",
                List.of("INCOME_CERTIFICATE", "CASTE_CERTIFICATE", "MARKS", "BANK_ACCOUNT")));
        consents.grant(request.id(), citizen, new com.samanvay.consent.api.AuthProof("session-jti"));
        assertThat(journeys.start(
                        "POST_MATRIC_SCHOLARSHIP", citizen, JsonMapper.builder().build().createObjectNode())
                .id())
                .isNotNull();

        assertThatThrownBy(() -> linking.assertLink(
                        citizen, "FIRE", "PREMISE", "X", new AuthProof(LinkProofKind.DEPT_IDP, "assertion")))
                .isInstanceOf(LinkProofInvalidException.class);
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
}
