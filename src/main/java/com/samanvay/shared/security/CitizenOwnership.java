package com.samanvay.shared.security;

import java.util.UUID;

/**
 * Answers "is this citizen record bound to this token subject?". Implemented
 * by {@code identity} (which owns the subject binding); declared here so any
 * module can enforce own-record rules without importing identity internals.
 */
public interface CitizenOwnership {

    boolean isBoundTo(UUID citizenId, String authSubject);
}
