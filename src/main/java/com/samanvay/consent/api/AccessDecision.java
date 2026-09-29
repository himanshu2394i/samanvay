package com.samanvay.consent.api;

import java.util.Optional;

public sealed interface AccessDecision {
    record Granted(AccessGrant grant) implements AccessDecision {}

    /**
     * @param message plain-language explanation for the citizen (from the consent copy table),
     *     or the reason code when the reason has no copy
     */
    record Denied(DenialReason reason, Optional<ConsentRequest> remedy, String message) implements AccessDecision {

        public Denied(DenialReason reason, Optional<ConsentRequest> remedy) {
            this(reason, remedy, reason.name());
        }
    }
}
