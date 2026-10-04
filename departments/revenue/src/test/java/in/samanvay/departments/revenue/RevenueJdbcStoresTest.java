package in.samanvay.departments.revenue;

import static org.assertj.core.api.Assertions.assertThat;

import in.samanvay.departments.revenue.RevenueRecords.Doc;
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
 * Revenue's records and logins in a REAL Postgres, built from the committed {@code db/schema.sql} exactly as a deployment does.
 * The tiny seed here is the test's own; the demo citizens come from the generated seed file (scripts/gen-demo-data.py).
 */
class RevenueJdbcStoresTest {

    static PostgreSQLContainer pg;
    static JdbcClient jdbc;

    @BeforeAll
    static void start() throws Exception {
        pg = new PostgreSQLContainer("postgres:16").withDatabaseName("revenue").withUsername("revenue_app").withPassword("test-pw");
        pg.start();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.execute(Files.readString(Path.of("db", "schema.sql"), StandardCharsets.UTF_8));
            s.execute("""
                    INSERT INTO person VALUES ('RV-9001', 'Meera Kulkarni', '1999-05-04', 'Pune', 'Haveli', 'Loni');
                    INSERT INTO person VALUES ('RV-9002', 'Other Person', '1990-01-01', 'Nashik', 'Nashik', 'Ojhar');
                    INSERT INTO citizen_login VALUES ('9100000001', crypt('right-password', gen_salt('bf')), 'RV-9001');
                    INSERT INTO certificate VALUES ('INC-OLD', 'INCOME_CERTIFICATE', 'RV-9001', '2025-04-01', 'Tahsildar, Haveli',
                        '{"annualIncome":"150000","annualIncomeDisplay":"Rs 150000","holderName":"Meera Kulkarni","district":"Pune","issuerOffice":"Tahsildar, Haveli","financialYear":"2024-25"}');
                    INSERT INTO certificate VALUES ('INC-NEW', 'INCOME_CERTIFICATE', 'RV-9001', '2026-04-02', 'Tahsildar, Haveli',
                        '{"annualIncome":"175000","annualIncomeDisplay":"Rs 175000","holderName":"Meera Kulkarni","district":"Pune","issuerOffice":"Tahsildar, Haveli","financialYear":"2025-26"}');
                    INSERT INTO certificate VALUES ('CST-1', 'CASTE_CERTIFICATE', 'RV-9001', '2024-08-20', 'Tahsildar, Haveli',
                        '{"holderName":"Meera Kulkarni","caste":"Kunbi","casteCategory":"OBC","issuerOffice":"Tahsildar, Haveli"}');
                    """);
        }
        jdbc = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @Test
    void a_person_is_known_only_if_the_database_holds_them() {
        JdbcRevenueRecords records = new JdbcRevenueRecords(jdbc);
        assertThat(records.personExists("RV-9001")).isTrue();
        assertThat(records.personExists("RV-0000")).isFalse();
    }

    @Test
    void a_persons_certificates_of_one_type_come_back_newest_first_and_the_latest_is_flagged() {
        JdbcRevenueRecords records = new JdbcRevenueRecords(jdbc);
        assertThat(records.forPerson("RV-9001", "INCOME_CERTIFICATE")).extracting(Doc::key).containsExactly("INC-NEW", "INC-OLD");
        assertThat(records.latest("RV-9001", "INCOME_CERTIFICATE")).get().extracting(Doc::key).isEqualTo("INC-NEW");
        assertThat(records.forPerson("RV-9001", "DOMICILE_CERTIFICATE")).isEmpty();
        assertThat(records.forPerson("RV-9002", "INCOME_CERTIFICATE")).isEmpty();
    }

    @Test
    void a_certificate_is_fetched_by_its_own_key_with_the_fields_the_department_holds() {
        JdbcRevenueRecords records = new JdbcRevenueRecords(jdbc);
        Doc doc = records.byKey("INCOME_CERTIFICATE", "INC-NEW").orElseThrow();
        assertThat(doc.personId()).isEqualTo("RV-9001");
        assertThat(doc.issuedOn()).hasToString("2026-04-02");
        assertThat(doc.fields()).containsEntry("annualIncome", "175000").containsEntry("financialYear", "2025-26");
        assertThat(doc.fields().keySet()).containsExactly("annualIncome", "annualIncomeDisplay", "holderName", "district", "issuerOffice", "financialYear");
    }

    @Test
    void a_key_of_the_wrong_type_or_an_unknown_key_finds_nothing() {
        JdbcRevenueRecords records = new JdbcRevenueRecords(jdbc);
        assertThat(records.byKey("CASTE_CERTIFICATE", "INC-NEW")).isEmpty();
        assertThat(records.byKey("INCOME_CERTIFICATE", "NOPE")).isEmpty();
    }

    @Test
    void a_person_is_looked_up_with_name_and_date_of_birth_for_the_login_assertion() {
        JdbcCitizens citizens = new JdbcCitizens(jdbc);
        assertThat(citizens.person("RV-9001")).get().satisfies(p -> {
            assertThat(p.name()).isEqualTo("Meera Kulkarni");
            assertThat(p.dob()).hasToString("1999-05-04");
        });
        assertThat(citizens.person("RV-0000")).isEmpty();
    }

    @Test
    void login_accepts_the_right_password_for_a_registered_mobile_and_returns_that_persons_id() {
        JdbcCitizens citizens = new JdbcCitizens(jdbc);
        assertThat(citizens.authenticate("9100000001", "right-password")).contains("RV-9001");
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
