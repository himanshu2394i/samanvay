package com.samanvay.identity.internal.proof;

import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.LinkProofContext;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProvider;
import com.samanvay.identity.api.VerifiedLocalId;
import com.samanvay.identity.internal.repository.CitizenRepository;
import com.samanvay.shared.security.CitizenTokenVerifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A citizen signed in through a department's identity provider, brokered by Keycloak (the
 * citizen realm's {@code dept-idp} broker), and presents the resulting citizen-realm access
 * token as the proof. Present only when {@code samanvay.identity.department-idp.enabled=true};
 * without it the provider does not exist, the proof kind is refused and it is not listed.
 *
 * <p>The proof is the token itself, and it is never trusted as given:
 *
 * <ol>
 *   <li>{@link CitizenTokenVerifier}: signature, issuer, expiry, {@code aud}, {@code azp},
 *       {@code typ=Bearer} - the API's own bearer checks.
 *   <li>The token's {@code sub} is the auth subject the target citizen record is bound to. A
 *       token of another citizen (or a record not bound to any subject) is refused.
 *   <li>The broker claims are present and match: {@code dept_idp} is the configured broker
 *       alias, {@code dept_code} the department being linked, and {@code dept_local_id_type} /
 *       {@code dept_local_id} the local id being asserted - exactly, never "bind to whatever
 *       was asked" (unlike the DigiLocker sandbox). The claims exist only in a session the
 *       broker created (session notes, see keycloak/gen_realms.py); an email-code or passkey
 *       token has none.
 *   <li>Fresh: {@code auth_time} (the login itself, not a token refresh) is present and no
 *       older than {@code max-auth-age}, so a long-lived session cannot mint links later.
 * </ol>
 *
 * Every failure is the same {@link LinkProofInvalidException}; the reason goes to the log
 * (never the token). Public (unlike its siblings) only so identity's service tests can compose it
 * with the real {@code IdentityServices}; the package is module-internal.
 */
@Component
@ConditionalOnProperty(prefix = "samanvay.identity.department-idp", name = "enabled", havingValue = "true")
public class DepartmentBrokerLinkProofProvider implements LinkProofProvider {

    static final String CLAIM_IDP = "dept_idp";
    static final String CLAIM_DEPARTMENT = "dept_code";
    static final String CLAIM_LOCAL_ID_TYPE = "dept_local_id_type";
    static final String CLAIM_LOCAL_ID = "dept_local_id";
    static final String CLAIM_AUTH_TIME = "auth_time";

    static final String DEFAULT_ALIAS = "dept-idp";
    static final Duration DEFAULT_MAX_AUTH_AGE = Duration.ofMinutes(10);
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(DepartmentBrokerLinkProofProvider.class);

    private final CitizenTokenVerifier tokens;
    private final CitizenRepository citizens;
    private final Clock clock;
    private final String brokerAlias;
    private final Duration maxAuthAge;

    @Autowired
    DepartmentBrokerLinkProofProvider(
            CitizenTokenVerifier tokens,
            CitizenRepository citizens,
            Clock clock,
            @Value("${samanvay.identity.department-idp.alias:" + DEFAULT_ALIAS + "}") String brokerAlias,
            @Value("${samanvay.identity.department-idp.max-auth-age:PT10M}") String maxAuthAge) {
        this(tokens, citizens, clock, brokerAlias, parse(maxAuthAge));
    }

    public DepartmentBrokerLinkProofProvider(
            CitizenTokenVerifier tokens,
            CitizenRepository citizens,
            Clock clock,
            String brokerAlias,
            Duration maxAuthAge) {
        if (brokerAlias == null || brokerAlias.isBlank()) {
            throw new IllegalArgumentException("samanvay.identity.department-idp.alias must not be blank");
        }
        if (maxAuthAge == null || maxAuthAge.isZero() || maxAuthAge.isNegative()) {
            throw new IllegalArgumentException("samanvay.identity.department-idp.max-auth-age must be positive");
        }
        this.tokens = tokens;
        this.citizens = citizens;
        this.clock = clock;
        this.brokerAlias = brokerAlias;
        this.maxAuthAge = maxAuthAge;
    }

    private static Duration parse(String iso8601) {
        try {
            return Duration.parse(iso8601.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "samanvay.identity.department-idp.max-auth-age must be an ISO-8601 duration such as PT10M", e);
        }
    }

    @Override
    public LinkProofKind kind() {
        return LinkProofKind.DEPT_IDP;
    }

    @Override
    public String label() {
        return "Department sign-in (mock department IdP)";
    }

    @Override
    public VerifiedLocalId verify(AuthProof proof, LinkProofContext context) {
        if (proof == null || proof.provider() != LinkProofKind.DEPT_IDP) {
            throw new LinkProofInvalidException();
        }
        if (isBlank(proof.payload())
                || context == null
                || context.citizenId() == null
                || isBlank(context.departmentCode())
                || isBlank(context.localIdType())
                || isBlank(context.localId())) {
            throw refuse("proof or link request incomplete");
        }
        CitizenTokenVerifier.VerifiedToken token =
                tokens.verify(proof.payload()).orElseThrow(() -> refuse("token failed verification"));

        if (!citizens.existsByIdAndAuthSubject(context.citizenId(), token.subject())) {
            throw refuse("token subject is not the subject bound to citizen " + context.citizenId());
        }
        Map<String, Object> claims = token.claims();
        if (!brokerAlias.equals(text(claims, CLAIM_IDP))) {
            throw refuse("token was not issued from a sign-in brokered through " + brokerAlias);
        }
        requireFresh(claims);

        String department = text(claims, CLAIM_DEPARTMENT);
        String localIdType = text(claims, CLAIM_LOCAL_ID_TYPE);
        String localId = text(claims, CLAIM_LOCAL_ID);
        if (department == null || localIdType == null || localId == null) {
            throw refuse("brokered token carries no department / local id claims");
        }
        if (!department.equals(context.departmentCode())
                || !localIdType.equals(context.localIdType())
                || !localId.equals(context.localId())) {
            throw refuse("brokered department identity does not match the requested link");
        }
        return new VerifiedLocalId(localIdType, localId);
    }

    private void requireFresh(Map<String, Object> claims) {
        if (!(claims.get(CLAIM_AUTH_TIME) instanceof Number seconds)) {
            throw refuse("token has no auth_time");
        }
        Instant authenticated = Instant.ofEpochSecond(seconds.longValue());
        Instant now = clock.instant();
        if (authenticated.isAfter(now.plus(CLOCK_SKEW))) {
            throw refuse("auth_time is in the future");
        }
        if (authenticated.isBefore(now.minus(maxAuthAge))) {
            throw refuse("department sign-in is older than " + maxAuthAge);
        }
    }

    private static String text(Map<String, Object> claims, String name) {
        return claims.get(name) instanceof String s && !s.isBlank() ? s : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static LinkProofInvalidException refuse(String reason) {
        log.info("department IdP link proof refused: {}", reason);
        return new LinkProofInvalidException();
    }
}
