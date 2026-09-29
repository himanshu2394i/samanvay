package com.samanvay.consent.api;

import java.util.Optional;

public sealed interface AccessDecision {
    /**
     * @param claim the one-check claim this grant holds, settled after the connector call via
     *     {@link ConsentUsage}; {@code null} when the consent has no one-check rule
     */
    record Granted(AccessGrant grant, UsageClaim claim) implements AccessDecision {

        public Granted(AccessGrant grant) {
            this(grant, null);
        }
    }

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
