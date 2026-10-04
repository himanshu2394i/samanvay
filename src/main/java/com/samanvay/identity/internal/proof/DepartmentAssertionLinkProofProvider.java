package com.samanvay.identity.internal.proof;

import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.DepartmentIdentity;
import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.LinkProofContext;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProvider;
import com.samanvay.identity.api.VerifiedLocalId;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger log = LoggerFactory.getLogger(DepartmentAssertionLinkProofProvider.class);

    private final DepartmentCatalog departments;
    private final DepartmentAssertionVerifier verifier;
    private final DepartmentLoginStates states;

    @Autowired
    DepartmentAssertionLinkProofProvider(DepartmentCatalog departments, DepartmentAssertionVerifier verifier, DepartmentLoginStates states) {
        this.departments = departments;
        this.verifier = verifier;
        this.states = states;
    }

    public DepartmentAssertionLinkProofProvider(
            DepartmentCatalog departments, JwksSource jwks, DepartmentLoginStates states, Clock clock, Duration maxAuthAge) {
        this(departments, new DepartmentAssertionVerifier(departments, jwks, clock, maxAuthAge), states);
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
        VerifiedAssertion a = verifier.verify(proof.payload(), context.departmentCode());
        if (!isBlank(context.localId()) && !context.localId().equals(a.personId())) {
            throw refuse("requested local id is not the asserted person");
        }
        if (!isBlank(context.localIdType()) && !context.localIdType().equals(identity.personIdType())) {
            throw refuse("requested local id type is not the asserted type");
        }
        if (a.state() == null || a.nonce() == null
                || !states.consume(context.citizenId(), context.departmentCode(), a.state(), a.nonce())) {
            throw refuse("no matching pending login for this citizen (unknown, used or expired)");
        }
        return new VerifiedLocalId(identity.personIdType(), a.personId());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static LinkProofInvalidException refuse(String reason) {
        log.info("department assertion link proof refused: {}", reason);
        return new LinkProofInvalidException();
    }
}
