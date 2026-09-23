package com.samanvay.identity.internal.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.LinkProofContext;
import com.samanvay.identity.api.LinkProofInvalidException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalIdOtpLinkProofProviderTest {

    private final LocalIdOtpLinkProofProvider provider = new LocalIdOtpLinkProofProvider();

    @Test
    void demoOtpVerifiesWithoutSms() {
        assertThat(provider.label()).isEqualTo("Local ID + OTP (demo)");
        assertThat(provider.verify(AuthProof.localIdOtpDemo(), context()))
                .extracting("localId")
                .isEqualTo("RC-1");
    }

    @Test
    void wrongOtpIsRejected() {
        assertThatThrownBy(() -> provider.verify(
                        new AuthProof(com.samanvay.identity.api.LinkProofKind.LOCAL_ID_OTP, "123456"), context()))
                .isInstanceOf(LinkProofInvalidException.class);
    }

    private static LinkProofContext context() {
        return new LinkProofContext(UUID.randomUUID(), "REVENUE", "RATION", "RC-1");
    }
}
