package com.samanvay.consent.api;

import java.util.UUID;

/**
 * A request for a citizen's consent. Only who and which purpose: the purpose
 * text and data categories are taken from the catalog purpose, and the
 * requester must be the purpose's requesting department.
 *
 * @param requesterId department code of the requester (web callers: derived from the token)
 */
public record ConsentRequestDraft(UUID citizenId, String requesterId, String purposeCode) {}
