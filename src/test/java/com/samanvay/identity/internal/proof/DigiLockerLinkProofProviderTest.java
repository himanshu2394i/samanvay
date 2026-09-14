package com.samanvay.identity.internal.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.LinkProofContext;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.VerifiedLocalId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DigiLockerLinkProofProviderTest {

    private final DigiLockerLinkProofProvider provider = new DigiLockerLinkProofProvider(new MockDigiLockerClient());

    @Test
    void sandboxTokenBindsRequestedLocalId() {
        VerifiedLocalId verified = provider.verify(
                AuthProof.digiLockerSandbox(), context("RATION", "RC-1"));
        assertThat(verified).isEqualTo(new VerifiedLocalId("RATION", "RC-1"));
        assertThat(provider.label()).isEqualTo("DigiLocker sandbox (mock)");
        assertThat(provider.kind()).isEqualTo(LinkProofKind.DIGILOCKER);
    }

    @Test
    void explicitSandboxClaimMustMatchRequest() {
        AuthProof proof = new AuthProof(LinkProofKind.DIGILOCKER, "sandbox:RATION:RC-1");
        assertThat(provider.verify(proof, context("RATION", "RC-1")))
                .isEqualTo(new VerifiedLocalId("RATION", "RC-1"));
        assertThatThrownBy(() -> provider.verify(proof, context("RATION", "OTHER")))
                .isInstanceOf(LinkProofInvalidException.class);
    }

    @Test
    void unknownTokenIsRejected() {
        assertThatThrownBy(() -> provider.verify(
                        new AuthProof(LinkProofKind.DIGILOCKER, "live-looking-jwt"), context("RATION", "RC-1")))
                .isInstanceOf(LinkProofInvalidException.class);
    }

    private static LinkProofContext context(String type, String id) {
        return new LinkProofContext(UUID.randomUUID(), "REVENUE", type, id);
    }
}
