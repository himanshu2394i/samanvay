package com.samanvay.consent.api;

import java.util.UUID;

/**
 * The one-check claim a grant holds (V189/V190 consent_usage row {@code id} and the random
 * {@code token} written with this claim). Settling matches both, so a claim taken over by a later
 * check (which writes a new token) can never be settled by the check that lost it.
 */
public record UsageClaim(UUID id, UUID token) {}
