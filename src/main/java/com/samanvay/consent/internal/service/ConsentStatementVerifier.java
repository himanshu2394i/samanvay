package com.samanvay.consent.internal.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.consent.api.ConsentStatementInvalidException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Checks the cryptography and the claims of a department's consent statement. It does not look at the database: whether the request is
 * open, the nonce unused and the {@code jti} new is decided by {@link DepartmentConsentService}.
 *
 * <p>The statement is an ES256 JWS of type {@value #TYPE} carrying the department's public key in its header. That key is trusted only
 * if its thumbprint equals the one pinned from the department's signed manifest, so the department must have been onboarded first.
 * Every failure is the same {@link ConsentStatementInvalidException}; the reason is only logged, never the statement.
 */
@Component
public class ConsentStatementVerifier {

    public static final String TYPE = "samanvay-consent";
    public static final String METHOD = "dept-otp";

    static final Duration MAX_LIFETIME = Duration.ofMinutes(5);
    static final Duration MAX_CONFIRMATION_AGE = Duration.ofMinutes(10);
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(ConsentStatementVerifier.class);

    /** What the statement says, once the signature and claims check out. */
    public record VerifiedStatement(
            String jti, UUID requestId, UUID citizenId, String purpose, Set<String> categories, String nonce, String thumbprint) {}

    private final DepartmentCatalog departments;
    private final Clock clock;

    ConsentStatementVerifier(DepartmentCatalog departments, Clock clock) {
        this.departments = departments;
        this.clock = clock;
    }

    public VerifiedStatement verify(String statement, String departmentCode) {
        if (statement == null || statement.isBlank() || departmentCode == null || departmentCode.isBlank()) {
            throw refuse("statement or department missing");
        }
        String pinned = departments.manifestKeyThumbprint(departmentCode).orElseThrow(() -> refuse("no key pinned for " + departmentCode));
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(statement.trim());
        } catch (ParseException | RuntimeException e) {
            throw refuse("not a signed JWT");
        }
        if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())
                || jwt.getHeader().getType() == null || !TYPE.equals(jwt.getHeader().getType().getType())) {
            throw refuse("wrong algorithm or type");
        }
        if (!(jwt.getHeader().getJWK() instanceof ECKey key) || key.isPrivate() || !Curve.P_256.equals(key.getCurve())) {
            throw refuse("no usable public key in the header");
        }
        try {
            if (!pinned.equals(key.computeThumbprint().toString())) {
                throw refuse("header key is not the pinned key");
            }
            if (!jwt.verify(new ECDSAVerifier(key))) {
                throw refuse("signature does not verify");
            }
        } catch (JOSEException e) {
            throw refuse("signature could not be checked");
        }
        JWTClaimsSet c;
        try {
            c = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw refuse("claims are not valid");
        }
        if (!("dept:" + departmentCode).equals(c.getIssuer()) || !departmentCode.equals(text(c, "dept_code"))) {
            throw refuse("statement is for another department");
        }
        if (!METHOD.equals(text(c, "method"))) {
            throw refuse("unknown confirmation method");
        }
        requireTimes(c);
        String jti = c.getJWTID();
        String nonce = text(c, "nonce");
        String purpose = text(c, "purpose");
        if (jti == null || jti.isBlank() || nonce == null || purpose == null) {
            throw refuse("jti, nonce or purpose missing");
        }
        return new VerifiedStatement(jti, uuid(c, "request_id"), uuid(c, "citizen_id"), purpose, categories(c), nonce, pinned);
    }

    private void requireTimes(JWTClaimsSet c) {
        Instant now = clock.instant();
        if (c.getIssueTime() == null || c.getExpirationTime() == null) {
            throw refuse("no iat or exp");
        }
        Instant iat = c.getIssueTime().toInstant();
        Instant exp = c.getExpirationTime().toInstant();
        if (exp.plus(CLOCK_SKEW).isBefore(now) || iat.isAfter(now.plus(CLOCK_SKEW)) || Duration.between(iat, exp).compareTo(MAX_LIFETIME) > 0) {
            throw refuse("statement is expired, from the future or lives too long");
        }
        if (!(c.getClaim("confirmed_at") instanceof Number at)) {
            throw refuse("no confirmed_at");
        }
        Instant confirmed = Instant.ofEpochSecond(at.longValue());
        if (confirmed.isAfter(now.plus(CLOCK_SKEW)) || confirmed.isBefore(now.minus(MAX_CONFIRMATION_AGE))) {
            throw refuse("confirmation is not recent");
        }
    }

    private static String text(JWTClaimsSet c, String name) {
        return c.getClaim(name) instanceof String s && !s.isBlank() ? s : null;
    }

    private static UUID uuid(JWTClaimsSet c, String name) {
        try {
            return UUID.fromString(text(c, name));
        } catch (RuntimeException e) {
            throw refuse(name + " is not a UUID");
        }
    }

    private static Set<String> categories(JWTClaimsSet c) {
        if (!(c.getClaim("categories") instanceof List<?> list) || list.isEmpty() || !list.stream().allMatch(String.class::isInstance)) {
            throw refuse("categories missing");
        }
        Set<String> out = new HashSet<>();
        list.forEach(o -> out.add((String) o));
        return out;
    }

    static ConsentStatementInvalidException refuse(String reason) {
        log.info("consent statement refused: {}", reason);
        return new ConsentStatementInvalidException();
    }
}
