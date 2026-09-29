package com.samanvay.connector.internal.source.ifscbank;

import com.samanvay.connector.api.BankCheckAdapter;
import com.samanvay.connector.api.BankCheckAdapter.NameMatch;
import java.util.Map;
import java.util.Optional;

/** Runs {@link IfscBankSourceContract} against the department simulator container. */
class IfscBankSimulatorContractIT extends IfscBankSourceContract {

    /** Applicant names are ordinary synthetic inputs; the simulator's verdicts are fixed per account. */
    static final Fixtures FIXTURES = new Fixtures(
            "SBIN0000300",
            "State Bank of India",
            "ABCD0123456",
            "SBIN000030",
            Map.of(
                    NameMatch.MATCH, new Account("SBIN0000300", "00001000000001", "Asha Patil"),
                    NameMatch.PARTIAL, new Account("MAHB0000001", "00001000000002", "R. Deshmukh"),
                    NameMatch.NO_MATCH, new Account("HDFC0000001", "00001000000003", "Someone Else"),
                    NameMatch.NOT_CHECKED, new Account("SBIN0001593", "00001000000006", "Meera Kulkarni")),
            new Account("BKID0000150", "00001000000004", "Vikas Jadhav"),
            new Account("UTIB0000004", "00001000000005", "Sunita Pawar"),
            new Account("MAHB0000001", "00001999999999", "Nobody"),
            Optional.of("SAMS0000408"),
            Optional.of("SAMS0000500"),
            Optional.of("SAMS0000422"),
            Optional.of(new Account("SBIN0000300", "00009000000408", "Asha Patil")),
            Optional.of(new Account("SBIN0000300", "00009000000500", "Asha Patil")),
            Optional.of(new Account("SBIN0000300", "00009000000422", "Asha Patil")));

    static final IfscBankClient CLIENT = DepartmentSimulator.client(DepartmentSimulator.KEY_SECRET);
    static final IfscBankClient WRONG = DepartmentSimulator.client("not-the-secret");

    @Override
    protected BankCheckAdapter adapter() {
        return CLIENT;
    }

    @Override
    protected BankCheckAdapter adapterWithWrongCredentials() {
        return WRONG;
    }

    @Override
    protected boolean expectSimulatorMarker() {
        return true;
    }

    @Override
    protected Fixtures fixtures() {
        return FIXTURES;
    }
}
