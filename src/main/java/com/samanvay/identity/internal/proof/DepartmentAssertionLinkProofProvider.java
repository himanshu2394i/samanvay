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
import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.LinkProofContext;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProvider;
import com.samanvay.identity.api.VerifiedLocalId;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A citizen proved who they are by logging in at the department itself; the department's signed assertion
 * (docs/contracts/login-assertion.md) is the proof. It is never trusted as given:
 *
 * <ol>
 *   <li>It is an ES256 JWS only (no unsigned or HMAC token), verified against the public keys the department
 *       published; an unknown key ID triggers one fresh fetch (key rotation).
 *   <li>{@code iss}, {@code aud=samanvay}, {@code dept_code} and {@code person_id_type} match the onboarded department.
 *   <li>It is short-lived ({@code exp - iat} at most 5 minutes) and its {@code auth_time} (the actual login) is recent.
 *   <li>Its {@code state} and {@code nonce} are the ones issued to THIS citizen for THIS department, and are consumed
 *       (single use, so a replayed assertion fails). The state is consumed last: a bad assertion cannot burn a
 *       citizen's pending login.
 * </ol>
 *
 * The person ID is the assertion's {@code sub}; a {@code localId} in the request, when given, must equal it. Every
 * failure is the same {@link LinkProofInvalidException}; the reason is only logged (never the token).
 */
@Component
public class DepartmentAssertionLinkProofProvider implements LinkProofProvider {

    static final String AUDIENCE = "samanvay";
    static final Duration MAX_ASSERTION_LIFETIME = Duration.ofMinutes(5);
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    static final Duration DEFAULT_MAX_AUTH_AGE = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(DepartmentAssertionLinkProofProvider.class);

    private final DepartmentCatalog departments;
    private final JwksSource jwks;
    private final DepartmentLoginStates states;
    private final Clock clock;
    private final Duration maxAuthAge;

    @Autowired
    DepartmentAssertionLinkProofProvider(
            DepartmentCatalog departments,
            JwksSource jwks,
            DepartmentLoginStates states,
            Clock clock,
            @Value("${samanvay.identity.department-assertion.max-auth-age:PT10M}") String maxAuthAge) {
        this(departments, jwks, states, clock, Duration.parse(maxAuthAge.trim()));
    }

    public DepartmentAssertionLinkProofProvider(
            DepartmentCatalog departments, JwksSource jwks, DepartmentLoginStates states, Clock clock, Duration maxAuthAge) {
        if (maxAuthAge == null || maxAuthAge.isZero() || maxAuthAge.isNegative()) {
            throw new IllegalArgumentException("samanvay.identity.department-assertion.max-auth-age must be positive");
        }
        this.departments = departments;
        this.jwks = jwks;
        this.states = states;
        this.clock = clock;
        this.maxAuthAge = maxAuthAge;
    }

    @Override
    public LinkProofKind kind() {
        return LinkProofKind.DEPT_ASSERTION;
    }

    @Override
    public String label() {
        return "Department login (signed assertion)";
    }

    @Override
    public VerifiedLocalId verify(AuthProof proof, LinkProofContext context) {
        if (proof == null || proof.provider() != LinkProofKind.DEPT_ASSERTION || isBlank(proof.payload())
                || context == null || context.citizenId() == null || isBlank(context.departmentCode())) {
            throw refuse("proof or link request incomplete");
        }
        DepartmentIdentity identity = departments.identity(context.departmentCode())
                .orElseThrow(() -> refuse("department " + context.departmentCode() + " publishes no login"));

        SignedJWT jwt = parse(proof.payload());
        verifySignature(jwt, identity);
        JWTClaimsSet c = claims(jwt);

        String issuer = isBlank(identity.assertionIssuer()) ? "dept:" + context.departmentCode() : identity.assertionIssuer();
        if (!issuer.equals(c.getIssuer())) {
            throw refuse("wrong issuer");
        }
        if (c.getAudience() == null || !c.getAudience().contains(AUDIENCE)) {
            throw refuse("not addressed to samanvay");
        }
        if (!context.departmentCode().equals(text(c, "dept_code"))) {
            throw refuse("assertion is for another department");
        }
        if (!identity.personIdType().equals(text(c, "person_id_type"))) {
            throw refuse("unexpected person id type");
        }
        String personId = c.getSubject();
        if (isBlank(personId) || isBlank(c.getJWTID())) {
            throw refuse("no subject or jti");
        }
        if (!isBlank(context.localId()) && !context.localId().equals(personId)) {
            throw refuse("requested local id is not the asserted person");
        }
        if (!isBlank(context.localIdType()) && !context.localIdType().equals(identity.personIdType())) {
            throw refuse("requested local id type is not the asserted type");
        }
        requireTimes(c);
        String state = text(c, "state");
        String nonce = text(c, "nonce");
        if (state == null || nonce == null || !states.consume(context.citizenId(), context.departmentCode(), state, nonce)) {
            throw refuse("no matching pending login for this citizen (unknown, used or expired)");
        }
        return new VerifiedLocalId(identity.personIdType(), personId);
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

    private static String text(JWTClaimsSet c, String name) {
        return c.getClaim(name) instanceof String s && !s.isBlank() ? s : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static LinkProofInvalidException refuse(String reason) {
        log.info("department assertion link proof refused: {}", reason);
        return new LinkProofInvalidException();
    }
}
