package com.samanvay.catalog.internal.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.samanvay.shared.InvalidRequestException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the signature a department puts on its manifest (docs/contracts/manifest-signature.md): a compact ES256 JWS in the
 * {@value #HEADER} response header whose payload is {@code {"sha256": <hex of the exact body bytes>, "iat": <epoch seconds>,
 * "aud": <the origin it was served from>}} and whose header carries the department's PUBLIC key ({@code jwk}).
 *
 * <p>The key travels with the signature, so a valid signature alone proves nothing about WHO signed: trust comes from the
 * admin approving the returned thumbprint once (out of band, with the department) and Samanvay pinning it. After that a
 * manifest must be signed by that same key. {@code iat} must be recent, so an old signed manifest cannot be replayed, and
 * {@code aud} must be the origin Samanvay fetched it from, so a manifest signed for one host cannot be replayed from another.
 */
final class ManifestSignatures {

    static final String HEADER = "X-Samanvay-Signature";
    static final Duration MAX_AGE = Duration.ofMinutes(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ManifestSignatures() {}

    /**
     * @param expectedAud the origin ({@code scheme://host[:port]}) the manifest was fetched from; the signature must name it
     * @return the thumbprint (RFC 7638, base64url SHA-256) of the signing key; empty when the manifest carries no signature
     * @throws InvalidRequestException when a signature is present but is not valid for these bytes or this host
     */
    static Optional<String> verify(byte[] body, String header, Instant now, String expectedAud) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        try {
            JWSObject jws = JWSObject.parse(header.trim());
            if (!JWSAlgorithm.ES256.equals(jws.getHeader().getAlgorithm())) {
                throw bad("it is not ES256");
            }
            JWK jwk = jws.getHeader().getJWK();
            if (!(jwk instanceof ECKey ec) || ec.isPrivate()) {
                throw bad("its header must carry the department's public EC key");
            }
            if (!jws.verify(new ECDSAVerifier(ec.toPublicJWK()))) {
                throw bad("it does not match the key it carries");
            }
            JsonNode claims = JSON.readTree(jws.getPayload().toString());
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            JsonNode signed = claims.get("sha256");
            if (signed == null || !MessageDigest.isEqual(digest.getBytes(StandardCharsets.UTF_8), signed.asString().getBytes(StandardCharsets.UTF_8))) {
                throw bad("it was made over different content than was received");
            }
            JsonNode iat = claims.get("iat");
            if (iat == null || !iat.canConvertToLong()) {
                throw bad("it has no issue time");
            }
            Duration age = Duration.between(Instant.ofEpochSecond(iat.asLong()), now);
            if (age.compareTo(MAX_AGE) > 0) {
                throw bad("it is too old (signed " + age.toMinutes() + " minutes ago)");
            }
            if (age.compareTo(MAX_AGE.negated()) < 0) {
                throw bad("it is dated in the future");
            }
            JsonNode aud = claims.get("aud");
            if (aud == null || !aud.isString() || aud.asString().isBlank()) {
                throw bad("it names no audience (aud), so it is not bound to this host; the department must sign the origin it serves the manifest from");
            }
            if (!normalizeOrigin(aud.asString()).equals(normalizeOrigin(expectedAud))) {
                throw bad("it was signed for " + aud.asString() + ", not for " + expectedAud + " where it was fetched from");
            }
            return Optional.of(ec.computeThumbprint().toString());
        } catch (InvalidRequestException e) {
            throw e;
        } catch (Exception e) {
            throw bad("it could not be read");
        }
    }

    /** Lower case, no trailing slash, default port dropped: {@code HTTPS://Dept.Gov:443/} and {@code https://dept.gov} are one origin. */
    static String normalizeOrigin(String origin) {
        String o = origin.trim().toLowerCase(Locale.ROOT).replaceAll("/+$", "");
        try {
            URI u = URI.create(o);
            if (u.getScheme() == null || u.getHost() == null || u.getRawPath() != null && !u.getRawPath().isEmpty()) {
                return o;
            }
            boolean dflt = u.getPort() < 0 || ("http".equals(u.getScheme()) && u.getPort() == 80) || ("https".equals(u.getScheme()) && u.getPort() == 443);
            return u.getScheme() + "://" + u.getHost() + (dflt ? "" : ":" + u.getPort());
        } catch (RuntimeException e) {
            return o;
        }
    }

    private static InvalidRequestException bad(String why) {
        return new InvalidRequestException("The manifest's signature is not valid: " + why + ".");
    }
}
