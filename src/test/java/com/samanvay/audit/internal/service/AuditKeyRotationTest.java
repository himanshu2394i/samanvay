package com.samanvay.audit.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.Checkpoint;
import com.samanvay.audit.api.Outcome;
import com.samanvay.audit.internal.repository.InMemoryAuditEntryRepository;
import com.samanvay.audit.internal.repository.InMemoryCheckpointRepository;
import com.samanvay.shared.CanonicalJson;
import com.samanvay.shared.SecretStore;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Sign with key v1, rotate to v2: v1-signed history still verifies, new checkpoints sign with v2. */
class AuditKeyRotationTest {

    private final Map<String, SecretStore.Secret> secrets = new HashMap<>();
    private final SecretStore store = new SecretStore() {
        @Override
        public Secret resolve(String key) {
            return find(key).orElseThrow(() -> new IllegalStateException("not provisioned: " + key));
        }

        @Override
        public java.util.Optional<Secret> find(String key) {
            return java.util.Optional.ofNullable(secrets.get(key));
        }
    };

    private InMemoryAuditEntryRepository entries;
    private InMemoryCheckpointRepository checkpoints;
    private JdbcAuditService audit;
    private final CheckpointVerifier verifier = new CheckpointVerifier(store);

    private KeyPair v1;
    private KeyPair v2;

    @BeforeEach
    void setUp() throws Exception {
        v1 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        v2 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        // v1 keeps the pre-rotation names; v2 is suffixed.
        provision("audit-checkpoint-signing-key", v1.getPrivate().getEncoded());
        provision("audit-checkpoint-verifying-key", v1.getPublic().getEncoded());
        provision("audit-checkpoint-signing-key-v2", v2.getPrivate().getEncoded());
        provision("audit-checkpoint-verifying-key-v2", v2.getPublic().getEncoded());

        entries = new InMemoryAuditEntryRepository();
        checkpoints = new InMemoryCheckpointRepository();
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.query(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(ResultSetExtractor.class),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);
        audit = new JdbcAuditService(entries, checkpoints, jdbc, new CanonicalJson());
    }

    private void provision(String name, byte[] bytes) {
        secrets.put(name, new SecretStore.Secret(bytes));
    }

    private void record(String subject) {
        TransactionSynchronizationManager.setActualTransactionActive(true); // stands in for the @Transactional proxy
        try {
            audit.record(new AuditEntry(
                    ActorType.SYSTEM, "test", "PING", subject, null, null, null, null, Outcome.ALLOWED, null, Map.of()));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private ChainCheckpointScheduler schedulerSigningWith(String keyId) {
        return new ChainCheckpointScheduler(entries, checkpoints, store, AuditSigningKeys.withActive(keyId));
    }

    @Test
    void rotationKeepsOldCheckpointsVerifiableAndSignsNewOnesWithTheNewKey() {
        record("a");
        schedulerSigningWith("v1").createCheckpoint();
        Checkpoint signedWithV1 = checkpoints.findLatest().orElseThrow();
        assertThat(signedWithV1.keyId()).isEqualTo("v1");

        // rotate: v2 becomes the signing key, v1 stays provisioned for verification only
        record("b");
        schedulerSigningWith("v2").createCheckpoint();
        Checkpoint signedWithV2 = checkpoints.findLatest().orElseThrow();
        assertThat(signedWithV2.keyId()).isEqualTo("v2");
        assertThat(signedWithV2.uptoEntrySeq()).isGreaterThan(signedWithV1.uptoEntrySeq());

        assertThat(verifier.verify(signedWithV1).valid()).isTrue();
        assertThat(verifier.verify(signedWithV1).keyId()).isEqualTo("v1");
        assertThat(verifier.verify(signedWithV2).valid()).isTrue();
        assertThat(verifier.verify(signedWithV2).keyId()).isEqualTo("v2");

        // and each signature really belongs to its own key
        assertThat(ChainCheckpointScheduler.verifySignature(
                        signedWithV2.rootHash(), signedWithV2.uptoEntrySeq(), signedWithV2.signature(),
                        v2.getPublic().getEncoded()))
                .isTrue();
        assertThat(ChainCheckpointScheduler.verifySignature(
                        signedWithV2.rootHash(), signedWithV2.uptoEntrySeq(), signedWithV2.signature(),
                        v1.getPublic().getEncoded()))
                .isFalse();
    }

    @Test
    void checkpointWithoutKeyIdIsLegacyAndVerifiesAgainstV1EvenAfterRotation() {
        record("a");
        // Written exactly as before rotation existed: 3-arg scheduler, then the row has no key id (NULL).
        new ChainCheckpointScheduler(entries, checkpoints, store).createCheckpoint();
        Checkpoint written = checkpoints.findLatest().orElseThrow();
        Checkpoint legacy = new Checkpoint(
                written.seq(), written.uptoEntrySeq(), written.rootHash(), Instant.now(), written.signature(), null);
        assertThat(legacy.keyId()).isNull();

        // the active key is now v2, yet the legacy row must NOT be checked against it
        var result = verifier.verify(legacy);
        assertThat(result.valid()).isTrue();
        assertThat(result.keyId()).isEqualTo(AuditSigningKeys.LEGACY_KEY_ID);
    }

    @Test
    void defaultSchedulerBehaviourIsUnchanged_signsWithTheOriginalSecretName() {
        record("a");
        new ChainCheckpointScheduler(entries, checkpoints, store).createCheckpoint();
        Checkpoint cp = checkpoints.findLatest().orElseThrow();

        assertThat(ChainCheckpointScheduler.verifySignature(
                        cp.rootHash(), cp.uptoEntrySeq(), cp.signature(), v1.getPublic().getEncoded()))
                .isTrue();
    }

    @Test
    void relabellingACheckpointWithAnotherKeyIdDoesNotVerify() {
        record("a");
        schedulerSigningWith("v1").createCheckpoint();
        Checkpoint cp = checkpoints.findLatest().orElseThrow();
        Checkpoint relabelled = new Checkpoint(
                cp.seq(), cp.uptoEntrySeq(), cp.rootHash(), cp.signedAt(), cp.signature(), null, "v2");

        var result = verifier.verify(relabelled);
        assertThat(result.valid()).isFalse();
        assertThat(result.keyId()).isEqualTo("v2");
    }

    @Test
    void tamperedRootHashFailsUnderItsOwnKey() {
        record("a");
        schedulerSigningWith("v2").createCheckpoint();
        Checkpoint cp = checkpoints.findLatest().orElseThrow();
        byte[] tampered = cp.rootHash().clone();
        tampered[0] ^= 1;

        assertThat(verifier.verify(new Checkpoint(
                                cp.seq(), cp.uptoEntrySeq(), tampered, cp.signedAt(), cp.signature(), null, "v2"))
                        .valid())
                .isFalse();
    }

    @Test
    void unprovisionedOrUnknownKeyIdFailsVerificationNeverFallsBack() {
        record("a");
        schedulerSigningWith("v2").createCheckpoint();
        Checkpoint cp = checkpoints.findLatest().orElseThrow();

        secrets.remove("audit-checkpoint-verifying-key-v2");
        var missing = verifier.verify(cp);
        assertThat(missing.valid()).isFalse();
        assertThat(missing.reason()).contains("unavailable").doesNotContain("not provisioned:");

        Checkpoint unknown = new Checkpoint(
                cp.seq(), cp.uptoEntrySeq(), cp.rootHash(), cp.signedAt(), cp.signature(), null, "v9");
        assertThat(verifier.verify(unknown).valid()).isFalse();
        Checkpoint malformed = new Checkpoint(
                cp.seq(), cp.uptoEntrySeq(), cp.rootHash(), cp.signedAt(), cp.signature(), null, "../etc");
        assertThat(verifier.verify(malformed).valid()).isFalse();
    }

    @Test
    void signingWithAnUnprovisionedNewKeyFailsRatherThanFallingBack() {
        secrets.remove("audit-checkpoint-signing-key-v2");
        record("a");

        assertThatThrownBy(() -> schedulerSigningWith("v2").createCheckpoint()).isInstanceOf(RuntimeException.class);
        assertThat(checkpoints.findLatest()).isEmpty();
    }

    @Test
    void keyNamingAndBootRequirements() {
        assertThat(AuditSigningKeys.signingSecretName("v1")).isEqualTo("audit-checkpoint-signing-key");
        assertThat(AuditSigningKeys.verifyingSecretName(null)).isEqualTo("audit-checkpoint-verifying-key");
        assertThat(AuditSigningKeys.signingSecretName("v2")).isEqualTo("audit-checkpoint-signing-key-v2");
        assertThat(AuditSigningKeys.withActive("v2").keys())
                .containsExactly("audit-checkpoint-signing-key-v2", "audit-checkpoint-verifying-key-v2");
        assertThat(AuditSigningKeys.legacyOnly().keys())
                .containsExactly("audit-checkpoint-signing-key", "audit-checkpoint-verifying-key");
        assertThatThrownBy(() -> AuditSigningKeys.withActive("v 2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuditSigningKeys.withActive("../x")).isInstanceOf(IllegalStateException.class);
    }
}
