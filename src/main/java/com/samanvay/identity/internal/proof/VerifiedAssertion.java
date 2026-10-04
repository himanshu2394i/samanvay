package com.samanvay.identity.internal.proof;

import java.time.Instant;
import java.time.LocalDate;

/** What a department's signed login assertion proved. {@code name} and {@code dob} are optional claims; {@code dob} is null if absent or malformed. */
public record VerifiedAssertion(
        String departmentCode,
        String personIdType,
        String personId,
        String jti,
        String state,
        String nonce,
        String name,
        LocalDate dob,
        Instant expiresAt) {}
