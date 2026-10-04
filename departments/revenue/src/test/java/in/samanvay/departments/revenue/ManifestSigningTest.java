package in.samanvay.departments.revenue;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * The department signs the exact bytes of its manifest (docs/contracts/manifest-signature.md) with a key that survives
 * restarts, and may ask Samanvay for a discovery credential before it will show the manifest at all.
 */
class ManifestSigningTest {

    static final String PATH = "/.well-known/samanvay/manifest";
    static final String BODY = "{\"manifestVersion\":2}";
    static final String PUBLIC_URL = "https://Dept.Example.com:8443/";
    static final String AUDIENCE = "https://dept.example.com:8443";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    ManifestSigningFilter filter(String file, String discoveryKey) throws Exception {
        return new ManifestSigningFilter(dir.resolve(file).toString(), discoveryKey, PUBLIC_URL);
    }

    MockHttpServletResponse call(ManifestSigningFilter f, String path, String discoveryHeader) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        if (discoveryHeader != null) {
            req.addHeader("X-Discovery-Key", discoveryHeader);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void doGet(jakarta.servlet.http.HttpServletRequest rq, jakarta.servlet.http.HttpServletResponse rs) throws java.io.IOException {
                rs.setContentType("application/json");
                rs.getWriter().write(BODY);
            }
        }));
        return res;
    }

    /** What Samanvay does: the key in the header must verify the signature, and the payload must name these exact bytes. */
    static String verifiedThumbprint(byte[] body, String header) throws Exception {
        JWSObject jws = JWSObject.parse(header);
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        ECKey pub = (ECKey) jws.getHeader().getJWK();
        assertThat(pub.isPrivate()).isFalse();
        assertThat(jws.verify(new ECDSAVerifier(pub))).isTrue();
        var claims = JSON.readTree(jws.getPayload().toString());
        assertThat(claims.get("sha256").asString()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        assertThat(Instant.ofEpochSecond(claims.get("iat").asLong())).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(5));
        assertThat(claims.get("aud").asString()).as("aud is the origin of the department's public address").isNotBlank().doesNotEndWith("/");
        return pub.computeThumbprint().toString();
    }

    @Test
    void the_manifest_response_carries_a_signature_over_its_exact_bytes() throws Exception {
        MockHttpServletResponse res = call(filter("k.jwk", ""), PATH, null);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).isEqualTo(BODY);
        assertThat(verifiedThumbprint(BODY.getBytes(StandardCharsets.UTF_8), res.getHeader("X-Samanvay-Signature"))).isNotBlank();
    }

    @Test
    void the_signed_audience_is_the_origin_of_the_configured_public_address() throws Exception {
        MockHttpServletResponse res = call(filter("aud.jwk", ""), PATH, null);
        String payload = JWSObject.parse(res.getHeader("X-Samanvay-Signature")).getPayload().toString();
        assertThat(JSON.readTree(payload).get("aud").asString()).isEqualTo(AUDIENCE);
    }

    @Test
    void a_path_parameter_on_the_manifest_path_is_refused_not_served_unsigned_or_without_the_discovery_key() throws Exception {
        ManifestSigningFilter f = filter("p.jwk", "needed");
        for (String uri : new String[] {PATH + ";x=1", PATH + "%3Bx=1", PATH + ";jsessionid=1"}) {
            MockHttpServletResponse res = call(f, uri, null);
            assertThat(res.getStatus()).as(uri).isEqualTo(400);
            assertThat(res.getContentAsString()).as(uri).doesNotContain("manifestVersion");
        }
    }

    @Test
    void the_filter_reports_the_thumbprint_of_its_key_so_an_operator_can_confirm_it_out_of_band() throws Exception {
        ManifestSigningFilter f = filter("t.jwk", "");
        String fromHeader = verifiedThumbprint(BODY.getBytes(StandardCharsets.UTF_8), call(f, PATH, null).getHeader("X-Samanvay-Signature"));
        assertThat(f.thumbprint()).isEqualTo(fromHeader);
    }

    @Test
    void other_paths_are_not_touched() throws Exception {
        MockHttpServletResponse res = call(filter("k.jwk", "needed"), "/v1/anything", null);
        assertThat(res.getHeader("X-Samanvay-Signature")).isNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void the_signing_key_survives_a_restart_and_is_not_shared_between_services() throws Exception {
        String first = verifiedThumbprint(BODY.getBytes(StandardCharsets.UTF_8), call(filter("a.jwk", ""), PATH, null).getHeader("X-Samanvay-Signature"));
        String afterRestart = verifiedThumbprint(BODY.getBytes(StandardCharsets.UTF_8), call(filter("a.jwk", ""), PATH, null).getHeader("X-Samanvay-Signature"));
        String other = verifiedThumbprint(BODY.getBytes(StandardCharsets.UTF_8), call(filter("b.jwk", ""), PATH, null).getHeader("X-Samanvay-Signature"));
        assertThat(afterRestart).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void with_a_discovery_key_configured_the_manifest_is_shown_only_to_a_caller_who_sends_it() throws Exception {
        ManifestSigningFilter f = filter("c.jwk", "samanvay-discovery-1");
        assertThat(call(f, PATH, null).getStatus()).isEqualTo(401);
        assertThat(call(f, PATH, "wrong").getStatus()).isEqualTo(401);
        assertThat(call(f, PATH, null).getContentAsString()).doesNotContain("manifestVersion");
        MockHttpServletResponse ok = call(f, PATH, "samanvay-discovery-1");
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getHeader("X-Samanvay-Signature")).isNotNull();
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "revenue.manifest.key-file=target/test-manifest-key.jwk")
    class OverHttp {
        @LocalServerPort
        int port;

        @Test
        void the_real_manifest_endpoint_is_signed_and_the_signature_verifies() throws Exception {
            HttpResponse<byte[]> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(verifiedThumbprint(r.body(), r.headers().firstValue("X-Samanvay-Signature").orElseThrow())).isNotBlank();
            String payload = JWSObject.parse(r.headers().firstValue("X-Samanvay-Signature").orElseThrow()).getPayload().toString();
            assertThat(JSON.readTree(payload).get("aud").asString()).isEqualTo("http://localhost:8091");
        }
    }
}
