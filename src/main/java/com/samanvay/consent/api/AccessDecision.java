package com.samanvay.consent.api;

import java.util.Optional;

public sealed interface AccessDecision {
    record Granted(AccessGrant grant) implements AccessDecision {}

    record Denied(DenialReason reason, Optional<ConsentRequest> remedy) implements AccessDecision {}
}
