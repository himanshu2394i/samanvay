package com.samanvay.consent.api;

import java.util.Optional;

public sealed interface AccessDecision {
    record Granted(AccessGrant grant) implements AccessDecision {}

    record Denied(DenialReason reason, Optional<ConsentRequest> remedy) implements AccessDecision {
        /** Plain-language explanation for the citizen, or the reason code when there is none. */
        public String message() {
            return reason.plainMessage() != null ? reason.plainMessage() : reason.name();
        }
    }
}
