package com.samanvay.connector.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.connector.api.BankCheckAdapter.AccountStatus;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckAnswer;
import com.samanvay.connector.api.BankCheckAdapter.NameMatch;
import com.samanvay.connector.api.BankCheckReview.Decision;
import com.samanvay.connector.api.BankCheckReview.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BankCheckReviewTest {

    @Test
    void onlyAUsableAccountWithAFullNameMatchIsAccepted() {
        assertThat(BankCheckReview.of(new BankCheckAnswer(AccountStatus.VALID, NameMatch.MATCH)))
                .isEqualTo(new BankCheckReview(Decision.ACCEPT, Reason.NAME_MATCHED));
    }

    @Test
    void validAccountWithAnImperfectNameGoesToAnOfficerWithAReason() {
        assertThat(BankCheckReview.of(new BankCheckAnswer(AccountStatus.VALID, NameMatch.PARTIAL)))
                .isEqualTo(new BankCheckReview(Decision.OFFICER_REVIEW, Reason.NAME_PARTIAL));
        assertThat(BankCheckReview.of(new BankCheckAnswer(AccountStatus.VALID, NameMatch.NO_MATCH)))
                .isEqualTo(new BankCheckReview(Decision.OFFICER_REVIEW, Reason.NAME_NOT_MATCHED));
        assertThat(BankCheckReview.of(new BankCheckAnswer(AccountStatus.VALID, NameMatch.NOT_CHECKED)))
                .isEqualTo(new BankCheckReview(Decision.OFFICER_REVIEW, Reason.NAME_NOT_COMPARED));
    }

    /** A closed/invalid account always goes to an officer (never auto-accepted, never auto-rejected). */
    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"CLOSED", "INVALID"})
    void unusableAccountsAlwaysGoToAnOfficer(AccountStatus status) {
        BankCheckReview review = BankCheckReview.of(new BankCheckAnswer(status, NameMatch.NOT_CHECKED));
        assertThat(review.decision()).isEqualTo(Decision.OFFICER_REVIEW);
        assertThat(review.reason()).isEqualTo(Reason.ACCOUNT_NOT_USABLE);
    }

    /** No outcome is ever rejected outright: a machine guess never makes the final call. */
    @ParameterizedTest
    @EnumSource(NameMatch.class)
    void nothingIsAutoRejected(NameMatch nameMatch) {
        AccountStatus status = nameMatch == NameMatch.MATCH ? AccountStatus.VALID : AccountStatus.VALID;
        assertThat(BankCheckReview.of(new BankCheckAnswer(status, nameMatch)).decision())
                .isIn(Decision.ACCEPT, Decision.OFFICER_REVIEW);
    }
}
