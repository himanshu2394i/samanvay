package in.samanvay.departments.dbt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * DBT's bank records and logins in a REAL Postgres, built from the committed {@code db/schema.sql} exactly as a deployment does.
 * The tiny seed here is the test's own; the demo citizens come from the generated seed file (scripts/gen-demo-data.py).
 */
class DbtJdbcStoresTest {

    static PostgreSQLContainer pg;
    static JdbcClient jdbc;

    @BeforeAll
    static void start() throws Exception {
        pg = new PostgreSQLContainer("postgres:16").withDatabaseName("dbt").withUsername("dbt_app").withPassword("test-pw");
        pg.start();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.execute(Files.readString(Path.of("db", "schema.sql"), StandardCharsets.UTF_8));
            s.execute("""
                    INSERT INTO beneficiary VALUES ('DBT-9001', 'Meera Kulkarni', '9100000001', '1999-05-04');
                    INSERT INTO beneficiary VALUES ('DBT-9002', 'No Account Yet', '9100000002', '1990-01-01');
                    INSERT INTO bank_account VALUES ('DBT-9001', 'XXXXXX4321', 'SBIN0XXX123', 'Meera Kulkarni', 'State Bank of India', '2025-06-01');
                    INSERT INTO citizen_login VALUES ('9100000001', crypt('right-password', gen_salt('bf')), 'DBT-9001');
                    """);
        }
        jdbc = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @Test
    void the_bank_record_for_a_dbt_id_is_what_dbt_shares_and_nothing_more() {
        BankRecords.Bank bank = new JdbcBankRecords(jdbc).find("DBT-9001").orElseThrow();
        assertThat(bank.accountRef()).isEqualTo("XXXXXX4321");
        assertThat(bank.ifscMasked()).isEqualTo("SBIN0XXX123");
        assertThat(bank.holderName()).isEqualTo("Meera Kulkarni");
    }

    @Test
    void a_beneficiary_with_no_account_or_an_unknown_id_finds_nothing() {
        JdbcBankRecords records = new JdbcBankRecords(jdbc);
        assertThat(records.find("DBT-9002")).isEmpty();
        assertThat(records.find("DBT-0000")).isEmpty();
        assertThat(records.find("' OR '1'='1")).isEmpty();
    }

    @Test
    void login_accepts_the_right_password_for_a_registered_mobile_and_returns_that_beneficiarys_id() {
        assertThat(new JdbcCitizens(jdbc).authenticate("9100000001", "right-password")).contains("DBT-9001");
    }

    @Test
    void login_refuses_a_wrong_password_an_unknown_mobile_and_an_empty_password() {
        JdbcCitizens citizens = new JdbcCitizens(jdbc);
        assertThat(citizens.authenticate("9100000001", "wrong")).isEmpty();
        assertThat(citizens.authenticate("9100000001", "")).isEmpty();
        assertThat(citizens.authenticate("9199999999", "right-password")).isEmpty();
    }

    @Test
    void passwords_are_stored_hashed_never_in_plaintext() {
        String stored = jdbc.sql("SELECT password_hash FROM citizen_login WHERE mobile = '9100000001'").query(String.class).single();
        assertThat(stored).isNotEqualTo("right-password").startsWith("$2");
    }

    @Test
    void the_password_is_a_bound_value_so_sql_in_it_does_nothing() {
        JdbcCitizens citizens = new JdbcCitizens(jdbc);
        assertThat(citizens.authenticate("9100000001", "' OR '1'='1")).isEmpty();
        assertThat(citizens.authenticate("9100000001' OR '1'='1", "right-password")).isEmpty();
    }
}
