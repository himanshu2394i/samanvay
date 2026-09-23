package com.samanvay.identity.internal.proof;

import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.LinkProofContext;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProvider;
import com.samanvay.identity.api.VerifiedLocalId;
import org.springframework.stereotype.Component;

@Component
class DigiLockerLinkProofProvider implements LinkProofProvider {

    private final DigiLockerClient client;

    DigiLockerLinkProofProvider(DigiLockerClient client) {
        this.client = client;
    }

    @Override
    public LinkProofKind kind() {
        return LinkProofKind.DIGILOCKER;
    }

    @Override
    public String label() {
        return "DigiLocker sandbox (mock)";
    }

    @Override
    public VerifiedLocalId verify(AuthProof proof, LinkProofContext context) {
        if (proof == null || proof.provider() != LinkProofKind.DIGILOCKER) {
            throw new LinkProofInvalidException();
        }
        DigiLockerClaims claims = client.redeem(proof.payload());
        if (claims.sandboxBind()) {
            return new VerifiedLocalId(context.localIdType(), context.localId());
        }
        if (!context.localIdType().equals(claims.localIdType()) || !context.localId().equals(claims.localId())) {
            throw new LinkProofInvalidException();
        }
        return new VerifiedLocalId(claims.localIdType(), claims.localId());
    }
}
