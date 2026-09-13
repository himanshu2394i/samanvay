package com.samanvay.consent.api;

public interface AccessAuthority {
    AccessDecision authorize(AccessRequest request);
}
