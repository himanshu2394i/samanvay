package in.samanvay.departments.dbt;

import java.util.Optional;

/**
 * The bank account DBT holds for a person (fake). One DBT ID unlocks it, so there is no resolve step.
 *
 * <p>Two stores: {@link JdbcBankRecords} reads DBT's own Postgres when {@code dbt.db.url} is set (a deployment),
 * {@link SeedBankRecords} is the built-in fake seed used by tests and a bare local run.
 */
interface BankRecords {

    record Bank(String accountRef, String ifscMasked, String holderName) {}

    Optional<Bank> find(String dbtId);
}
