package in.samanvay.departments.agriculture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Agriculture's logins in a REAL Postgres, built from the committed {@code db/schema.sql} exactly as a deployment does, with the
 * two database roles a deployment creates: the service's own account and Samanvay's read-only one.
 */
class AgricultureJdbcStoresTest {

    static PostgreSQLContainer pg;
    static JdbcClient jdbc;

    @BeforeAll
    static void start() throws Exception {
        pg = new PostgreSQLContainer("postgres:16").withDatabaseName("agridb").withUsername("agri_admin").withPassword("admin-pw");
        pg.start();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.execute(Files.readString(Path.of("db", "schema.sql"), StandardCharsets.UTF_8));
            s.execute("""
                    INSERT INTO farmer VALUES ('AG-9001', 'Meera Kulkarni', 'Loni', 'Haveli', 1.50, 'internal: audit pending', '1990-05-17');
                    INSERT INTO citizen_login VALUES ('9100000001', crypt('right-password', gen_salt('bf')), 'AG-9001');
                    CREATE ROLE agriculture_app LOGIN PASSWORD 'app-pw';
                    GRANT SELECT ON citizen_login TO agriculture_app;
                    GRANT SELECT (agri_person_id, farmer_name, date_of_birth) ON farmer TO agriculture_app;
                    CREATE ROLE agri_ro LOGIN PASSWORD 'ro-pw';
                    GRANT SELECT ON v_farmer_record TO agri_ro;
                    """);
        }
        jdbc = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "agriculture_app", "app-pw"));
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @Test
    void login_accepts_the_right_password_for_a_registered_mobile_and_returns_that_farmers_id() {
        assertThat(new JdbcCitizens(jdbc).authenticate("9100000001", "right-password")).contains("AG-9001");
    }

    @Test
    void the_person_behind_an_id_has_a_name_and_date_of_birth_for_the_login_assertion() {
        JdbcCitizens citizens = new JdbcCitizens(jdbc);
        assertThat(citizens.person("AG-9001")).contains(new in.samanvay.departments.kit.Person("AG-9001", "Meera Kulkarni", java.time.LocalDate.of(1990, 5, 17)));
        assertThat(citizens.person("AG-0000")).isEmpty();
    }

    @Test
    void login_refuses_a_wrong_password_an_unknown_mobile_and_an_empty_password() {
        JdbcCitizens citizens = new JdbcCitizens(jdbc);
        assertThat(citizens.authenticate("9100000001", "wrong")).isEmpty();
        assertThat(citizens.authenticate("9100000001", "")).isEmpty();
        assertThat(citizens.authenticate("9199999999", "right-password")).isEmpty();
    }

    @Test
    void the_password_is_a_bound_value_so_sql_in_it_does_nothing() {
        JdbcCitizens citizens = new JdbcCitizens(jdbc);
        assertThat(citizens.authenticate("9100000001", "' OR '1'='1")).isEmpty();
        assertThat(citizens.authenticate("9100000001' OR '1'='1", "right-password")).isEmpty();
    }

    @Test
    void the_services_own_account_can_log_farmers_in_but_cannot_read_the_farmer_table() {
        assertThatThrownBy(() -> jdbc.sql("SELECT internal_notes FROM farmer").query(String.class).list()).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void samanvays_read_only_account_sees_the_view_but_not_the_base_table_or_the_logins() throws Exception {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "agri_ro", "ro-pw"); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT farmer_name FROM v_farmer_record WHERE agri_person_id = 'AG-9001'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Meera Kulkarni");
            assertThatThrownBy(() -> s.executeQuery("SELECT internal_notes FROM farmer")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.executeQuery("SELECT password_hash FROM citizen_login")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM v_farmer_record")).hasMessageContaining("permission denied");
        }
    }
}
