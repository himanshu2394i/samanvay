package com.samanvay.connector.api;

import com.samanvay.connector.api.BankCheckAdapter.AccountStatus;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckAnswer;
import com.samanvay.connector.api.BankCheckAdapter.NameMatch;

/**
 * What to do with a bank-check answer, per the Principal Architect's and Product
 * Designer's rulings on #31: a machine guess about a person never makes the final
 * decision in either direction. So the <em>only</em> automatic pass is a usable
 * account whose name fully matched; every other outcome goes to an officer with a
 * reason, and nothing is auto-rejected.
 *
 * <p>Pure decision logic — no UI, no storage. The officer card, the passbook
 * upload and the citizen copy (which never says "no match") are the follow-up.
 */
public record BankCheckReview(Decision decision, Reason reason) {

    public enum Decision {
        /** Usable account, name fully matched: accepted without an officer. */
        ACCEPT,
        /** Send to an officer with {@link #reason}; never auto-rejected. */
        OFFICER_REVIEW
    }

    public enum Reason {
        NAME_MATCHED,
        /** Only part of the name matched. */
        NAME_PARTIAL,
        /** The name did not match. */
        NAME_NOT_MATCHED,
        /** The bank's name could not be compared automatically (e.g. different scripts). */
        NAME_NOT_COMPARED,
        /** The account is closed or invalid, whatever the name says. */
        ACCOUNT_NOT_USABLE
    }

    public static BankCheckReview of(BankCheckAnswer answer) {
        if (answer.accountStatus() != AccountStatus.VALID) {
            // Contract v1: a non-VALID account carries nameMatch == NOT_CHECKED. The account itself
            // is the problem, so an officer decides (never an automatic reject).
            return new BankCheckReview(Decision.OFFICER_REVIEW, Reason.ACCOUNT_NOT_USABLE);
        }
        return switch (answer.nameMatch()) {
            case MATCH -> new BankCheckReview(Decision.ACCEPT, Reason.NAME_MATCHED);
            case PARTIAL -> new BankCheckReview(Decision.OFFICER_REVIEW, Reason.NAME_PARTIAL);
            case NO_MATCH -> new BankCheckReview(Decision.OFFICER_REVIEW, Reason.NAME_NOT_MATCHED);
            case NOT_CHECKED -> new BankCheckReview(Decision.OFFICER_REVIEW, Reason.NAME_NOT_COMPARED);
        };
    }
}
