package com.samanvay.connector.internal.source.ifscbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.samanvay.connector.api.BankCheckAdapter;
import com.samanvay.connector.api.BankCheckAdapter.AccountStatus;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckAnswer;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckRequest;
import com.samanvay.connector.api.BankCheckAdapter.IfscAnswer;
import com.samanvay.connector.api.BankCheckAdapter.NameMatch;
import com.samanvay.connector.api.SourceOutcome;
import com.samanvay.connector.api.SourceOutcome.Answered;
import com.samanvay.connector.api.SourceOutcome.ReasonCode;
import com.samanvay.connector.api.SourceOutcome.RequestRejected;
import com.samanvay.connector.api.SourceOutcome.SourceFault;
import com.samanvay.connector.api.SourceOutcome.SourceTimeout;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * THE executable form of bank-check contract v1 ({@code docs/contracts/bank-check-v1.yaml}),
 * owned by the caller, with one copy only. It drives a {@link BankCheckAdapter}
 * (the connector.api interface) over HTTP and asserts TYPED outcomes
 * ({@link SourceOutcome}), never HTTP statuses. Nothing here knows what is on the
 * other end.
 *
 * <ul>
 *   <li>{@link IfscBankSimulatorContractIT}: the simulator container (runs in CI).
 *   <li>A future {@code IfscBankLiveContractIT}: the same class, pointed at the live
 *       adapter (PFMS or the department's own validation mapped onto contract v1)
 *       with that environment's test accounts. The marker is expected false.
 * </ul>
 * Fault tests use fixture-driven triggers, so the adapter never sends anything
 * simulator-specific. A target that can't be told to fault leaves those fixtures
 * empty, and the tests are skipped rather than failed.
 */
public abstract class IfscBankSourceContract {

    /** Test data at the target. {@code applicantName} is what the caller sends. */
    public record Account(String ifsc, String accountNumber, String applicantName) {
        BankCheckRequest request() {
            return new BankCheckRequest(ifsc, accountNumber, applicantName);
        }
    }

    public record Fixtures(
            String knownIfsc,
            String knownIfscBank,
            String unknownIfsc,
            String malformedIfsc,
            Map<NameMatch, Account> validAccountsByNameMatch,
            Account closedAccount,
            Account invalidAccount,
            Account unknownAccount,
            Optional<String> timeoutIfsc,
            Optional<String> serverErrorIfsc,
            Optional<String> malformedResponseIfsc,
            Optional<Account> timeoutAccount,
            Optional<Account> serverErrorAccount,
            Optional<Account> malformedResponseAccount) {}

    protected abstract BankCheckAdapter adapter();

    /** An adapter whose SecretStore credential the target must refuse. */
    protected abstract BankCheckAdapter adapterWithWrongCredentials();

    protected abstract Fixtures fixtures();

    protected abstract boolean expectSimulatorMarker();

    // --- IFSC lookup (public) --------------------------------------------------

    @Test
    void known_ifsc_resolves_to_its_branch() {
        Answered<IfscAnswer> answered = answered(adapter().lookupIfsc(fixtures().knownIfsc()));
        var branch = answered.answer().branch().orElseThrow();
        assertThat(branch.ifsc()).isEqualTo(fixtures().knownIfsc()).matches("^[A-Z]{4}0[A-Z0-9]{6}$");
        assertThat(branch.bank()).isEqualTo(fixtures().knownIfscBank());
        assertThat(branch.bankCode()).isEqualTo(fixtures().knownIfsc().substring(0, 4));
    }

    @Test
    void ifsc_lookup_is_case_insensitive() {
        assertThat(answered(adapter().lookupIfsc(fixtures().knownIfsc().toLowerCase())).answer().branch()).isPresent();
    }

    @Test
    void unknown_ifsc_is_answered_absent() {
        assertThat(answered(adapter().lookupIfsc(fixtures().unknownIfsc())).answer().branch()).isEmpty();
    }

    @Test
    void malformed_ifsc_is_answered_absent() {
        assertThat(answered(adapter().lookupIfsc(fixtures().malformedIfsc())).answer().branch()).isEmpty();
    }

    // --- Bank check: answers -----------------------------------------------------

    @ParameterizedTest
    @EnumSource(NameMatch.class)
    void valid_account_answers_each_name_match_verdict(NameMatch expected) {
        Account a = fixtures().validAccountsByNameMatch().get(expected);
        assertThat(a).as("fixture for %s", expected).isNotNull();
        assertThat(answered(adapter().check(a.request())).answer())
                .isEqualTo(new BankCheckAnswer(AccountStatus.VALID, expected));
    }

    @Test
    void closed_account_is_an_answer_not_a_failure() {
        assertAnswer(fixtures().closedAccount(), AccountStatus.CLOSED);
    }

    @Test
    void invalid_account_is_an_answer_not_a_failure() {
        assertAnswer(fixtures().invalidAccount(), AccountStatus.INVALID);
    }

    @Test
    void account_not_held_at_the_branch_is_answered_invalid() {
        assertAnswer(fixtures().unknownAccount(), AccountStatus.INVALID);
    }

    @Test
    void well_formed_but_unknown_ifsc_is_answered_invalid() {
        Account a = fixtures().unknownAccount();
        assertAnswer(new Account(fixtures().unknownIfsc(), a.accountNumber(), a.applicantName()), AccountStatus.INVALID);
    }

    @Test
    void malformed_ifsc_is_request_rejected_naming_the_field() {
        Account a = fixtures().validAccountsByNameMatch().get(NameMatch.MATCH);
        SourceOutcome<BankCheckAnswer> outcome =
                adapter().check(new BankCheckRequest(fixtures().malformedIfsc(), a.accountNumber(), a.applicantName()));
        assertThat(outcome).isInstanceOfSatisfying(RequestRejected.class, r -> {
            assertThat(r.rejectedFields()).contains("ifsc");
            assertThat(r.simulatorMarker()).isEqualTo(expectSimulatorMarker());
        });
    }

    @Test
    void wrong_credential_is_a_source_fault_auth_rejected() {
        Account a = fixtures().validAccountsByNameMatch().get(NameMatch.MATCH);
        assertFault(adapterWithWrongCredentials().check(a.request()), ReasonCode.AUTH_REJECTED);
    }

    // --- Failures (skipped where the target can't be told to fault) -------------

    @Test
    void ifsc_lookup_timeout_is_source_timeout() {
        assumeThat(fixtures().timeoutIfsc()).isPresent();
        assertThat(adapter().lookupIfsc(fixtures().timeoutIfsc().orElseThrow())).isInstanceOf(SourceTimeout.class);
    }

    @Test
    void ifsc_lookup_5xx_is_source_fault_server_error() {
        assumeThat(fixtures().serverErrorIfsc()).isPresent();
        assertFault(adapter().lookupIfsc(fixtures().serverErrorIfsc().orElseThrow()), ReasonCode.SERVER_ERROR);
    }

    @Test
    void ifsc_lookup_truncated_body_is_source_fault_truncated_body() {
        assumeThat(fixtures().malformedResponseIfsc()).isPresent();
        assertFault(adapter().lookupIfsc(fixtures().malformedResponseIfsc().orElseThrow()), ReasonCode.TRUNCATED_BODY);
    }

    @Test
    void bank_check_timeout_is_source_timeout() {
        assumeThat(fixtures().timeoutAccount()).isPresent();
        assertThat(adapter().check(fixtures().timeoutAccount().orElseThrow().request())).isInstanceOf(SourceTimeout.class);
    }

    @Test
    void bank_check_5xx_is_source_fault_server_error() {
        assumeThat(fixtures().serverErrorAccount()).isPresent();
        assertFault(adapter().check(fixtures().serverErrorAccount().orElseThrow().request()), ReasonCode.SERVER_ERROR);
    }

    @Test
    void bank_check_truncated_body_is_source_fault_truncated_body() {
        assumeThat(fixtures().malformedResponseAccount()).isPresent();
        assertFault(adapter().check(fixtures().malformedResponseAccount().orElseThrow().request()), ReasonCode.TRUNCATED_BODY);
    }

    private void assertAnswer(Account a, AccountStatus expected) {
        assertThat(answered(adapter().check(a.request())).answer())
                .isEqualTo(new BankCheckAnswer(expected, NameMatch.NOT_CHECKED));
    }

    @SuppressWarnings("unchecked")
    private <T> Answered<T> answered(SourceOutcome<T> outcome) {
        assertThat(outcome).isInstanceOf(Answered.class);
        assertThat(outcome.simulatorMarker()).isEqualTo(expectSimulatorMarker());
        return (Answered<T>) outcome;
    }

    private void assertFault(SourceOutcome<?> outcome, ReasonCode expected) {
        assertThat(outcome).isInstanceOfSatisfying(SourceFault.class, f -> {
            assertThat(f.reasonCode()).isEqualTo(expected);
            assertThat(f.simulatorMarker()).isEqualTo(expectSimulatorMarker());
        });
    }
}
