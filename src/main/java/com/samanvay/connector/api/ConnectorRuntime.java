package com.samanvay.connector.api;

import com.samanvay.connector.api.BankCheckAdapter.BankCheckAnswer;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckRequest;
import com.samanvay.consent.api.AccessGrant;

public interface ConnectorRuntime {
    ConnectorResult execute(AccessGrant grant, Capability capability, ExecutionInputs inputs);

    /**
     * Verifies the grant (category BANK_ACCOUNT, connector ref = {@code sourceCode})
     * and only then looks up the source's adapter and calls it. A grant that fails
     * verification is audited and thrown ({@code InvalidGrantException}); no adapter
     * is looked up and no call leaves the platform.
     */
    SourceOutcome<BankCheckAnswer> bankCheck(AccessGrant grant, String sourceCode, BankCheckRequest request);
}
