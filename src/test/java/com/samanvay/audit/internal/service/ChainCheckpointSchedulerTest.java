package com.samanvay.audit.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.Outcome;
import com.samanvay.audit.internal.repository.InMemoryAuditEntryRepository;
import com.samanvay.audit.internal.repository.InMemoryCheckpointRepository;
import com.samanvay.shared.CanonicalJson;
import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class ChainCheckpointSchedulerTest {

    @Test
    void checkpointSignatureVerifies_andTamperedRootHashFails() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SecretStore store = key -> new SecretStore.Secret(pair.getPrivate().getEncoded());

        InMemoryAuditEntryRepository entries = new InMemoryAuditEntryRepository();
        InMemoryCheckpointRepository checkpoints = new InMemoryCheckpointRepository();
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.query(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(ResultSetExtractor.class),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);

        JdbcAuditService audit = new JdbcAuditService(entries, checkpoints, jdbc, new CanonicalJson());
        audit.record(new AuditEntry(
                ActorType.SYSTEM, "test", "PING", "s", null, null, null, null, Outcome.ALLOWED, null, Map.of()));

        ChainCheckpointScheduler scheduler = new ChainCheckpointScheduler(entries, checkpoints, store);
        scheduler.createCheckpoint();

        var checkpoint = checkpoints.findLatest().orElseThrow();
        assertThat(ChainCheckpointScheduler.verifySignature(
                        checkpoint.rootHash(),
                        checkpoint.uptoEntrySeq(),
                        checkpoint.signature(),
                        pair.getPublic().getEncoded()))
                .isTrue();

        byte[] tampered = JdbcAuditService.sha256("nope".getBytes(StandardCharsets.UTF_8));
        assertThat(ChainCheckpointScheduler.verifySignature(
                        tampered, checkpoint.uptoEntrySeq(), checkpoint.signature(), pair.getPublic().getEncoded()))
                .isFalse();
    }

    @Test
    void createCheckpoint_isNoOpWhenChainIsEmpty() {
        InMemoryAuditEntryRepository entries = new InMemoryAuditEntryRepository();
        InMemoryCheckpointRepository checkpoints = new InMemoryCheckpointRepository();
        ChainCheckpointScheduler scheduler = new ChainCheckpointScheduler(entries, checkpoints, key -> {
            throw new AssertionError("must not resolve a key on an empty chain");
        });

        scheduler.createCheckpoint();

        assertThat(checkpoints.findLatest()).isEmpty();
    }
}
