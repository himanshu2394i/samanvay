package in.samanvay.departments.education;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Signs this department's manifest (docs/contracts/manifest-signature.md): an ES256 JWS in {@code X-Samanvay-Signature} over the
 * SHA-256 of the exact response bytes plus the issue time, carrying the PUBLIC key. Samanvay pins the key's thumbprint once an
 * admin approves it, so a manifest tampered with in transit, or served by someone else, no longer verifies.
 *
 * <p>The key is kept in a file ({@code education.manifest.key-file}) and created on first start, so the thumbprint an admin approved
 * survives restarts. Keep the file private; deleting it rotates the key and Samanvay will ask an admin to approve the new one.
 *
 * <p>If {@code education.manifest.discovery-key} is set, the manifest is shown only to a caller that sends it in
 * {@code X-Discovery-Key} (the credential this department issued to Samanvay). Empty = the manifest is public.
 */
@Component
class ManifestSigningFilter extends OncePerRequestFilter {

    static final String PATH = "/.well-known/samanvay/manifest";
    static final String SIGNATURE_HEADER = "X-Samanvay-Signature";
    static final String DISCOVERY_HEADER = "X-Discovery-Key";

    private final ECKey key;
    private final byte[] discoveryKey;

    ManifestSigningFilter(
            @Value("${education.manifest.key-file:manifest-signing-key.jwk}") String keyFile,
            @Value("${education.manifest.discovery-key:}") String discoveryKey) throws Exception {
        this.key = loadOrCreate(Path.of(keyFile));
        this.discoveryKey = discoveryKey.getBytes(StandardCharsets.UTF_8);
    }

    private static ECKey loadOrCreate(Path file) throws Exception {
        if (Files.exists(file)) {
            return ECKey.parse(Files.readString(file, StandardCharsets.UTF_8));
        }
        ECKey created = new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate();
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, created.toJSONString(), StandardCharsets.UTF_8);
        file.toFile().setReadable(false, false);
        file.toFile().setReadable(true, true);
        file.toFile().setWritable(false, false);
        file.toFile().setWritable(true, true);
        return created;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
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
            JWSObject jws = new JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("samanvay-manifest")).jwk(key.toPublicJWK()).build(),
                    new Payload("{\"sha256\":\"" + digest + "\",\"iat\":" + Instant.now().getEpochSecond() + "}"));
            jws.sign(new ECDSASigner(key));
            return jws.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not sign the manifest", e);
        }
    }
}
