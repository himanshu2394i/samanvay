package com.samanvay.catalog.internal.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.samanvay.shared.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the signature a department puts on its manifest (docs/contracts/manifest-signature.md): a compact ES256 JWS in the
 * {@value #HEADER} response header whose payload is {@code {"sha256": <hex of the exact body bytes>, "iat": <epoch seconds>}}
 * and whose header carries the department's PUBLIC key ({@code jwk}).
 *
 * <p>The key travels with the signature, so a valid signature alone proves nothing about WHO signed: trust comes from the
 * admin approving the returned thumbprint once (out of band, with the department) and Samanvay pinning it. After that a
 * manifest must be signed by that same key. {@code iat} must be recent, so an old signed manifest cannot be replayed.
 */
final class ManifestSignatures {

    static final String HEADER = "X-Samanvay-Signature";
    static final Duration MAX_AGE = Duration.ofMinutes(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ManifestSignatures() {}

    /**
     * @return the thumbprint (RFC 7638, base64url SHA-256) of the signing key; empty when the manifest carries no signature
     * @throws InvalidRequestException when a signature is present but is not valid for these bytes
     */
    static Optional<String> verify(byte[] body, String header, Instant now) {
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
            return Optional.of(ec.computeThumbprint().toString());
        } catch (InvalidRequestException e) {
            throw e;
        } catch (Exception e) {
            throw bad("it could not be read");
        }
    }

    private static InvalidRequestException bad(String why) {
        return new InvalidRequestException("The manifest's signature is not valid: " + why + ".");
    }
}
