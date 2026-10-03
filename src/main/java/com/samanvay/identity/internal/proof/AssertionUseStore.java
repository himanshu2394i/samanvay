package com.samanvay.identity.internal.proof;

import java.time.Instant;

/** Remembers which login assertions (by {@code jti}) were already accepted, so a copied assertion is useless. */
public interface AssertionUseStore {

    /** True only the first time this (department, jti) is seen; atomic. {@code expiresAt} lets old rows be cleaned up. */
    boolean firstUse(String departmentCode, String jti, Instant expiresAt);
}
