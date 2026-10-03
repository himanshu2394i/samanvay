package in.samanvay.departments.revenue;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs Revenue's login assertion (docs/contracts/login-assertion.md): an ES256 JWT stating "this citizen just
 * logged in and is person X at Revenue". The public half of the key is published as a JWKS.
 *
 * <p>ponytail: the key is generated at startup, so a restart rotates it (Samanvay re-fetches the JWKS on an unknown
 * {@code kid}); load a persistent key when assertions must survive restarts.
 */
@Component
class AssertionSigner {

    static final String ISSUER = "dept:REVENUE";
    static final String DEPT_CODE = "REVENUE";
    static final String PERSON_ID_TYPE = "REVENUE_PERSON_ID";

    private final ECKey key;
    private final Duration ttl;

    AssertionSigner(@Value("${revenue.login.assertion-ttl}") Duration ttl) throws JOSEException {
        this.key = new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate();
        this.ttl = ttl;
    }

    String sign(String personId, String state, String nonce) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience("samanvay").subject(personId)
                .claim("person_id_type", PERSON_ID_TYPE).claim("dept_code", DEPT_CODE)
                .claim("auth_time", now.getEpochSecond())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(ttl)))
                .jwtID(UUID.randomUUID().toString()).claim("nonce", nonce).claim("state", state)
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the login assertion", e);
        }
    }

    /** The public key only. */
    String jwksJson() {
        return new JWKSet(key.toPublicJWK()).toString();
    }
}
