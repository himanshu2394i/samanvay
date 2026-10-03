package com.samanvay.connector.api;

import com.samanvay.connector.api.BankCheckAdapter.BankCheckAnswer;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckRequest;
import com.samanvay.consent.api.AccessGrant;

public interface ConnectorRuntime {
    ConnectorResult execute(AccessGrant grant, Capability capability, ExecutionInputs inputs);

    /**
     * A TRIAL fetch (onboarding): runs a connector, drafted or published, for the department's published FAKE sample person
     * so an admin sees real data arrive before publishing. No consent grant is involved (no citizen is), no citizen data access
     * is recorded, and exactly one {@code CONNECTOR_TRIAL} audit row names the admin and the connector. A department failure
     * propagates, so the caller can show the real cause.
     *
     * @throws com.samanvay.shared.InvalidRequestException if {@code samplePersonId} is blank
     */
    ConnectorResult trial(String connectorRef, String samplePersonId, com.samanvay.shared.PrincipalRef by);

    /**
     * Verifies the grant (category BANK_ACCOUNT, connector ref = {@code sourceCode})
     * and only then looks up the source's adapter and calls it. A grant that fails
     * verification is audited and thrown ({@code InvalidGrantException}); no adapter
     * is looked up and no call leaves the platform.
     */
    SourceOutcome<BankCheckAnswer> bankCheck(AccessGrant grant, String sourceCode, BankCheckRequest request);
}
