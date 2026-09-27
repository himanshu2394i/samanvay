package com.samanvay.connector.internal.source.ifscbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.samanvay.connector.internal.source.ifscbank.IfscBankClient.AccountValidation;
import com.samanvay.connector.internal.source.ifscbank.IfscBankClient.IfscLookup;
import com.samanvay.connector.internal.source.ifscbank.IfscBankSourceException.Failure;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * THE contract for the IFSC / bank-account source, owned by the caller
 * ({@link IfscBankClient}), with one copy only. Every test drives the real
 * client over HTTP; nothing here knows what is on the other end.
 *
 * <p>A concrete subclass supplies three things: a client configured for its
 * target ({@link #client()}), the target's test data ({@link #fixtures()}), and
 * whether the target should carry the simulator marker
 * ({@link #expectSimulatorMarker()}).
 * <ul>
 *   <li>{@link IfscBankSimulatorContractIT}: the simulator container (runs in CI).
 *   <li>A future {@code IfscBankLiveContractIT}: same class, base URL + credentials
 *       + test accounts from the live/sandbox environment, marker expected false.
 * </ul>
 * Fault tests use fixture-driven triggers (special IFSC/account values), so the
 * client never sends anything simulator-specific. A target that can't be told to
 * fault leaves those fixtures empty and the tests are skipped, not failed.
 */
public abstract class IfscBankSourceContract {

    /** Known-good test data at the target. */
    public record Account(String ifsc, String accountNumber, String holderName) {}

    public record Fixtures(
            String knownIfsc,
            String knownIfscBank,
            String unknownIfsc,
            String malformedIfsc,
            Account activeAccount,
            Account closedAccount,
            Account unknownAccount,
            Optional<String> timeoutIfsc,
            Optional<String> serverErrorIfsc,
            Optional<String> malformedResponseIfsc,
            Optional<Account> timeoutAccount,
            Optional<Account> serverErrorAccount) {}

    protected abstract IfscBankClient client();

    /** A client with credentials the target must refuse. */
    protected abstract IfscBankClient clientWithWrongCredentials();

    protected abstract Fixtures fixtures();

    protected abstract boolean expectSimulatorMarker();

    // --- IFSC lookup ---------------------------------------------------------

    @Test
    void known_ifsc_resolves_to_its_branch() {
        IfscLookup lookup = client().lookupIfsc(fixtures().knownIfsc());
        assertThat(lookup.branch()).isPresent();
        var branch = lookup.branch().orElseThrow();
        assertThat(branch.ifsc()).isEqualTo(fixtures().knownIfsc());
        assertThat(branch.bank()).isEqualTo(fixtures().knownIfscBank());
        assertThat(branch.bankCode()).isEqualTo(fixtures().knownIfsc().substring(0, 4));
        assertThat(branch.ifsc()).matches("^[A-Z]{4}0[A-Z0-9]{6}$");
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

    // --- Account validation (penny drop) --------------------------------------

    @Test
    void valid_account_is_active_with_matching_registered_name() {
        Account a = fixtures().activeAccount();
        AccountValidation v = client().validateAccount(a.ifsc(), a.accountNumber(), a.holderName());
        assertThat(v.rejected()).isFalse();
        assertThat(v.status()).isEqualTo("completed");
        assertThat(v.accountActive()).isTrue();
        assertThat(v.registeredNameMatches(a.holderName())).isTrue();
        assertThat(v.validationId()).isNotBlank();
        assertThat(v.simulatorMarker()).isEqualTo(expectSimulatorMarker());
    }

    @Test
    void account_mismatch_is_detectable_from_the_registered_name() {
        Account a = fixtures().activeAccount();
        AccountValidation v = client().validateAccount(a.ifsc(), a.accountNumber(), "SOMEBODY ELSE ENTIRELY");
        assertThat(v.accountActive()).isTrue();
        assertThat(v.registeredNameMatches("SOMEBODY ELSE ENTIRELY")).isFalse();
        assertThat(v.registeredNameMatches(a.holderName())).isTrue();
    }

    @Test
    void closed_account_is_invalid_without_a_registered_name() {
        Account a = fixtures().closedAccount();
        AccountValidation v = client().validateAccount(a.ifsc(), a.accountNumber(), a.holderName());
        assertThat(v.status()).isEqualTo("completed");
        assertThat(v.accountStatus()).isEqualTo("invalid");
        assertThat(v.registeredName()).isNull();
    }

    @Test
    void unknown_account_is_invalid() {
        Account a = fixtures().unknownAccount();
        AccountValidation v = client().validateAccount(a.ifsc(), a.accountNumber(), a.holderName());
        assertThat(v.accountStatus()).isEqualTo("invalid");
        assertThat(v.registeredName()).isNull();
    }

    @Test
    void invalid_ifsc_is_rejected_on_the_ifsc_field() {
        Account a = fixtures().activeAccount();
        AccountValidation v = client().validateAccount(fixtures().malformedIfsc(), a.accountNumber(), a.holderName());
        assertThat(v.rejected()).isTrue();
        assertThat(v.rejectedField()).isEqualTo("ifsc");
        assertThat(v.simulatorMarker()).isEqualTo(expectSimulatorMarker());
    }

    @Test
    void wrong_credentials_are_refused() {
        Account a = fixtures().activeAccount();
        assertThatThrownBy(() -> clientWithWrongCredentials().validateAccount(a.ifsc(), a.accountNumber(), a.holderName()))
                .isInstanceOfSatisfying(IfscBankSourceException.class, e -> assertThat(e.failure()).isEqualTo(Failure.AUTH_REJECTED));
    }

    // --- Failure modes (skipped where the target can't be told to fault) ------

    @Test
    void timeout_surfaces_as_timeout() {
        assumeThat(fixtures().timeoutIfsc()).isPresent();
        assertFailure(() -> client().lookupIfsc(fixtures().timeoutIfsc().orElseThrow()), Failure.TIMEOUT);
    }

    @Test
    void server_error_surfaces_as_remote_fault() {
        assumeThat(fixtures().serverErrorIfsc()).isPresent();
        assertFailure(() -> client().lookupIfsc(fixtures().serverErrorIfsc().orElseThrow()), Failure.REMOTE_FAULT);
    }

    @Test
    void malformed_body_surfaces_as_malformed_response() {
        assumeThat(fixtures().malformedResponseIfsc()).isPresent();
        assertFailure(() -> client().lookupIfsc(fixtures().malformedResponseIfsc().orElseThrow()), Failure.MALFORMED_RESPONSE);
    }

    @Test
    void account_validation_timeout_and_server_error() {
        assumeThat(fixtures().timeoutAccount()).isPresent();
        assumeThat(fixtures().serverErrorAccount()).isPresent();
        Account slow = fixtures().timeoutAccount().orElseThrow();
        assertFailure(() -> client().validateAccount(slow.ifsc(), slow.accountNumber(), slow.holderName()), Failure.TIMEOUT);
        Account broken = fixtures().serverErrorAccount().orElseThrow();
        assertFailure(
                () -> client().validateAccount(broken.ifsc(), broken.accountNumber(), broken.holderName()), Failure.REMOTE_FAULT);
    }

    private void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Failure expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(IfscBankSourceException.class, e -> {
            assertThat(e.failure()).isEqualTo(expected);
            if (expected != Failure.TIMEOUT) {
                assertThat(e.simulatorMarker()).isEqualTo(expectSimulatorMarker());
            }
        });
    }
}
