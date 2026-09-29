package com.samanvay.audit.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** V195: a checkpoint written the old way reads back with no key id (legacy); a new one stores its id. */
@SpringBootTest(classes = SamanvayApplication.class)
class CheckpointKeyIdIT extends PostgresIntegrationTest {

    @Autowired
    AuditService auditService;

    @Autowired
    CheckpointRepository checkpoints;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void legacyRowHasNullKeyId_andNewRowStoresItsKeyId() {
        auditService.record(new AuditEntry(
                ActorType.SYSTEM, "keyid-it", "PING", "s", null, null, null, null, Outcome.ALLOWED, null, Map.of()));
        long seq = auditService.headSeq();
        byte[] hash = new byte[] {1, 2, 3};

        // exactly the statement the pre-V195 code ran: no key_id column
        jdbc.update(
                "INSERT INTO audit.audit_checkpoint (upto_entry_seq, root_hash, signature) VALUES (?, ?, ?)",
                seq, hash, hash);
        assertThat(checkpoints.findLatest().orElseThrow().keyId()).isNull();

        checkpoints.insert(seq, hash, hash, "v2");
        assertThat(checkpoints.findLatest().orElseThrow().keyId()).isEqualTo("v2");
    }
}
