package com.samanvay.catalog.internal.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Signs a manifest the way a department does (docs/contracts/manifest-signature.md), for tests. */
final class ManifestSigningFixture {

    private ManifestSigningFixture() {}

    static ECKey newKey() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("test").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static String thumbprint(ECKey key) {
        try {
            return key.computeThumbprint().toString();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sign(String body, ECKey key, Instant iat) {
        return sign(body.getBytes(StandardCharsets.UTF_8), key, iat);
    }

    static String sign(byte[] body, ECKey key, Instant iat) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            JWSObject jws = new JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).type(new com.nimbusds.jose.JOSEObjectType("samanvay-manifest")).jwk(key.toPublicJWK()).build(),
                    new Payload("{\"sha256\":\"" + digest + "\",\"iat\":" + iat.getEpochSecond() + "}"));
            jws.sign(new ECDSASigner(key));
            return jws.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
