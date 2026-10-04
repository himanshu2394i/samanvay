package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.ECKey;
import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.DepartmentCatalog;
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
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The signed-manifest trust model against the real database: a department signs the exact bytes of its manifest, an admin
 * approves the signing key's thumbprint once, Samanvay pins it, and from then on only that key will do. Strict mode (unsigned
 * manifests refused), unlike {@link ManifestOnboardingIT}.
 */
@SpringBootTest(classes = SamanvayApplication.class,
        properties = {"samanvay.catalog.allowed-private-hosts=127.0.0.1,localhost", "samanvay.catalog.allow-unsigned-manifests=false"})
class SignedManifestOnboardingIT extends PostgresIntegrationTest {

    @Autowired
    ManifestOnboarding onboarding;

    @Autowired
    DepartmentCatalog departments;

    @Autowired
    CatalogServices catalogServices;

    HttpServer server;
    volatile String served;
    volatile ECKey signWith;
    volatile String forcedSignature;
    volatile String signAud; // null = the host the manifest is served from
    volatile String requireDiscoveryKey;

    @AfterEach
    void stop() {
        catalogServices.discoveryCredentials(key -> Optional.empty());
        if (server != null) {
            server.stop(0);
        }
    }

    /** Serves a department's manifest, signed with {@link #signWith} (unsigned when null), asking for a discovery key if set. */
    String serve(String code) throws IOException {
        served = ManifestOnboardingIT.unique("dbt", code);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/samanvay/manifest", ex -> {
            if (requireDiscoveryKey != null && !requireDiscoveryKey.equals(ex.getRequestHeaders().getFirst("X-Discovery-Key"))) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            byte[] out = served.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if (forcedSignature != null) {
                ex.getResponseHeaders().add("X-Samanvay-Signature", forcedSignature);
            } else if (signWith != null) {
                ex.getResponseHeaders().add("X-Samanvay-Signature", ManifestSigningFixture.sign(out, signWith, Instant.now(), signAud == null ? "http://127.0.0.1:" + server.getAddress().getPort() : signAud));
            }
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

    OnboardRequest request(OnboardingPlan plan, String url, String approvedKey) {
        return new OnboardRequest(url, plan.manifestDigest(), List.of("BANK_ACCOUNT"), true, Map.of(), approvedKey);
    }

    @Test
    void an_unsigned_manifest_is_refused_in_strict_mode() throws IOException {
        String url = serve(code("SGU"));
        assertThatThrownBy(() -> onboarding.plan(url)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("not signed");
    }

    @Test
    void a_signed_manifest_shows_its_key_and_onboarding_needs_the_admin_to_approve_exactly_that_key_then_pins_it() throws IOException {
        String dept = code("SGA");
        signWith = ManifestSigningFixture.newKey();
        String thumb = ManifestSigningFixture.thumbprint(signWith);
        String url = serve(dept);

        OnboardingPlan plan = onboarding.plan(url);
        assertThat(plan.manifestKeyThumbprint()).isEqualTo(thumb);
        assertThat(plan.pinnedKeyThumbprint()).isNull();

        assertThatThrownBy(() -> onboarding.onboard(request(plan, url, null))).isInstanceOf(InvalidRequestException.class).hasMessageContaining(thumb);
        assertThatThrownBy(() -> onboarding.onboard(request(plan, url, "someOtherKey"))).isInstanceOf(InvalidRequestException.class);
        assertThat(departments.byCode(dept)).isEmpty(); // nothing was written

        onboarding.onboard(request(plan, url, thumb));
        assertThat(onboarding.plan(url).pinnedKeyThumbprint()).isEqualTo(thumb);
        // pinned: the same key needs no further approval
        onboarding.onboard(request(onboarding.plan(url), url, null));
    }

    @Test
    void after_a_key_is_pinned_a_different_key_is_refused_until_approved_and_unsigned_is_never_accepted() throws IOException {
        String dept = code("SGB");
        signWith = ManifestSigningFixture.newKey();
        String url = serve(dept);
        OnboardingPlan first = onboarding.plan(url);
        onboarding.onboard(request(first, url, first.manifestKeyThumbprint()));

        ECKey replacement = ManifestSigningFixture.newKey();
        signWith = replacement;
        String newThumb = ManifestSigningFixture.thumbprint(replacement);
        OnboardingPlan changed = onboarding.plan(url);
        assertThat(changed.manifestKeyThumbprint()).isEqualTo(newThumb);
        assertThat(changed.pinnedKeyThumbprint()).isEqualTo(first.manifestKeyThumbprint());
        assertThatThrownBy(() -> onboarding.onboard(request(changed, url, null))).isInstanceOf(InvalidRequestException.class).hasMessageContaining("changed");
        // approving the new key is not enough: a changed signing key is an identity change and needs its own acknowledgement
        assertThatThrownBy(() -> onboarding.onboard(request(changed, url, newThumb))).isInstanceOf(com.samanvay.catalog.api.IdentityChangeNotAcknowledgedException.class);
        onboarding.onboard(new OnboardRequest(url, changed.manifestDigest(), List.of("BANK_ACCOUNT"), true, Map.of(), newThumb, true));
        assertThat(onboarding.plan(url).pinnedKeyThumbprint()).isEqualTo(newThumb);

        signWith = null; // the department stops signing: refused, even though a key is pinned
        assertThatThrownBy(() -> onboarding.plan(url)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("signed before");
    }

    @Test
    void a_signature_that_does_not_match_the_content_is_refused() throws IOException {
        signWith = null;
        forcedSignature = ManifestSigningFixture.sign("some other manifest", ManifestSigningFixture.newKey(), Instant.now(), "http://127.0.0.1");
        String url = serve(code("SGT"));
        assertThatThrownBy(() -> onboarding.plan(url)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("signature");
    }

    @Test
    void a_department_that_asks_for_a_discovery_credential_gets_the_one_provisioned_for_its_host() throws IOException {
        signWith = ManifestSigningFixture.newKey();
        requireDiscoveryKey = "discovery-secret-1";
        String url = serve(code("SGD"));
        String secretKey = "manifest-127-0-0-1---" + server.getAddress().getPort() + "-credential";

        assertThatThrownBy(() -> onboarding.plan(url)).isInstanceOf(InvalidRequestException.class).hasMessageContaining(secretKey);

        catalogServices.discoveryCredentials(key -> key.equals(secretKey) ? Optional.of("wrong") : Optional.empty());
        assertThatThrownBy(() -> onboarding.plan(url)).isInstanceOf(InvalidRequestException.class).hasMessageContaining(secretKey);

        catalogServices.discoveryCredentials(key -> key.equals(secretKey) ? Optional.of("discovery-secret-1") : Optional.empty());
        assertThat(onboarding.plan(url).manifestKeyThumbprint()).isEqualTo(ManifestSigningFixture.thumbprint(signWith));
    }

    @Test
    void a_manifest_signed_for_another_host_is_refused_even_though_the_signature_is_otherwise_valid() throws IOException {
        signWith = ManifestSigningFixture.newKey();
        signAud = "https://some-other-department.example.gov";
        String url = serve(code("SGH"));
        assertThatThrownBy(() -> onboarding.plan(url)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("some-other-department.example.gov").hasMessageContaining("127.0.0.1");
    }

    @Test
    void the_discovery_key_named_before_the_encoding_was_made_unambiguous_is_still_accepted_for_a_host_with_a_port() throws IOException {
        signWith = ManifestSigningFixture.newKey();
        requireDiscoveryKey = "discovery-secret-2";
        String url = serve(code("SGL"));
        String legacy = "manifest-127-0-0-1-" + server.getAddress().getPort() + "-credential";
        catalogServices.discoveryCredentials(key -> key.equals(legacy) ? Optional.of("discovery-secret-2") : Optional.empty());
        assertThat(onboarding.plan(url).manifestKeyThumbprint()).isEqualTo(ManifestSigningFixture.thumbprint(signWith));
    }
}
