package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.ECKey;
import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.DataSourceDraft;
import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.catalog.api.DepartmentIdentity;
import com.samanvay.catalog.api.IdentityChangeNotAcknowledgedException;
import com.samanvay.catalog.api.JourneyDraft;
import com.samanvay.catalog.api.JourneyWrite;
import com.samanvay.catalog.api.ManifestOnboarding;
import com.samanvay.catalog.api.OnboardRequest;
import com.samanvay.catalog.api.OnboardingPlan;
import com.samanvay.shared.InvalidRequestException;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A department that already exists is not silently taken over: a manifest that changes its identity (login, keys, issuer, person-ID
 * type) or is signed by a different key needs an explicit acknowledgement; the plain catalog endpoints merge instead of wiping; and a
 * manifest cannot adopt a journey that belongs to another department.
 */
@SpringBootTest(classes = SamanvayApplication.class,
        properties = {"samanvay.catalog.allowed-private-hosts=127.0.0.1,localhost", "samanvay.catalog.allow-unsigned-manifests=false"})
class OnboardingIdentityAndAdoptionIT extends PostgresIntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    ManifestOnboarding onboarding;

    @Autowired
    CatalogOnboarding wizard;

    @Autowired
    JourneyWrite journeyWrite;

    @Autowired
    DepartmentCatalog departments;

    @Autowired
    JdbcClient jdbc;

    HttpServer server;
    volatile String served;
    volatile ECKey signWith = ManifestSigningFixture.newKey();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    String serve(String code) throws IOException {
        served = ManifestOnboardingIT.unique("dbt", code);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/samanvay/manifest", ex -> {
            byte[] out = served.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.getResponseHeaders().add("X-Samanvay-Signature",
                    ManifestSigningFixture.sign(out, signWith, Instant.now(), "http://127.0.0.1:" + server.getAddress().getPort()));
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static String code(String prefix) {
        return prefix + System.nanoTime();
    }

    OnboardRequest request(OnboardingPlan plan, String url, String approvedKey, boolean acknowledge) {
        return new OnboardRequest(url, plan.manifestDigest(), List.of("BANK_ACCOUNT"), true, Map.of(), approvedKey, acknowledge);
    }

    /** Rewrites one field of the manifest's identity block, the way a different department (or an attacker) would. */
    void changeIdentity(String field, String value) {
        ObjectNode m = (ObjectNode) JSON.readTree(served);
        ((ObjectNode) m.get("identity")).put(field, value);
        served = m.toString();
    }

    String identitySpec(String dept) {
        return jdbc.sql("SELECT identity_spec::text FROM catalog_department WHERE code = :c").param("c", dept).query(String.class).single();
    }

    String column(String dept, String column) {
        return jdbc.sql("SELECT " + column + " FROM catalog_department WHERE code = :c").param("c", dept).query(String.class).single();
    }

    // --- identity change needs an explicit acknowledgement --------------------------------------------------------

    @Test
    void a_changed_identity_is_shown_as_a_loud_warning_and_refused_with_409_until_acknowledged() throws IOException {
        String dept = code("IDA");
        String url = serve(dept);
        OnboardingPlan first = onboarding.plan(url);
        assertThat(first.identityChange()).isNull(); // new department: nothing to change
        onboarding.onboard(request(first, url, first.manifestKeyThumbprint(), false));
        String before = identitySpec(dept);

        changeIdentity("assertionIssuer", "dept:SOMEONE-ELSE");
        OnboardingPlan changed = onboarding.plan(url);
        assertThat(changed.identityChange()).isNotNull();
        assertThat(changed.identityChange().warning()).contains("IDENTITY").contains("dept:SOMEONE-ELSE");
        assertThat(changed.identityChange().changes()).anyMatch(c -> c.contains("assertionIssuer"));

        assertThatThrownBy(() -> onboarding.onboard(request(changed, url, null, false))).isInstanceOf(IdentityChangeNotAcknowledgedException.class)
                .satisfies(e -> assertThat(((IdentityChangeNotAcknowledgedException) e).status()).isEqualTo(409));
        assertThat(identitySpec(dept)).isEqualTo(before); // nothing was overwritten

        onboarding.onboard(request(changed, url, null, true));
        assertThat(identitySpec(dept)).contains("dept:SOMEONE-ELSE");
    }

    @Test
    void a_login_address_or_person_id_type_change_counts_too_and_an_unchanged_identity_needs_no_acknowledgement() throws IOException {
        String dept = code("IDB");
        String url = serve(dept);
        OnboardingPlan first = onboarding.plan(url);
        onboarding.onboard(request(first, url, first.manifestKeyThumbprint(), false));

        OnboardingPlan same = onboarding.plan(url);
        assertThat(same.identityChange()).isNull();
        onboarding.onboard(request(same, url, null, false)); // no acknowledgement needed

        changeIdentity("personIdType", "SOMETHING_ELSE");
        assertThatThrownBy(() -> onboarding.onboard(request(onboarding.plan(url), url, null, false))).isInstanceOf(IdentityChangeNotAcknowledgedException.class);
    }

    @Test
    void a_different_signing_key_is_also_an_identity_change_and_needs_both_the_key_approval_and_the_acknowledgement() throws IOException {
        String dept = code("IDC");
        String url = serve(dept);
        OnboardingPlan first = onboarding.plan(url);
        onboarding.onboard(request(first, url, first.manifestKeyThumbprint(), false));

        signWith = ManifestSigningFixture.newKey();
        String newThumb = ManifestSigningFixture.thumbprint(signWith);
        OnboardingPlan rotated = onboarding.plan(url);
        assertThat(rotated.identityChange()).isNotNull();
        assertThat(rotated.identityChange().warning()).contains(newThumb);

        assertThatThrownBy(() -> onboarding.onboard(request(rotated, url, null, true))).isInstanceOf(InvalidRequestException.class).hasMessageContaining("changed");
        assertThatThrownBy(() -> onboarding.onboard(request(rotated, url, newThumb, false))).isInstanceOf(IdentityChangeNotAcknowledgedException.class);
        assertThat(column(dept, "manifest_key_thumbprint")).isEqualTo(first.manifestKeyThumbprint());

        onboarding.onboard(request(rotated, url, newThumb, true));
        assertThat(column(dept, "manifest_key_thumbprint")).isEqualTo(newThumb);
    }

    // --- the plain endpoints merge, they do not wipe -------------------------------------------------------------

    @Test
    void registering_an_existing_department_again_keeps_its_pinned_key_digest_and_identity() throws IOException {
        String dept = code("IDD");
        String url = serve(dept);
        OnboardingPlan plan = onboarding.plan(url);
        onboarding.onboard(request(plan, url, plan.manifestKeyThumbprint(), false));
        String digest = column(dept, "manifest_digest");
        String identity = identitySpec(dept);

        wizard.registerDepartment(new DepartmentDraft(dept, "Renamed department", null, "ops@example.gov", 2000));

        assertThat(column(dept, "name")).isEqualTo("Renamed department");
        assertThat(column(dept, "manifest_digest")).isEqualTo(digest);
        assertThat(column(dept, "manifest_key_thumbprint")).isEqualTo(plan.manifestKeyThumbprint());
        assertThat(identitySpec(dept)).isEqualTo(identity);
    }

    @Test
    void registering_an_existing_department_with_a_different_identity_is_refused() throws IOException {
        String dept = code("IDE");
        String url = serve(dept);
        OnboardingPlan plan = onboarding.plan(url);
        onboarding.onboard(request(plan, url, plan.manifestKeyThumbprint(), false));
        String identity = identitySpec(dept);

        assertThatThrownBy(() -> wizard.registerDepartment(new DepartmentDraft(dept, "X", null, null, 1000,
                new DepartmentIdentity("ANY", "https://evil.example/login", "https://evil.example/jwks", "dept:EVIL"))))
                .isInstanceOf(IdentityChangeNotAcknowledgedException.class);
        assertThat(identitySpec(dept)).isEqualTo(identity);
    }

    @Test
    void registering_an_existing_data_source_again_keeps_its_onboarded_flag_and_cannot_move_it_to_another_department() throws IOException {
        String dept = code("IDF");
        String url = serve(dept);
        OnboardingPlan plan = onboarding.plan(url);
        var result = onboarding.onboard(request(plan, url, plan.manifestKeyThumbprint(), false));
        String source = result.dataSources().get(0);

        wizard.registerDataSource(new DataSourceDraft(source, dept, "REST", "127.0.0.1:" + server.getAddress().getPort(), "NONE", "secret:none"));
        assertThat(jdbc.sql("SELECT onboarded FROM catalog_data_source WHERE code = :c").param("c", source).query(Boolean.class).single()).isTrue();

        String other = code("IDO");
        wizard.registerDepartment(new DepartmentDraft(other, "Other", null, null, 1000));
        assertThatThrownBy(() -> wizard.registerDataSource(new DataSourceDraft(source, other, "REST", "127.0.0.1:" + server.getAddress().getPort(), "NONE", "secret:none")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("belongs");
    }

    // --- journeys ------------------------------------------------------------------------------------------------

    @Test
    void a_manifest_cannot_adopt_a_journey_whose_requester_is_another_department() throws IOException {
        String dept = code("IDG");
        String other = code("IDH");
        wizard.registerDepartment(new DepartmentDraft(other, "Other", null, null, 1000));
        String journeyCode = "DBT_ACCOUNT_SEEDING_" + dept;
        journeyWrite.createJourney(new JourneyDraft(journeyCode, "Theirs", "TH", 48, "SCHOLARSHIP_ELIGIBILITY", other, List.of("BANK_ACCOUNT"), Map.of("BANK_ACCOUNT", other), null));
        jdbc.sql("UPDATE catalog_journey SET onboarded = TRUE WHERE code = :c").param("c", journeyCode).update();
        String url = serve(dept);

        assertThatThrownBy(() -> onboarding.plan(url)).isInstanceOf(InvalidRequestException.class).hasMessageContaining(journeyCode).hasMessageContaining(other);
        assertThat(departments.byCode(dept)).isEmpty();
    }

    @Test
    void a_journey_whose_requester_is_not_the_manifests_own_department_is_refused() throws IOException {
        String dept = code("IDI");
        String url = serve(dept);
        served = served.replace("\"requester\":\"" + dept + "\"", "\"requester\":\"REVENUE\"");
        assertThatThrownBy(() -> onboarding.plan(url)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("requester");
        assertThat(departments.byCode(dept)).isEmpty();
    }

    @Test
    void adopting_an_existing_data_source_of_the_same_department_marks_it_onboarded_and_another_departments_source_is_refused() throws IOException {
        String dept = code("IDJ");
        String url = serve(dept);
        wizard.registerDepartment(new DepartmentDraft(dept, "Pre-existing", null, null, 1000));
        String source = dept.toLowerCase() + "-rest";
        wizard.registerDataSource(new DataSourceDraft(source, dept, "REST", "127.0.0.1:" + server.getAddress().getPort(), "NONE", "secret:none"));
        assertThat(jdbc.sql("SELECT onboarded FROM catalog_data_source WHERE code = :c").param("c", source).query(Boolean.class).single()).isFalse();

        OnboardingPlan plan = onboarding.plan(url);
        onboarding.onboard(request(plan, url, plan.manifestKeyThumbprint(), false));

        assertThat(jdbc.sql("SELECT onboarded FROM catalog_data_source WHERE code = :c").param("c", source).query(Boolean.class).single()).isTrue();
    }

    @Test
    void a_source_code_that_belongs_to_another_department_is_not_adopted() throws IOException {
        String dept = code("IDK");
        String other = code("IDL");
        String url = serve(dept);
        wizard.registerDepartment(new DepartmentDraft(other, "Other", null, null, 1000));
        wizard.registerDataSource(new DataSourceDraft(dept.toLowerCase() + "-rest", other, "REST", "127.0.0.1:" + server.getAddress().getPort(), "NONE", "secret:none"));

        OnboardingPlan plan = onboarding.plan(url);
        assertThatThrownBy(() -> onboarding.onboard(request(plan, url, plan.manifestKeyThumbprint(), false))).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("belongs");
        assertThat(departments.byCode(dept)).isEmpty();
    }
}
