package com.samanvay.identity.internal.proof;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.DepartmentIdentity;
import com.samanvay.identity.api.LinkProofInvalidException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The checks every department login assertion must pass (docs/contracts/login-assertion.md), whoever asks:
 *
 * <ol>
 *   <li>ES256 JWS only, verified against the keys the department published (one fresh fetch on an unknown key ID).
 *   <li>{@code iss}, {@code aud=samanvay}, {@code dept_code} and {@code person_id_type} match the onboarded department.
 *   <li>Short life ({@code exp - iat} at most 5 minutes) and a recent {@code auth_time}.
 * </ol>
 *
 * It does NOT decide whether the assertion was already used: linking checks the {@code state} Samanvay issued, a home
 * sign in checks the {@code jti} ({@link DepartmentHomeLogin}). Every failure is the same {@link LinkProofInvalidException};
 * the reason is only logged, never the token.
 */
@Component
public class DepartmentAssertionVerifier {

    static final String AUDIENCE = "samanvay";
    static final Duration MAX_ASSERTION_LIFETIME = Duration.ofMinutes(5);
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    /** Longest subject or jti accepted (the columns hold 200). */
    static final int MAX_ID_LENGTH = 180;
    /** Longer names are cut to this many characters (identity_profile.name_latin holds 200). */
    static final int MAX_NAME_LENGTH = 200;

    private static final Logger log = LoggerFactory.getLogger(DepartmentAssertionVerifier.class);

    private final DepartmentCatalog departments;
    private final JwksSource jwks;
    private final Clock clock;
    private final Duration maxAuthAge;

    @Autowired
    DepartmentAssertionVerifier(
            DepartmentCatalog departments,
            JwksSource jwks,
            Clock clock,
            @Value("${samanvay.identity.department-assertion.max-auth-age:PT10M}") String maxAuthAge) {
        this(departments, jwks, clock, Duration.parse(maxAuthAge.trim()));
    }

    public DepartmentAssertionVerifier(DepartmentCatalog departments, JwksSource jwks, Clock clock, Duration maxAuthAge) {
        if (maxAuthAge == null || maxAuthAge.isZero() || maxAuthAge.isNegative()) {
            throw new IllegalArgumentException("samanvay.identity.department-assertion.max-auth-age must be positive");
        }
        this.departments = departments;
        this.jwks = jwks;
        this.clock = clock;
        this.maxAuthAge = maxAuthAge;
    }

    /** Verifies {@code token} as a login assertion of {@code departmentCode}; throws {@link LinkProofInvalidException} otherwise. */
    public VerifiedAssertion verify(String token, String departmentCode) {
        if (isBlank(token) || isBlank(departmentCode)) {
            throw refuse("assertion or department missing");
        }
        DepartmentIdentity identity = departments.identity(departmentCode)
                .orElseThrow(() -> refuse("department " + departmentCode + " publishes no login"));
        SignedJWT jwt = parse(token);
        verifySignature(jwt, identity);
        JWTClaimsSet c = claims(jwt);

        String issuer = isBlank(identity.assertionIssuer()) ? "dept:" + departmentCode : identity.assertionIssuer();
        if (!issuer.equals(c.getIssuer())) {
            throw refuse("wrong issuer");
        }
        if (c.getAudience() == null || !c.getAudience().contains(AUDIENCE)) {
            throw refuse("not addressed to samanvay");
        }
        if (!departmentCode.equals(text(c, "dept_code"))) {
            throw refuse("assertion is for another department");
        }
        if (!identity.personIdType().equals(text(c, "person_id_type"))) {
            throw refuse("unexpected person id type");
        }
        String personId = c.getSubject();
        if (isBlank(personId) || isBlank(c.getJWTID())) {
            throw refuse("no subject or jti");
        }
        if (personId.length() > MAX_ID_LENGTH || c.getJWTID().length() > MAX_ID_LENGTH) {
            throw refuse("subject or jti is longer than " + MAX_ID_LENGTH); // they are stored in 200-character columns
        }
        requireTimes(c);
        return new VerifiedAssertion(departmentCode, identity.personIdType(), personId, c.getJWTID(),
                text(c, "state"), text(c, "nonce"), truncated(text(c, "name"), MAX_NAME_LENGTH), date(c, "dob"), c.getExpirationTime().toInstant());
    }

    private SignedJWT parse(String token) {
        try {
            return SignedJWT.parse(token.trim());
        } catch (ParseException | RuntimeException e) {
            throw refuse("not a signed JWT");
        }
    }

    private void verifySignature(SignedJWT jwt, DepartmentIdentity identity) {
        if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
            throw refuse("algorithm is not ES256");
        }
        String kid = jwt.getHeader().getKeyID();
        if (isBlank(kid) || isBlank(identity.jwksUrl())) {
            throw refuse("no key id or no published keys");
        }
        // Onboarding only checked the HOST; the keys must also come over the same scheme and port as the login the department declared.
        String keysOrigin = origin(identity.jwksUrl());
        if (keysOrigin == null || !keysOrigin.equals(origin(identity.loginUrl()))) {
            throw refuse("the published keys are not on the department's own login origin");
        }
        JWK jwk;
        try {
            jwk = jwks.keys(identity.jwksUrl(), false).getKeyByKeyId(kid);
            if (jwk == null) {
                jwk = jwks.keys(identity.jwksUrl(), true).getKeyByKeyId(kid); // the department may have rotated its keys
            }
        } catch (RuntimeException e) {
            throw refuse("the department's published keys could not be fetched");
        }
        if (!(jwk instanceof ECKey ec)) {
            throw refuse("no published EC key with that id");
        }
        try {
            if (!jwt.verify(new ECDSAVerifier(ec.toPublicJWK()))) {
                throw refuse("signature does not verify");
            }
        } catch (JOSEException e) {
            throw refuse("signature could not be checked");
        }
    }

    private static JWTClaimsSet claims(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw refuse("claims are not valid");
        }
    }

    private void requireTimes(JWTClaimsSet c) {
        Instant now = clock.instant();
        Date exp = c.getExpirationTime();
        Date iat = c.getIssueTime();
        if (exp == null || iat == null) {
            throw refuse("no exp or iat");
        }
        if (exp.toInstant().plus(CLOCK_SKEW).isBefore(now)) {
            throw refuse("assertion has expired");
        }
        if (iat.toInstant().isAfter(now.plus(CLOCK_SKEW))) {
            throw refuse("assertion is issued in the future");
        }
        Date nbf = c.getNotBeforeTime();
        if (nbf != null && nbf.toInstant().isAfter(now.plus(CLOCK_SKEW))) {
            throw refuse("assertion is not valid yet");
        }
        if (Duration.between(iat.toInstant(), exp.toInstant()).compareTo(MAX_ASSERTION_LIFETIME) > 0) {
            throw refuse("assertion lives longer than " + MAX_ASSERTION_LIFETIME);
        }
        Object at = c.getClaim("auth_time");
        if (!(at instanceof Number seconds)) {
            throw refuse("no auth_time");
        }
        Instant authenticated = Instant.ofEpochSecond(seconds.longValue());
        if (authenticated.isAfter(now.plus(CLOCK_SKEW))) {
            throw refuse("auth_time is in the future");
        }
        if (authenticated.isBefore(now.minus(maxAuthAge))) {
            throw refuse("department login is older than " + maxAuthAge);
        }
    }

    /** scheme://host:port in lower case with the default port made explicit; null if the address has no scheme or host. */
    static String origin(String url) {
        try {
            java.net.URI u = java.net.URI.create(url == null ? "" : url.trim());
            if (u.getScheme() == null || u.getHost() == null) {
                return null;
            }
            String scheme = u.getScheme().toLowerCase(java.util.Locale.ROOT);
            int port = u.getPort() >= 0 ? u.getPort() : "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
            return scheme + "://" + u.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String truncated(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String text(JWTClaimsSet c, String name) {
        return c.getClaim(name) instanceof String s && !s.isBlank() ? s : null;
    }

    private static LocalDate date(JWTClaimsSet c, String name) {
        String s = text(c, name);
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static LinkProofInvalidException refuse(String reason) {
        log.info("department assertion refused: {}", reason);
        return new LinkProofInvalidException();
    }
}
