package com.samanvay.identity.api;

public record AuthProof(LinkProofKind provider, String payload) {

    public static AuthProof digiLockerSandbox() {
        return new AuthProof(LinkProofKind.DIGILOCKER, "sandbox");
    }

    public static AuthProof localIdOtpDemo() {
        return new AuthProof(LinkProofKind.LOCAL_ID_OTP, "000000");
    }

    /**
     * A department-brokered sign-in: the citizen-realm access token issued after the citizen
     * signed in through the department's identity provider. Verified, never trusted as given.
     */
    public static AuthProof departmentIdp(String brokeredAccessToken) {
        return new AuthProof(LinkProofKind.DEPT_IDP, brokeredAccessToken);
    }
}
