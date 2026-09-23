package com.samanvay.identity.internal.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = SamanvayApplication.class)
class NoAutoLinkConstraintIT extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void activeProbabilisticInsertFails() {
        UUID citizen = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_citizen (id, status) VALUES (?, 'ACTIVE')", citizen);
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO identity_link
                        (id, citizen_id, department_code, local_id_type, local_id_token, provenance, status)
                        VALUES (?, ?, 'REVENUE', 'X', ?, 'PROBABILISTIC', 'ACTIVE')
                        """,
                        UUID.randomUUID(),
                        citizen,
                        "tok-" + citizen))
                .hasMessageContaining("chk_no_auto_probabilistic_link");
    }
}
