package com.samanvay.audit.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCheckpointWitnessTest {

    private final byte[] rootHash = "root-hash".getBytes(StandardCharsets.UTF_8);
    private final byte[] signature = "signature".getBytes(StandardCharsets.UTF_8);

    @Test
    void witnessingIsDisabledWhenNoDirectoryConfigured() {
        assertThat(new FileCheckpointWitness("").publish(1, rootHash, signature, "v1"))
                .isNull();
        assertThat(new FileCheckpointWitness(null).publish(1, rootHash, signature, "v1"))
                .isNull();
    }

    @Test
    void publishesAnAppendOnlyLineAndReturnsAContentDigest(@TempDir Path dir) throws Exception {
        var witness = new FileCheckpointWitness(dir.toString());

        String ref1 = witness.publish(1, rootHash, signature, "v1");
        String ref2 = witness.publish(2, rootHash, signature, "v1");

        assertThat(ref1).startsWith("sha256:");
        assertThat(ref2).startsWith("sha256:").isNotEqualTo(ref1); // different checkpoint -> different ref

        var lines = Files.readAllLines(dir.resolve("checkpoints.log"));
        assertThat(lines).hasSize(2); // append-only
        assertThat(lines.get(0))
                .contains("\"uptoEntrySeq\":1")
                .contains(Base64.getEncoder().encodeToString(rootHash));
    }

    @Test
    void digestIsDeterministicForTheSameCheckpoint(@TempDir Path a, @TempDir Path b) {
        String refA = new FileCheckpointWitness(a.toString()).publish(7, rootHash, signature, "v2");
        String refB = new FileCheckpointWitness(b.toString()).publish(7, rootHash, signature, "v2");
        assertThat(refA).isEqualTo(refB); // content-addressed, not storage-specific
    }
}
