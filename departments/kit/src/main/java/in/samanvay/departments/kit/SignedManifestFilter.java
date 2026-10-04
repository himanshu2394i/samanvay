package in.samanvay.departments.kit;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signs this department's manifest (docs/contracts/manifest-signature.md): an ES256 JWS in {@code X-Samanvay-Signature} over the
 * SHA-256 of the exact response bytes, the issue time and the audience, carrying the PUBLIC key. The audience ({@code aud}) is the
 * origin of the department's configured public address, so a signed manifest cannot be replayed from one host as another's. Samanvay
 * pins the key's thumbprint once an admin approves it, so a manifest tampered with in transit, or served by someone else, no longer
 * verifies.
 *
 * <p>The key comes from {@link ManifestKey} (the same file the consent signer uses); deleting the file rotates the key and Samanvay
 * will ask an admin to approve the new one. If a discovery key is set, the manifest is shown only to a caller that sends it in
 * {@code X-Discovery-Key} (empty = public). A path with ';' parameters or other tricks on the manifest path is refused with 400, never
 * served unsigned ({@link RequestPaths}).
 *
 * <p>Each department extends this with a constructor that reads its own property names; it is the department's class that is the bean.
 */
public class SignedManifestFilter extends OncePerRequestFilter {

    public static final String PATH = "/.well-known/samanvay/manifest";
    public static final String SIGNATURE_HEADER = "X-Samanvay-Signature";
    public static final String DISCOVERY_HEADER = "X-Discovery-Key";
    private static final Logger log = LoggerFactory.getLogger(SignedManifestFilter.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ECKey key;
    private final byte[] discoveryKey;
    private final String audience;

    /** @param publicBaseUrl the department's configured public address; only its origin is signed */
    public SignedManifestFilter(String keyFile, String discoveryKey, String publicBaseUrl) {
        this.key = ManifestKey.loadOrCreate(Path.of(keyFile));
        this.discoveryKey = (discoveryKey == null ? "" : discoveryKey).getBytes(StandardCharsets.UTF_8);
        this.audience = origin(publicBaseUrl);
        // Public information: the fingerprint an admin confirms with this department before Samanvay pins the key.
        log.info("Manifest signing key thumbprint: {}; audience: {}", thumbprint(), audience);
    }

    /**
     * {@code scheme://host[:port]} of a URL, scheme and host in lower case, no path and no trailing slash. The port is there only if the
     * configured address has one. Anything that is not an absolute http(s) address with a host is refused.
     */
    public static String origin(String url) {
        try {
            URI u = URI.create(url == null ? "" : url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getHost().isBlank()) {
                throw new IllegalArgumentException("the department's public address must be an absolute http(s) URL, but is: " + url);
            }
            return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + (u.getPort() == -1 ? "" : ":" + u.getPort());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the department's public address must be an absolute http(s) URL, but is: " + url, e);
        }
    }

    /** RFC 7638 thumbprint of the public signing key (what Samanvay shows the admin to confirm). */
    public String thumbprint() {
        try {
            return key.computeThumbprint().toString();
        } catch (Exception e) {
            throw new IllegalStateException("could not compute the key thumbprint", e);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(RequestPaths.malformed(request) || PATH.equals(RequestPaths.normalised(request)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (RequestPaths.malformed(request)) {
            RequestHygieneFilter.refuse(response);
            return;
        }
        if (discoveryKey.length > 0) {
            String given = request.getHeader(DISCOVERY_HEADER);
            if (given == null || !MessageDigest.isEqual(discoveryKey, given.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"missing or invalid " + DISCOVERY_HEADER + "\"}");
                return;
            }
        }
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapped);
        byte[] body = wrapped.getContentAsByteArray();
        if (wrapped.getStatus() == HttpServletResponse.SC_OK && body.length > 0) {
            wrapped.setHeader(SIGNATURE_HEADER, sign(body));
        }
        wrapped.copyBodyToResponse();
    }

    private String sign(byte[] body) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String payload = JSON.writeValueAsString(java.util.Map.of("sha256", digest, "iat", Instant.now().getEpochSecond(), "aud", audience));
            JWSObject jws = new JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("samanvay-manifest")).jwk(key.toPublicJWK()).build(),
                    new Payload(payload));
            jws.sign(new ECDSASigner(key));
            return jws.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not sign the manifest", e);
        }
    }
}
