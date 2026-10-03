package in.samanvay.departments.dbt;

import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * The built-in fake seed: two beneficiaries. Used when no database is configured (tests, a bare local run).
 *
 * <p>ponytail: in-memory, restart resets it. A deployment points {@code dbt.db.url} at Postgres instead.
 */
@Component
@ConditionalOnExpression("'${dbt.db.url:}' == ''")
class SeedBankRecords implements BankRecords {

    private static final Map<String, Bank> BANK = Map.of(
            "DBT-1001", new Bank("XXXXXX1234", "SBIN0XXX300", "Asha Patil"),
            "DBT-1002", new Bank("XXXXXX5678", "HDFC0XXX210", "Ravi Deshmukh"));

    @Override
    public Optional<Bank> find(String dbtId) {
        return Optional.ofNullable(BANK.get(dbtId));
    }
}
