package com.samanvay.shared.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** V214: the hot lookups by citizen / consent have an index (each is a leading column of some index). */
@SpringBootTest(classes = SamanvayApplication.class)
class ForeignKeyIndexesIT extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void hotForeignKeysAreIndexed() {
        for (String[] tc : new String[][] {
            {"identity_link", "citizen_id"},
            {"consent_access_grant", "consent_id"},
            {"consent_request", "subject_citizen_id"},
            {"consent_event", "consent_id"},
            {"orchestration_instance", "citizen_id"},
        }) {
            List<String> leading = jdbc.queryForList(
                    "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]"
                            + " WHERE i.indrelid = ?::regclass",
                    String.class,
                    tc[0]);
            assertThat(leading).as(tc[0]).contains(tc[1]);
        }
    }
}
