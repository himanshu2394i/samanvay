package in.samanvay.departments.dbt;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** DBT's bank records from its own Postgres (see {@code db/schema.sql}). Only the three fields DBT shares are selected. */
@Component
@ConditionalOnExpression("'${dbt.db.url:}' != ''")
class JdbcBankRecords implements BankRecords {

    private final JdbcClient jdbc;

    JdbcBankRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Bank> find(String dbtId) {
        return jdbc.sql("SELECT account_ref, ifsc_masked, holder_name FROM bank_account WHERE person_id = :id")
                .param("id", dbtId).query((rs, i) -> new Bank(rs.getString("account_ref"), rs.getString("ifsc_masked"), rs.getString("holder_name")))
                .optional();
    }
}
