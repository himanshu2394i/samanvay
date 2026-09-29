package com.samanvay.connector.api;

import java.util.Optional;

/**
 * A bank-check source, as seen by the rest of the platform. Speaks Samanvay's
 * bank-check contract v1 ({@code docs/contracts/bank-check-v1.yaml}). Look one up
 * by source code through {@link BankCheckAdapters}, and only after the grant
 * check (see {@link ConnectorRuntime#bankCheck}). The simulator and a future live
 * adapter (PFMS or the department's own validation) are both implementations.
 */
public interface BankCheckAdapter {

    /** The source code this adapter serves, e.g. {@code ifsc-bank}. */
    String sourceCode();

    /** Public IFSC lookup (open RBI data). */
    SourceOutcome<IfscAnswer> lookupIfsc(String ifsc);

    /** One-call account + name check. The answer never contains the holder's name. */
    SourceOutcome<BankCheckAnswer> check(BankCheckRequest request);

    enum AccountStatus { VALID, CLOSED, INVALID }

    enum NameMatch { MATCH, PARTIAL, NO_MATCH, NOT_CHECKED }

    /** Invariant (contract v1): accountStatus != VALID implies nameMatch == NOT_CHECKED. */
    record BankCheckAnswer(AccountStatus accountStatus, NameMatch nameMatch) {}

    /** {@code branch} is empty when the source says the IFSC does not exist. */
    record IfscAnswer(Optional<IfscBranch> branch) {}

    record IfscBranch(
            String ifsc, String bank, String bankCode, String branch, String city, String district, String state,
            String micr, boolean neft, boolean rtgs, boolean imps, boolean upi) {}

    /** The request carries personal data, so {@link #toString()} redacts it. */
    record BankCheckRequest(String ifsc, String accountNumber, String applicantName) {
        @Override
        public String toString() {
            return "BankCheckRequest[ifsc=" + ifsc + ", accountNumber=<redacted>, applicantName=<redacted>]";
        }
    }
}
