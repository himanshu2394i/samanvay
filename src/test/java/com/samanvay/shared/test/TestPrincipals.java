package com.samanvay.shared.test;

import com.samanvay.shared.PrincipalRef;

/** Principals for tests that call module APIs directly (no HTTP, so no token). */
public final class TestPrincipals {

    public static final PrincipalRef OFFICER = new PrincipalRef(PrincipalRef.Kind.OFFICER, "officer-test");
    public static final PrincipalRef DEPARTMENT = new PrincipalRef(PrincipalRef.Kind.DEPARTMENT, "dept-test-client");

    private TestPrincipals() {}
}
