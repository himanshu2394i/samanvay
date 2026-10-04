package in.samanvay.departments.kit;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Signs the department's statement that a citizen confirmed a consent (docs/contracts/department-consent-statement.md). Call it only
 * after the citizen re-entered the one-time code, and only with the wording Samanvay returned for this request: the statement repeats
 * that request's ID, purpose, categories and nonce, which is what ties it to exactly what was shown.
 */
public final class ConsentSigner {

    static final String TYPE = "samanvay-consent";
    static final Duration LIFETIME = Duration.ofMinutes(5);

    private final ECKey key;
    private final String deptCode;

    public ConsentSigner(ECKey key, String deptCode) {
        this.key = key;
        this.deptCode = deptCode;
    }

    /** @param wording the map Samanvay returned from {@code POST /api/department/consents/requests} */
    public String sign(Map<String, Object> wording, UUID citizenId, Instant confirmedAt) {
        // A statement is only worth signing if it repeats exactly what was shown: never sign the text "null" for a missing field.
        if (citizenId == null) {
            throw new IllegalArgumentException("citizenId is required");
        }
        String requestId = required(wording, "requestId");
        String purpose = required(wording, "purposeCode");
        String nonce = required(wording, "nonce");
        if (!(wording.get("categories") instanceof List<?> categories) || categories.isEmpty()) {
            throw new IllegalArgumentException("categories is required in the consent wording");
        }
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("dept:" + deptCode).claim("dept_code", deptCode).jwtID(UUID.randomUUID().toString())
                .claim("citizen_id", citizenId.toString())
                .claim("request_id", requestId)
                .claim("purpose", purpose)
                .claim("nonce", nonce)
                .claim("categories", categories)
                .claim("method", "dept-otp")
                .claim("confirmed_at", confirmedAt.getEpochSecond())
                .issueTime(Date.from(confirmedAt)).expirationTime(Date.from(confirmedAt.plus(LIFETIME)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType(TYPE)).jwk(key.toPublicJWK()).build(), claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the consent statement", e);
        }
    }

    private static String required(Map<String, Object> wording, String field) {
        Object v = wording.get(field);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(field + " is required in the consent wording");
        }
        return s;
    }
}
