package com.samanvay.identity.api;

public record AuthProof(LinkProofKind provider, String payload) {

    public static AuthProof digiLockerSandbox() {
        return new AuthProof(LinkProofKind.DIGILOCKER, "sandbox");
    }

    public static AuthProof localIdOtpDemo() {
        return new AuthProof(LinkProofKind.LOCAL_ID_OTP, "000000");
    }
}
