package com.samanvay.identity.internal.proof;

import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.LinkProofContext;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProvider;
import com.samanvay.identity.api.VerifiedLocalId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A fixed-OTP (000000) link proof: a demo stand-in, never a real proof. Registered only when
 * {@code samanvay.identity.demo-otp-link=true} (set in application-dev/-demo.yml and the integration-test config), so
 * a deployment without the switch neither accepts nor lists it.
 */
@Component
@ConditionalOnProperty(name = "samanvay.identity.demo-otp-link", havingValue = "true")
class LocalIdOtpLinkProofProvider implements LinkProofProvider {

    static final String DEMO_OTP = "000000";

    @Override
    public LinkProofKind kind() {
        return LinkProofKind.LOCAL_ID_OTP;
    }

    @Override
    public String label() {
        return "Local ID + OTP (demo)";
    }

    @Override
    public VerifiedLocalId verify(AuthProof proof, LinkProofContext context) {
        if (proof == null || proof.provider() != LinkProofKind.LOCAL_ID_OTP) {
            throw new LinkProofInvalidException();
        }
        if (!DEMO_OTP.equals(proof.payload())) {
            throw new LinkProofInvalidException();
        }
        return new VerifiedLocalId(context.localIdType(), context.localId());
    }
}
