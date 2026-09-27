package com.samanvay.connector.internal.source.ifscbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.samanvay.connector.internal.source.ifscbank.IfscBankClient.AccountStatus;
import com.samanvay.connector.internal.source.ifscbank.IfscBankClient.BankCheck;
import com.samanvay.connector.internal.source.ifscbank.IfscBankClient.IfscLookup;
import com.samanvay.connector.internal.source.ifscbank.IfscBankClient.NameMatch;
import com.samanvay.connector.internal.source.ifscbank.IfscBankSourceException.Failure;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * THE executable form of bank-check contract v1 ({@code docs/contracts/bank-check-v1.yaml}),
 * owned by the caller ({@link IfscBankClient}), with one copy only. Every test
 * drives the real client over HTTP; nothing here knows what is on the other end.
 *
 * <p>A concrete subclass supplies a client for its target, the target's test data
 * and whether the simulator marker is expected:
 * <ul>
 *   <li>{@link IfscBankSimulatorContractIT}: the simulator container (runs in CI).
 *   <li>A future {@code IfscBankLiveContractIT}: the same class, pointed at the live
 *       adapter's base URL + credentials (the adapter maps PFMS or the department's
 *       own validation onto contract v1), with that environment's test accounts.
 *       The marker is expected false.
 * </ul>
 * Fault tests use fixture-driven triggers, so the client never sends anything
 * simulator-specific. A target that can't be told to fault leaves those fixtures
 * empty, and the tests are skipped rather than failed.
 */
public abstract class IfscBankSourceContract {

    /** Test data at the target. {@code applicantName} is what the caller sends. */
    public record Account(String ifsc, String accountNumber, String applicantName) {}

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

    protected abstract IfscBankClient client();

    /** A client with credentials the target must refuse. */
    protected abstract IfscBankClient clientWithWrongCredentials();

    protected abstract Fixtures fixtures();

    protected abstract boolean expectSimulatorMarker();

    // --- IFSC lookup (public) --------------------------------------------------

    @Test
    void known_ifsc_resolves_to_its_branch() {
        IfscLookup lookup = client().lookupIfsc(fixtures().knownIfsc());
        var branch = lookup.branch().orElseThrow();
        assertThat(branch.ifsc()).isEqualTo(fixtures().knownIfsc()).matches("^[A-Z]{4}0[A-Z0-9]{6}$");
        assertThat(branch.bank()).isEqualTo(fixtures().knownIfscBank());
        assertThat(branch.bankCode()).isEqualTo(fixtures().knownIfsc().substring(0, 4));
        assertThat(lookup.simulatorMarker()).isEqualTo(expectSimulatorMarker());
    }

    @Test
    void ifsc_lookup_is_case_insensitive() {
        assertThat(client().lookupIfsc(fixtures().knownIfsc().toLowerCase()).branch()).isPresent();
    }

    @Test
    void unknown_ifsc_is_absent_not_an_error() {
        IfscLookup lookup = client().lookupIfsc(fixtures().unknownIfsc());
        assertThat(lookup.branch()).isEmpty();
        assertThat(lookup.simulatorMarker()).isEqualTo(expectSimulatorMarker());
    }

    @Test
    void malformed_ifsc_is_absent_not_an_error() {
        assertThat(client().lookupIfsc(fixtures().malformedIfsc()).branch()).isEmpty();
    }

    // --- Bank check: verdicts --------------------------------------------------

    @ParameterizedTest
    @EnumSource(NameMatch.class)
    void valid_account_reports_each_name_match_verdict(NameMatch expected) {
        Account a = fixtures().validAccountsByNameMatch().get(expected);
        assertThat(a).as("fixture for %s", expected).isNotNull();
        BankCheck check = client().check(a.ifsc(), a.accountNumber(), a.applicantName());
        assertThat(check.accountStatus()).isEqualTo(AccountStatus.VALID);
        assertThat(check.nameMatch()).isEqualTo(expected);
        assertThat(check.simulatorMarker()).isEqualTo(expectSimulatorMarker());
    }

    @Test
    void closed_account_is_closed_and_not_name_checked() {
        assertVerdict(fixtures().closedAccount(), AccountStatus.CLOSED);
    }

    @Test
    void invalid_account_is_invalid_and_not_name_checked() {
        assertVerdict(fixtures().invalidAccount(), AccountStatus.INVALID);
    }

    @Test
    void account_not_held_at_the_branch_is_invalid() {
        assertVerdict(fixtures().unknownAccount(), AccountStatus.INVALID);
    }

    @Test
    void well_formed_but_unknown_ifsc_is_invalid_not_an_error() {
        Account a = fixtures().unknownAccount();
        assertVerdict(new Account(fixtures().unknownIfsc(), a.accountNumber(), a.applicantName()), AccountStatus.INVALID);
    }

    @Test
    void malformed_ifsc_is_rejected_naming_the_field() {
        Account a = fixtures().validAccountsByNameMatch().get(NameMatch.MATCH);
        assertThatThrownBy(() -> client().check(fixtures().malformedIfsc(), a.accountNumber(), a.applicantName()))
                .isInstanceOfSatisfying(IfscBankSourceException.class, e -> {
                    assertThat(e.failure()).isEqualTo(Failure.REQUEST_REJECTED);
                    assertThat(e.rejectedFields()).contains("ifsc");
                    assertThat(e.simulatorMarker()).isEqualTo(expectSimulatorMarker());
                });
    }

    @Test
    void wrong_credentials_are_refused() {
        Account a = fixtures().validAccountsByNameMatch().get(NameMatch.MATCH);
        assertFailure(() -> clientWithWrongCredentials().check(a.ifsc(), a.accountNumber(), a.applicantName()),
                Failure.AUTH_REJECTED);
    }

    // --- Failure modes (skipped where the target can't be told to fault) ------

    @Test
    void ifsc_lookup_timeout() {
        assumeThat(fixtures().timeoutIfsc()).isPresent();
        assertFailure(() -> client().lookupIfsc(fixtures().timeoutIfsc().orElseThrow()), Failure.TIMEOUT);
    }

    @Test
    void ifsc_lookup_server_error() {
        assumeThat(fixtures().serverErrorIfsc()).isPresent();
        assertFailure(() -> client().lookupIfsc(fixtures().serverErrorIfsc().orElseThrow()), Failure.REMOTE_FAULT);
    }

    @Test
    void ifsc_lookup_truncated_body() {
        assumeThat(fixtures().malformedResponseIfsc()).isPresent();
        assertFailure(() -> client().lookupIfsc(fixtures().malformedResponseIfsc().orElseThrow()), Failure.MALFORMED_RESPONSE);
    }

    @Test
    void bank_check_timeout() {
        assumeThat(fixtures().timeoutAccount()).isPresent();
        Account a = fixtures().timeoutAccount().orElseThrow();
        assertFailure(() -> client().check(a.ifsc(), a.accountNumber(), a.applicantName()), Failure.TIMEOUT);
    }

    @Test
    void bank_check_server_error() {
        assumeThat(fixtures().serverErrorAccount()).isPresent();
        Account a = fixtures().serverErrorAccount().orElseThrow();
        assertFailure(() -> client().check(a.ifsc(), a.accountNumber(), a.applicantName()), Failure.REMOTE_FAULT);
    }

    @Test
    void bank_check_truncated_body() {
        assumeThat(fixtures().malformedResponseAccount()).isPresent();
        Account a = fixtures().malformedResponseAccount().orElseThrow();
        assertFailure(() -> client().check(a.ifsc(), a.accountNumber(), a.applicantName()), Failure.MALFORMED_RESPONSE);
    }

    private void assertVerdict(Account a, AccountStatus expected) {
        BankCheck check = client().check(a.ifsc(), a.accountNumber(), a.applicantName());
        assertThat(check.accountStatus()).isEqualTo(expected);
        assertThat(check.nameMatch()).isEqualTo(NameMatch.NOT_CHECKED);
        assertThat(check.simulatorMarker()).isEqualTo(expectSimulatorMarker());
    }

    private void assertFailure(ThrowingCallable call, Failure expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(IfscBankSourceException.class, e -> {
            assertThat(e.failure()).isEqualTo(expected);
            if (expected != Failure.TIMEOUT) {
                assertThat(e.simulatorMarker()).isEqualTo(expectSimulatorMarker());
            }
        });
    }
}
