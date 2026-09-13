package com.samanvay.consent.api;

import com.samanvay.shared.DataCategory;

public interface AccessGrantVerifier {
    void verifyOrThrow(AccessGrant grant, DataCategory expectedCategory, String expectedConnectorRef);
}
