package com.samanvay.identity.internal.proof;

import java.util.UUID;

/**
 * The login a citizen started at a department: a one-time {@code state} and {@code nonce}, bound to that citizen and
 * department. The department echoes both in its assertion, so an assertion is only accepted for the citizen who
 * started that exact login, and only once (this is also the replay protection).
 */
public interface DepartmentLoginStates {

    record LoginState(String state, String nonce) {}

    /** A fresh, unguessable state and nonce for this citizen and department, valid for a short time. */
    LoginState issue(UUID citizenId, String departmentCode);

    /**
     * Marks the login used and returns true only if this exact (citizen, department, state, nonce) was issued,
     * is unexpired and was not used before. Atomic: two concurrent calls cannot both succeed.
     */
    boolean consume(UUID citizenId, String departmentCode, String state, String nonce);
}
