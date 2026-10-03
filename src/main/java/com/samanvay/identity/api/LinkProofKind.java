package com.samanvay.identity.api;

public enum LinkProofKind {
    LOCAL_ID_OTP,
    DEPT_IDP,
    /** A citizen logged in at the department itself; its signed assertion is the proof (docs/contracts/login-assertion.md). */
    DEPT_ASSERTION
}
