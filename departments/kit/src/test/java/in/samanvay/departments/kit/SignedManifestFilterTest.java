package in.samanvay.departments.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** The shared manifest signer: ES256 over the exact bytes, the issue time and the audience (the origin of the department's public address). */
class SignedManifestFilterTest {

    static final String PATH = "/.well-known/samanvay/manifest";
    static final String BODY = "{\"manifestVersion\":2}";

    @TempDir
    Path dir;

    MockHttpServletResponse call(SignedManifestFilter f, String uri, String discovery) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        if (discovery != null) {
            req.addHeader("X-Discovery-Key", discovery);
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

    SignedManifestFilter filter(String publicUrl, String discovery) {
        return new SignedManifestFilter(dir.resolve("k.jwk").toString(), discovery, publicUrl);
    }

    @Test
    void the_signed_payload_carries_sha256_iat_and_the_audience() throws Exception {
        MockHttpServletResponse res = call(filter("https://Revenue.Example.com/", ""), PATH, null);
        JWSObject jws = JWSObject.parse(res.getHeader("X-Samanvay-Signature"));
        assertThat(jws.verify(new ECDSAVerifier((ECKey) jws.getHeader().getJWK()))).isTrue();
        var claims = JsonMapper.builder().build().readTree(jws.getPayload().toString());
        assertThat(claims.get("sha256").asString()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BODY.getBytes(StandardCharsets.UTF_8))));
        assertThat(Instant.ofEpochSecond(claims.get("iat").asLong())).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(5));
        assertThat(claims.get("aud").asString()).isEqualTo("https://revenue.example.com");
    }

    @Test
    void the_audience_is_the_origin_only() {
        assertThat(SignedManifestFilter.origin("https://Revenue.Example.com/")).isEqualTo("https://revenue.example.com");
        assertThat(SignedManifestFilter.origin("http://127.0.0.1:8091")).isEqualTo("http://127.0.0.1:8091");
        assertThat(SignedManifestFilter.origin("HTTPS://dept.example.com:8443/some/path/?q=1#f")).isEqualTo("https://dept.example.com:8443");
        assertThat(SignedManifestFilter.origin("https://dept.example.com")).isEqualTo("https://dept.example.com");
    }

    @Test
    void without_a_usable_public_address_the_filter_refuses_to_exist() {
        for (String bad : new String[] {null, "", "  ", "not a url", "revenue.example.com", "ftp://x.example.com", "https://"}) {
            assertThatThrownBy(() -> filter(bad, "")).as("" + bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void a_path_parameter_on_the_manifest_path_is_refused_not_served_unsigned() throws Exception {
        SignedManifestFilter f = filter("https://r.example.com", "needed");
        for (String uri : new String[] {PATH + ";x", PATH + "%3Bx", "/.well-known/samanvay/manifest;jsessionid=1", "/.well-known/./samanvay/manifest"}) {
            MockHttpServletResponse res = call(f, uri, null);
            assertThat(res.getStatus()).as(uri).isEqualTo(400);
            assertThat(res.getContentAsString()).as(uri).doesNotContain("manifestVersion");
            assertThat(res.getHeader("X-Samanvay-Signature")).as(uri).isNull();
        }
    }

    @Test
    void the_discovery_key_still_guards_the_manifest() throws Exception {
        SignedManifestFilter f = filter("https://r.example.com", "samanvay-discovery-1");
        assertThat(call(f, PATH, null).getStatus()).isEqualTo(401);
        assertThat(call(f, PATH, "samanvay-discovery-1").getStatus()).isEqualTo(200);
    }
}
