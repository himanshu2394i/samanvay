package com.samanvay.consent.api;

import com.samanvay.shared.PrincipalRef;
import java.util.UUID;

/**
 * A request for a citizen's consent. Only who and which purpose: the purpose
 * text and data categories are taken from the catalog purpose, and the
 * requester must be the purpose's requesting department.
 *
 * @param requesterId department code of the requester (web callers: derived from the token)
 * @param principal who asked (from the token), recorded on refusal audit entries; {@code null}
 *     for internal callers, which are then recorded as the requester department
 */
public record ConsentRequestDraft(UUID citizenId, String requesterId, String purposeCode, PrincipalRef principal) {

    public ConsentRequestDraft(UUID citizenId, String requesterId, String purposeCode) {
        this(citizenId, requesterId, purposeCode, null);
    }
}
