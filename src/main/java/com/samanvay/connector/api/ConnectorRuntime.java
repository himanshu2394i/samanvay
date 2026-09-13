package com.samanvay.connector.api;

import com.samanvay.consent.api.AccessGrant;

public interface ConnectorRuntime {
    ConnectorResult execute(AccessGrant grant, Capability capability, ExecutionInputs inputs);
}
