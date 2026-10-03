package com.samanvay.identity.api;

import java.util.UUID;

/** Sends a citizen to a department's own login so they can prove who they are there (docs/contracts/login-assertion.md). */
public interface DepartmentLogin {

    /**
     * Starts a login for this citizen at the department and returns the department login URL to send their browser to
     * (carrying {@code return_to}, a one-time {@code state} and a {@code nonce}). Refused if the department publishes
     * no login or {@code returnTo} is not an allow-listed Samanvay address.
     */
    String startLogin(UUID citizenId, String departmentCode, String returnTo);
}
