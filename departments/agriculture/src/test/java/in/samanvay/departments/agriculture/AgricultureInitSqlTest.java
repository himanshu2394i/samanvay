package in.samanvay.departments.agriculture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The older all-in-one {@code db/init.sql} (the local docker compose and the end-to-end test) must give a FRESH database everything the
 * service's own queries need: the farmer's date of birth and the citizen_login table, with the two demo farmers able to sign in.
 */
class AgricultureInitSqlTest {

    static PostgreSQLContainer pg;
    static JdbcClient app;

    @BeforeAll
    static void start() throws Exception {
        pg = new PostgreSQLContainer("postgres:16").withDatabaseName("agridb").withUsername("agri_admin").withPassword("admin-pw");
        pg.start();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.execute(Files.readString(Path.of("db", "init.sql"), StandardCharsets.UTF_8));
        }
        app = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "agriculture_app", "agriculture_app_demo"));
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @Test
    void the_demo_farmers_sign_in_with_the_service_account_on_a_fresh_database() {
        JdbcCitizens citizens = new JdbcCitizens(app);
        assertThat(citizens.authenticate("9000000001", "asha-demo-pass")).contains("AG-1001");
        assertThat(citizens.authenticate("9000000002", "ravi-demo-pass")).contains("AG-1002");
        assertThat(citizens.authenticate("9000000001", "wrong")).isEmpty();
    }

    @Test
    void each_farmer_has_a_name_and_date_of_birth_for_the_login_assertion() {
        JdbcCitizens citizens = new JdbcCitizens(app);
        assertThat(citizens.person("AG-1001")).get().satisfies(p -> {
            assertThat(p.name()).isEqualTo("Asha Patil");
            assertThat(p.dob()).isEqualTo(LocalDate.of(2004, 3, 9));
        });
        assertThat(citizens.person("AG-1002")).get().satisfies(p -> assertThat(p.dob()).isEqualTo(LocalDate.of(2003, 11, 21)));
    }

    @Test
    void the_view_samanvay_reads_still_hides_the_date_of_birth_and_the_logins() throws Exception {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "agri_ro", "agri_ro_demo"); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT * FROM v_farmer_record");
            var columns = new java.util.ArrayList<String>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                columns.add(rs.getMetaData().getColumnName(i));
            }
            assertThat(columns).doesNotContain("date_of_birth", "internal_notes");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.executeQuery("SELECT * FROM citizen_login")).hasMessageContaining("permission denied");
        }
    }
}
