package com.samanvay.identity.internal.proof;

import com.samanvay.identity.api.LinkProofInvalidException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A citizen signed in at their HOME department's own portal, and that portal passes Samanvay the signed assertion. There is
 * no Samanvay-issued state to match, so the assertion is accepted once: its {@code jti} is remembered and a second use is refused.
 */
@Component
public class DepartmentHomeLogin {

    private static final Logger log = LoggerFactory.getLogger(DepartmentHomeLogin.class);

    private final DepartmentAssertionVerifier verifier;
    private final AssertionUseStore uses;

    DepartmentHomeLogin(DepartmentAssertionVerifier verifier, AssertionUseStore uses) {
        this.verifier = verifier;
        this.uses = uses;
    }

    public VerifiedAssertion verify(String token, String departmentCode) {
        VerifiedAssertion a = verifier.verify(token, departmentCode);
        if (!uses.firstUse(departmentCode, a.jti(), a.expiresAt())) {
            log.info("department assertion refused: already used");
            throw new LinkProofInvalidException();
        }
        return a;
    }
}
