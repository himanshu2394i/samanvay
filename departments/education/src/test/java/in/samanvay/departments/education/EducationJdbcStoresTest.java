package in.samanvay.departments.education;

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
 * The Board's marks and logins in a REAL Postgres, built from the committed {@code db/schema.sql} exactly as a deployment does.
 * The tiny seed here is the test's own; the demo students come from the generated seed file (scripts/gen-demo-data.py).
 */
class EducationJdbcStoresTest {

    static PostgreSQLContainer pg;
    static JdbcClient jdbc;

    @BeforeAll
    static void start() throws Exception {
        pg = new PostgreSQLContainer("postgres:16").withDatabaseName("education").withUsername("education_app").withPassword("test-pw");
        pg.start();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.execute(Files.readString(Path.of("db", "schema.sql"), StandardCharsets.UTF_8));
            s.execute("""
                    INSERT INTO student VALUES ('EDU-9001', 'Meera Kulkarni', '9100000001', '2007-05-04', 'B123456', 'Z.P. Junior College, Haveli');
                    INSERT INTO student VALUES ('EDU-9002', 'No Marks Yet', '9100000002', '2008-01-01', 'B123457', 'Z.P. Junior College, Haveli');
                    INSERT INTO marks_statement VALUES ('EDU-9001', 'SSC 2023', 'msbshse', 88.20, 2023);
                    INSERT INTO marks_statement VALUES ('EDU-9001', 'HSC 2025', 'msbshse', 91.00, 2025);
                    INSERT INTO citizen_login VALUES ('9100000001', crypt('right-password', gen_salt('bf')), 'EDU-9001');
                    """);
        }
        jdbc = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @Test
    void a_students_latest_marks_statement_is_returned_with_the_percentage_printed_plainly() {
        MarksRecords.Marks marks = new JdbcMarksRecords(jdbc).find("EDU-9001").orElseThrow();
        assertThat(marks.studentId()).isEqualTo("EDU-9001");
        assertThat(marks.exam()).isEqualTo("HSC 2025");
        assertThat(marks.percentage()).isEqualTo("91");
        assertThat(marks.board()).isEqualTo("msbshse");
    }

    @Test
    void a_student_with_no_marks_or_an_unknown_id_finds_nothing() {
        JdbcMarksRecords records = new JdbcMarksRecords(jdbc);
        assertThat(records.find("EDU-9002")).isEmpty();
        assertThat(records.find("EDU-0000")).isEmpty();
        assertThat(records.find("' OR '1'='1")).isEmpty();
    }

    @Test
    void login_accepts_the_right_password_for_a_registered_mobile_and_returns_that_students_id() {
        assertThat(new JdbcCitizens(jdbc).authenticate("9100000001", "right-password")).contains("EDU-9001");
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

    @Test
    void an_unregistered_mobile_costs_one_bcrypt_check_like_a_wrong_password_so_timing_does_not_reveal_registered_mobiles() {
        JdbcClient spy = org.mockito.Mockito.spy(jdbc);
        JdbcCitizens citizens = new JdbcCitizens(spy);
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);

        assertThat(citizens.authenticate("9100000001", "wrong")).isEmpty();
        org.mockito.Mockito.verify(spy, org.mockito.Mockito.times(1)).sql(sql.capture());
        assertThat(sql.getAllValues().getFirst()).contains("crypt(");

        org.mockito.Mockito.clearInvocations(spy);
        assertThat(citizens.authenticate("9199999999", "wrong")).isEmpty();
        org.mockito.Mockito.verify(spy, org.mockito.Mockito.times(2)).sql(sql.capture());
        assertThat(sql.getAllValues().getLast()).contains("crypt(");
    }
}
