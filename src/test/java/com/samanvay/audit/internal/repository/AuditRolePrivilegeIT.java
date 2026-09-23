package com.samanvay.audit.internal.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SamanvayApplication.class)
class AuditRolePrivilegeIT extends PostgresIntegrationTest {

    @Autowired
    AuditService auditService;

    @Test
    void samanvayAppCannotUpdateAuditEntry() throws Exception {
        auditService.record(new AuditEntry(
                ActorType.SYSTEM, "priv-it", "PING", "s", null, null, null, null, Outcome.ALLOWED, null, Map.of()));

        try (var conn = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), "samanvay_app", "samanvay_app_dev_password");
                var st = conn.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("UPDATE audit.audit_entry SET reason = 'x'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }
}
